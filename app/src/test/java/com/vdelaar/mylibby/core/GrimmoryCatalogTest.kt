package com.vdelaar.mylibby.core

import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.core.network.AuthorSummaryDto
import com.vdelaar.mylibby.core.network.BookDto
import com.vdelaar.mylibby.core.network.BookSummaryDto
import com.vdelaar.mylibby.core.network.BrowsePageDto
import com.vdelaar.mylibby.core.network.BrowseQuery
import com.vdelaar.mylibby.core.network.FacetGroupsDto
import com.vdelaar.mylibby.core.network.FileProgressDto
import com.vdelaar.mylibby.core.network.GrimmoryApi
import com.vdelaar.mylibby.core.network.GrimmoryCatalog
import com.vdelaar.mylibby.core.network.PageResponse
import com.vdelaar.mylibby.core.network.ReadProgressRequest
import com.vdelaar.mylibby.core.network.ReadStatusRequest
import com.vdelaar.mylibby.core.network.UpdateProgressRequest
import com.vdelaar.mylibby.core.network.toDetail
import com.vdelaar.mylibby.core.network.toProgress
import com.vdelaar.mylibby.core.network.toSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import okhttp3.ResponseBody.Companion.toResponseBody
import com.vdelaar.mylibby.core.network.SeriesSummaryDto
import com.vdelaar.mylibby.core.network.FacetGroupDto
import com.vdelaar.mylibby.core.network.FacetMetadataDto
import com.vdelaar.mylibby.core.network.BrowseLinkDto
import com.vdelaar.mylibby.core.network.BrowseLinkPropertiesDto
import com.vdelaar.mylibby.core.network.BookMetadataDto
import com.vdelaar.mylibby.core.network.BrowsePageMetaDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Grimmory 4.0 removes the /api/v1/app routes. The fixtures in test/resources/grimmory are real answers of a Grimmory
 * nightly (develop) server; the fake API below stands in for it.
 */
private val unimplemented: GrimmoryApi = Proxy.newProxyInstance(
    GrimmoryApi::class.java.classLoader, arrayOf(GrimmoryApi::class.java),
    InvocationHandler { _, m, _ -> throw NotImplementedError(m.name) },
) as GrimmoryApi

class GrimmoryCatalogTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("grimmory/$name")!!.readText()
    private fun bookPage() = AppJson.decodeFromString<BrowsePageDto<BookDto>>(fixture("books-page.json"))
    private fun facets() = AppJson.decodeFromString<FacetGroupsDto>(fixture("books-facets.json"))
    private fun facetsRead() = AppJson.decodeFromString<FacetGroupsDto>(fixture("books-facets-read.json"))
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    /** Every call fails unless a test overrides it. */
    private open class FakeApi : GrimmoryApi by unimplemented {
        override suspend fun booksFacets(facet: List<String>?, facetLogic: String?, query: String?) = FacetGroupsDto()
    }


    private fun summary(id: Long) = BookSummaryDto(id = id, title = "legacy $id")

    // Mapping ------------------------------------------------------------------------------------

    @Test fun aBookPageBecomesTheSummariesTheAppAlreadyReads() {
        val page = bookPage()
        assertEquals(2, page.content.size)
        assertEquals(3L, page.page.totalElements)
        val b = page.content[0].toSummary()
        assertEquals(5L, b.id)
        assertEquals("Alpha Rising", b.title)
        assertEquals(listOf("Ann Author"), b.authors)
        assertEquals("Alpha Saga", b.seriesName)
        assertEquals(1f, b.seriesNumber)
        assertEquals("READING", b.readStatus)
        assertEquals(8, b.personalRating)
        assertEquals(42.5f, b.readProgress)
        assertEquals("EPUB", b.primaryFileType)
        assertEquals(5L, b.primaryFileId)
        assertEquals("Alpha Rising.epub", b.primaryFileName)
        assertNull(page.content[1].toSummary().readProgress)
    }

    @Test fun theDetailKeepsFilesShelvesAndPosition() {
        val d = AppJson.decodeFromString<BookDto>(fixture("book-detail.json")).toDetail()
        assertEquals(5L, d.id)
        assertEquals("Main", d.libraryName)
        assertEquals(listOf(1L), d.shelves?.map { it.id })
        val file = d.files!!.single()
        assertTrue(file.isPrimary)
        assertEquals("EPUB", file.bookType)
        assertEquals(listOf("EPUB"), d.fileTypes)
        assertEquals("epubcfi(/6/2!/4/2)", d.epubProgress?.cfi)
        assertEquals(42.5f, d.epubProgress?.percentage)
    }

    @Test fun theFurthestReaderWinsLikeBefore() {
        val book = AppJson.decodeFromString<BookDto>(
            """{"id":1,"epubProgress":{"percentage":10.0},"koreaderProgress":{"percentage":55.5},"koboProgress":{"percentage":30.0}}""",
        )
        assertEquals(55.5f, book.toProgress().readProgress)
        assertEquals(10f, book.toProgress().epubProgress?.percentage)
    }

    // Query --------------------------------------------------------------------------------------

    @Test fun filtersBecomeFacets() {
        val f = BrowseQuery.facets(7L, listOf("Ann Author"), listOf("Saga: One"), listOf("Horror"), listOf("en"), listOf("READING", "UNSET"), null)
        assertEquals(
            listOf("shelf:7", "author:Ann Author", "series:Saga: One", "genre:Horror", "language:en", "read_status:READING", "read_status:UNSET"),
            f,
        )
    }

    @Test fun sortKeysFollowTheServersSortRegistry() {
        assertEquals("-addedOn", BrowseQuery.sort("addedOn", "desc"))
        assertEquals("title", BrowseQuery.sort("title", "asc"))
        assertEquals("seriesName,seriesNumber", BrowseQuery.sort("seriesName", "asc"))
        assertEquals("-publishedDate", BrowseQuery.sort("publishedDate", "desc"))
        assertEquals("random", BrowseQuery.sort("random", "desc"))
        assertNull(BrowseQuery.sort("noSuchKey", "asc"))
        assertNull(BrowseQuery.sort(null, null))
    }

    // Calls --------------------------------------------------------------------------------------

    @Test fun booksUseThePageRouteWithFacetsAndOrLogic() = runBlocking {
        var seen: List<Any?> = emptyList()
        val api = object : FakeApi() {
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> {
                seen = listOf(page, size, sort, facet, facetLogic, query)
                return bookPage()
            }
        }
        val res = GrimmoryCatalog(api).books(page = 0, size = 500, sort = "lastReadTime", dir = "desc", search = " alp ", authors = listOf("A", "B"), status = listOf("READING"))
        assertEquals(listOf(0, 100, "-lastReadTime", listOf("author:A", "author:B", "read_status:READING"), "or", "alp"), seen)
        assertEquals(2, res.content.size)
        assertEquals(3L, res.totalElements)
        assertTrue(res.hasNext)
        assertFalse(res.hasPrevious)
    }

    @Test fun continueReadingAndRecentlyAddedNeedNoAppRoute() = runBlocking {
        val calls = ArrayList<List<Any?>>()
        val api = object : FakeApi() {
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> {
                calls += listOf(size, sort, facet, facetLogic)
                return bookPage()
            }
        }
        val catalog = GrimmoryCatalog(api)
        assertEquals(2, catalog.continueReading(12).size)
        assertEquals(2, catalog.recentlyAdded(20).size)
        assertEquals(listOf(12, "-lastReadTime", listOf("read_status:READING", "read_status:RE_READING"), "or"), calls[0])
        assertEquals(listOf(20, "-addedOn", null, null), calls[1])
    }

    @Test fun anOldServerWithoutTheBrowseRouteStillWorks() = runBlocking {
        val api = object : FakeApi() {
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> = throw http(404)
            override suspend fun books(page: Int, size: Int, sort: String?, dir: String?, search: String?, shelfId: Long?, authors: List<String>?, series: List<String>?, category: List<String>?, language: List<String>?, status: List<String>?, fileType: List<String>?) =
                PageResponse(content = listOf(summary(1)), totalElements = 1)
        }
        assertEquals("legacy 1", GrimmoryCatalog(api).books().content.single().title)
    }

    @Test fun serverBeforeFacetsGoesStraightToTheOldRoutes() = runBlocking {
        // Grimmory 3.0-3.2: /books/page exists but ignores the facets, /books/facets fails.
        var pageCalls = 0
        val api = object : FakeApi() {
            override suspend fun booksFacets(facet: List<String>?, facetLogic: String?, query: String?): FacetGroupsDto = throw http(500)
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> { pageCalls++; return bookPage() }
            override suspend fun books(page: Int, size: Int, sort: String?, dir: String?, search: String?, shelfId: Long?, authors: List<String>?, series: List<String>?, category: List<String>?, language: List<String>?, status: List<String>?, fileType: List<String>?) =
                PageResponse(content = listOf(summary(1)), totalElements = 1)
            override suspend fun continueReading(limit: Int) = listOf(summary(2))
            override suspend fun series(page: Int, size: Int, search: String?) = PageResponse(content = listOf(SeriesSummaryDto("S")))
        }
        val catalog = GrimmoryCatalog(api)
        assertEquals("legacy 1", catalog.books(authors = listOf("A")).content.single().title)
        assertEquals("legacy 2", catalog.continueReading(5).single().title)
        assertEquals("S", catalog.series().content.single().seriesName)
        assertEquals(0, pageCalls)
    }

    @Test fun noConnectionIsNotRememberedAsAnOldServer() = runBlocking {
        var offline = true
        val api = object : FakeApi() {
            override suspend fun booksFacets(facet: List<String>?, facetLogic: String?, query: String?): FacetGroupsDto =
                if (offline) throw java.io.IOException("offline") else FacetGroupsDto()
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?) = bookPage()
        }
        val catalog = GrimmoryCatalog(api)
        try { catalog.books(); fail("expected IOException") } catch (e: java.io.IOException) { }
        offline = false
        assertEquals(2, catalog.books().content.size)
    }

    @Test fun seriesBeyondTheFacetLimitAreCountedFromTheBooks() = runBlocking {
        // 130 series, but the facet list only has 100 of them.
        val capped = FacetGroupsDto(facets = listOf(FacetGroupDto(
            metadata = FacetMetadataDto(key = "series"),
            links = (0 until 100).map { BrowseLinkDto(value = "S%03d".format(it), properties = BrowseLinkPropertiesDto(1)) },
        )))
        fun book(i: Int, series: String?) = BookDto(id = i.toLong(), metadata = BookMetadataDto(seriesName = series))
        val all = (129 downTo 0).map { book(it, "S%03d".format(it)) } + listOf(book(500, null))
        val api = object : FakeApi() {
            override suspend fun booksFacets(facet: List<String>?, facetLogic: String?, query: String?) = capped
            override suspend fun bookFacet(name: String, page: Int, size: Int, search: String?, facet: List<String>?, facetLogic: String?): FacetGroupsDto = throw http(404)
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> =
                if (sort == "-seriesName") BrowsePageDto(all.drop(page * size).take(size), BrowsePageMetaDto(number = page, size = size, totalElements = all.size.toLong(), totalPages = (all.size + size - 1) / size))
                else BrowsePageDto()
        }
        val res = GrimmoryCatalog(api).series(page = 3, size = 40)
        assertEquals(130L, res.totalElements)
        assertEquals(listOf("S120", "S121", "S122", "S123", "S124", "S125", "S126", "S127", "S128", "S129"), res.content.map { it.seriesName })
        assertFalse(res.hasNext)
    }

    @Test fun whenBothRoutesFailTheFirstErrorIsReported() = runBlocking {
        val first = http(400)
        val api = object : FakeApi() {
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> = throw first
            override suspend fun books(page: Int, size: Int, sort: String?, dir: String?, search: String?, shelfId: Long?, authors: List<String>?, series: List<String>?, category: List<String>?, language: List<String>?, status: List<String>?, fileType: List<String>?): PageResponse<BookSummaryDto> = throw http(404)
        }
        try {
            GrimmoryCatalog(api).books()
            fail("expected an error")
        } catch (e: HttpException) {
            assertSame(first, e)
        }
    }

    @Test fun noConnectionIsNotMistakenForAMissingRoute() = runBlocking {
        val api = object : FakeApi() {
            override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> = throw IOException("offline")
        }
        try {
            GrimmoryCatalog(api).books()
            fail("expected an error")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test fun aBookThatDoesNotExistIsNotRetriedOnTheOldRoute() = runBlocking {
        var legacy = 0
        val api = object : FakeApi() {
            override suspend fun bookById(id: Long, withDescription: Boolean): BookDto = throw http(404)
            override suspend fun bookDetail(id: Long): com.vdelaar.mylibby.core.network.BookDetailDto { legacy++; throw http(404) }
        }
        try {
            GrimmoryCatalog(api).bookDetail(9)
            fail("expected an error")
        } catch (e: HttpException) {
            assertEquals(404, e.code())
        }
        assertEquals(0, legacy)
    }

    @Test fun progressAndStatusGoToTheRegularRoutes() = runBlocking {
        var progress: ReadProgressRequest? = null
        var status: ReadStatusRequest? = null
        val api = object : FakeApi() {
            override suspend fun postProgress(body: ReadProgressRequest): Response<Unit> { progress = body; return Response.success(null) }
            override suspend fun postStatus(body: ReadStatusRequest): Response<okhttp3.ResponseBody> { status = body; return Response.success("[]".toResponseBody()) }
        }
        val catalog = GrimmoryCatalog(api)
        val file = FileProgressDto(bookFileId = 4, positionData = "cfi", positionHref = "c1.xhtml", progressPercent = 12.5f)
        assertTrue(catalog.updateProgress(6, UpdateProgressRequest(fileProgress = file)).isSuccessful)
        assertTrue(catalog.updateStatus(6, "READ").isSuccessful)
        assertEquals(ReadProgressRequest(6, file), progress)
        assertEquals(ReadStatusRequest(listOf(6), "READ"), status)
    }

    @Test fun aRejectedProgressWriteFallsBackToTheOldRoute() = runBlocking {
        var legacy = false
        val api = object : FakeApi() {
            override suspend fun postProgress(body: ReadProgressRequest): Response<Unit> = Response.error(404, "".toResponseBody())
            override suspend fun updateProgress(id: Long, body: UpdateProgressRequest): Response<Unit> { legacy = true; return Response.success(null) }
        }
        assertTrue(GrimmoryCatalog(api).updateProgress(1, UpdateProgressRequest()).isSuccessful)
        assertTrue(legacy)
    }

    @Test fun filterOptionsComeFromTheFacets() = runBlocking {
        val api = object : FakeApi() {
            override suspend fun booksFacets(facet: List<String>?, facetLogic: String?, query: String?) = facets()
        }
        val o = GrimmoryCatalog(api).filterOptions()
        assertEquals(listOf("Ann Author" to 4L, "Cor Schrijver" to 2L, "Bob Writer" to 1L), o.authors.map { it.name to it.count }.take(3))
        assertEquals(listOf("en", "nl"), o.languages.map { it.code })
        assertEquals("English", o.languages.first().label)
        assertEquals(listOf("Alpha Saga", "Gamma Reeks", "Eps Cycle"), o.series.map { it.name })
        assertEquals(listOf("UNSET", "READING", "READ"), o.readStatuses.map { it.name })
    }

    @Test fun authorsUseTheAuthorPageRoute() = runBlocking {
        val api = object : FakeApi() {
            override suspend fun authorsPage(page: Int, size: Int, sort: String?, query: String?): BrowsePageDto<AuthorSummaryDto> {
                assertEquals("name", sort)
                assertEquals("ann", query)
                return AppJson.decodeFromString(fixture("authors-page.json"))
            }
        }
        val res = GrimmoryCatalog(api).authors(search = " ann ")
        assertEquals(listOf("Ann Author", "Bob Writer"), res.content.map { it.name })
        assertEquals(4, res.content.first().bookCount)
        assertTrue(res.hasNext)
    }

    @Test fun authorsOnAServerWithoutTheAuthorPageRouteUseTheOldOne() = runBlocking {
        val api = object : FakeApi() {
            // Grimmory 3.x answers /authors/page with a 500 (it reads "page" as an author id).
            override suspend fun authorsPage(page: Int, size: Int, sort: String?, query: String?): BrowsePageDto<AuthorSummaryDto> = throw http(500)
            override suspend fun authors(page: Int, size: Int, search: String?) =
                PageResponse(content = listOf(AuthorSummaryDto(2, "Ann Author", 4)))
        }
        assertEquals(listOf("Ann Author"), GrimmoryCatalog(api).authors().content.map { it.name })
    }

    // Series -------------------------------------------------------------------------------------

    private fun seriesApi(pagedFacet: Boolean) = object : FakeApi() {
        override suspend fun bookFacet(name: String, page: Int, size: Int, search: String?, facet: List<String>?, facetLogic: String?): FacetGroupsDto {
            if (!pagedFacet) throw SerializationException("<!doctype html>") // an older server answers its web page
            return facets()
        }
        override suspend fun booksFacets(facet: List<String>?, facetLogic: String?, query: String?) =
            if (facet == null) facets() else facetsRead()
        override suspend fun booksPage(page: Int, size: Int, sort: String?, facet: List<String>?, facetLogic: String?, query: String?): BrowsePageDto<BookDto> {
            assertEquals("seriesName,seriesNumber", sort)
            assertTrue(facet!!.all { it.startsWith("series:") })
            // The fixture says there are two pages; the second one is empty here.
            return if (page == 0) bookPage() else BrowsePageDto(emptyList(), bookPage().page.copy(number = page))
        }
    }

    @Test fun seriesComeFromTheSeriesFacetOnAnyServer() = runBlocking {
        for (paged in listOf(false, true)) {
            val res = GrimmoryCatalog(seriesApi(paged)).series()
            assertEquals(listOf("Alpha Saga", "Gamma Reeks", "Eps Cycle"), res.content.map { it.seriesName })
            val alpha = res.content.first()
            assertEquals(3, alpha.bookCount)
            assertEquals(1, alpha.booksRead)
            assertEquals(listOf("Ann Author"), alpha.authors)
            assertEquals(listOf(5L, 8L), alpha.coverBooks!!.map { it.bookId })
            assertEquals(0, res.content[1].booksRead)
        }
    }

    @Test fun seriesCanBeSearchedByName() = runBlocking {
        val res = GrimmoryCatalog(seriesApi(pagedFacet = false)).series(search = "gamma")
        assertEquals(listOf("Gamma Reeks"), res.content.map { it.seriesName })
    }
}
