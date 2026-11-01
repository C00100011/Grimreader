package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.strOr
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.database.BookTextStatsEntity
import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import com.vdelaar.mylibby.core.database.SectionStatEntity
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.network.ReadingSessionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

/** What fetching the sessions from Grimmory came back with. */
data class SessionPullResult(val books: Int, val added: Int, val failed: Int)

data class ReadingSpeed(
    /** Book content bytes per active minute (the unit foliate-js reports remaining content in). */
    val bytesPerMinute: Double,
    /** Estimated words per minute, using the measured words/bytes ratio of read books. */
    val wordsPerMinute: Int,
    val measured: Boolean,
)

class StatsRepository(
    private val api: ApiProvider,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val sync: SyncScheduler,
) {
    val streak: Flow<StreakInfo> = combine(db.sessions().observeDayTotals(), settings.goal) { totals, goal ->
        StreakCalculator.compute(totals, goal)
    }

    val totalSeconds = db.sessions().observeTotalSeconds()
    val totalPages = db.sessions().observeTotalPages()
    val avgChapterSeconds = db.sectionStats().observeAvgChapterSeconds()
    val avgSecondsPerPage = db.sectionStats().observeAvgSecondsPerPage()
    fun topBooks(limit: Int = 5) = db.sessions().observeTopBooks(limit)
    fun recentSessions(limit: Int = 20) = db.sessions().observeRecent(limit)
    fun bookSeconds(bookId: Long) = db.sessions().observeBookSeconds(bookId)
    /** Every session of one book, newest first: read here, imported from a Kobo or fetched from Grimmory. */
    fun bookSessions(bookId: Long) = db.sessions().observeForBook(bookId)
    /** The sessions that started on one local day (yyyy-MM-dd), in the order they happened. */
    fun daySessions(localDate: String) = db.sessions().observeForDay(localDate)
    fun sectionStats(bookId: Long) = db.sectionStats().observeForBook(bookId)

    /** Reading time and pace for one book; null until there is enough reading to say something. */
    suspend fun bookPace(bookId: Long): BookPace? = withContext(Dispatchers.IO) {
        val text = db.textStats().get(bookId)
        BookPaceCalculator.compute(db.sessions().bookTotals(bookId), text?.totalWords, text?.totalBytes, referenceWpm = settings.app.value.paceWpm)
    }

    val speed: Flow<ReadingSpeed> = db.sessions().observeRecentForSpeed(30).map { computeSpeed(it, wordsPerByte()) }

    suspend fun currentSpeed(bookId: Long? = null): ReadingSpeed = withContext(Dispatchers.IO) {
        val ratio = bookId?.let { db.textStats().get(it) }?.takeIf { it.totalBytes > 0 }
            ?.let { it.totalWords.toDouble() / it.totalBytes } ?: wordsPerByte()
        computeSpeed(db.sessions().recentForSpeed(30), ratio)
    }

    private suspend fun wordsPerByte(): Double {
        val all = db.textStats().all().filter { it.totalBytes > 0 }
        if (all.isEmpty()) return DEFAULT_WORDS_PER_BYTE
        return all.sumOf { it.totalWords }.toDouble() / all.sumOf { it.totalBytes }
    }

    private fun computeSpeed(sessions: List<ReadingSessionEntity>, wordsPerByte: Double): ReadingSpeed {
        // Weight recent sessions more, ignore implausible outliers.
        var bytes = 0.0
        var minutes = 0.0
        sessions.forEachIndexed { i, s ->
            val m = s.durationSeconds / 60.0
            if (m < 1.0) return@forEachIndexed
            val bpm = s.bytesRead / m
            if (bpm < 200 || bpm > 20_000) return@forEachIndexed
            val w = 1.0 / (1 + i * 0.1)
            bytes += s.bytesRead * w
            minutes += m * w
        }
        // Blend with a prior of 10 minutes at the default speed so a few short sessions
        // don't produce extreme estimates.
        val prior = 10.0
        val measured = minutes >= 10
        val bpm = (bytes + DEFAULT_BYTES_PER_MINUTE * prior) / (minutes + prior)
        return ReadingSpeed(bpm, (bpm * wordsPerByte).toInt().coerceIn(50, 1500), measured)
    }

    suspend fun saveTextStats(bookId: Long, bytes: Long, words: Long) = withContext(Dispatchers.IO) {
        if (bytes > 0 && words > 0) db.textStats().upsert(BookTextStatsEntity(bookId, bytes, words))
    }

    suspend fun hasTextStats(bookId: Long): Boolean = withContext(Dispatchers.IO) { db.textStats().get(bookId) != null }

    suspend fun addSectionTime(bookId: Long, index: Int, label: String, seconds: Long, pages: Int, finished: Boolean) =
        withContext(Dispatchers.IO) {
            val cur = db.sectionStats().get(bookId, index)
            db.sectionStats().upsert(
                SectionStatEntity(
                    bookId = bookId,
                    sectionIndex = index,
                    label = label.ifBlank { cur?.label ?: "Section ${index + 1}" },
                    seconds = (cur?.seconds ?: 0) + seconds,
                    pages = (cur?.pages ?: 0) + pages,
                    finished = finished || cur?.finished == true,
                )
            )
        }

    suspend fun recordSession(session: ReadingSessionEntity) = withContext(Dispatchers.IO) {
        db.sessions().insert(session)
        if (!pushSessions()) sync.requestSync()
    }

    private val pullLock = Mutex()

    /**
     * Fetches one book's sessions from Grimmory and keeps the ones this device does not have yet (same start time =
     * same session). Returns how many were added, or -1 when Grimmory could not be reached.
     */
    suspend fun pullBookSessions(bookId: Long, title: String? = null): Int = pullLock.withLock { pullBookSessionsLocked(bookId, title) }

    private suspend fun pullBookSessionsLocked(bookId: Long, title: String?): Int = withContext(Dispatchers.IO) {
        if (bookId < 0) return@withContext 0 // a book on the device only has no Grimmory sessions
        val g = runCatching { api.grimmory() }.getOrNull() ?: return@withContext -1
        val have = db.sessions().starts().filter { it.bookId == bookId }.map { it.startTime }.toMutableList()
        var added = 0
        var page = 0
        while (page < MAX_SESSION_PAGES) {
            val res = try {
                g.bookSessions(bookId, page, SESSION_PAGE)
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404) return@withContext added // the book is gone from Grimmory
                return@withContext -1
            } catch (_: Exception) {
                return@withContext -1
            }
            for (e in sessionsFromGrimmory(res.content, bookId, title, have)) {
                db.sessions().insert(e)
                have += e.startTime
                added++
            }
            if (res.last == true || res.content.size < SESSION_PAGE) break
            page++
        }
        added
    }

    /**
     * Fetches the sessions of every book in the library (Grimmory has no call for all books at once); books that were
     * started come first. [onProgress] gets (books done, books total).
     */
    suspend fun pullAllSessions(onProgress: (Int, Int) -> Unit = { _, _ -> }): SessionPullResult = withContext(Dispatchers.IO) {
        fun isStarted(b: com.vdelaar.mylibby.core.database.BookEntity): Boolean {
            val status = b.readStatus
            return (status != null && status != "UNREAD" && status != "UNSET") || (b.readProgress ?: 0f) > 0f || b.lastReadTime != null
        }
        val library = db.books().all().sortedByDescending { isStarted(it) }
        val done = AtomicInteger()
        val added = AtomicInteger()
        val failed = AtomicInteger()
        val gate = Semaphore(PARALLEL_PULLS)
        onProgress(0, library.size)
        coroutineScope {
            library.map { b ->
                async {
                    gate.withPermit {
                        val n = pullBookSessions(b.id, b.title)
                        if (n < 0) failed.incrementAndGet() else added.addAndGet(n)
                        onProgress(done.incrementAndGet(), library.size)
                    }
                }
            }.awaitAll()
        }
        SessionPullResult(library.size, added.get(), failed.get())
    }

    /** Sends unsynced sessions to Grimmory. Returns false if any failed. */
    suspend fun pushSessions(): Boolean = withContext(Dispatchers.IO) {
        val g = runCatching { api.grimmory() }.getOrNull() ?: return@withContext false
        var ok = true
        for (s in db.sessions().unsynced()) {
            if (s.bookId < 0) { db.sessions().markSynced(s.id); continue } // local-only book
            try {
                val r = g.recordSession(s.toRequest())
                if (r.isSuccessful || r.code() in 400..404) db.sessions().markSynced(s.id) else ok = false
            } catch (_: Exception) {
                ok = false
            }
        }
        ok
    }

    companion object {
        const val SESSION_PAGE = 100
        const val MAX_SESSION_PAGES = 50
        const val PARALLEL_PULLS = 4
        const val DEFAULT_BYTES_PER_MINUTE = 1600.0
        const val DEFAULT_WORDS_PER_BYTE = 1.0 / 7.5
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> strOr(R.string.dur_h_m, "%1${'$'}dh %2${'$'}dm", h, m)
        m > 0 -> strOr(R.string.dur_m_s, "%1${'$'}dm %2${'$'}ds", m, s)
        else -> strOr(R.string.dur_s, "%1${'$'}ds", s)
    }
}

/** Compact duration for tiles: "<1m", "12m", "3h 5m". */
fun formatShortDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> if (m > 0) strOr(R.string.dur_short_h_m, "%1${'$'}dh %2${'$'}dm", h, m) else strOr(R.string.dur_short_h, "%1${'$'}dh", h)
        m > 0 -> strOr(R.string.dur_short_m, "%1${'$'}dm", m)
        else -> strOr(R.string.dur_short_lt, "<1m")
    }
}

fun formatMinutes(minutes: Double): String {
    if (minutes.isNaN() || minutes.isInfinite()) return "–"
    val total = minutes.toLong().coerceAtLeast(if (minutes > 0) 1 else 0)
    val h = total / 60
    val m = total % 60
    return when {
        h > 0 && m > 0 -> strOr(R.string.min_h_m, "%1${'$'}d h %2${'$'}d min", h, m)
        h > 0 -> strOr(R.string.min_h, "%1${'$'}d h", h)
        total == 0L -> strOr(R.string.min_lt, "< 1 min")
        else -> strOr(R.string.min_m, "%1${'$'}d min", m)
    }
}

private val ReadingSessionEntity.hasProgress: Boolean get() = !(startProgress == 0f && endProgress == 0f && source == ReadingSessionEntity.SOURCE_KOBO)

internal fun ReadingSessionEntity.toRequest() = ReadingSessionRequest(
    bookId = bookId,
    bookType = bookType,
    startTime = Instant.ofEpochMilli(startTime).toString(),
    endTime = Instant.ofEpochMilli(endTime).toString(),
    durationSeconds = durationSeconds,
    durationFormatted = formatDuration(durationSeconds.toLong()),
    // A session that came without progress (imported from a Kobo) says nothing about progress: leave it out, not 0.
    startProgress = if (hasProgress) startProgress.round2() else null,
    endProgress = if (hasProgress) endProgress.round2() else null,
    progressDelta = if (hasProgress) (endProgress - startProgress).round2() else null,
    startLocation = startCfi,
    endLocation = endCfi,
)

/** Grimmory keeps session progress to two decimals; sending more only adds noise. */
private fun Float.round2(): Float = Math.round(this * 100f) / 100f


/**
 * Turns the sessions Grimmory sent into local ones, leaving out those whose start is in [have] (already known),
 * that have no usable start time, or that lasted no time. Sessions in the list itself that start together count once.
 */
internal fun sessionsFromGrimmory(dtos: List<com.vdelaar.mylibby.core.network.ReadingSessionDto>, bookId: Long, title: String?, have: Collection<Long>): List<ReadingSessionEntity> {
    val known = have.toMutableList()
    val out = ArrayList<ReadingSessionEntity>()
    for (dto in dtos) {
        val start = parseInstant(dto.startTime) ?: continue
        val end = parseInstant(dto.endTime) ?: (start + (dto.durationSeconds ?: 0) * 1000L)
        val seconds = dto.durationSeconds ?: ((end - start) / 1000).toInt()
        if (seconds <= 0 || isDuplicateSession(known, start)) continue
        known += start
        out += ReadingSessionEntity(
            bookId = bookId, bookTitle = dto.bookTitle ?: title.orEmpty(), bookType = dto.bookType,
            startTime = start, endTime = end, durationSeconds = seconds,
            startProgress = dto.startProgress ?: 0f, endProgress = dto.endProgress ?: 0f,
            startCfi = null, endCfi = null, bytesRead = 0, pagesTurned = 0,
            localDate = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
            listening = dto.bookType.equals("AUDIOBOOK", ignoreCase = true),
            synced = true, source = ReadingSessionEntity.SOURCE_GRIMMORY,
        )
    }
    return out
}
