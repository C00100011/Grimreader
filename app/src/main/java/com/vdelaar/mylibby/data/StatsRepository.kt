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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

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

    /** Sends unsynced sessions to Grimmory. Returns false if any failed. */
    suspend fun pushSessions(): Boolean = withContext(Dispatchers.IO) {
        val g = runCatching { api.grimmory() }.getOrNull() ?: return@withContext false
        var ok = true
        for (s in db.sessions().unsynced()) {
            if (s.bookId < 0) { db.sessions().markSynced(s.id); continue } // local-only book
            try {
                val r = g.recordSession(
                    ReadingSessionRequest(
                        bookId = s.bookId,
                        bookType = s.bookType,
                        startTime = Instant.ofEpochMilli(s.startTime).toString(),
                        endTime = Instant.ofEpochMilli(s.endTime).toString(),
                        durationSeconds = s.durationSeconds,
                        durationFormatted = formatDuration(s.durationSeconds.toLong()),
                        startProgress = s.startProgress,
                        endProgress = s.endProgress,
                        progressDelta = s.endProgress - s.startProgress,
                        startLocation = s.startCfi,
                        endLocation = s.endCfi,
                    )
                )
                if (r.isSuccessful || r.code() in 400..404) db.sessions().markSynced(s.id) else ok = false
            } catch (_: Exception) {
                ok = false
            }
        }
        ok
    }

    companion object {
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
