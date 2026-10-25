package com.vdelaar.mylibby

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.BookEntity
import com.vdelaar.mylibby.core.database.LocalBookEntity
import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import com.vdelaar.mylibby.data.KoboImportRepository
import com.vdelaar.mylibby.data.NotKoboDatabaseException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The Kobo import against real SQLite and Room (in memory), with Grimmory replaced by fakes. */
@RunWith(AndroidJUnit4::class)
class KoboImportInstrumentedTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private val pulled = mutableListOf<Long>()
    private var pushes = 0
    private var pushResult = true
    private var pullResult = -1

    private val t0 = 1_777_649_549L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        file = File(context.cacheDir, "test-kobo.sqlite").also { it.delete() }
    }

    @After fun tearDown() { db.close(); file.delete() }

    private fun repo() = KoboImportRepository(context, db, pullBook = { id, _ -> pulled += id; pullResult }, pushSessions = { pushes++; pushResult })

    private fun book(id: Long, title: String, authors: String = "Someone", type: String? = "EPUB") = BookEntity(
        id = id, title = title, authors = authors, seriesName = null, seriesNumber = null, categories = "", language = null, readStatus = null,
        readProgress = null, lastReadTime = null, addedOn = null, primaryFileId = null, primaryFileType = type, coverUpdatedOn = null, pageCount = null,
    )

    /** Page turns [n] pages apart by [step] seconds from [start]: one session lasting (n - 1) * step seconds. */
    private fun turns(start: Long, n: Int, step: Int = 20) = (0 until n).map { start + it * step }

    private fun blob(turns: List<Long>): ByteArray {
        val bytes = ByteArrayOutputStream(); val o = DataOutputStream(bytes)
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_16BE); o.writeInt(b.size); o.write(b) }
        o.writeInt(3)
        str("wordsRead"); o.writeInt(2); o.writeByte(0); o.writeInt(turns.size)
        str("eventTimestamps"); o.writeInt(9); o.writeByte(0); o.writeInt(turns.size)
        turns.forEach { o.writeInt(2); o.writeByte(0); o.writeInt(it.toInt()) }
        str("ViewType"); o.writeInt(10); o.writeByte(0); str("ReadingView")
        return bytes.toByteArray()
    }

    private class KoboRow(val contentId: String, val title: String, val author: String?, val isbn: String?, val type: Int = 46, val data: ByteArray?)

    private fun writeKobo(vararg rows: KoboRow) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { d ->
            d.execSQL("CREATE TABLE content (ContentID TEXT PRIMARY KEY, Title TEXT, Attribution TEXT, ISBN TEXT)")
            d.execSQL("CREATE TABLE Event (EventType INTEGER NOT NULL, FirstOccurrence TEXT, LastOccurrence TEXT, EventCount INTEGER DEFAULT 0, ContentID TEXT, ExtraData BLOB, Checksum TEXT)")
            for (r in rows.distinctBy { it.contentId }) d.execSQL("INSERT INTO content VALUES (?, ?, ?, ?)", arrayOf(r.contentId, r.title, r.author, r.isbn))
            for (r in rows) d.execSQL("INSERT INTO Event (EventType, ContentID, ExtraData) VALUES (?, ?, ?)", arrayOf(r.type, r.contentId, r.data))
        }
    }

    // Reading the Kobo database -----------------------------------------------------------------------------------

    @Test fun readsBooksWithPageTurns() {
        writeKobo(
            KoboRow("a", "De waarheid", "Sophie", "978", data = blob(turns(t0, 5))),
            KoboRow("b", "Nothing read", "X", null, data = blob(emptyList())),
            KoboRow("c", "Other event", "X", null, type = 5, data = blob(turns(t0, 5))),
            KoboRow("d", "No data", "X", null, data = null),
            KoboRow("e", "Garbage", "X", null, data = byteArrayOf(1, 2, 3)),
        )
        val books = KoboImportRepository.readKoboBooks(file)
        assertEquals(listOf("De waarheid"), books.map { it.title })
        assertEquals("Sophie", books[0].author)
        assertEquals("978", books[0].isbn)
        assertEquals(turns(t0, 5), books[0].turns)
    }

    @Test fun twoEventRowsOfOneBookAreMerged() {
        writeKobo(
            KoboRow("a", "Boek", null, null, data = blob(turns(t0, 3))),
            KoboRow("a", "Boek", null, null, data = blob(turns(t0 + 5000, 3))),
        )
        val b = KoboImportRepository.readKoboBooks(file).single()
        assertEquals(6, b.turns.size)
        assertEquals(b.turns.sorted(), b.turns)
    }

    @Test fun aDatabaseThatIsNotAKoboOneIsRefused() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("CREATE TABLE other (x INTEGER)") }
        try { KoboImportRepository.readKoboBooks(file); fail("expected NotKoboDatabaseException") } catch (_: NotKoboDatabaseException) {}
    }

    @Test fun aFileThatIsNotADatabaseIsRefused() {
        file.writeText("this is not sqlite at all, just text".repeat(50))
        try { KoboImportRepository.readKoboBooks(file); fail("expected NotKoboDatabaseException") } catch (_: NotKoboDatabaseException) {}
    }

    // Preview and import ------------------------------------------------------------------------------------------

    @Test fun previewMatchesServerAndLocalBooksAndListsTheRest() = runBlocking {
        db.books().upsertAll(listOf(book(10, "De waarheid", "Sophie Kinsella")))
        db.localBooks().upsert(LocalBookEntity(-1, "Mijn boek", "Ik", null, "EPUB", "/x.epub", null, 1))
        writeKobo(
            KoboRow("a", "De waarheid", "Sophie Kinsella", null, data = blob(turns(t0, 10))),
            KoboRow("b", "Mijn boek", "Ik", null, data = blob(turns(t0 + 10_000, 10))),
            KoboRow("c", "Onbekend", "Niemand", null, data = blob(turns(t0 + 20_000, 10))),
            KoboRow("d", "Glanced", "X", null, data = blob(turns(t0 + 30_000, 1))),
        )
        val p = repo().preview(Uri.fromFile(file))
        assertEquals(setOf(10L, -1L), p.matches.map { it.book.id }.toSet())
        assertEquals(listOf("Onbekend"), p.notFound)
        assertEquals(2, p.newSessions)
        assertEquals(2 * 180L, p.newSeconds)
        assertEquals(0, p.alreadyHere)
        assertEquals(listOf(10L), pulled) // only Grimmory books are looked up on the server
        assertEquals(0, db.sessions().all().size) // a preview changes nothing in the statistics
        assertTrue(file.exists()) // the picked file itself is never touched
    }

    @Test fun importAddsSessionsWithTheRightFields() = runBlocking {
        db.books().upsertAll(listOf(book(10, "De waarheid", type = "PDF")))
        writeKobo(KoboRow("a", "De waarheid", null, null, data = blob(turns(t0, 10) + turns(t0 + 5000, 4, step = 30))))
        val r = repo()
        val res = r.import(r.preview(Uri.fromFile(file)), sendToGrimmory = false)
        assertEquals(2, res.added)
        assertFalse(res.sentToGrimmory); assertFalse(res.queued)
        assertEquals(0, pushes)
        val s = db.sessions().all()
        assertEquals(listOf(t0 * 1000, (t0 + 5000) * 1000), s.map { it.startTime })
        assertEquals(listOf(180, 90), s.map { it.durationSeconds })
        assertEquals(listOf(10, 4), s.map { it.pagesTurned })
        s.forEach {
            assertEquals(10L, it.bookId); assertEquals("De waarheid", it.bookTitle); assertEquals("PDF", it.bookType)
            assertEquals(ReadingSessionEntity.SOURCE_KOBO, it.source); assertTrue(it.synced)
            assertFalse(it.listening); assertEquals(0L, it.bytesRead)
            assertEquals(it.endTime - it.startTime, it.durationSeconds * 1000L)
            assertEquals(java.time.Instant.ofEpochMilli(it.startTime).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(), it.localDate)
        }
    }

    @Test fun importingTwiceAddsNothingTheSecondTime() = runBlocking {
        db.books().upsertAll(listOf(book(10, "De waarheid")))
        writeKobo(KoboRow("a", "De waarheid", null, null, data = blob(turns(t0, 10))))
        val r = repo()
        assertEquals(1, r.import(r.preview(Uri.fromFile(file)), false).added)
        val again = r.preview(Uri.fromFile(file))
        assertEquals(0, again.newSessions); assertEquals(1, again.alreadyHere); assertTrue(again.matches.isEmpty())
        assertEquals(0, r.import(again, false).added)
        assertEquals(1, db.sessions().all().size)
    }

    @Test fun aSessionAlreadyHereWithinFiveSecondsIsNotAddedAgain() = runBlocking {
        db.books().upsertAll(listOf(book(10, "De waarheid")))
        db.sessions().insert(
            ReadingSessionEntity(bookId = 10, bookTitle = "De waarheid", bookType = "EPUB", startTime = t0 * 1000 + 3000, endTime = t0 * 1000 + 9000, durationSeconds = 6,
                startProgress = 0f, endProgress = 0f, startCfi = null, endCfi = null, bytesRead = 0, pagesTurned = 0, localDate = "2026-05-01", synced = true)
        )
        writeKobo(KoboRow("a", "De waarheid", null, null, data = blob(turns(t0, 10))))
        val r = repo()
        val p = r.preview(Uri.fromFile(file))
        assertEquals(0, p.newSessions); assertEquals(1, p.alreadyHere)
    }

    @Test fun sendingToGrimmoryLeavesGrimmoryBooksUnsyncedAndPushes() = runBlocking {
        db.books().upsertAll(listOf(book(10, "De waarheid")))
        db.localBooks().upsert(LocalBookEntity(-1, "Mijn boek", "Ik", null, "EPUB", "/x.epub", null, 1))
        writeKobo(
            KoboRow("a", "De waarheid", null, null, data = blob(turns(t0, 10))),
            KoboRow("b", "Mijn boek", null, null, data = blob(turns(t0 + 9000, 10))),
        )
        val r = repo()
        val res = r.import(r.preview(Uri.fromFile(file)), sendToGrimmory = true)
        assertEquals(2, res.added)
        assertEquals(1, pushes)
        assertTrue(res.sentToGrimmory); assertFalse(res.queued)
        val byBook = db.sessions().all().associateBy { it.bookId }
        assertFalse(byBook.getValue(10).synced) // waiting for the push (the fake push does not mark anything)
        assertTrue(byBook.getValue(-1).synced) // a book on the device only never goes to Grimmory
        assertTrue(10L in pulled) // fetched first, so nothing Grimmory has is sent twice
    }

    @Test fun whenThePushFailsTheSessionsStayQueued() = runBlocking {
        db.books().upsertAll(listOf(book(10, "De waarheid")))
        writeKobo(KoboRow("a", "De waarheid", null, null, data = blob(turns(t0, 10))))
        pushResult = false
        val r = repo()
        val res = r.import(r.preview(Uri.fromFile(file)), sendToGrimmory = true)
        assertEquals(1, res.added); assertFalse(res.sentToGrimmory); assertTrue(res.queued)
        assertEquals(1, db.sessions().unsynced().size)
    }

    @Test fun sessionsOfABookNotInTheLibraryAreSkippedNotLost() = runBlocking {
        writeKobo(KoboRow("a", "Onbekend", null, null, data = blob(turns(t0, 10))))
        val r = repo()
        val p = r.preview(Uri.fromFile(file))
        assertEquals(listOf("Onbekend"), p.notFound)
        assertEquals(0, r.import(p, false).added)
    }

    @Test fun anUnreadableUriGivesAnError() = runBlocking {
        try { repo().preview(Uri.parse("content://com.vdelaar.mylibby.nope/none")); fail("expected an exception") } catch (_: Exception) {}
    }
}
