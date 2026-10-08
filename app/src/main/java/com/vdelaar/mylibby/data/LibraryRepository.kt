package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.database.DownloadState
import com.vdelaar.mylibby.core.database.FavouriteEntity
import com.vdelaar.mylibby.core.database.OutboxEntity
import com.vdelaar.mylibby.core.database.OutboxType
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.model.FilterOptions
import com.vdelaar.mylibby.core.model.LibraryFilter
import com.vdelaar.mylibby.core.model.SeriesInfo
import com.vdelaar.mylibby.core.model.mergeWith
import com.vdelaar.mylibby.core.model.toBook
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.network.AuthorSummaryDto
import com.vdelaar.mylibby.core.network.BookReviewDto
import com.vdelaar.mylibby.core.network.PersonalRatingRequest
import com.vdelaar.mylibby.core.network.ShelfCreateRequest
import com.vdelaar.mylibby.core.network.ShelvesAssignmentRequest
import com.vdelaar.mylibby.core.network.UpdateStatusRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class BookPage(val books: List<Book>, val hasNext: Boolean, val total: Long, val offline: Boolean = false)

/** Books we think you'll like, with the genres they were picked for. */
data class Suggestions(val books: List<Book>, val genres: List<String>)

data class HomeData(
    val continueReading: List<Book>,
    val recentlyAdded: List<Book>,
    val offline: Boolean,
)

class LibraryRepository(
    private val api: ApiProvider,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val sync: SyncScheduler,
) : HardcoverLibrary {
    // Grimmory creates a "Favorites" shelf for every user; reuse it (accept both spellings).
    private val favouritesShelfNames = listOf("Favorites", "Favourites")

    suspend fun cache(books: List<Book>) {
        if (books.isEmpty()) return
        val merged = books.map { b -> db.books().get(b.id).mergeWith(b) }
        db.books().upsertAll(merged)
    }

    suspend fun books(filter: LibraryFilter, page: Int, pageSize: Int = 30): BookPage = withContext(Dispatchers.IO) {
        if (filter.localOnly || settings.app.value.noServer) return@withContext localPage(filter)
        if (filter.downloadedOnly) return@withContext downloadedPage(filter)
        withDeviceBooks(serverPage(filter, page, pageSize), filter, page)
    }

    /**
     * With "This device" switched on, the books imported here come first in Library (page 0), next to the server's.
     * Favourites-only lists stay as they are: a local favourite is not on the server's shelf.
     */
    private suspend fun withDeviceBooks(p: BookPage, filter: LibraryFilter, page: Int): BookPage {
        if (page > 0 || !settings.app.value.deviceOn || filter.favouritesOnly) return p
        val own = localPage(filter).books
        if (own.isEmpty()) return p
        return p.copy(books = own + p.books, total = p.total + own.size)
    }

    private suspend fun serverPage(filter: LibraryFilter, page: Int, pageSize: Int): BookPage {
        try {
            val shelfId = if (filter.favouritesOnly) ensureFavouritesShelf() else null
            val res = api.grimmory().books(
                page = page,
                size = pageSize,
                sort = filter.sort,
                dir = filter.dir,
                search = filter.search.ifBlank { null },
                shelfId = shelfId,
                authors = filter.authors.toList().ifEmpty { null },
                series = filter.series.toList().ifEmpty { null },
                category = filter.genres.toList().ifEmpty { null },
                language = filter.languages.toList().ifEmpty { null },
                status = filter.statuses.toList().ifEmpty { null },
            )
            val books = res.content.map { it.toBook() }
            cache(books)
            return BookPage(books, res.hasNext, res.totalElements)
        } catch (e: Exception) {
            if (page > 0) throw e
            return offlinePage(filter)
        }
    }

    private suspend fun offlinePage(filter: LibraryFilter): BookPage {
        val favIds = if (filter.favouritesOnly) db.favourites().ids().toSet() else null
        val books = db.books().search(filter.search.trim()).map { it.toBook() }.filter { b ->
            (favIds == null || b.id in favIds) &&
                (filter.authors.isEmpty() || b.authors.any { it in filter.authors }) &&
                (filter.series.isEmpty() || b.seriesName in filter.series) &&
                (filter.genres.isEmpty() || b.categories.any { it in filter.genres }) &&
                (filter.languages.isEmpty() || b.language in filter.languages) &&
                (filter.statuses.isEmpty() || b.readStatus in filter.statuses)
        }
        return BookPage(books, false, books.size.toLong(), offline = true)
    }

    private suspend fun localPage(filter: LibraryFilter): BookPage {
        val q = filter.search.trim()
        val books = db.localBooks().all().map { it.toBook() }.filter { b ->
            (q.isEmpty() || b.title.contains(q, true) || b.authors.any { it.contains(q, true) }) &&
                (filter.authors.isEmpty() || b.authors.any { it in filter.authors }) &&
                (filter.series.isEmpty() || b.seriesName in filter.series) &&
                (filter.genres.isEmpty() || b.categories.any { it in filter.genres }) &&
                (filter.languages.isEmpty() || b.language in filter.languages) &&
                (filter.statuses.isEmpty() || b.readStatus in filter.statuses)
        }
        return BookPage(books, false, books.size.toLong())
    }

    private suspend fun downloadedPage(filter: LibraryFilter): BookPage {
        val ids = db.downloads().all().filter { it.state == DownloadState.DONE }.map { it.bookId }.toSet()
        val page = offlinePage(filter.copy(downloadedOnly = false))
        // "On device" = downloaded from Grimmory + imported books.
        val books = page.books.filter { it.id in ids } + localPage(filter).books
        return BookPage(books, false, books.size.toLong(), offline = page.offline)
    }

    suspend fun home(): HomeData = withContext(Dispatchers.IO) {
        if (settings.app.value.noServer) {
            // Demo: the bundled books are shown in the "own books" row; only books being read go on top.
            val reading = db.localBooks().all().map { it.toBook() }
                .filter { (it.progress ?: 0f) in 0.1f..99.5f }
                .sortedByDescending { it.lastReadTime ?: "" }
            return@withContext HomeData(reading, emptyList(), offline = false)
        }
        try {
            val g = api.grimmory()
            // continue-reading misses books whose progress came in through the per-file progress
            // API, so merge it with books currently marked as reading.
            val reading = runCatching {
                g.books(size = 12, sort = "lastReadTime", dir = "desc", status = listOf("READING", "RE_READING")).content
            }.getOrDefault(emptyList())
            val cont = (g.continueReading(12) + reading)
                .distinctBy { it.id }
                .sortedByDescending { it.lastReadTime ?: "" }
                .map { it.toBook() }
            val recent = g.recentlyAdded(20).map { it.toBook() }
            cache(cont + recent)
            HomeData(cont, recent, offline = false)
        } catch (_: Exception) {
            val recentRead = db.books().recentlyRead(12).map { it.toBook() }
                .filter { (it.progress ?: 0f) in 0.1f..99.5f }
            val all = db.books().search("").map { it.toBook() }
            HomeData(recentRead, all.take(20), offline = true)
        }
    }

    suspend fun detail(id: Long): Book = withContext(Dispatchers.IO) {
        if (id < 0) return@withContext db.localBooks().get(id)?.toBook() ?: error("Book not found")
        try {
            val dto = api.grimmory().bookDetail(id)
            val book = dto.toBook()
            cache(listOf(book))
            // Keep favourites in sync with the server's shelf membership.
            val favShelf = settings.app.value.favouritesShelfId
            if (favShelf != null && dto.shelves != null) {
                val onShelf = dto.shelves.any { it.id == favShelf }
                val pending = db.outbox().all().any { it.bookId == id && it.type in FAV_TYPES }
                if (!pending) {
                    if (onShelf) db.favourites().add(FavouriteEntity(id)) else db.favourites().remove(id)
                }
            }
            book
        } catch (e: Exception) {
            db.books().get(id)?.toBook() ?: throw e
        }
    }

    fun observeBook(id: Long): Flow<com.vdelaar.mylibby.core.database.BookEntity?> = db.books().observe(id)

    suspend fun filterOptions(): FilterOptions = withContext(Dispatchers.IO) {
        val o = api.grimmory().filterOptions()
        FilterOptions(
            authors = o.authors.map { it.name to it.count },
            series = o.series.map { it.name to it.count },
            genres = o.categories.map { it.name to it.count },
            languages = o.languages.map { Triple(it.code, it.label ?: it.code, it.count) },
            statuses = o.readStatuses.map { it.name to it.count },
        )
    }

    suspend fun series(search: String? = null): List<SeriesInfo> = withContext(Dispatchers.IO) {
        val q = search?.trim().orEmpty()
        if (settings.app.value.noServer) return@withContext seriesOf(localBooks(), q)
        try {
            api.grimmory().series(0, 60, q.ifBlank { null }).content.map {
                SeriesInfo(
                    name = it.seriesName,
                    bookCount = it.bookCount,
                    total = it.seriesTotal,
                    authors = it.authors.orEmpty(),
                    booksRead = it.booksRead,
                    coverBookIds = it.coverBooks.orEmpty().mapNotNull { c -> c.bookId },
                )
            }
        } catch (e: Exception) {
            // Offline: what we have cached still tells which series there are.
            val cached = db.books().search("").map { it.toBook() }
            if (cached.isEmpty()) throw e
            seriesOf(cached, q)
        }
    }

    /** Authors of the library, optionally filtered by name. Falls back to local / cached books without a server. */
    suspend fun authors(search: String? = null): List<AuthorSummaryDto> = withContext(Dispatchers.IO) {
        val q = search?.trim().orEmpty()
        if (settings.app.value.noServer) return@withContext authorsOf(localBooks(), q)
        try {
            api.grimmory().authors(search = q.ifBlank { null }).content
        } catch (e: Exception) {
            val cached = db.books().search("").map { it.toBook() }
            if (cached.isEmpty()) throw e
            authorsOf(cached, q)
        }
    }

    private suspend fun localBooks(): List<Book> = db.localBooks().all().map { it.toBook() }

    suspend fun seriesBooks(name: String): List<Book> = withContext(Dispatchers.IO) {
        try {
            val books = api.grimmory().seriesBooks(name).content.map { it.toBook() }
                .sortedBy { it.seriesNumber ?: Float.MAX_VALUE }
            cache(books)
            books
        } catch (e: Exception) {
            db.books().search("").map { it.toBook() }.filter { it.seriesName == name }
                .sortedBy { it.seriesNumber ?: Float.MAX_VALUE }
                .ifEmpty { throw e }
        }
    }

    suspend fun authorBooks(name: String): List<Book> =
        books(LibraryFilter(authors = setOf(name), sort = "title", dir = "asc"), 0, 100).books

    // Favourites --------------------------------------------------------------------------------

    fun observeFavouriteIds(): Flow<List<FavouriteEntity>> = db.favourites().observeAll()

    fun observeIsFavourite(bookId: Long): Flow<Boolean> = db.favourites().observeIsFavourite(bookId)

    /** Returns the id of the user's "Favourites" shelf on Grimmory, creating it if needed. */
    @Volatile private var shelfVerified = false

    suspend fun ensureFavouritesShelf(): Long = withContext(Dispatchers.IO) {
        val g = api.grimmory()
        val cached = settings.app.value.favouritesShelfId
        if (cached != null && shelfVerified) return@withContext cached
        val shelves = g.shelves()
        // The cached shelf may have been deleted in Grimmory: verify it once per session.
        if (cached != null && shelves.any { it.id == cached }) {
            shelfVerified = true
            return@withContext cached
        }
        val existing = favouritesShelfNames.firstNotNullOfOrNull { name -> shelves.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val id = existing?.id ?: g.createShelf(ShelfCreateRequest(name = favouritesShelfNames.first(), icon = "heart", iconType = "LUCIDE")).id
        settings.updateApp { it.copy(favouritesShelfId = id) }
        shelfVerified = true
        id
    }

    /** Pull the favourites shelf contents into the local table. */
    suspend fun refreshFavourites() = withContext(Dispatchers.IO) {
        runCatching {
            val shelfId = ensureFavouritesShelf()
            val all = mutableListOf<Book>()
            var page = 0
            do {
                val res = api.grimmory().books(page = page, size = 100, shelfId = shelfId)
                all += res.content.map { it.toBook() }
                page++
            } while (res.hasNext && page < 20)
            cache(all)
            val pending = db.outbox().all().filter { it.type in FAV_TYPES }.map { it.bookId }.toSet()
            val local = db.favourites().ids().toSet()
            val remote = all.map { it.id }.toSet()
            for (id in local - remote - pending) db.favourites().remove(id)
            db.favourites().addAll((remote - local).map { FavouriteEntity(it) })
        }
    }

    suspend fun setFavourite(bookId: Long, favourite: Boolean) = withContext(Dispatchers.IO) {
        if (favourite) db.favourites().add(FavouriteEntity(bookId)) else db.favourites().remove(bookId)
        // Imported books and local-only mode: favourites stay on this device.
        if (bookId < 0 || settings.app.value.noServer) return@withContext
        db.outbox().deleteFor(bookId, FAV_TYPES)
        try {
            val shelfId = ensureFavouritesShelf()
            api.grimmory().assignShelves(
                ShelvesAssignmentRequest(
                    bookIds = setOf(bookId),
                    shelvesToAssign = if (favourite) setOf(shelfId) else emptySet(),
                    shelvesToUnassign = if (favourite) emptySet() else setOf(shelfId),
                )
            ).also { if (!it.isSuccessful) error("HTTP ${it.code()}") }
        } catch (_: Exception) {
            db.outbox().insert(OutboxEntity(type = if (favourite) OutboxType.FAVOURITE_ADD else OutboxType.FAVOURITE_REMOVE, bookId = bookId))
            sync.requestSync()
        }
    }

    /** Favorite or unfavorite many books with a single Grimmory call. */
    suspend fun setFavourites(allIds: Set<Long>, favourite: Boolean) = withContext(Dispatchers.IO) {
        val bookIds = if (settings.app.value.noServer) emptySet() else allIds.filter { it >= 0 }.toSet()
        if (bookIds.isEmpty()) return@withContext
        for (id in bookIds) {
            if (favourite) db.favourites().add(FavouriteEntity(id)) else db.favourites().remove(id)
            db.outbox().deleteFor(id, FAV_TYPES)
        }
        try {
            val shelfId = ensureFavouritesShelf()
            val r = api.grimmory().assignShelves(
                ShelvesAssignmentRequest(
                    bookIds = bookIds,
                    shelvesToAssign = if (favourite) setOf(shelfId) else emptySet(),
                    shelvesToUnassign = if (favourite) emptySet() else setOf(shelfId),
                )
            )
            if (!r.isSuccessful) error("HTTP ${r.code()}")
        } catch (_: Exception) {
            val type = if (favourite) OutboxType.FAVOURITE_ADD else OutboxType.FAVOURITE_REMOVE
            for (id in bookIds) db.outbox().insert(OutboxEntity(type = type, bookId = id))
            sync.requestSync()
        }
    }

    suspend fun favourites(): List<Book> = withContext(Dispatchers.IO) {
        val ids = db.favourites().observeAll().first().map { it.bookId }
        ids.mapNotNull { id -> if (id < 0) db.localBooks().get(id)?.toBook() else db.books().get(id)?.toBook() }
    }

    suspend fun setStatus(bookId: Long, status: String) = withContext(Dispatchers.IO) {
        if (bookId < 0) {
            db.localBooks().updateStatus(bookId, status)
            return@withContext
        }
        db.books().get(bookId)?.let { db.books().upsertAll(listOf(it.copy(readStatus = status))) }
        try {
            val r = api.grimmory().updateStatus(bookId, UpdateStatusRequest(status))
            if (!r.isSuccessful) error("HTTP ${r.code()}")
        } catch (_: Exception) {
            db.outbox().insert(OutboxEntity(type = OutboxType.STATUS, bookId = bookId, payload = status))
            sync.requestSync()
        }
    }

    /** Rate a book 1..10 (half stars); null or 0 clears the rating. Queued when offline. */
    suspend fun setRating(bookId: Long, rating: Int?) = withContext(Dispatchers.IO) {
        if (bookId < 0) return@withContext
        val value = rating?.coerceIn(1, 10)
        db.books().get(bookId)?.let { db.books().upsertAll(listOf(it.copy(personalRating = value))) }
        db.outbox().deleteFor(bookId, listOf(OutboxType.RATING))
        try {
            val g = api.grimmory()
            val r = if (value != null) g.setPersonalRating(PersonalRatingRequest(listOf(bookId), value)) else g.resetPersonalRating(listOf(bookId))
            if (!r.isSuccessful) error("HTTP ${r.code()}")
        } catch (_: Exception) {
            db.outbox().insert(OutboxEntity(type = OutboxType.RATING, bookId = bookId, payload = (value ?: 0).toString()))
            sync.requestSync()
        }
    }

    /**
     * Books Grimmory finds similar to [bookId], most similar first. Never throws: no server, an old server
     * without the endpoint or a network error simply give an empty list. Not cached (the answer carries no progress).
     */
    suspend fun similarBooks(bookId: Long, limit: Int = 12): List<Book> = withContext(Dispatchers.IO) {
        if (bookId < 0 || settings.app.value.noServer) return@withContext emptyList()
        runCatching { api.grimmory().recommendations(bookId, limit.coerceIn(1, 25)) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.similarityScore }
            .mapNotNull { it.book?.toBook() }
            .filter { it.id != bookId }
            .distinctBy { it.id }
    }

    /** Books the user finished, rated well or favourited (most recent first): what "for you" is based on. */
    override suspend fun tasteSeeds(limit: Int): List<Book> = withContext(Dispatchers.IO) {
        val favIds = db.favourites().ids().toSet()
        libraryBooks()
            .filter { it.readStatus == "READ" || (it.rating ?: 0) >= 8 || it.id in favIds || (it.progress ?: 0f) >= 95f }
            .sortedByDescending { it.lastReadTime ?: "" }
            .take(limit)
    }

    /** Everything we know about: cached Grimmory books plus books imported on the device. */
    override suspend fun libraryBooks(): List<Book> = withContext(Dispatchers.IO) {
        db.books().search("").map { it.toBook() } + localBooks()
    }

    /** Reviews Grimmory has fetched for this book (Goodreads, Amazon, ...). Empty when none or offline. */
    suspend fun reviews(bookId: Long): List<BookReviewDto> = withContext(Dispatchers.IO) {
        if (bookId < 0) return@withContext emptyList()
        runCatching { api.grimmory().reviews(bookId) }.getOrDefault(emptyList())
    }

    /**
     * "Your next read": unread books in the genres you read, favourite and rate highest.
     * Taste = genres of finished/reading books, favourites, well-rated books, plus the genres picked in the profile.
     */
    suspend fun suggestions(limit: Int = 20): Suggestions = withContext(Dispatchers.IO) {
        if (settings.app.value.noServer) return@withContext Suggestions(emptyList(), emptyList())
        val cached = db.books().search("").map { it.toBook() }
        val favIds = db.favourites().ids().toSet()
        val weights = HashMap<String, Double>()
        fun add(genres: List<String>, w: Double) = genres.forEach { weights.merge(it, w, Double::plus) }
        settings.app.value.profile.favouriteGenres.forEach { weights.merge(it, 3.0, Double::plus) }
        for (b in cached) {
            if (b.readStatus == "READ") add(b.categories, 2.0)
            if (b.readStatus == "READING" || b.readStatus == "RE_READING") add(b.categories, 1.0)
            if (b.id in favIds) add(b.categories, 3.0)
            val r = b.rating
            if (r != null) add(b.categories, if (r >= 8) 3.0 else if (r <= 4) -3.0 else 0.0)
        }
        val top = weights.filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(3).map { it.key }
        if (top.isEmpty()) return@withContext Suggestions(emptyList(), emptyList())

        fun score(b: Book) = b.categories.sumOf { weights[it]?.coerceAtLeast(0.0) ?: 0.0 }
        val candidates = try {
            val g = api.grimmory()
            top.flatMap { genre ->
                runCatching { g.books(size = 30, sort = "addedOn", dir = "desc", category = listOf(genre), status = listOf("UNREAD")).content.map { it.toBook() } }
                    .getOrDefault(emptyList())
            }.also { cache(it) }
        } catch (_: Exception) {
            emptyList()
        }.ifEmpty {
            cached.filter { b -> (b.readStatus == null || b.readStatus == "UNREAD") && b.categories.any { it in top } }
        }
        val books = candidates.distinctBy { it.id }
            .filter { it.id !in favIds && (it.readStatus == null || it.readStatus == "UNREAD") }
            .sortedByDescending(::score)
            .take(limit)
        Suggestions(books, top)
    }

    companion object {
        val FAV_TYPES = listOf(OutboxType.FAVOURITE_ADD, OutboxType.FAVOURITE_REMOVE)
    }
}

/** Series derived from a list of books (demo / local-only / offline). */
internal fun seriesOf(books: List<Book>, query: String): List<SeriesInfo> =
    books.filter { it.seriesName != null }
        .groupBy { it.seriesName!! }
        .filterKeys { query.isBlank() || it.contains(query, ignoreCase = true) }
        .map { (name, list) ->
            SeriesInfo(
                name = name,
                bookCount = list.size,
                total = null,
                authors = list.flatMap { it.authors }.distinct(),
                booksRead = list.count { it.readStatus == "READ" },
                coverBookIds = list.sortedBy { it.seriesNumber ?: Float.MAX_VALUE }.map { it.id }.filter { it > 0 }.take(3),
            )
        }
        .sortedBy { it.name.lowercase() }

/** Authors derived from a list of books (demo / local-only / offline). */
internal fun authorsOf(books: List<Book>, query: String): List<AuthorSummaryDto> =
    books.flatMap { b -> b.authors.map { it to b } }
        .groupBy({ it.first }, { it.second })
        .filterKeys { query.isBlank() || it.contains(query, ignoreCase = true) }
        .map { (name, list) -> AuthorSummaryDto(id = 1_000_000_000L + (name.hashCode().toLong() and 0xFFFFFFL), name = name, bookCount = list.size, hasPhoto = false) }
        .sortedBy { it.name.lowercase() }
