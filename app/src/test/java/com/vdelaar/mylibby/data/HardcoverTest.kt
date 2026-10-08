package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.core.network.HardcoverException
import com.vdelaar.mylibby.core.network.HardcoverGateway
import com.vdelaar.mylibby.core.network.HardcoverKeyStore
import com.vdelaar.mylibby.core.network.HARDCOVER_ENDPOINT
import com.vdelaar.mylibby.core.network.parseGraphqlData
import com.vdelaar.mylibby.core.network.parseHardcoverKey
import com.vdelaar.mylibby.ui.hardcover.formatCount
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun json(s: String): JsonObject = AppJson.parseToJsonElement(s).jsonObject

class HardcoverKeyParsingTest {
    @Test fun acceptsWhatPeoplePaste() {
        assertEquals("eyJabc.def", parseHardcoverKey("eyJabc.def")!!.token)
        assertEquals("eyJabc.def", parseHardcoverKey("  Bearer eyJabc.def \n")!!.token)
        assertEquals("eyJabc.def", parseHardcoverKey("authorization: Bearer eyJabc.def")!!.token)
        assertEquals("eyJabc.def", parseHardcoverKey("\"Bearer eyJabc.def\"")!!.token)
        assertEquals(HARDCOVER_ENDPOINT, parseHardcoverKey("Bearer x")!!.endpoint)
    }

    @Test fun rejectsNonsense() {
        assertNull(parseHardcoverKey(null))
        assertNull(parseHardcoverKey("   "))
        assertNull(parseHardcoverKey("Bearer"))
        assertNull(parseHardcoverKey("two words"))
    }

    @Test fun endpointOverrideOnlyWhenAllowed() {
        val c = parseHardcoverKey("http://10.0.2.2:8765/hardcover/graphql|mocktoken", allowOverride = true)!!
        assertEquals("mocktoken", c.token)
        assertEquals("http://10.0.2.2:8765/hardcover/graphql", c.endpoint)
        // release builds never send the key anywhere else
        assertNull(parseHardcoverKey("http://evil.example/graphql|tok", allowOverride = false)?.takeIf { it.endpoint != HARDCOVER_ENDPOINT })
    }
}

class HardcoverGraphqlTest {
    @Test fun returnsData() {
        assertEquals("1", parseGraphqlData(json("""{"data":{"x":1}}""")).getValue("x").jsonPrimitive.content)
    }

    private fun kindOf(body: String): HardcoverException.Kind =
        try { parseGraphqlData(json(body)); fail("expected an exception"); error("unreachable") } catch (e: HardcoverException) { e.kind }

    @Test fun mapsGraphqlErrors() {
        assertEquals(HardcoverException.Kind.INVALID_KEY, kindOf("""{"errors":[{"message":"Could not verify JWT: JWTExpired","extensions":{"code":"invalid-jwt"}}]}"""))
        assertEquals(HardcoverException.Kind.MISSING_SCOPE, kindOf("""{"errors":[{"message":"field 'books' not found in type: 'query_root'","extensions":{"code":"access-denied"}}]}"""))
        assertEquals(HardcoverException.Kind.BAD_QUERY, kindOf("""{"errors":[{"message":"syntax error"}]}"""))
        assertEquals(HardcoverException.Kind.SERVER, kindOf("""{"data":null}"""))
    }
}

class HardcoverParsingTest {
    private val book = json(
        """{"id": 42, "title": "Dune", "subtitle": null, "slug": "dune", "rating": 4.27, "ratings_count": 5123, "users_count": 20111,
            "release_year": 1965, "pages": 612, "description": "<p>Desert planet.</p>",
            "image": {"url": "https://assets.hardcover.app/dune.jpg"},
            "contributions": [{"author": {"name": "Frank Herbert"}}, {"author": {"name": "Frank Herbert"}}, {"author": null}],
            "book_series": [{"position": 1.0, "series": {"name": "Dune"}}]}"""
    )

    @Test fun parsesAFullBook() {
        val b = parseHardcoverBook(book)!!
        assertEquals(42, b.id)
        assertEquals("Dune", b.title)
        assertEquals(listOf("Frank Herbert"), b.authors)
        assertEquals("https://assets.hardcover.app/dune.jpg", b.imageUrl)
        assertEquals(4.27, b.rating!!, 1e-9)
        assertEquals(20111, b.readers)
        assertEquals(1965, b.year)
        assertEquals("Dune", b.series)
        assertEquals(1.0, b.seriesPosition!!, 1e-9)
        assertEquals("https://hardcover.app/books/dune", b.url)
        assertNull(b.subtitle)
    }

    @Test fun toleratesSparseAndNullFields() {
        val b = parseHardcoverBook(json("""{"id": 7, "title": "Bare", "image": null, "rating": null, "contributions": [], "book_series": [], "pages": null}"""))!!
        assertNull(b.imageUrl)
        assertNull(b.rating)
        assertTrue(b.authors.isEmpty())
        assertNull(b.series)
        assertNull(b.url)
    }

    @Test fun skipsBooksWithoutIdOrTitle() {
        assertNull(parseHardcoverBook(json("""{"title": "No id"}""")))
        assertNull(parseHardcoverBook(json("""{"id": 3, "title": "  "}""")))
    }

    @Test fun readsIdLists() {
        assertEquals(listOf(5, 9, 2), idsOf(json("""{"ids":[5,null,9,2],"error":null}""")))
        assertTrue(idsOf(null).isEmpty())
    }

    @Test fun formatsCounts() {
        assertEquals("999", formatCount(999))
        assertEquals("1.2k", formatCount(1234))
        assertEquals("20k", formatCount(20111))
        assertEquals("1.5M", formatCount(1_500_000))
    }
}

class HardcoverRankingTest {
    @Test fun booksHigherInMoreListsWin() {
        // 3 appears 1st in one list and 2nd in another; 9 only 1st in one list
        val r = mergeSimilar(listOf(listOf(9, 3, 4), listOf(3, 5)), exclude = emptySet(), limit = 10)
        assertEquals(listOf(3, 9, 5, 4), r)
    }

    @Test fun excludesSeedsAndHonoursLimit() {
        assertEquals(listOf(2, 3), mergeSimilar(listOf(listOf(1, 2, 3, 4)), exclude = setOf(1), limit = 2))
        assertTrue(mergeSimilar(emptyList(), emptySet(), 5).isEmpty())
    }
}

class HardcoverMatchingTest {
    private fun hc(title: String, vararg authors: String) = HardcoverBook(1, title, authors = authors.toList())
    private fun book(title: String, vararg authors: String) = Book(1, title, authors.toList())

    @Test fun normalisesTitles() {
        assertEquals("time machine", normalizeTitle("The Time Machine"))
        assertEquals("avonden", normalizeTitle("De Avonden"))
        assertEquals("dune", normalizeTitle("Dune: Deluxe Edition"))
        assertEquals("leading articles are dropped, accents too", "miserables", normalizeTitle("Les Misérables"))
        assertEquals("i robot", normalizeTitle("I, Robot"))
    }

    @Test fun sameWorkNeedsTitleAndNoContradictingAuthor() {
        assertTrue(sameWork(book("The Time Machine", "H. G. Wells"), hc("Time Machine", "H.G. Wells")))
        assertTrue(sameWork(book("Dune", "Frank Herbert"), hc("Dune: Deluxe Edition", "Frank Herbert")))
        // authors are compared by last name only (initials and spellings differ between catalogues)
        assertTrue(sameWork(book("Dune", "Frank Herbert"), hc("Dune", "F. Herbert")))
        assertFalse(sameWork(book("Dracula", "Bram Stoker"), hc("Dracula", "Someone Else")))
        assertFalse(sameWork(book("Dracula", "Bram Stoker"), hc("Carmilla", "Bram Stoker")))
        // an unknown author on either side is not a contradiction
        assertTrue(sameWork(book("Dracula"), hc("Dracula", "Bram Stoker")))
    }

    @Test fun picksTheMatchingCandidateOrNothing() {
        val b = book("Foundation", "Isaac Asimov")
        val cands = listOf(hc("Foundation and Empire", "Isaac Asimov"), hc("Foundation", "Isaac Asimov"))
        assertEquals("Foundation", pickBestMatch(b, cands)!!.title)
        assertNull(pickBestMatch(b, listOf(hc("Foundations of Geometry", "Euclid"))))
    }

    @Test fun findsBooksAlreadyInTheLibrary() {
        val lib = listOf(book("Frankenstein", "Mary Shelley"), book("Dracula", "Bram Stoker"))
        assertNotNull(inLibrary(hc("Dracula", "Bram Stoker"), lib))
        assertNull(inLibrary(hc("Dune", "Frank Herbert"), lib))
    }
}

class HardcoverCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun answersExpireAfterTheirTtl() {
        var now = 1_000_000L
        val cache = HardcoverCache(tmp.newFolder("c"), { now })
        cache.write("k", json("""{"a":1}"""))
        assertNotNull(cache.read("k", 60_000))
        now += 59_000
        assertNotNull(cache.read("k", 60_000))
        now += 2_000
        assertNull(cache.read("k", 60_000))
        assertNotNull("an expired answer can still be served when offline", cache.read("k", Long.MAX_VALUE))
        cache.clear()
        assertNull(cache.read("k", Long.MAX_VALUE))
    }
}

/** Fake Hardcover: answers the repository's four queries from a tiny in-memory catalogue and counts requests. */
private class FakeHardcover : HardcoverGateway {
    override var hasKey = true
    var calls = 0
    var failWith: HardcoverException? = null
    val trendingIds = listOf(30, 10, 20)
    val catalogue = mapOf(
        10 to ("Dune" to "Frank Herbert"), 20 to ("Neuromancer" to "William Gibson"), 30 to ("Dracula" to "Bram Stoker"),
        40 to ("Foundation" to "Isaac Asimov"), 50 to ("Hyperion" to "Dan Simmons"), 60 to ("Solaris" to "Stanislaw Lem"),
    )
    val similar = mapOf(40 to listOf(10, 50, 60, 30))

    private fun bookJson(id: Int): String {
        val (t, a) = catalogue.getValue(id)
        return """{"id":$id,"title":"$t","slug":"s$id","rating":4.0,"ratings_count":10,"users_count":100,"image":null,
            "contributions":[{"author":{"name":"$a"}}],"book_series":[]}"""
    }

    override suspend fun query(query: String, variables: JsonObject?): JsonObject {
        calls++
        failWith?.let { throw it }
        val ids = variables?.get("ids")?.jsonArray?.map { it.jsonPrimitive.content.toInt() }
        return when {
            "books_trending" in query -> json("""{"books_trending":{"ids":$trendingIds,"error":null}}""")
            "cached_similar_book_ids" in query -> json("""{"books":[${ids!!.joinToString(",") { """{"id":$it,"cached_similar_book_ids":${similar[it].orEmpty()}}""" }}]}""")
            "search(" in query -> {
                val q = variables!!["q"]!!.jsonPrimitive.content.lowercase()
                val hit = catalogue.entries.filter { (_, v) -> q.contains(v.first.lowercase()) }.map { it.key }
                json("""{"search":{"ids":$hit,"error":null}}""")
            }
            "BooksByIds" in query -> json("""{"books":[${ids!!.filter { it in catalogue }.joinToString(",") { bookJson(it) }}]}""")
            else -> error("unexpected query")
        }
    }
}

private class FakeKeys : HardcoverKeyStore { override var hardcoverKey: String? = null }

private class FakeLibrary(val seeds: List<Book>, val all: List<Book>) : HardcoverLibrary {
    override suspend fun tasteSeeds(limit: Int) = seeds.take(limit)
    override suspend fun libraryBooks() = all
}

class HardcoverRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(gw: FakeHardcover, lib: FakeLibrary = FakeLibrary(emptyList(), emptyList()), keys: FakeKeys = FakeKeys()) =
        HardcoverRepository(keys, gw, lib, HardcoverCache(tmp.newFolder())) to keys

    @Test fun trendingKeepsHardcoversOrderAndIsCached() = runBlocking {
        val gw = FakeHardcover()
        val (r, _) = repo(gw)
        val first = r.trending(HardcoverDuration.WEEK)
        assertEquals(listOf("Dracula", "Dune", "Neuromancer"), first.map { it.title })
        assertEquals(2, gw.calls) // ids + details
        r.trending(HardcoverDuration.WEEK)
        assertEquals("a second look is served from the cache", 2, gw.calls)
    }

    @Test fun trendingFallsBackToTheCacheWhenOffline() = runBlocking {
        val gw = FakeHardcover()
        val dir = tmp.newFolder()
        var now = 0L
        val r = HardcoverRepository(FakeKeys(), gw, FakeLibrary(emptyList(), emptyList()), HardcoverCache(dir) { now })
        r.trending(HardcoverDuration.MONTH)
        now += HardcoverRepository.TTL_BOOK * 2 // everything is stale
        gw.failWith = HardcoverException(HardcoverException.Kind.OFFLINE, "no network")
        assertEquals(3, r.trending(HardcoverDuration.MONTH).size)
    }

    @Test fun invalidKeyIsNotHiddenByTheCache() = runBlocking {
        val gw = FakeHardcover().apply { failWith = HardcoverException(HardcoverException.Kind.INVALID_KEY, "bad") }
        val (r, _) = repo(gw)
        try { r.trending(HardcoverDuration.WEEK); fail("expected an exception") } catch (e: HardcoverException) { assertEquals(HardcoverException.Kind.INVALID_KEY, e.kind) }
    }

    @Test fun suggestionsComeFromSimilarBooksMinusTheLibrary() = runBlocking {
        val gw = FakeHardcover()
        val foundation = Book(1, "Foundation", listOf("Isaac Asimov"), readStatus = "READ")
        val dracula = Book(2, "Dracula", listOf("Bram Stoker"))
        val (r, _) = repo(gw, FakeLibrary(seeds = listOf(foundation), all = listOf(foundation, dracula)))
        val s = r.suggestions()
        // similar to Foundation: Dune, Hyperion, Solaris, Dracula; Dracula is already in the library
        assertEquals(listOf("Dune", "Hyperion", "Solaris"), s.books.map { it.title })
        assertEquals(listOf("Foundation"), s.because)
    }

    @Test fun noSeedsMeansNoSuggestionsAndNoRequests() = runBlocking {
        val gw = FakeHardcover()
        val (r, _) = repo(gw)
        val s = r.suggestions()
        assertTrue(s.books.isEmpty())
        assertEquals(0, gw.calls)
    }

    @Test fun anUnknownSeedIsSkippedRatherThanGuessed() = runBlocking {
        val gw = FakeHardcover()
        val obscure = Book(1, "A Book Hardcover Lacks", listOf("Nobody"), readStatus = "READ")
        val (r, _) = repo(gw, FakeLibrary(seeds = listOf(obscure), all = listOf(obscure)))
        assertTrue(r.suggestions().books.isEmpty())
    }

    @Test fun connectKeepsTheOldKeyWhenTheNewOneIsRefused() = runBlocking {
        val gw = FakeHardcover()
        val keys = FakeKeys().apply { hardcoverKey = "old-key" }
        val (r, _) = repo(gw, keys = keys)
        gw.failWith = HardcoverException(HardcoverException.Kind.INVALID_KEY, "no")
        assertTrue(r.connect("Bearer new-key").isFailure)
        assertEquals("old-key", keys.hardcoverKey)
        gw.failWith = null
        assertTrue(r.connect("Bearer new-key").isSuccess)
        assertEquals("Bearer new-key", keys.hardcoverKey)
        r.disconnect()
        assertNull(keys.hardcoverKey)
    }

    @Test fun connectRejectsGarbageWithoutAnyRequest() = runBlocking {
        val gw = FakeHardcover()
        val (r, keys) = repo(gw)
        assertTrue(r.connect("not a key").isFailure)
        assertNull(keys.hardcoverKey)
        assertEquals(0, gw.calls)
    }
}
