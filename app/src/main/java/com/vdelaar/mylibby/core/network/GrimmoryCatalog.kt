package com.vdelaar.mylibby.core.network

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import retrofit2.Response

/**
 * Everything the app asks Grimmory about its books, series and authors. Grimmory 4.0 removes the `/api/v1/app` routes,
 * so this uses the regular browse API (`/books/page` with facets, `/authors/page`, `/books/{id}`, `/books/progress`,
 * `/books/status`). Servers that do not have a route yet get the old `/api/v1/app` call instead, so older Grimmory
 * versions keep working.
 */
class GrimmoryCatalog(private val api: GrimmoryApi) {

    suspend fun books(
        page: Int = 0,
        size: Int = 30,
        sort: String? = null,
        dir: String? = null,
        search: String? = null,
        shelfId: Long? = null,
        authors: List<String>? = null,
        series: List<String>? = null,
        category: List<String>? = null,
        language: List<String>? = null,
        status: List<String>? = null,
        fileType: List<String>? = null,
    ): PageResponse<BookSummaryDto> = withFallback(
        modern = {
            val facets = BrowseQuery.facets(shelfId, authors, series, category, language, status, fileType)
            api.booksPage(
                page = page,
                size = size.coerceIn(1, BrowseQuery.MAX_PAGE),
                sort = BrowseQuery.sort(sort, dir),
                facet = facets.ifEmpty { null },
                facetLogic = if (facets.isEmpty()) null else BrowseQuery.OR,
                query = search?.trim()?.ifBlank { null },
            ).toPageResponse { it.toSummary() }
        },
        legacy = { api.books(page, size, sort, dir, search, shelfId, authors, series, category, language, status, fileType) },
        needsBrowse = true,
    )

    suspend fun bookDetail(id: Long): BookDetailDto = withFallback(
        modern = { api.bookById(id).toDetail() },
        legacy = { api.bookDetail(id) },
        // 404 is the server saying there is no such book.
        missing = { it.isMissingRoute(notFound = false) },
    )

    suspend fun bookProgress(id: Long): BookProgressResponseDto = withFallback(
        modern = { api.bookById(id, withDescription = false).toProgress() },
        legacy = { api.bookProgress(id) },
        missing = { it.isMissingRoute(notFound = false) },
    )

    suspend fun updateProgress(id: Long, body: UpdateProgressRequest): Response<Unit> =
        responseWithFallback(
            modern = { api.postProgress(ReadProgressRequest(id, body.fileProgress, body.dateFinished)) },
            legacy = { api.updateProgress(id, body) },
        )

    suspend fun updateStatus(id: Long, status: String): Response<*> =
        responseWithFallback<Response<*>>(
            modern = { api.postStatus(ReadStatusRequest(listOf(id), status)) },
            legacy = { api.updateStatus(id, UpdateStatusRequest(status)) },
        )

    /** Books being read, most recent first. */
    suspend fun continueReading(limit: Int = 12): List<BookSummaryDto> = withFallback(
        modern = {
            api.booksPage(
                size = limit.coerceIn(1, BrowseQuery.MAX_PAGE),
                sort = BrowseQuery.sort("lastReadTime", "desc"),
                facet = listOf("read_status:READING", "read_status:RE_READING"),
                facetLogic = BrowseQuery.OR,
            ).content.map { it.toSummary() }
        },
        legacy = { api.continueReading(limit) },
        needsBrowse = true,
    )

    suspend fun recentlyAdded(limit: Int = 20): List<BookSummaryDto> = withFallback(
        modern = { api.booksPage(size = limit.coerceIn(1, BrowseQuery.MAX_PAGE), sort = BrowseQuery.sort("addedOn", "desc")).content.map { it.toSummary() } },
        legacy = { api.recentlyAdded(limit) },
        needsBrowse = true,
    )

    suspend fun filterOptions(): FilterOptionsDto = withFallback(
        modern = {
            val groups = api.booksFacets().facets
            fun counted(key: String) = groups.firstOrNull { it.metadata.key == key }?.counted().orEmpty()
            FilterOptionsDto(
                authors = counted("author"),
                languages = counted("language").map { LanguageOption(it.name, languageLabel(it.name), it.count) },
                readStatuses = counted("read_status"),
                fileTypes = counted("file_type"),
                categories = counted("genre"),
                series = counted("series"),
                tags = counted("tag"),
                shelves = counted("shelf"),
            )
        },
        legacy = { api.filterOptions() },
        needsBrowse = true,
    )

    suspend fun series(page: Int = 0, size: Int = 40, search: String? = null): PageResponse<SeriesSummaryDto> = withFallback(
        modern = { modernSeries(page, size.coerceIn(1, BrowseQuery.MAX_PAGE), search?.trim().orEmpty()) },
        legacy = { api.series(page, size, search) },
        needsBrowse = true,
    )

    suspend fun seriesBooks(name: String, page: Int = 0, size: Int = 100): PageResponse<BookSummaryDto> = withFallback(
        modern = {
            api.booksPage(
                page = page,
                size = size.coerceIn(1, BrowseQuery.MAX_PAGE),
                sort = "seriesNumber",
                facet = listOf("series:$name"),
                facetLogic = BrowseQuery.OR,
            ).toPageResponse { it.toSummary() }
        },
        legacy = { api.seriesBooks(name, page, size) },
        needsBrowse = true,
    )

    suspend fun authors(page: Int = 0, size: Int = 60, search: String? = null): PageResponse<AuthorSummaryDto> = withFallback(
        modern = {
            api.authorsPage(page, size.coerceIn(1, BrowseQuery.MAX_PAGE), sort = "name", query = search?.trim()?.ifBlank { null })
                .toPageResponse { it }
        },
        legacy = { api.authors(page, size, search) },
        // Before Grimmory has /authors/page, that path is read as /authors/{id} and fails with a 500.
        missing = { it.isMissingRoute() || (it is HttpException && it.code() == 500) },
    )

    // Series ------------------------------------------------------------------------------------

    /**
     * Grimmory has no series list in its regular API yet, only the "series" facet (every series with its book count).
     * The names come from there, the rest (authors, covers, how many are read) is gathered with two more calls.
     */
    private suspend fun modernSeries(page: Int, size: Int, search: String): PageResponse<SeriesSummaryDto> {
        val values = facetValues("series", search, page, size)
        if (values.names.isEmpty()) return PageResponse(page = page, size = size)
        val read = runCatching { facetCounts(api.booksFacets(listOf("read_status:READ")), "series") }.getOrDefault(emptyMap())
        val details = seriesDetails(values.names.map { it.name })
        val content = values.names.map { v ->
            val d = details[v.name]
            SeriesSummaryDto(
                seriesName = v.name,
                bookCount = v.count.toInt(),
                seriesTotal = d?.total,
                authors = d?.authors.orEmpty(),
                booksRead = (read[v.name] ?: 0L).toInt(),
                coverBooks = d?.covers.orEmpty().map { SeriesCoverBookDto(it.first, it.second) },
            )
        }
        return PageResponse(content = content, page = page, size = size, totalElements = values.total ?: content.size.toLong(), hasNext = values.hasNext, hasPrevious = page > 0)
    }

    private class Series(val total: Int?, val authors: List<String>, val covers: List<Pair<Long, String?>>)

    private suspend fun seriesDetails(names: List<String>): Map<String, Series> {
        val out = HashMap<String, Series>()
        for (chunk in names.chunked(SERIES_PER_CALL)) {
            val books = ArrayList<BookDto>()
            var page = 0
            do {
                val res = runCatching {
                    api.booksPage(
                        page = page,
                        size = BrowseQuery.MAX_PAGE,
                        sort = "seriesName,seriesNumber",
                        facet = chunk.map { "series:$it" },
                        facetLogic = BrowseQuery.OR,
                    )
                }.getOrNull() ?: break
                books += res.content
                page++
            } while (res.page.number + 1 < res.page.totalPages && page < SERIES_PAGES)
            for ((name, list) in books.groupBy { it.metadata?.seriesName }) {
                if (name == null) continue
                out[name] = Series(
                    total = list.mapNotNull { it.metadata?.seriesTotal }.maxOrNull(),
                    authors = list.flatMap { it.metadata?.authors.orEmpty() }.distinct(),
                    covers = list.take(3).map { it.id to it.metadata?.coverUpdatedOn },
                )
            }
        }
        return out
    }

    private class FacetValues(val names: List<CountedOption>, val hasNext: Boolean, val total: Long? = null)

    /** Values of one facet, matching [search]. Uses the paged facet route when the server has it, else the facet list. */
    private suspend fun facetValues(key: String, search: String, page: Int, size: Int): FacetValues {
        try {
            val res = api.bookFacet(key, page, size, search.ifBlank { null })
            val group = res.facets.firstOrNull { it.metadata.key == key }
            if (group != null) return FacetValues(group.counted(), res.links.any { it.hasRel("next") })
        } catch (e: Exception) {
            if (!e.isMissingRoute()) throw e
        }
        // The facet list holds at most 100 values (the most popular); `query` narrows it to what matches.
        var all = api.booksFacets(query = search.ifBlank { null }).facets.firstOrNull { it.metadata.key == key }?.counted().orEmpty()
        if (key == "series" && search.isBlank() && all.size >= BrowseQuery.MAX_PAGE) all = scanSeries()?.takeIf { it.size > all.size } ?: all
        all = all.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
        val from = page * size
        return FacetValues(all.drop(from).take(size), all.size > from + size, all.size.toLong())
    }

    @Volatile private var seriesScan: Pair<Long, List<CountedOption>>? = null

    /**
     * All series of a library with more than 100 of them: the books sorted by series name (descending, so books without
     * a series come last) counted per series. Costs one call per 100 books in a series, so the result is kept a while.
     */
    private suspend fun scanSeries(): List<CountedOption>? {
        val now = System.currentTimeMillis()
        seriesScan?.let { (at, list) -> if (now - at < SCAN_KEEP_MS) return list }
        val counts = HashMap<String, Long>()
        var page = 0
        scan@ while (page < SCAN_PAGES) {
            val res = try {
                api.booksPage(page = page, size = BrowseQuery.MAX_PAGE, sort = "-seriesName")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || !e.isMissingRoute()) throw e
                return null
            }
            for (b in res.content) {
                val name = b.metadata?.seriesName?.takeIf { it.isNotBlank() } ?: break@scan
                counts.merge(name, 1L, Long::plus)
            }
            page++
            if (page >= res.page.totalPages) break
        }
        val list = counts.map { CountedOption(it.key, it.value) }.sortedWith(compareByDescending<CountedOption> { it.count }.thenBy { it.name })
        seriesScan = now to list
        return list
    }

    private fun facetCounts(res: FacetGroupsDto, key: String): Map<String, Long> =
        res.facets.firstOrNull { it.metadata.key == key }?.counted().orEmpty().associate { it.name to it.count }

    // Fallback ----------------------------------------------------------------------------------

    private suspend fun <T> withFallback(
        modern: suspend () -> T,
        legacy: suspend () -> T,
        missing: (Exception) -> Boolean = { it.isMissingRoute() },
        needsBrowse: Boolean = false,
    ): T {
        if (needsBrowse && !browseSupported()) return legacy()
        try {
            return modern()
        } catch (e: Exception) {
            if (!missing(e)) throw e
            // The old route only helps on servers that still have it; if it fails too, report the first problem.
            return try {
                legacy()
            } catch (old: Exception) {
                if (old is kotlinx.coroutines.CancellationException) throw old
                e.addSuppressed(old)
                throw e
            }
        }
    }

    @Volatile private var browseCheckedAt = 0L
    @Volatile private var browseOk = true

    /**
     * Facet filtering in `/books/page` exists from Grimmory 3.3. Older servers still have a `/books/page`, but it ignores
     * the facets (every filter would silently show all books) and fails on sorting, so `/books/facets` decides.
     * Only connection problems are not an answer; those are rethrown and not remembered.
     */
    private suspend fun browseSupported(): Boolean {
        val now = System.currentTimeMillis()
        if (browseCheckedAt != 0L && now - browseCheckedAt < RECHECK_MS) return browseOk
        val ok = try {
            api.booksFacets(); true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is HttpException && e !is SerializationException && e !is IllegalArgumentException) throw e
            false
        }
        browseOk = ok
        browseCheckedAt = now
        return ok
    }

    private suspend fun <T : Response<*>> responseWithFallback(modern: suspend () -> T, legacy: suspend () -> T): T {
        val r = try {
            modern()
        } catch (e: Exception) {
            if (!e.isMissingRoute()) throw e
            return legacy()
        }
        if (r.isSuccessful || r.code() !in MISSING_CODES) return r
        return try { legacy() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e else r }
    }

    private companion object {
        const val SERIES_PER_CALL = 30
        const val SERIES_PAGES = 3
        const val SCAN_PAGES = 60
        const val SCAN_KEEP_MS = 2 * 60 * 1000L
        const val RECHECK_MS = 10 * 60 * 1000L
    }
}

private val MISSING_CODES = setOf(400, 404, 405, 501)

/**
 * The server does not know this route: an HTTP error that says so, or an answer that is not JSON (some servers return
 * their web page for unknown routes). Connection problems are not "missing".
 */
internal fun Exception.isMissingRoute(notFound: Boolean = true): Boolean = when (this) {
    is HttpException -> code() in MISSING_CODES && (notFound || code() != 404)
    is SerializationException, is IllegalArgumentException -> true
    else -> false
}

private fun FacetGroupDto.counted(): List<CountedOption> =
    links.mapNotNull { l ->
        val name = l.value ?: l.title ?: return@mapNotNull null
        CountedOption(name, l.properties?.numberOfItems ?: 0L)
    }

private fun languageLabel(code: String): String? =
    runCatching { java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH) }
        .getOrNull()?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }

private fun <T, R> BrowsePageDto<T>.toPageResponse(map: (T) -> R): PageResponse<R> = PageResponse(
    content = content.map(map),
    page = page.number,
    size = page.size,
    totalElements = page.totalElements,
    totalPages = page.totalPages,
    hasNext = page.number + 1 < page.totalPages,
    hasPrevious = page.number > 0,
)

/** How the app's filters become Grimmory's `facet` / `sort` parameters. */
object BrowseQuery {
    const val MAX_PAGE = 100
    const val OR = "or"

    private val sortKeys = setOf(
        "addedOn", "lastReadTime", "title", "seriesName", "seriesNumber", "publishedDate", "personalRating",
        "readStatus", "pageCount", "language", "authorName", "random",
    )

    /** `facet=key:value` per selected value; values of one kind combine with OR, different kinds with AND. */
    fun facets(
        shelfId: Long?,
        authors: List<String>?,
        series: List<String>?,
        genres: List<String>?,
        languages: List<String>?,
        statuses: List<String>?,
        fileTypes: List<String>?,
    ): List<String> = buildList {
        if (shelfId != null) add("shelf:$shelfId")
        authors?.forEach { add("author:$it") }
        series?.forEach { add("series:$it") }
        genres?.forEach { add("genre:$it") }
        languages?.forEach { add("language:$it") }
        statuses?.forEach { add("read_status:$it") }
        fileTypes?.forEach { add("file_type:$it") }
    }

    /** `-` in front of a key sorts descending. A series is ordered by its number within it. */
    fun sort(sort: String?, dir: String?): String? {
        if (sort == null || sort !in sortKeys) return null
        val keys = if (sort == "seriesName") listOf("seriesName", "seriesNumber") else listOf(sort)
        val prefix = if (dir.equals("desc", ignoreCase = true) && sort != "random") "-" else ""
        return keys.joinToString(",") { prefix + it }
    }
}

// Grimmory's Book DTO to the shapes the rest of the app already reads --------------------------

/** The furthest overall figure Grimmory knows (KOReader, Kobo, then the web reader), as a percentage. */
fun BookDto.readProgress(): Float? =
    koreaderProgress?.percentage ?: koboProgress?.percentage ?: epubProgress?.percentage ?: pdfProgress?.percentage ?: cbxProgress?.percentage

fun BookDto.toSummary(): BookSummaryDto {
    val m = metadata
    return BookSummaryDto(
        id = id,
        title = m?.title ?: title,
        authors = m?.authors,
        readStatus = readStatus,
        personalRating = personalRating,
        seriesName = m?.seriesName,
        seriesNumber = m?.seriesNumber,
        libraryId = libraryId,
        addedOn = addedOn,
        lastReadTime = lastReadTime,
        readProgress = readProgress(),
        primaryFileId = primaryFile?.id,
        primaryFileType = primaryFile?.bookType,
        primaryFileName = primaryFile?.fileName,
        coverUpdatedOn = m?.coverUpdatedOn,
        isPhysical = isPhysical,
        publisher = m?.publisher,
        categories = m?.categories,
        tags = m?.tags,
        language = m?.language,
        publishedDate = m?.publishedDate,
        pageCount = m?.pageCount,
        fileSizeKb = primaryFile?.fileSizeKb,
        goodreadsRating = m?.goodreadsRating,
    )
}

fun BookDto.toDetail(): BookDetailDto {
    val m = metadata
    val files = listOfNotNull(primaryFile?.let { it to true }) + alternativeFormats.orEmpty().map { it to false }
    return BookDetailDto(
        id = id,
        title = m?.title ?: title,
        authors = m?.authors,
        readStatus = readStatus,
        personalRating = personalRating,
        seriesName = m?.seriesName,
        seriesNumber = m?.seriesNumber,
        libraryId = libraryId,
        addedOn = addedOn,
        lastReadTime = lastReadTime,
        subtitle = m?.subtitle,
        description = m?.description,
        categories = m?.categories,
        publisher = m?.publisher,
        publishedDate = m?.publishedDate,
        pageCount = m?.pageCount,
        isbn13 = m?.isbn13,
        language = m?.language,
        goodreadsRating = m?.goodreadsRating,
        goodreadsReviewCount = m?.goodreadsReviewCount,
        libraryName = libraryName,
        shelves = shelves,
        readProgress = readProgress(),
        primaryFileType = primaryFile?.bookType,
        fileTypes = files.mapNotNull { it.first.bookType }.distinct(),
        files = files.map { (f, primary) ->
            BookFileDto(f.id, f.bookId ?: id, f.fileName, f.isBook, f.bookType, f.fileSizeKb, f.extension, primary)
        },
        coverUpdatedOn = m?.coverUpdatedOn,
        isPhysical = isPhysical,
        epubProgress = epubProgress?.let { EpubProgressDto(it.cfi, it.href, it.percentage) },
        koreaderProgress = koreaderProgress?.let { KoreaderProgressDto(it.percentage) },
    )
}

fun BookDto.toProgress() = BookProgressResponseDto(
    readProgress = readProgress(),
    readStatus = readStatus,
    lastReadTime = lastReadTime,
    epubProgress = epubProgress?.let { EpubProgressDto(it.cfi, it.href, it.percentage) },
)
