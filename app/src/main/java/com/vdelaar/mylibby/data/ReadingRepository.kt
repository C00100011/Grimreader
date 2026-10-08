package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.AnnotationEntity
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.BookmarkEntity
import com.vdelaar.mylibby.core.database.PendingAction
import com.vdelaar.mylibby.core.database.ProgressEntity
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.network.CreateAnnotationRequest
import com.vdelaar.mylibby.core.network.CreateBookmarkRequest
import com.vdelaar.mylibby.core.network.FileProgressDto
import com.vdelaar.mylibby.core.network.UpdateAnnotationRequest
import com.vdelaar.mylibby.core.network.UpdateProgressRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/** Where to open a book, after reconciling the local and server positions. */
data class StartPosition(
    val cfi: String?,
    val percent: Float,
    val fromServer: Boolean,
    val serverNewer: Boolean,
    /** Set when another reader (a Kobo, KOReader) is further than this app: its percentage; there is no exact spot to open at. */
    val otherPercent: Float? = null,
)

/** Progress, highlights and bookmarks, written locally first and pushed to Grimmory. */
class ReadingRepository(
    private val api: ApiProvider,
    private val db: AppDatabase,
    private val sync: SyncScheduler,
    private val settings: SettingsRepository,
) {

    // Progress ----------------------------------------------------------------------------------

    suspend fun startPosition(bookId: Long): StartPosition = withContext(Dispatchers.IO) {
        val local = db.progress().get(bookId)
        // Local-only books never talk to Grimmory.
        val server = if (bookId < 0) null else runCatching { api.grimmory().bookProgress(bookId) }.getOrNull()
        val serverCfi = server?.epubProgress?.cfi
        val serverTime = parseInstant(server?.epubProgress?.updatedAt ?: server?.lastReadTime)
        val serverPercent = server?.epubProgress?.percentage ?: server?.readProgress ?: 0f
        val base = when {
            local?.cfi == null && serverCfi != null -> StartPosition(serverCfi, serverPercent, fromServer = true, serverNewer = false)
            local?.cfi != null && serverCfi != null && serverTime != null &&
                serverTime > local.updatedAt + 5_000 && serverCfi != local.cfi ->
                // Server is ahead (read on another device); the reader offers to jump there.
                StartPosition(serverCfi, serverPercent, fromServer = true, serverNewer = true)
            local?.cfi != null -> StartPosition(local.cfi, local.percent, fromServer = false, serverNewer = false)
            else -> StartPosition(null, 0f, fromServer = false, serverNewer = false)
        }
        if (bookId < 0 || server == null) return@withContext base
        // A Kobo or KOReader keeps its own position; the furthest of the readers wins.
        val action = reconcileProgress(
            localPercent = local?.percent, localDirty = local?.dirty == true, canPush = local?.bookFileId != null,
            serverOverall = server.readProgress, serverWeb = server.epubProgress?.percentage,
            lastReassertAt = settings.syncStatus.value.reasserted[bookId], now = System.currentTimeMillis(),
        )
        when (action) {
            ProgressAction.OFFER_JUMP -> base.copy(otherPercent = server.readProgress)
            ProgressAction.REASSERT -> { local?.let { reassert(it) }; base }
            ProgressAction.NONE -> base
        }
    }

    /** Tell Grimmory about our position again, now, so that it counts as the newest and is handed on to a Kobo. */
    internal suspend fun reassert(p: ProgressEntity): Boolean {
        val now = System.currentTimeMillis()
        db.progress().upsert(p.copy(updatedAt = now, dirty = true))
        val sent = pushOne(p.copy(updatedAt = now, dirty = true))
        if (sent) settings.updateSyncStatus { it.copy(reasserted = it.reasserted + (p.bookId to now)) }
        return sent
    }

    /**
     * For a whole library at once (the full synchronisation): every book this app has read further than the server's
     * overall figure gets its position sent again. Returns how many were sent.
     */
    suspend fun reassertAhead(serverBooks: List<Book>): Int = withContext(Dispatchers.IO) {
        val byId = serverBooks.associateBy { it.id }
        val reasserted = settings.syncStatus.value.reasserted
        var sent = 0
        for (p in db.progress().all()) {
            if (p.bookId < 0) continue
            val server = byId[p.bookId] ?: continue
            val action = reconcileProgress(p.percent, p.dirty, p.bookFileId != null, server.progress, null, reasserted[p.bookId], System.currentTimeMillis())
            if (action == ProgressAction.REASSERT && reassert(p)) sent++
        }
        sent
    }

    suspend fun localPosition(bookId: Long): ProgressEntity? = withContext(Dispatchers.IO) { db.progress().get(bookId) }

    suspend fun saveProgress(bookId: Long, bookFileId: Long?, cfi: String, href: String?, percent: Float, ttsCfi: String? = null) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            db.progress().upsert(ProgressEntity(bookId, bookFileId, cfi, href, percent, ttsCfi, now, dirty = true))
            db.books().get(bookId)?.let {
                db.books().upsertAll(listOf(it.copy(readProgress = percent, lastReadTime = Instant.ofEpochMilli(now).toString())))
            }
            if (bookId < 0) db.localBooks().updateProgress(bookId, percent, Instant.ofEpochMilli(now).toString())
        }

    /** Push one book's progress now; leaves it dirty for the sync worker if offline. */
    suspend fun pushProgress(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        val p = db.progress().get(bookId) ?: return@withContext true
        if (!p.dirty) return@withContext true
        pushOne(p).also { if (!it) sync.requestSync() }
    }

    internal suspend fun pushOne(p: ProgressEntity): Boolean {
        val fileId = p.bookFileId ?: return true.also { db.progress().markClean(p.bookId, p.updatedAt) }
        return try {
            val r = api.grimmory().updateProgress(
                p.bookId,
                UpdateProgressRequest(
                    fileProgress = FileProgressDto(
                        bookFileId = fileId,
                        positionData = p.cfi,
                        positionHref = p.href,
                        progressPercent = (p.percent * 10f).toInt() / 10f,
                        ttsPositionCfi = p.ttsCfi,
                    )
                )
            )
            if (r.isSuccessful) {
                db.progress().markClean(p.bookId, p.updatedAt)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pushAllProgress() {
        for (p in db.progress().dirty()) pushOne(p)
    }

    // Annotations -------------------------------------------------------------------------------

    fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>> = db.annotations().observeForBook(bookId)

    suspend fun addAnnotation(bookId: Long, cfi: String, text: String, color: String, style: String, note: String?, chapter: String?): AnnotationEntity =
        withContext(Dispatchers.IO) {
            val existing = db.annotations().byCfi(bookId, cfi)
            val entity = if (existing != null) {
                existing.copy(color = color, style = style, note = note ?: existing.note, pending = if (existing.remoteId == null) PendingAction.CREATE else PendingAction.UPDATE)
                    .also { db.annotations().upsert(it) }
            } else {
                val e = AnnotationEntity(bookId = bookId, cfi = cfi, text = text.take(5000), color = color, style = style, note = note, chapterTitle = chapter?.take(500))
                e.copy(localId = db.annotations().insert(e))
            }
            pushAnnotations()
            entity
        }

    suspend fun updateAnnotation(a: AnnotationEntity, color: String = a.color, style: String = a.style, note: String? = a.note) = withContext(Dispatchers.IO) {
        // The caller may hold a stale copy (e.g. before the server id was assigned).
        val current = db.annotations().get(a.localId) ?: a
        db.annotations().upsert(
            current.copy(color = color, style = style, note = note, pending = if (current.remoteId == null) PendingAction.CREATE else PendingAction.UPDATE)
        )
        pushAnnotations()
    }

    suspend fun deleteAnnotation(a: AnnotationEntity) = withContext(Dispatchers.IO) {
        val current = db.annotations().get(a.localId) ?: a
        if (current.remoteId == null) db.annotations().delete(current.localId)
        else db.annotations().upsert(current.copy(pending = PendingAction.DELETE))
        pushAnnotations()
    }

    private val pushLock = kotlinx.coroutines.sync.Mutex()

    suspend fun pushAnnotations(): Unit = pushLock.withLock { pushAnnotationsLocked() }

    private suspend fun pushAnnotationsLocked() {
        val g = runCatching { api.grimmory() }.getOrNull() ?: return
        var failed = false
        for (a in db.annotations().pending()) {
            if (a.bookId < 0) {
                // Local books keep highlights on the device only.
                if (a.pending == PendingAction.DELETE) db.annotations().delete(a.localId)
                else db.annotations().upsert(a.copy(pending = PendingAction.NONE))
                continue
            }
            try {
                when (a.pending) {
                    PendingAction.CREATE -> try {
                        val dto = g.createAnnotation(CreateAnnotationRequest(a.bookId, a.cfi, a.text.ifBlank { "…" }, a.color, a.style, a.note, a.chapterTitle))
                        db.annotations().upsert(a.copy(remoteId = dto.id, pending = PendingAction.NONE))
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() != 409) throw e
                        // Already on the server (same position): adopt it and push our version.
                        val existing = g.annotations(a.bookId).firstOrNull { it.cfi == a.cfi } ?: throw e
                        g.updateAnnotation(existing.id, UpdateAnnotationRequest(a.color, a.style, a.note))
                        db.annotations().upsert(a.copy(remoteId = existing.id, pending = PendingAction.NONE))
                    }
                    PendingAction.UPDATE -> {
                        g.updateAnnotation(a.remoteId!!, UpdateAnnotationRequest(a.color, a.style, a.note))
                        db.annotations().upsert(a.copy(pending = PendingAction.NONE))
                    }
                    PendingAction.DELETE -> {
                        val r = g.deleteAnnotation(a.remoteId!!)
                        if (r.isSuccessful || r.code() == 404) db.annotations().delete(a.localId)
                    }
                    PendingAction.NONE -> Unit
                }
            } catch (_: Exception) {
                failed = true
            }
        }
        if (failed) sync.requestSync()
    }

    /** Merge the server's annotations for a book into the local table. */
    suspend fun pullAnnotations(bookId: Long) = withContext(Dispatchers.IO) {
        if (bookId < 0) return@withContext
        val remote = runCatching { api.grimmory().annotations(bookId) }.getOrNull() ?: return@withContext
        val local = db.annotations().allForBook(bookId)
        val byRemote = local.filter { it.remoteId != null }.associateBy { it.remoteId }
        for (r in remote) {
            val l = byRemote[r.id]
            if (l == null) {
                db.annotations().insert(
                    AnnotationEntity(
                        remoteId = r.id, bookId = bookId, cfi = r.cfi, text = r.text.orEmpty(),
                        color = r.color ?: "#FFD54F", style = r.style ?: "highlight", note = r.note,
                        chapterTitle = r.chapterTitle, createdAt = parseInstant(r.createdAt) ?: System.currentTimeMillis(),
                        pending = PendingAction.NONE,
                    )
                )
            } else if (l.pending == PendingAction.NONE) {
                db.annotations().upsert(l.copy(color = r.color ?: l.color, style = r.style ?: l.style, note = r.note))
            }
        }
        // Removed on the server (and not changed locally) -> remove here.
        val remoteIds = remote.map { it.id }.toSet()
        for (l in local) if (l.remoteId != null && l.remoteId !in remoteIds && l.pending == PendingAction.NONE) db.annotations().delete(l.localId)
    }

    // Bookmarks ---------------------------------------------------------------------------------

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = db.bookmarks().observeForBook(bookId)

    suspend fun addBookmark(bookId: Long, cfi: String, title: String?, percent: Float) = withContext(Dispatchers.IO) {
        db.bookmarks().insert(BookmarkEntity(bookId = bookId, cfi = cfi, title = title, percent = percent))
        pushBookmarks()
    }

    suspend fun deleteBookmark(b: BookmarkEntity) = withContext(Dispatchers.IO) {
        if (b.remoteId == null) db.bookmarks().delete(b.localId) else db.bookmarks().upsert(b.copy(pending = PendingAction.DELETE))
        pushBookmarks()
    }

    suspend fun pushBookmarks() {
        val g = runCatching { api.grimmory() }.getOrNull() ?: return
        var failed = false
        for (b in db.bookmarks().pending()) {
            if (b.bookId < 0) {
                if (b.pending == PendingAction.DELETE) db.bookmarks().delete(b.localId)
                else db.bookmarks().upsert(b.copy(pending = PendingAction.NONE))
                continue
            }
            try {
                when (b.pending) {
                    PendingAction.CREATE, PendingAction.UPDATE -> {
                        val dto = g.createBookmark(CreateBookmarkRequest(b.bookId, b.cfi, b.title))
                        db.bookmarks().upsert(b.copy(remoteId = dto.id, pending = PendingAction.NONE))
                    }
                    PendingAction.DELETE -> {
                        val r = g.deleteBookmark(b.remoteId!!)
                        if (r.isSuccessful || r.code() == 404) db.bookmarks().delete(b.localId)
                    }
                    PendingAction.NONE -> Unit
                }
            } catch (_: Exception) {
                failed = true
            }
        }
        if (failed) sync.requestSync()
    }

    suspend fun pullBookmarks(bookId: Long) = withContext(Dispatchers.IO) {
        if (bookId < 0) return@withContext
        val remote = runCatching { api.grimmory().bookmarks(bookId) }.getOrNull() ?: return@withContext
        val local = db.bookmarks().allForBook(bookId)
        val known = local.mapNotNull { it.remoteId }.toSet()
        for (r in remote) {
            val cfi = r.cfi ?: continue
            if (r.id !in known) {
                db.bookmarks().insert(BookmarkEntity(remoteId = r.id, bookId = bookId, cfi = cfi, title = r.title, pending = PendingAction.NONE))
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        for (l in local) if (l.remoteId != null && l.remoteId !in remoteIds && l.pending == PendingAction.NONE) db.bookmarks().delete(l.localId)
    }
}

fun parseInstant(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}
