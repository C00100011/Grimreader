package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.BuildConfig
import com.vdelaar.mylibby.core.database.AnnotationEntity
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.BookmarkEntity
import com.vdelaar.mylibby.core.database.FavouriteEntity
import com.vdelaar.mylibby.core.database.OutboxEntity
import com.vdelaar.mylibby.core.database.OutboxType
import com.vdelaar.mylibby.core.database.PendingAction
import com.vdelaar.mylibby.core.database.ProgressEntity
import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import com.vdelaar.mylibby.core.database.SectionStatEntity
import com.vdelaar.mylibby.core.datastore.GoalSettings
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything that belongs to the reader and can be taken to another device or app install. */
@Serializable
data class PersonalExport(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val profile: ExportProfile? = null,
    val goal: GoalSettings? = null,
    val books: List<ExportBook> = emptyList(),
    val sessions: List<ExportSession> = emptyList(),
    val sections: List<ExportSection> = emptyList(),
    val progress: List<ExportProgress> = emptyList(),
    val annotations: List<ExportAnnotation> = emptyList(),
    val bookmarks: List<ExportBookmark> = emptyList(),
) {
    companion object { const val FORMAT = "grimreader-export" }
}

@Serializable data class ExportProfile(val name: String = "", val avatar: String = "📚", val yearlyBookGoal: Int = 12, val favouriteGenres: List<String> = emptyList())

/** One book of the read list; [key] (title + authors) is how books are recognised on another install. */
@Serializable
data class ExportBook(
    val key: String,
    val bookId: Long,
    val title: String,
    val authors: String,
    val local: Boolean = false,
    val readStatus: String? = null,
    val progress: Float? = null,
    val rating: Int? = null,
    val favourite: Boolean = false,
    val lastReadTime: String? = null,
)

@Serializable
data class ExportSession(
    val bookKey: String, val bookTitle: String, val bookType: String? = null,
    val startTime: Long, val endTime: Long, val durationSeconds: Int,
    val startProgress: Float = 0f, val endProgress: Float = 0f, val bytesRead: Long = 0, val pagesTurned: Int = 0,
    val localDate: String, val listening: Boolean = false,
)

@Serializable data class ExportSection(val bookKey: String, val sectionIndex: Int, val label: String, val seconds: Long, val pages: Int, val finished: Boolean = false)
@Serializable data class ExportProgress(val bookKey: String, val cfi: String? = null, val href: String? = null, val percent: Float, val updatedAt: Long)
@Serializable data class ExportAnnotation(val bookKey: String, val cfi: String, val text: String, val color: String, val style: String, val note: String? = null, val chapterTitle: String? = null, val createdAt: Long)
@Serializable data class ExportBookmark(val bookKey: String, val cfi: String, val title: String? = null, val percent: Float = 0f, val createdAt: Long)

/** What a file contains, shown before anything is changed. */
data class ImportPreview(
    val data: PersonalExport,
    val sessions: Int,
    val books: Int,
    val favourites: Int,
    val highlights: Int,
    val bookmarks: Int,
    val hasProfile: Boolean,
)

data class ImportResult(
    val sessionsAdded: Int,
    val booksMatched: Int,
    val booksNotFound: Int,
    val favouritesAdded: Int,
    val highlightsAdded: Int,
    val bookmarksAdded: Int,
    val queuedForSync: Boolean,
)

class InvalidExportException(message: String) : Exception(message)

class PersonalDataRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val sync: SyncScheduler,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private fun key(title: String, authors: String) = (title.trim().lowercase() + "|" + authors.trim().lowercase()).replace(Regex("\\s+"), " ")

    private data class Known(val id: Long, val title: String, val authors: String, val local: Boolean)

    private suspend fun knownBooks(): List<Known> =
        db.books().all().map { Known(it.id, it.title, it.authors, false) } +
            db.localBooks().all().map { Known(it.id, it.title, it.authors, true) }

    suspend fun export(): String = withContext(Dispatchers.IO) {
        val favs = db.favourites().ids().toSet()
        val serverBooks = db.books().all()
        val localBooks = db.localBooks().all()
        val keyById = HashMap<Long, String>()
        val titleById = HashMap<Long, String>()
        val books = ArrayList<ExportBook>()
        for (b in serverBooks) {
            val k = key(b.title, b.authors); keyById[b.id] = k; titleById[b.id] = b.title
            val interesting = b.readStatus != null || (b.readProgress ?: 0f) > 0f || b.personalRating != null || b.id in favs
            if (interesting) books += ExportBook(k, b.id, b.title, b.authors, false, b.readStatus, b.readProgress, b.personalRating, b.id in favs, b.lastReadTime)
        }
        for (b in localBooks) {
            val k = key(b.title, b.authors); keyById[b.id] = k; titleById[b.id] = b.title
            books += ExportBook(k, b.id, b.title, b.authors, true, b.readStatus, b.readProgress, null, b.id in favs, b.lastReadTime)
        }
        val sessions = db.sessions().all()
        // Sessions of books that are no longer in the cache still count; they are identified by their title.
        fun keyOf(id: Long, title: String) = keyById[id] ?: key(title, "")
        val app = settings.app.value
        val export = PersonalExport(
            appVersion = BuildConfig.VERSION_NAME,
            profile = ExportProfile(app.profile.name, app.profile.avatar, app.profile.yearlyBookGoal, app.profile.favouriteGenres),
            goal = settings.goal.value,
            books = books,
            sessions = sessions.map {
                ExportSession(keyOf(it.bookId, it.bookTitle), it.bookTitle, it.bookType, it.startTime, it.endTime, it.durationSeconds, it.startProgress, it.endProgress, it.bytesRead, it.pagesTurned, it.localDate, it.listening)
            },
            sections = db.sectionStats().all().mapNotNull { s -> keyById[s.bookId]?.let { ExportSection(it, s.sectionIndex, s.label, s.seconds, s.pages, s.finished) } },
            progress = db.progress().all().mapNotNull { p -> keyById[p.bookId]?.let { ExportProgress(it, p.cfi, p.href, p.percent, p.updatedAt) } },
            annotations = db.annotations().all().mapNotNull { a -> keyById[a.bookId]?.let { ExportAnnotation(it, a.cfi, a.text, a.color, a.style, a.note, a.chapterTitle, a.createdAt) } },
            bookmarks = db.bookmarks().all().mapNotNull { b -> keyById[b.bookId]?.let { ExportBookmark(it, b.cfi, b.title, b.percent, b.createdAt) } },
        )
        json.encodeToString(PersonalExport.serializer(), export)
    }

    fun preview(text: String): ImportPreview {
        val data = try {
            json.decodeFromString(PersonalExport.serializer(), text)
        } catch (e: Exception) {
            throw InvalidExportException(e.message ?: "invalid")
        }
        if (data.format != PersonalExport.FORMAT) throw InvalidExportException("format")
        if (data.version > 1) throw InvalidExportException("version")
        return ImportPreview(
            data, data.sessions.size, data.books.size, data.books.count { it.favourite },
            data.annotations.size, data.bookmarks.size, data.profile != null,
        )
    }

    /**
     * Merge the file into this install. Nothing is overwritten: existing sessions, highlights and bookmarks are
     * recognised and skipped. With [syncToServer] the imported changes are queued for Grimmory, otherwise they
     * stay on this device.
     */
    suspend fun import(preview: ImportPreview, syncToServer: Boolean): ImportResult = withContext(Dispatchers.IO) {
        val data = preview.data
        val queue = syncToServer && !settings.app.value.noServer
        val known = knownBooks()
        val byKey = HashMap<String, Known>()
        for (k in known) byKey.putIfAbsent(key(k.title, k.authors), k)
        // Books we cannot find keep a stable private id so their stats stay grouped by book.
        fun orphanId(k: String) = -(1_000_000_000L + (k.hashCode().toLong() and 0x7FFFFFFFL) % 1_000_000_000L)
        fun idFor(k: String) = byKey[k]?.id ?: orphanId(k)

        // Profile and goals: only on a fresh install, so a configured profile is never overwritten.
        val app = settings.app.value
        if (app.profile.name.isBlank() || app.profile.name == "Demo-lezer") {
            data.profile?.let { p -> settings.updateApp { it.copy(profile = it.profile.copy(name = p.name, avatar = p.avatar, yearlyBookGoal = p.yearlyBookGoal, favouriteGenres = p.favouriteGenres)) } }
            data.goal?.let { g -> settings.updateGoal { g } }
        }

        // Read list
        var matched = 0
        var missing = 0
        var favourites = 0
        val existingFavs = db.favourites().ids().toSet()
        for (b in data.books) {
            val target = byKey[b.key]
            if (target == null) { missing++; continue }
            matched++
            if (b.favourite && target.id !in existingFavs) {
                db.favourites().add(FavouriteEntity(target.id)); favourites++
                if (queue && !target.local) db.outbox().insert(OutboxEntity(type = OutboxType.FAVOURITE_ADD, bookId = target.id))
            }
            if (target.local) {
                b.progress?.let { pr ->
                    val current = db.localBooks().get(target.id)?.readProgress ?: 0f
                    if (pr > current) db.localBooks().updateProgress(target.id, pr, b.lastReadTime ?: java.time.Instant.now().toString())
                }
                b.readStatus?.let { s -> if (db.localBooks().get(target.id)?.readStatus == null) db.localBooks().updateStatus(target.id, s) }
            } else {
                val entity = db.books().get(target.id) ?: continue
                var changed = entity
                if (entity.readStatus == null && b.readStatus != null) {
                    changed = changed.copy(readStatus = b.readStatus)
                    if (queue) db.outbox().insert(OutboxEntity(type = OutboxType.STATUS, bookId = target.id, payload = b.readStatus))
                }
                if (entity.personalRating == null && b.rating != null) {
                    changed = changed.copy(personalRating = b.rating)
                    if (queue) db.outbox().insert(OutboxEntity(type = OutboxType.RATING, bookId = target.id, payload = b.rating.toString()))
                }
                if (changed != entity) db.books().upsertAll(listOf(changed))
            }
        }

        // Reading position: keep whichever is further along.
        for (p in data.progress) {
            val id = byKey[p.bookKey]?.id ?: continue
            val current = db.progress().get(id)
            if (current == null || current.percent < p.percent) {
                db.progress().upsert(ProgressEntity(id, current?.bookFileId, p.cfi, p.href, p.percent, current?.ttsCfi, p.updatedAt, dirty = queue && id > 0))
            }
        }

        // Sessions (stats)
        val sessionKeys = db.sessions().all().map { it.startTime to it.bookTitle }.toHashSet()
        var sessionsAdded = 0
        for (s in data.sessions) {
            if ((s.startTime to s.bookTitle) in sessionKeys) continue
            val id = idFor(s.bookKey)
            db.sessions().insert(
                ReadingSessionEntity(
                    bookId = id, bookTitle = s.bookTitle, bookType = s.bookType, startTime = s.startTime, endTime = s.endTime,
                    durationSeconds = s.durationSeconds, startProgress = s.startProgress, endProgress = s.endProgress, startCfi = null, endCfi = null,
                    bytesRead = s.bytesRead, pagesTurned = s.pagesTurned, localDate = s.localDate, listening = s.listening,
                    synced = !(queue && id > 0),
                )
            )
            sessionsAdded++
        }
        for (s in data.sections) {
            val id = idFor(s.bookKey)
            if (db.sectionStats().get(id, s.sectionIndex) == null) db.sectionStats().upsert(SectionStatEntity(id, s.sectionIndex, s.label, s.seconds, s.pages, s.finished))
        }

        // Highlights and bookmarks (only for books we can find)
        val knownAnnotations = db.annotations().all().map { it.bookId to it.cfi }.toHashSet()
        var highlights = 0
        for (a in data.annotations) {
            val id = byKey[a.bookKey]?.id ?: continue
            if ((id to a.cfi) in knownAnnotations) continue
            db.annotations().insert(AnnotationEntity(bookId = id, cfi = a.cfi, text = a.text, color = a.color, style = a.style, note = a.note, chapterTitle = a.chapterTitle, createdAt = a.createdAt, pending = if (queue && id > 0) PendingAction.CREATE else PendingAction.NONE))
            highlights++
        }
        val knownBookmarks = db.bookmarks().all().map { it.bookId to it.cfi }.toHashSet()
        var bookmarks = 0
        for (b in data.bookmarks) {
            val id = byKey[b.bookKey]?.id ?: continue
            if ((id to b.cfi) in knownBookmarks) continue
            db.bookmarks().insert(BookmarkEntity(bookId = id, cfi = b.cfi, title = b.title, percent = b.percent, createdAt = b.createdAt, pending = if (queue && id > 0) PendingAction.CREATE else PendingAction.NONE))
            bookmarks++
        }

        if (queue) sync.requestSync()
        ImportResult(sessionsAdded, matched, missing, favourites, highlights, bookmarks, queue)
    }
}
