package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.PendingAction
import com.vdelaar.mylibby.core.database.ShelfBookEntity
import com.vdelaar.mylibby.core.database.ShelfEntity
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.toBook
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.network.BookSummaryDto
import com.vdelaar.mylibby.core.network.PageResponse
import com.vdelaar.mylibby.core.network.ShelfCreateRequest
import com.vdelaar.mylibby.core.network.ShelfDto
import com.vdelaar.mylibby.core.network.ShelvesAssignmentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** The id the screens use for the built-in Favourites shelf (kept in its own table, see FavouriteEntity). */
const val FAVOURITES_SHELF = 0L

/** The server refused with [code] (any other failure is an IOException or similar). */
class ShelfRemoteException(val code: Int) : Exception("HTTP $code")

/** A shelf name that is not allowed, with the reason. */
class ShelfNameException(val error: ShelfNameError) : Exception(error.name)

/** What the shelves need from Grimmory; the tests use a fake. */
interface ShelfRemote {
    suspend fun shelves(): List<ShelfDto>
    suspend fun create(request: ShelfCreateRequest): ShelfDto
    suspend fun update(id: Long, request: ShelfCreateRequest): ShelfDto
    suspend fun delete(id: Long)
    suspend fun assign(bookIds: Set<Long>, add: Set<Long>, remove: Set<Long>)
    suspend fun books(shelfId: Long, page: Int): PageResponse<BookSummaryDto>
}

class GrimmoryShelfRemote(private val api: ApiProvider) : ShelfRemote {
    private suspend fun <T> http(block: suspend () -> T): T =
        try { block() } catch (e: HttpException) { throw ShelfRemoteException(e.code()) }

    private fun check(r: retrofit2.Response<*>) { if (!r.isSuccessful) throw ShelfRemoteException(r.code()) }

    override suspend fun shelves() = http { api.grimmory().shelves() }
    override suspend fun create(request: ShelfCreateRequest) = http { api.grimmory().createShelf(request) }
    override suspend fun update(id: Long, request: ShelfCreateRequest) = http { api.grimmory().updateShelf(id, request) }
    override suspend fun delete(id: Long) = http { check(api.grimmory().deleteShelf(id)) }
    override suspend fun assign(bookIds: Set<Long>, add: Set<Long>, remove: Set<Long>) = http {
        check(api.grimmory().assignShelves(ShelvesAssignmentRequest(bookIds, add, remove)))
    }
    override suspend fun books(shelfId: Long, page: Int) = http { api.grimmory().books(page = page, size = BOOKS_PER_PAGE, shelfId = shelfId) }

    companion object { const val BOOKS_PER_PAGE = 100 }
}

/** A shelf for the Shelves tab: the shelf, how many books are on it and the covers to show. */
data class ShelfCard(
    val id: Long,
    val name: String,
    val count: Int,
    val covers: List<Book>,
    /** The built-in Favourites shelf: it cannot be renamed or deleted. */
    val favourites: Boolean = false,
    /** Somebody else's public shelf on Grimmory: read-only. */
    val shared: Boolean = false,
    /** Also on Grimmory (false: on this device only, or not sent yet). */
    val synced: Boolean = false,
)

/**
 * Shelves: lists the reader makes ("To read on holiday", "Dutch classics"). They work on the device alone; with Grimmory
 * connected the same shelves and their books are kept in step with the server. Changes are written to the database first
 * (so they show at once and survive being offline) and sent when possible; what is waiting is flagged with a
 * [PendingAction], the way highlights and bookmarks are. Imported books (negative ids) never go to the server.
 */
class ShelfRepository(
    private val db: AppDatabase,
    private val remote: ShelfRemote,
    private val scope: CoroutineScope,
    /** A Grimmory connection exists and is switched on. */
    private val connected: () -> Boolean,
    private val favouritesShelfId: () -> Long?,
    /** Keeps the books of a server shelf in the local cache, so they show offline. */
    private val cacheBooks: suspend (List<Book>) -> Unit,
    private val requestSync: () -> Unit,
) {
    private val lock = Mutex()
    private val dao get() = db.shelves()

    // Reading -----------------------------------------------------------------------------------

    /** Favourites first, then the reader's shelves by name; each with its newest covers. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCards(): Flow<List<ShelfCard>> =
        combine(dao.observeRows(), dao.observeAllMembers(), db.favourites().observeAll()) { rows, members, favs -> Triple(rows, members, favs) }
            .mapLatest { (rows, members, favs) ->
                val byShelf = members.groupBy { it.shelfId }
                val coverIds = LinkedHashMap<Long, List<Long>>()
                coverIds[FAVOURITES_SHELF] = favs.map { it.bookId }.take(COVERS)
                for (r in rows) coverIds[r.shelf.localId] = byShelf[r.shelf.localId].orEmpty().map { it.bookId }.take(COVERS)
                val books = booksById(coverIds.values.flatten().distinct()).associateBy { it.id }
                fun covers(shelf: Long) = coverIds.getValue(shelf).mapNotNull { books[it] }
                buildList {
                    add(ShelfCard(FAVOURITES_SHELF, "", favs.size, covers(FAVOURITES_SHELF), favourites = true, synced = connected()))
                    for (r in rows) add(ShelfCard(r.shelf.localId, r.shelf.name, r.bookCount, covers(r.shelf.localId), shared = r.shelf.shared, synced = r.shelf.remoteId != null))
                }
            }

    fun observeShelf(id: Long): Flow<ShelfEntity?> = dao.observe(id)

    /** The books on a shelf, the newest addition first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBooks(shelfId: Long): Flow<List<Book>> {
        val ids: Flow<List<Long>> =
            if (shelfId == FAVOURITES_SHELF) db.favourites().observeAll().map { l -> l.map { it.bookId } }
            else dao.observeMembers(shelfId).map { l -> l.map { it.bookId } }
        return ids.mapLatest { booksById(it) }
    }

    /** For each shelf (the Favourites shelf is [FAVOURITES_SHELF]), how many of [bookIds] are on it. */
    fun observeMembership(bookIds: Set<Long>): Flow<Map<Long, Int>> {
        val ids = bookIds.toList()
        return combine(dao.observeMembersOf(ids), db.favourites().observeAll()) { members, favs ->
            val counts = members.groupingBy { it.shelfId }.eachCount().toMutableMap()
            val favCount = favs.count { it.bookId in bookIds }
            if (favCount > 0) counts[FAVOURITES_SHELF] = favCount
            counts
        }
    }

    /** The reader's shelves a single book is on (Favourites not included), for the chips on its page. */
    fun observeShelvesOfBook(bookId: Long): Flow<List<ShelfEntity>> =
        combine(dao.observeRows(), dao.observeMembersOf(listOf(bookId))) { rows, members ->
            val on = members.map { it.shelfId }.toSet()
            rows.map { it.shelf }.filter { it.localId in on }
        }

    private suspend fun booksById(ids: List<Long>): List<Book> {
        if (ids.isEmpty()) return emptyList()
        val server = ids.filter { it > 0 }.chunked(500).flatMap { db.books().getMany(it) }.associate { it.id to it.toBook() }
        val local = ids.filter { it < 0 }.chunked(500).flatMap { db.localBooks().getMany(it) }.associate { it.id to it.toBook() }
        return ids.mapNotNull { server[it] ?: local[it] }
    }

    private suspend fun ownNames(except: Long? = null): List<String> =
        dao.all().filter { !it.shared && it.pending != PendingAction.DELETE && it.localId != except }.map { it.name }

    // Changing ----------------------------------------------------------------------------------

    /** Makes a shelf. Throws [ShelfNameException] when the name is not allowed. */
    suspend fun create(raw: String): ShelfEntity {
        val name = cleanShelfName(raw)
        checkShelfName(name, ownNames())?.let { throw ShelfNameException(it) }
        val id = dao.insert(ShelfEntity(name = name, icon = DEFAULT_ICON, iconType = DEFAULT_ICON_TYPE))
        pushSoon()
        return dao.get(id)!!
    }

    suspend fun rename(id: Long, raw: String) {
        val shelf = dao.get(id) ?: return
        if (shelf.shared) return
        val name = cleanShelfName(raw)
        if (name == shelf.name) return
        checkShelfName(name, ownNames(except = id))?.let { throw ShelfNameException(it) }
        // Not on the server yet: it goes there under the new name; otherwise the rename is sent.
        dao.update(shelf.copy(name = name, pending = if (shelf.remoteId == null || shelf.pending == PendingAction.CREATE) PendingAction.CREATE else PendingAction.UPDATE))
        pushSoon()
    }

    /** Deletes a shelf; its books stay in the library. */
    suspend fun delete(id: Long) {
        val shelf = dao.get(id) ?: return
        if (shelf.shared) return
        if (shelf.remoteId == null) dao.delete(id) // the server never heard of it
        else dao.update(shelf.copy(pending = PendingAction.DELETE))
        pushSoon()
    }

    /** Puts books on a shelf (Favourites is not handled here, see LibraryRepository.setFavourites). */
    suspend fun add(shelfId: Long, bookIds: Collection<Long>) {
        val shelf = dao.get(shelfId) ?: return
        if (shelf.shared || shelf.pending == PendingAction.DELETE) return
        val have = dao.members(shelfId).associateBy { it.bookId }
        val now = System.currentTimeMillis()
        val rows = bookIds.distinct().mapIndexedNotNull { i, id ->
            val row = have[id]
            when {
                row == null -> ShelfBookEntity(shelfId, id, now + i, if (id > 0) PendingAction.CREATE else PendingAction.NONE)
                row.pending == PendingAction.DELETE -> row.copy(pending = PendingAction.NONE) // the server still has it
                else -> null
            }
        }
        if (rows.isEmpty()) return
        dao.upsertMembers(rows)
        pushSoon()
    }

    suspend fun remove(shelfId: Long, bookIds: Collection<Long>) {
        val shelf = dao.get(shelfId) ?: return
        if (shelf.shared) return
        val rows = dao.members(shelfId).filter { it.bookId in bookIds }
        val unsent = rows.filter { it.bookId < 0 || it.pending == PendingAction.CREATE || shelf.remoteId == null }.map { it.bookId }
        if (unsent.isNotEmpty()) dao.deleteMembers(shelfId, unsent)
        val tombstones = rows.filter { it.bookId !in unsent }.map { it.copy(pending = PendingAction.DELETE) }
        if (tombstones.isNotEmpty()) dao.upsertMembers(tombstones)
        pushSoon()
    }

    /** A book that has been deleted from the device. */
    suspend fun forgetBook(bookId: Long) = dao.deleteBook(bookId)

    // Grimmory ----------------------------------------------------------------------------------

    private fun pushSoon() {
        if (!connected()) return
        scope.launch {
            val failed = try { push() } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
            if (failed) requestSync()
        }
    }

    /** True when something is still waiting to be sent. */
    suspend fun hasPending(): Boolean =
        dao.all().any { it.pending != PendingAction.NONE } || dao.pendingMembers().isNotEmpty()

    private fun ShelfEntity.toRequest() =
        ShelfCreateRequest(name = name, icon = icon, iconType = iconType, publicShelf = publicShelf)

    private fun ShelfEntity.sent(dto: ShelfDto) =
        copy(remoteId = dto.id, name = dto.name, icon = dto.icon ?: icon, iconType = dto.iconType ?: iconType, publicShelf = dto.publicShelf, pending = PendingAction.NONE)

    /** Sends what is waiting: deleted shelves first (a new shelf may take the name), then new and renamed ones, then the books. Returns true when something could not be sent. */
    suspend fun push(): Boolean = lock.withLock {
        if (!connected()) return false
        var failed = false
        for (s in dao.all().filter { it.pending == PendingAction.DELETE }) {
            val id = s.remoteId
            if (id == null) { dao.delete(s.localId); continue }
            try {
                remote.delete(id)
                dao.delete(s.localId)
            } catch (e: ShelfRemoteException) {
                when (e.code) {
                    404 -> dao.delete(s.localId) // already gone
                    403 -> dao.update(s.copy(pending = PendingAction.NONE, shared = true)) // not ours: it stays, read-only
                    else -> failed = true
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        for (s in dao.all().filter { it.pending == PendingAction.CREATE && it.remoteId == null }) {
            try {
                val dto = try {
                    remote.create(s.toRequest())
                } catch (e: ShelfRemoteException) {
                    // The name exists on the server (a shelf made there, or by an earlier try): it is that shelf.
                    if (e.code != 400 && e.code != 409) throw e
                    remote.shelves().firstOrNull { !it.publicShelf && it.name.equals(s.name, ignoreCase = true) } ?: throw e
                }
                dao.update(s.sent(dto))
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        for (s in dao.all().filter { it.pending == PendingAction.UPDATE && it.remoteId != null }) {
            try {
                remote.update(s.remoteId!!, s.toRequest())
                dao.update(s.copy(pending = PendingAction.NONE))
            } catch (e: ShelfRemoteException) {
                when (e.code) {
                    404 -> resend(s) // deleted on the server meanwhile: put it there again
                    403 -> dao.update(s.copy(pending = PendingAction.NONE, shared = true))
                    400, 409 -> dao.update(s.copy(pending = PendingAction.NONE)) // the name is taken there: the server's name comes back at the next read
                    else -> failed = true
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        if (!pushMembers()) failed = true
        failed || hasPending()
    }

    /** Sends a shelf (and its Grimmory books) as a new one. */
    private suspend fun resend(s: ShelfEntity) {
        dao.update(s.copy(remoteId = null, pending = PendingAction.CREATE))
        val rows = dao.members(s.localId).filter { it.bookId > 0 && it.pending == PendingAction.NONE }
        if (rows.isNotEmpty()) dao.upsertMembers(rows.map { it.copy(pending = PendingAction.CREATE) })
    }

    private suspend fun pushMembers(): Boolean {
        var ok = true
        val shelves = dao.all().associateBy { it.localId }
        // A device book never goes to the server, whatever it was marked.
        val (device, grimmory) = dao.pendingMembers().partition { it.bookId < 0 }
        for (row in device) if (row.pending == PendingAction.DELETE) dao.deleteMembers(row.shelfId, listOf(row.bookId)) else dao.upsertMembers(listOf(row.copy(pending = PendingAction.NONE)))
        for ((key, rows) in grimmory.groupBy { it.shelfId to it.pending }) {
            val (shelfId, action) = key
            val shelf = shelves[shelfId]
            val remoteId = shelf?.remoteId
            if (shelf == null || remoteId == null) { ok = false; continue } // the shelf itself is not on the server yet
            for (chunk in rows.chunked(CHUNK)) {
                val ids = chunk.map { it.bookId }
                try {
                    if (action == PendingAction.CREATE) remote.assign(ids.toSet(), setOf(remoteId), emptySet())
                    else remote.assign(ids.toSet(), emptySet(), setOf(remoteId))
                    if (action == PendingAction.CREATE) dao.upsertMembers(chunk.map { it.copy(pending = PendingAction.NONE) }) else dao.deleteMembers(shelfId, ids)
                } catch (e: ShelfRemoteException) {
                    // 403/404: not ours, or the book or shelf is gone. Drop the request; the next read shows how things really are.
                    if (e.code == 403 || e.code == 404) dao.deleteMembers(shelfId, ids) else ok = false
                } catch (e: CancellationException) { throw e } catch (_: Exception) { ok = false }
            }
        }
        return ok
    }

    /** Reads the server's shelves and what is on them. Returns true when that did not work. */
    suspend fun pull(): Boolean = lock.withLock {
        if (!connected()) return false
        val all = try { remote.shelves() } catch (e: CancellationException) { throw e } catch (_: Exception) { return true }
        val favId = favouritesShelfId()
        val favourites = all.firstOrNull { it.id == favId } ?: all.firstOrNull { isReserved(it.name) && !it.publicShelf }
        val me = favourites?.userId
        val shelves = all.filter { !isReserved(it.name) && it.id != favId }.map {
            RemoteShelf(it.id, it.name, it.icon, it.iconType, it.publicShelf, shared = it.publicShelf && me != null && it.userId != me, bookCount = it.bookCount)
        }
        val plan = planShelves(dao.all(), shelves)
        for (id in plan.drop) dao.delete(id)
        for (id in plan.recreate) dao.get(id)?.let { resend(it) }
        for (r in plan.add) dao.insert(ShelfEntity(remoteId = r.id, name = r.name, icon = r.icon, iconType = r.iconType, publicShelf = r.publicShelf, shared = r.shared, pending = PendingAction.NONE))
        for ((id, r) in plan.link) dao.get(id)?.let { dao.update(it.copy(remoteId = r.id, name = r.name, icon = r.icon ?: it.icon, iconType = r.iconType ?: it.iconType, publicShelf = r.publicShelf, shared = r.shared, pending = PendingAction.NONE)) }
        for ((id, r) in plan.refresh) dao.get(id)?.let { dao.update(it.copy(name = r.name, icon = r.icon, iconType = r.iconType, publicShelf = r.publicShelf, shared = r.shared)) }

        var failed = false
        for (shelf in dao.all()) {
            val remoteId = shelf.remoteId ?: continue
            if (shelf.pending == PendingAction.DELETE) continue
            val known = shelves.firstOrNull { it.id == remoteId } ?: continue
            try {
                val ids = if (known.bookCount == 0) emptySet() else fetchBooks(remoteId)
                val local = dao.members(shelf.localId)
                val diff = planMembers(local, ids)
                val now = System.currentTimeMillis()
                if (diff.add.isNotEmpty()) dao.upsertMembers(diff.add.mapIndexed { i, id -> ShelfBookEntity(shelf.localId, id, now - i, PendingAction.NONE) })
                if (diff.remove.isNotEmpty()) dao.deleteMembers(shelf.localId, diff.remove.toList())
                if (diff.confirm.isNotEmpty()) dao.upsertMembers(local.filter { it.bookId in diff.confirm }.map { it.copy(pending = PendingAction.NONE) })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        failed
    }

    private suspend fun fetchBooks(shelfId: Long): Set<Long> {
        val ids = LinkedHashSet<Long>()
        var page = 0
        do {
            val res = remote.books(shelfId, page)
            val books = res.content.map { it.toBook() }
            cacheBooks(books)
            ids += books.map { it.id }
            page++
        } while (res.hasNext && page < MAX_PAGES)
        return ids
    }

    private fun isReserved(name: String) = RESERVED_SHELF_NAMES.any { it.equals(name, ignoreCase = true) }

    companion object {
        const val COVERS = 4
        const val DEFAULT_ICON = "bookmark"
        const val DEFAULT_ICON_TYPE = "LUCIDE"
        private const val CHUNK = 100
        private const val MAX_PAGES = 100
    }
}

/**
 * Signing out of Grimmory: the reader's shelves stay on the device and are sent again to the next server. Their Grimmory
 * books leave with the book cache (and the ids differ from server to server), so only the device's own books stay on them;
 * somebody else's public shelves go.
 */
suspend fun detachShelvesFromServer(db: AppDatabase) {
    val dao = db.shelves()
    for (s in dao.all()) {
        if (s.pending == PendingAction.DELETE || s.shared) { dao.delete(s.localId); continue }
        dao.update(s.copy(remoteId = null, pending = PendingAction.CREATE))
        dao.deleteMembers(s.localId, dao.members(s.localId).filter { it.bookId > 0 }.map { it.bookId })
    }
}
