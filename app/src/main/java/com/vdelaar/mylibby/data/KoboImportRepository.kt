package com.vdelaar.mylibby.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** The chosen file is not a Kobo database (or not readable as one). */
class NotKoboDatabaseException : Exception("Not a Kobo database")

/** A Kobo book that is in the library, with the sessions that are new here. */
data class KoboMatch(val book: KnownBook, val koboTitle: String, val sessions: List<KoboSession>) {
    val seconds: Long get() = sessions.sumOf { it.seconds.toLong() }
}

/** What is on the Kobo: books found in the library (with new sessions) and books that were not. */
data class KoboPreview(
    /** Only books that have new sessions. */
    val matches: List<KoboMatch>,
    val notFound: List<String>,
    val koboBooks: Int,
    /** Sessions of matched books that this device already has. */
    val alreadyHere: Int = 0,
) {
    val newSessions: Int get() = matches.sumOf { it.sessions.size }
    val newSeconds: Long get() = matches.sumOf { it.seconds }
    val firstAt: Long? get() = matches.flatMap { it.sessions }.minOfOrNull { it.start }
    val lastAt: Long? get() = matches.flatMap { it.sessions }.maxOfOrNull { it.end }
}

data class KoboImportResult(val added: Int, val sentToGrimmory: Boolean, val queued: Boolean)

/**
 * Experimental: reads the reading sessions out of the database of a Kobo e-reader (`.kobo/KoboReader.sqlite`, reached by
 * connecting the e-reader and picking the file) and adds them to the reading statistics. Optionally sends them to Grimmory.
 */
class KoboImportRepository(
    private val context: Context,
    private val db: AppDatabase,
    /** Fetches one book's sessions from Grimmory (returns how many were added, -1 if it could not be reached). */
    private val pullBook: suspend (bookId: Long, title: String?) -> Int,
    /** Sends the sessions that are waiting to Grimmory; true when all went through. */
    private val pushSessions: suspend () -> Boolean,
) {
    /** Reads the database behind [uri] and works out which sessions would be new. Nothing is changed. */
    suspend fun preview(uri: Uri): KoboPreview = withContext(Dispatchers.IO) {
        val copy = File(context.cacheDir, "kobo-import.sqlite")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw NotKoboDatabaseException()
            preview(readKoboBooks(copy))
        } finally {
            copy.delete()
        }
    }

    /** [tree] is the Kobo drive (or its .kobo folder) as the user picked it; the database is looked up inside. */
    suspend fun previewDrive(tree: Uri): KoboPreview = withContext(Dispatchers.IO) {
        preview(findDatabase(tree) ?: throw NotKoboDatabaseException())
    }

    private fun findDatabase(tree: Uri): Uri? {
        val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
        fun child(parent: String, name: String): String? {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            context.contentResolver.query(
                children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0) }
            return null
        }
        val id = (child(root, ".kobo") ?: root.takeIf { it.endsWith(".kobo") })?.let { child(it, "KoboReader.sqlite") }
            ?: child(root, "KoboReader.sqlite")
        return id?.let { android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, it) }
    }

    internal suspend fun preview(kobo: List<KoboBook>): KoboPreview {
        val known = knownBooks()
        val pairs = kobo.mapNotNull { b ->
            val sessions = koboSessions(b.turns)
            if (sessions.isEmpty()) null else Triple(b, matchKoboBook(b, known), sessions) // opened but never really read: skipped
        }
        // What Grimmory already has of these books (read in the web reader, imported before) counts as "already here".
        for (id in pairs.mapNotNull { it.second?.id }.filter { it > 0 }.distinct()) pullBook(id, null)
        val starts = db.sessions().starts().groupBy({ it.bookId }, { it.startTime })
        val matches = ArrayList<KoboMatch>()
        val notFound = ArrayList<String>()
        var already = 0
        for ((b, book, sessions) in pairs) {
            if (book == null) { notFound += b.title; continue }
            val have = starts[book.id].orEmpty()
            val (dupes, fresh) = sessions.partition { isDuplicateSession(have, it.start * 1000) }
            already += dupes.size
            if (fresh.isNotEmpty()) matches += KoboMatch(book, b.title, fresh)
        }
        return KoboPreview(matches.sortedByDescending { it.seconds }, notFound, kobo.size, already)
    }

    /**
     * Adds the sessions of [preview]. With [sendToGrimmory] the sessions of Grimmory books are sent as well; sessions
     * Grimmory already has are fetched first so nothing is sent twice.
     */
    suspend fun import(preview: KoboPreview, sendToGrimmory: Boolean): KoboImportResult = withContext(Dispatchers.IO) {
        val send = sendToGrimmory
        if (send) {
            // A session sent by an earlier import (or another tool) is on the server already: fetch first, then skip those.
            for (m in preview.matches) if (m.book.id > 0) pullBook(m.book.id, m.book.title)
        }
        val starts = db.sessions().starts().groupBy({ it.bookId }, { it.startTime }).mapValues { it.value.toMutableList() }.toMutableMap()
        var added = 0
        var queued = false
        for (m in preview.matches) {
            val have = starts.getOrPut(m.book.id) { ArrayList() }
            for (s in m.sessions) {
                if (isDuplicateSession(have, s.start * 1000)) continue
                val toServer = send && m.book.id > 0
                db.sessions().insert(
                    ReadingSessionEntity(
                        bookId = m.book.id, bookTitle = m.book.title, bookType = m.book.fileType ?: "EPUB",
                        startTime = s.start * 1000, endTime = s.end * 1000, durationSeconds = s.seconds,
                        startProgress = 0f, endProgress = 0f, startCfi = null, endCfi = null,
                        bytesRead = 0, pagesTurned = s.pages,
                        localDate = Instant.ofEpochSecond(s.start).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                        synced = !toServer, source = ReadingSessionEntity.SOURCE_KOBO,
                    )
                )
                have += s.start * 1000
                added++
                if (toServer) queued = true
            }
        }
        val sent = queued && pushSessions()
        KoboImportResult(added, sentToGrimmory = sent, queued = queued && !sent)
    }

    private suspend fun knownBooks(): List<KnownBook> =
        db.books().all().map { KnownBook(it.id, it.title, it.authors.replace(com.vdelaar.mylibby.core.database.BookEntity.SEP, ", "), it.primaryFileType) } +
            db.localBooks().all().map { KnownBook(it.id, it.title, it.authors, it.fileType) }

    companion object {
        /** Reads every book with page turns from a Kobo database file. */
        internal fun readKoboBooks(file: File): List<KoboBook> {
            val sql = try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (_: Exception) {
                throw NotKoboDatabaseException()
            }
            sql.use { d ->
                val byBook = LinkedHashMap<String, KoboBook>()
                try {
                    d.rawQuery(
                        "SELECT c.ContentID, c.Title, c.Attribution, c.ISBN, e.ExtraData FROM Event e " +
                            "JOIN content c ON c.ContentID = e.ContentID WHERE e.EventType = 46 AND e.ExtraData IS NOT NULL", null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue
                            val turns = koboPageTurns(c.getBlob(4))
                            if (turns.isEmpty()) continue
                            val old = byBook[id]
                            byBook[id] = KoboBook(c.getString(1).orEmpty(), c.getString(2), c.getString(3), (old?.turns.orEmpty() + turns).sorted())
                        }
                    }
                } catch (_: android.database.sqlite.SQLiteException) {
                    throw NotKoboDatabaseException() // no Event/content table: some other database
                }
                return byBook.values.filter { it.title.isNotBlank() }
            }
        }
    }
}
