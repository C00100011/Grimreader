package com.vdelaar.mylibby

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.BookEntity
import com.vdelaar.mylibby.core.database.FavouriteEntity
import com.vdelaar.mylibby.core.database.LocalBookEntity
import com.vdelaar.mylibby.core.database.PendingAction
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.BookSummaryDto
import com.vdelaar.mylibby.core.network.PageResponse
import com.vdelaar.mylibby.core.network.ShelfCreateRequest
import com.vdelaar.mylibby.core.network.ShelfDto
import com.vdelaar.mylibby.data.FAVOURITES_SHELF
import com.vdelaar.mylibby.data.ShelfNameError
import com.vdelaar.mylibby.data.ShelfNameException
import com.vdelaar.mylibby.data.ShelfRemote
import com.vdelaar.mylibby.data.ShelfRemoteException
import com.vdelaar.mylibby.data.ShelfRepository
import com.vdelaar.mylibby.data.detachShelvesFromServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** What Grimmory does with shelves (names unique per reader, only the owner may change one, a public shelf of somebody else is read-only), in memory. */
private class FakeGrimmory : ShelfRemote {
    val me = 1L
    val shelves = LinkedHashMap<Long, ShelfDto>()
    val members = HashMap<Long, MutableSet<Long>>()
    val log = ArrayList<String>()
    var down = false
    private var nextId = 100L

    fun seed(id: Long, name: String, user: Long = me, public: Boolean = false, books: Set<Long> = emptySet()) {
        shelves[id] = ShelfDto(id, name, "bookmark", "LUCIDE", user, public, 0)
        members[id] = books.toMutableSet()
    }

    private fun up() { if (down) throw IOException("server down") }
    private fun own(id: Long): ShelfDto {
        val s = shelves[id] ?: throw ShelfRemoteException(404)
        if (s.userId != me) throw ShelfRemoteException(403)
        return s
    }

    override suspend fun shelves(): List<ShelfDto> {
        up(); log += "GET shelves"
        return shelves.values.filter { it.userId == me || it.publicShelf }.map { it.copy(bookCount = members[it.id]?.size ?: 0) }
    }

    override suspend fun create(request: ShelfCreateRequest): ShelfDto {
        up(); log += "POST ${request.name}"
        if (shelves.values.any { it.userId == me && it.name == request.name }) throw ShelfRemoteException(409)
        val id = nextId++
        seed(id, request.name)
        return shelves.getValue(id)
    }

    override suspend fun update(id: Long, request: ShelfCreateRequest): ShelfDto {
        up(); log += "PUT $id ${request.name}"
        val s = own(id)
        if (shelves.values.any { it.userId == me && it.id != id && it.name == request.name }) throw ShelfRemoteException(409)
        return s.copy(name = request.name).also { shelves[id] = it }
    }

    override suspend fun delete(id: Long) {
        up(); log += "DELETE $id"
        own(id); shelves.remove(id); members.remove(id)
    }

    override suspend fun assign(bookIds: Set<Long>, add: Set<Long>, remove: Set<Long>) {
        up(); log += "ASSIGN $bookIds +$add -$remove"
        (add + remove).forEach { own(it) }
        add.forEach { members.getValue(it).addAll(bookIds) }
        remove.forEach { members.getValue(it).removeAll(bookIds) }
    }

    override suspend fun books(shelfId: Long, page: Int): PageResponse<BookSummaryDto> {
        up(); log += "BOOKS $shelfId"
        val ids = members[shelfId].orEmpty().sorted()
        return PageResponse(content = ids.map { BookSummaryDto(id = it, title = "Book $it") }, totalElements = ids.size.toLong())
    }
}

@RunWith(AndroidJUnit4::class)
class ShelfRepositoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var server: FakeGrimmory
    private lateinit var shelves: ShelfRepository
    private var connected = false
    private var favouritesShelf: Long? = null
    private val cached = ArrayList<Book>()
    private var syncRequests = 0

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = FakeGrimmory()
        shelves = ShelfRepository(
            db, server,
            // A change starts a push in the background; the tests call push() themselves to wait for it (it takes turns with the other).
            scope = CoroutineScope(Dispatchers.Default),
            connected = { connected },
            favouritesShelfId = { favouritesShelf },
            cacheBooks = { books -> cached += books; db.books().upsertAll(books.map { bookEntity(it.id, it.title) }) },
            requestSync = { syncRequests++ },
        )
    }

    @After fun tearDown() { db.close() }

    private fun bookEntity(id: Long, title: String = "Book $id") = BookEntity(
        id = id, title = title, authors = "Author $id", seriesName = null, seriesNumber = null, categories = "", language = null, readStatus = null,
        readProgress = null, lastReadTime = null, addedOn = null, primaryFileId = null, primaryFileType = "EPUB", coverUpdatedOn = null, pageCount = null,
    )

    private fun device(id: Long, title: String = "Own $id") = LocalBookEntity(id = id, title = title, authors = "Me", language = null, fileType = "EPUB", filePath = "/x/$id.epub", coverPath = null, sizeBytes = 1)

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(25) }
        return condition()
    }

    private fun library(vararg ids: Long) = runBlocking {
        db.books().upsertAll(ids.filter { it > 0 }.map { bookEntity(it) })
        ids.filter { it < 0 }.forEach { db.localBooks().upsert(device(it)) }
    }

    /** Waits for what is waiting to be sent to have been sent. */
    private fun settle() = runBlocking { shelves.push() }

    private fun cards() = runBlocking { shelves.observeCards().first() }
    private fun idOf(name: String) = cards().first { it.name == name }.id
    private fun titles(shelf: Long) = runBlocking { shelves.observeBooks(shelf).first() }.map { it.id }

    // On the device alone ---------------------------------------------------------------------

    @Test fun favouritesAreTheFirstCardAndCountTheFavourites() = runBlocking {
        library(1, 2)
        db.favourites().add(FavouriteEntity(1)); db.favourites().add(FavouriteEntity(2))
        val first = cards().first()
        assertTrue(first.favourites)
        assertEquals(FAVOURITES_SHELF, first.id)
        assertEquals(2, first.count)
        assertEquals(setOf(1L, 2L), titles(FAVOURITES_SHELF).toSet())
    }

    @Test fun aShelfIsMadeAndListedByName() = runBlocking {
        shelves.create("Zebra"); shelves.create("apple"); shelves.create("Mango")
        assertEquals(listOf("apple", "Mango", "Zebra"), cards().drop(1).map { it.name })
        assertEquals(0, cards()[1].count)
    }

    @Test fun badNamesAreRefused() = runBlocking {
        shelves.create("Holiday")
        for ((name, expected) in listOf("  " to ShelfNameError.BLANK, "holiday" to ShelfNameError.DUPLICATE, "Favorites" to ShelfNameError.RESERVED, "x".repeat(61) to ShelfNameError.TOO_LONG)) {
            try { shelves.create(name); fail("$name should be refused") } catch (e: ShelfNameException) { assertEquals(expected, e.error) }
        }
        assertEquals(1, cards().size - 1)
    }

    @Test fun booksOfTheLibraryAndOfTheDeviceGoOnAShelf() = runBlocking {
        library(1, 2, -1)
        val shelf = shelves.create("Mixed")
        shelves.add(shelf.localId, listOf(1, -1, 2))
        assertEquals(setOf(1L, 2L, -1L), titles(shelf.localId).toSet())
        assertEquals(3, cards().first { it.name == "Mixed" }.count)
        shelves.remove(shelf.localId, listOf(2, -1))
        assertEquals(listOf(1L), titles(shelf.localId))
    }

    @Test fun addingTwiceKeepsOneRow() = runBlocking {
        library(1)
        val shelf = shelves.create("Once")
        shelves.add(shelf.localId, listOf(1)); shelves.add(shelf.localId, listOf(1, 1))
        assertEquals(1, db.shelves().members(shelf.localId).size)
    }

    @Test fun theNewestAdditionComesFirst() = runBlocking {
        library(1, 2, 3)
        val shelf = shelves.create("Order")
        shelves.add(shelf.localId, listOf(1)); Thread.sleep(5)
        shelves.add(shelf.localId, listOf(2)); Thread.sleep(5)
        shelves.add(shelf.localId, listOf(3))
        assertEquals(listOf(3L, 2L, 1L), titles(shelf.localId))
    }

    @Test fun membershipCountsHowManyOfTheBooksAreOnEachShelf() = runBlocking {
        library(1, 2, 3)
        val a = shelves.create("A"); val b = shelves.create("B")
        shelves.add(a.localId, listOf(1, 2)); shelves.add(b.localId, listOf(1, 2, 3))
        db.favourites().add(FavouriteEntity(3))
        val m = shelves.observeMembership(setOf(1, 2, 3)).first()
        assertEquals(2, m[a.localId]); assertEquals(3, m[b.localId]); assertEquals(1, m[FAVOURITES_SHELF])
    }

    @Test fun renameChangesTheNameAndChecksTheOthers() = runBlocking {
        val a = shelves.create("A"); shelves.create("B")
        shelves.rename(a.localId, "  New   name ")
        assertEquals("New name", db.shelves().get(a.localId)!!.name)
        try { shelves.rename(a.localId, "b"); fail() } catch (e: ShelfNameException) { assertEquals(ShelfNameError.DUPLICATE, e.error) }
        shelves.rename(a.localId, "New name") // its own name is fine
    }

    @Test fun deletingAShelfLeavesTheBooks() = runBlocking {
        library(1)
        val shelf = shelves.create("Gone"); shelves.add(shelf.localId, listOf(1))
        shelves.delete(shelf.localId)
        assertNull(db.shelves().get(shelf.localId))
        assertTrue(db.shelves().members(shelf.localId).isEmpty())
        assertNotNull(db.books().get(1))
    }

    @Test fun aDeletedDeviceBookLeavesItsShelves() = runBlocking {
        library(-5)
        val shelf = shelves.create("Mine"); shelves.add(shelf.localId, listOf(-5))
        shelves.forgetBook(-5)
        assertTrue(db.shelves().members(shelf.localId).isEmpty())
    }

    @Test fun withoutAServerNothingIsSentAndNothingRequested() = runBlocking {
        library(1)
        val shelf = shelves.create("Offline"); shelves.add(shelf.localId, listOf(1))
        assertTrue(server.log.isEmpty())
        assertEquals(0, syncRequests)
    }

    // Together with Grimmory ----------------------------------------------------------------------

    @Test fun aNewShelfAndItsGrimmoryBooksAreSentButDeviceBooksStayHome() = runBlocking {
        connected = true
        library(1, 2, -1)
        val shelf = shelves.create("Holiday")
        shelves.add(shelf.localId, listOf(1, 2, -1))
        assertFalse(settle())
        val remoteId = db.shelves().get(shelf.localId)!!.remoteId!!
        assertEquals("Holiday", server.shelves.getValue(remoteId).name)
        assertEquals(setOf(1L, 2L), server.members.getValue(remoteId))
        assertTrue("nothing is waiting", db.shelves().pendingMembers().isEmpty())
        assertEquals(setOf(1L, 2L, -1L), db.shelves().members(shelf.localId).map { it.bookId }.toSet())
    }

    @Test fun shelvesMadeOfflineGoUpWhenTheServerIsConnected() = runBlocking {
        library(1, -2)
        val shelf = shelves.create("Before"); shelves.add(shelf.localId, listOf(1, -2))
        assertTrue(server.shelves.isEmpty())
        connected = true
        assertFalse(shelves.push())
        val remoteId = db.shelves().get(shelf.localId)!!.remoteId!!
        assertEquals(setOf(1L), server.members.getValue(remoteId))
    }

    @Test fun renameDeleteAndRemovalAreSent() = runBlocking {
        connected = true
        library(1, 2)
        val shelf = shelves.create("Plan"); shelves.add(shelf.localId, listOf(1, 2))
        assertFalse(settle())
        val remoteId = db.shelves().get(shelf.localId)!!.remoteId!!
        shelves.rename(shelf.localId, "Plan B")
        assertFalse(settle())
        assertEquals("Plan B", server.shelves.getValue(remoteId).name)
        shelves.remove(shelf.localId, listOf(1))
        assertFalse(settle())
        assertEquals(setOf(2L), server.members.getValue(remoteId))
        assertEquals(listOf(2L), titles(shelf.localId))
        shelves.delete(shelf.localId)
        assertFalse(settle())
        assertFalse(server.shelves.containsKey(remoteId))
        assertNull(db.shelves().get(shelf.localId))
    }

    @Test fun aShelfWhoseNameExistsOnTheServerBecomesThatShelf() = runBlocking {
        server.seed(50, "Classics", books = setOf(9))
        connected = true
        val shelf = shelves.create("Classics")
        assertFalse(settle())
        assertEquals(50L, db.shelves().get(shelf.localId)!!.remoteId)
        assertEquals(1, server.shelves.size)
    }

    @Test fun whenTheServerIsDownChangesWaitAndAreSentLater() = runBlocking {
        connected = true
        library(1)
        server.down = true
        val shelf = shelves.create("Later"); shelves.add(shelf.localId, listOf(1))
        assertTrue("push reports the failure", settle())
        assertTrue("a retry was asked for", waitFor { syncRequests > 0 })
        assertTrue(shelves.hasPending())
        assertEquals(PendingAction.CREATE, db.shelves().get(shelf.localId)!!.pending)
        server.down = false
        assertFalse(shelves.push())
        assertFalse(shelves.hasPending())
        assertEquals(setOf(1L), server.members.getValue(db.shelves().get(shelf.localId)!!.remoteId!!))
    }

    @Test fun pullBringsInTheServersShelvesAndBooksButNotFavorites() = runBlocking {
        server.seed(7, "Favorites", books = setOf(1))
        server.seed(8, "From the web", books = setOf(2, 3))
        connected = true
        favouritesShelf = 7
        assertFalse(shelves.pull())
        assertEquals(listOf("From the web"), cards().drop(1).map { it.name })
        assertEquals(setOf(2L, 3L), titles(idOf("From the web")).toSet())
        assertEquals(setOf(2L, 3L), cached.map { it.id }.toSet())
    }

    @Test fun aPublicShelfOfSomebodyElseIsReadOnly() = runBlocking {
        server.seed(7, "Favorites")
        server.seed(60, "Staff picks", user = 2, public = true, books = setOf(4))
        connected = true
        favouritesShelf = 7
        shelves.pull()
        val staff = idOf("Staff picks")
        assertTrue(db.shelves().get(staff)!!.shared)
        library(4, 5)
        shelves.add(staff, listOf(5)); shelves.rename(staff, "Mine now"); shelves.remove(staff, listOf(4)); shelves.delete(staff)
        assertEquals("Staff picks", db.shelves().get(staff)!!.name)
        assertEquals(listOf(4L), titles(staff))
        assertFalse(server.log.any { it.startsWith("PUT") || it.startsWith("DELETE") || it.startsWith("ASSIGN") })
    }

    @Test fun whatChangedOnTheServerComesIn() = runBlocking {
        server.seed(7, "Favorites")
        server.seed(8, "Web", books = setOf(1, 2))
        server.seed(9, "Doomed")
        connected = true; favouritesShelf = 7
        shelves.pull()
        server.members.getValue(8).apply { remove(1); add(3) }
        server.shelves[8] = server.shelves.getValue(8).copy(name = "Web 2")
        server.shelves.remove(9); server.members.remove(9)
        shelves.pull()
        assertEquals(listOf("Web 2"), cards().drop(1).map { it.name })
        assertEquals(setOf(2L, 3L), titles(idOf("Web 2")).toSet())
    }

    @Test fun changesWaitingToBeSentSurviveAPull() = runBlocking {
        server.seed(7, "Favorites")
        server.seed(8, "Web", books = setOf(1))
        connected = true; favouritesShelf = 7
        shelves.pull()
        val id = idOf("Web")
        library(1, 2)
        connected = false // the changes cannot go out
        shelves.rename(id, "Mine"); shelves.add(id, listOf(2)); shelves.remove(id, listOf(1))
        connected = true
        shelves.pull() // reads the server: still "Web" with book 1
        assertEquals("Mine", db.shelves().get(id)!!.name)
        assertEquals(listOf(2L), titles(id))
        assertFalse(shelves.push())
        assertEquals("Mine", server.shelves.getValue(8).name)
        assertEquals(setOf(2L), server.members.getValue(8))
    }

    @Test fun aShelfDeletedOnTheServerButRenamedHereComesBack() = runBlocking {
        server.seed(7, "Favorites")
        server.seed(8, "Web", books = setOf(1))
        connected = true; favouritesShelf = 7
        shelves.pull()
        val id = idOf("Web")
        connected = false
        shelves.rename(id, "Edited")
        connected = true
        server.shelves.remove(8); server.members.remove(8)
        shelves.pull()
        assertNull(db.shelves().get(id)!!.remoteId)
        assertFalse(shelves.push())
        val again = db.shelves().get(id)!!
        assertEquals("Edited", server.shelves.getValue(again.remoteId!!).name)
        assertEquals(setOf(1L), server.members.getValue(again.remoteId!!))
    }

    @Test fun aRenameToATakenNameIsDroppedAndTheServersNameReturns() = runBlocking {
        server.seed(7, "Favorites")
        server.seed(8, "One"); server.seed(9, "Two")
        connected = true; favouritesShelf = 7
        shelves.pull()
        val id = idOf("One")
        server.shelves[9] = server.shelves.getValue(9).copy(name = "Uno") // renamed on the web meanwhile, so the app does not know
        connected = false
        shelves.rename(id, "Uno")
        connected = true
        assertFalse(shelves.push()) // 409: given up, not retried for ever
        shelves.pull()
        assertEquals("One", db.shelves().get(id)!!.name)
    }

    @Test fun signingOutKeepsTheShelvesWithTheDeviceBooksOnly() = runBlocking {
        server.seed(7, "Favorites")
        server.seed(8, "Web", books = setOf(1))
        server.seed(60, "Staff picks", user = 2, public = true, books = setOf(2))
        connected = true; favouritesShelf = 7
        library(-3)
        shelves.pull()
        shelves.add(idOf("Web"), listOf(-3))
        settle()
        connected = false // signing out clears the tokens first, so a push still on its way does nothing
        detachShelvesFromServer(db)
        assertEquals(listOf("Web"), cards().drop(1).map { it.name })
        val web = db.shelves().get(idOf("Web"))!!
        assertNull(web.remoteId)
        assertEquals(PendingAction.CREATE, web.pending)
        assertEquals(listOf(-3L), db.shelves().members(web.localId).map { it.bookId })
    }

    @Test fun reconnectingToAnotherServerSendsTheShelvesThere() = runBlocking {
        library(-3)
        val shelf = shelves.create("Portable"); shelves.add(shelf.localId, listOf(-3))
        detachShelvesFromServer(db)
        val other = FakeGrimmory()
        val moved = ShelfRepository(db, other, CoroutineScope(Dispatchers.Unconfined), { true }, { null }, { }, { })
        assertFalse(moved.push())
        assertEquals(listOf("Portable"), other.shelves.values.map { it.name })
    }
}
