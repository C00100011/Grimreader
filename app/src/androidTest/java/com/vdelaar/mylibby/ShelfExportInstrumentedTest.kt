package com.vdelaar.mylibby

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.BookEntity
import com.vdelaar.mylibby.core.database.LocalBookEntity
import com.vdelaar.mylibby.core.database.PendingAction
import com.vdelaar.mylibby.core.database.ShelfBookEntity
import com.vdelaar.mylibby.core.database.ShelfEntity
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.data.PersonalDataRepository
import com.vdelaar.mylibby.data.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Shelves in "Export my data" / import, against real Room (in memory). */
@RunWith(AndroidJUnit4::class)
class ShelfExportInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var data: PersonalDataRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        data = PersonalDataRepository(db, SettingsRepository(context, CoroutineScope(Dispatchers.Default)), SyncScheduler(context))
    }

    @After fun tearDown() { db.close() }

    private fun book(id: Long, title: String, authors: String) = BookEntity(
        id = id, title = title, authors = authors, seriesName = null, seriesNumber = null, categories = "", language = null, readStatus = null,
        readProgress = null, lastReadTime = null, addedOn = null, primaryFileId = null, primaryFileType = "EPUB", coverUpdatedOn = null, pageCount = null,
    )

    private suspend fun library() {
        db.books().upsertAll(listOf(book(1, "Dracula", "Bram Stoker"), book(2, "Frankenstein", "Mary Shelley")))
        db.localBooks().upsert(LocalBookEntity(id = -1, title = "My own", authors = "Me", language = null, fileType = "EPUB", filePath = "/x", coverPath = null, sizeBytes = 1))
    }

    private suspend fun shelf(name: String, vararg books: Long, shared: Boolean = false, pending: PendingAction = PendingAction.NONE): Long {
        val id = db.shelves().insert(ShelfEntity(name = name, shared = shared, pending = pending))
        db.shelves().upsertMembers(books.map { ShelfBookEntity(id, it) })
        return id
    }

    @Test fun shelvesAndTheirBooksSurviveAnExportAndImport() = runBlocking {
        library()
        shelf("Gothic", 1, 2); shelf("Mine", -1); shelf("Empty")
        val file = data.export()
        for (s in db.shelves().all()) db.shelves().delete(s.localId)
        assertTrue(db.shelves().all().isEmpty())

        val preview = data.preview(file)
        assertEquals(3, preview.shelves)
        val result = data.import(preview, syncToServer = false)
        assertEquals(3, result.shelvesAdded)

        val byName = db.shelves().all().associateBy { it.name }
        assertEquals(setOf("Gothic", "Mine", "Empty"), byName.keys)
        assertEquals(setOf(1L, 2L), db.shelves().members(byName.getValue("Gothic").localId).map { it.bookId }.toSet())
        assertEquals(listOf(-1L), db.shelves().members(byName.getValue("Mine").localId).map { it.bookId })
        // A restored shelf goes to Grimmory when one is connected; the device book stays home.
        assertEquals(PendingAction.CREATE, byName.getValue("Gothic").pending)
        assertEquals(PendingAction.CREATE, db.shelves().members(byName.getValue("Gothic").localId).first().pending)
        assertEquals(PendingAction.NONE, db.shelves().members(byName.getValue("Mine").localId).first().pending)
    }

    @Test fun importingTwiceDoesNotDuplicate() = runBlocking {
        library(); shelf("Gothic", 1)
        val file = data.export()
        val first = data.import(data.preview(file), syncToServer = false)
        val second = data.import(data.preview(file), syncToServer = false)
        assertEquals(0, first.shelvesAdded); assertEquals(0, second.shelvesAdded)
        assertEquals(1, db.shelves().all().size)
        assertEquals(1, db.shelves().members(db.shelves().all().single().localId).size)
    }

    @Test fun anImportedShelfTopsUpOneWithTheSameName() = runBlocking {
        library(); shelf("Gothic", 1)
        val file = data.export()
        val other = db.shelves().all().single()
        db.shelves().deleteMembers(other.localId, listOf(1))
        db.shelves().upsertMembers(listOf(ShelfBookEntity(other.localId, 2)))
        data.import(data.preview(file), syncToServer = false)
        assertEquals(setOf(1L, 2L), db.shelves().members(other.localId).map { it.bookId }.toSet())
        assertEquals(1, db.shelves().all().size)
    }

    @Test fun sharedShelvesAndDeletedShelvesAreNotExported() = runBlocking {
        library()
        shelf("Staff picks", 1, shared = true); shelf("Gone", 1, pending = PendingAction.DELETE); shelf("Mine", 2)
        val preview = data.preview(data.export())
        assertEquals(listOf("Mine"), preview.data.shelves.map { it.name })
    }

    @Test fun booksNotInTheLibraryAreSkippedAndFilesWithoutShelvesStillImport() = runBlocking {
        library()
        val old = data.export() // a file from before shelves existed has none
        assertEquals(0, data.preview(old).shelves)
        val noShelvesField = old.replace(Regex(",\\s*\"shelves\"\\s*:\\s*\\[\\s*\\]"), "")
        assertFalse(noShelvesField.contains("\"shelves\""))
        assertEquals(0, data.preview(noShelvesField).shelves)

        shelf("Gothic", 1, 2)
        val file = data.export()
        db.books().clear() // the books are gone from this install
        for (s in db.shelves().all()) db.shelves().delete(s.localId)
        val result = data.import(data.preview(file), syncToServer = false)
        assertEquals(1, result.shelvesAdded)
        assertTrue("shelf made, but no books to put on it", db.shelves().members(db.shelves().all().single().localId).isEmpty())
    }
}
