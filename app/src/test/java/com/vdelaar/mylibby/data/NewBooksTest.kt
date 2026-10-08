package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.AppJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewBooksTest {
    private fun fixture(name: String): JsonObject =
        AppJson.parseToJsonElement(javaClass.getResource("/discover/$name")!!.readText()) as JsonObject

    private fun book(id: Long, title: String, authors: List<String> = listOf("A. Author"), categories: List<String> = emptyList(), language: String? = null, status: String? = null, rating: Int? = null) =
        Book(id = id, title = title, authors = authors, categories = categories, language = language, readStatus = status, rating = rating)

    // ---- Open Library, parsed from a real answer ----

    @Test fun realOpenLibraryAnswerParses() {
        val ideas = parseOpenLibrary(fixture("openlibrary-fantasy-dutch.json"))
        assertTrue("some ideas come out", ideas.isNotEmpty())
        ideas.forEach {
            assertTrue(it.title.isNotBlank())
            assertTrue("every idea has an author", it.authors.isNotEmpty())
            assertNotNull("and a cover picture", it.coverUrl())
            assertTrue(it.key.startsWith("/works/"))
        }
        assertTrue("Dutch editions are recognised", ideas.any { "nl" in it.languages })
    }

    @Test fun coverAndPageUrlsPointAtOpenLibrary() {
        val i = BookIdea(key = "/works/OL1W", title = "T", authors = listOf("A"), coverId = 42)
        assertEquals("https://covers.openlibrary.org/b/id/42-M.jpg", i.coverUrl())
        assertEquals("https://covers.openlibrary.org/b/id/42-L.jpg", i.coverUrl('L'))
        assertEquals("https://openlibrary.org/works/OL1W", i.pageUrl)
        assertNull(BookIdea("/works/OL2W", "T", listOf("A")).coverUrl())
    }

    @Test fun booksWithoutAnAuthorOrCoverAreLeftOut() {
        val root = AppJson.parseToJsonElement(
            """{"docs":[
              {"key":"/works/OL1W","title":"Good","author_name":["Ann"],"cover_i":1,"language":["dut","eng"],"isbn":["123","9781234567890"]},
              {"key":"/works/OL2W","title":"No cover","author_name":["Ann"]},
              {"key":"/works/OL3W","title":"No author","cover_i":3},
              {"key":"/works/OL4W","author_name":["Ann"],"cover_i":4}
            ]}"""
        ) as JsonObject
        val ideas = parseOpenLibrary(root)
        assertEquals(listOf("Good"), ideas.map { it.title })
        assertEquals(listOf("nl", "en"), ideas.single().languages)
        assertEquals("the 13-digit ISBN is preferred", "9781234567890", ideas.single().isbn)
    }

    @Test fun explicitBooksAreLeftOut() {
        val root = AppJson.parseToJsonElement(
            """{"docs":[
              {"key":"/works/OL1W","title":"A Gentle Tale","author_name":["Ann"],"cover_i":1,"subject":["Fantasy"]},
              {"key":"/works/OL2W","title":"New Pornographies","author_name":["Bob"],"cover_i":2},
              {"key":"/works/OL3W","title":"Moonlight","author_name":["Cy"],"cover_i":3,"subject":["Fantasy","Erotica"]},
              {"key":"/works/OL4W","title":"Sex Education for Parents","author_name":["Di"],"cover_i":4}
            ]}"""
        ) as JsonObject
        assertEquals(listOf("A Gentle Tale", "Sex Education for Parents"), parseOpenLibrary(root).map { it.title })
    }

    @Test fun popularNewBooksComeFirst() {
        assertEquals("want_to_read", OpenLibraryQuery.SORT)
    }

    @Test fun theQueryAsksForTheGenreTheLanguageAndRecentWorks() {
        assertEquals("""subject:"fantasy" language:dut first_publish_year:[2023 TO *]""", OpenLibraryQuery.newBooks("fantasy", "dut", 2023))
        assertEquals("""subject:"science fiction" language:eng first_publish_year:[2024 TO *]""", OpenLibraryQuery.newBooks("science fiction", "eng", 2024))
        assertFalse("quotes in a genre cannot break the query", OpenLibraryQuery.newBooks("say \"hi\"", "eng", 2024).contains("\"hi\""))
    }

    @Test fun editionsOfOneWorkCountOnce() {
        val a = BookIdea("/works/1", "The Rose Bargain", listOf("Sasha Peyton Smith"))
        val b = BookIdea("/works/2", "The Rose Bargain", listOf("Sasha Peyton Smith"))
        val c = BookIdea("/works/3", "Another", listOf("Sasha Peyton Smith"))
        assertEquals(listOf("/works/1", "/works/3"), listOf(a, b, c).distinctWorks().map { it.key })
    }

    // ---- Gutenberg, parsed from a real answer ----

    @Test fun realGutendexAnswerParses() {
        val books = parseGutendex(fixture("gutendex-dutch.json"))
        assertTrue(books.isNotEmpty())
        books.forEach {
            assertTrue(it.epubUrl.startsWith("http"))
            assertEquals("nl", it.language)
            assertFalse("catalogue codes are gone from the title", it.title.contains("$"))
        }
    }

    @Test fun catalogueTitlesAreCleaned() {
        assertEquals("Mathias Sandorf [1]: Een verijdelde samenzwering; Dokter Antekirrt", cleanCatalogTitle("Mathias Sandorf [1] : \$b Een verijdelde samenzwering; Dokter Antekirrt"))
        assertEquals("Plain title", cleanCatalogTitle("Plain title"))
    }

    @Test fun authorsAreShownFirstNameFirst() {
        assertEquals("Jane Austen", displayAuthor("Austen, Jane"))
        assertEquals("Homer", displayAuthor("Homer"))
    }

    @Test fun descriptionsComeAsStringOrObjectAndAreTidied() {
        val plain = AppJson.parseToJsonElement("""{"description":"A tale.\r\n\r\n\r\n\r\nSee [the site](http://x.org) too."}""") as JsonObject
        assertEquals("A tale.\n\nSee the site too.", parseWorkDescription(plain))
        val obj = AppJson.parseToJsonElement("""{"description":{"type":"/type/text","value":"Once upon a time.\n----------\nSource: a publisher"}}""") as JsonObject
        assertEquals("Once upon a time.", parseWorkDescription(obj))
        assertNull(parseWorkDescription(AppJson.parseToJsonElement("""{"title":"x"}""") as JsonObject))
        assertNull(parseWorkDescription(AppJson.parseToJsonElement("""{"description":"  "}""") as JsonObject))
    }

    // ---- languages ----

    @Test fun marcAndIsoCodesTranslate() {
        assertEquals("dut", marcOf("nl")); assertEquals("eng", marcOf("EN-gb")); assertNull(marcOf("xx"))
        assertEquals("nl", isoOf("dut")); assertNull(isoOf("zzz"))
        assertEquals("nl", isoLanguageOf("NL")); assertEquals("en", isoLanguageOf("en-GB")); assertEquals("de", isoLanguageOf("ger")); assertNull(isoLanguageOf(null))
    }

    @Test fun theAppLanguageComesFirstThenWhatTheLibraryHolds() {
        val lib = listOf(book(1, "a", language = "en"), book(2, "b", language = "en"), book(3, "c", language = "de"), book(4, "d", language = "nl"))
        assertEquals(listOf("nl", "en", "de"), readingLanguages(lib, "nl"))
        assertEquals(listOf("en", "de", "nl"), readingLanguages(lib, "en"))
        assertEquals(listOf("en"), readingLanguages(emptyList(), "xx"))
    }

    // ---- genres and taste ----

    @Test fun genresAreReducedToSearchTerms() {
        assertEquals("fantasy", normalizeGenre("Fiction / Fantasy / General"))
        assertEquals("science fiction", normalizeGenre("Science Fiction"))
        assertEquals("history", normalizeGenre("Non-Fiction / History"))
        assertNull(normalizeGenre("Fiction"))
        assertNull(normalizeGenre("General"))
    }

    @Test fun tasteFollowsWhatYouFinishedFavouriteAndRated() {
        val books = listOf(
            book(1, "a", categories = listOf("Fantasy"), status = "READ"),
            book(2, "b", categories = listOf("Fantasy"), status = "READ", rating = 9),
            book(3, "c", categories = listOf("Thriller"), status = "READING"),
            book(4, "d", categories = listOf("Romance"), status = "READ", rating = 2),
        )
        val w = genreWeights(books, favouriteIds = setOf(3L), profileGenres = listOf("Horror"))
        assertEquals(7.0, w["Fantasy"]!!, 0.0001)   // 2 + (2 + 3)
        assertEquals(4.0, w["Thriller"]!!, 0.0001)  // 1 + 3 (favourite)
        assertEquals(3.0, w["Horror"]!!, 0.0001)    // chosen in the profile
        assertEquals(-1.0, w["Romance"]!!, 0.0001)  // 2 - 3: finished but rated badly
    }

    @Test fun searchGenresAreTheMostLikedMergedByName() {
        val w = mapOf("Fiction / Fantasy" to 2.0, "Fantasy" to 3.0, "Thriller" to 4.0, "Romance" to -1.0, "General" to 9.0, "Horror" to 1.0)
        assertEquals(listOf("fantasy", "thriller"), searchGenres(w, 2))
        assertEquals(listOf("fantasy", "thriller", "horror"), searchGenres(w, 5))
    }

    // ---- is it already mine? ----

    @Test fun anIdeaYouAlreadyOwnIsRecognised() {
        val lib = listOf(book(1, "The Hobbit", authors = listOf("J.R.R. Tolkien")), book(2, "Dune", authors = listOf("Frank Herbert")))
        assertEquals(1L, libraryMatch("The Hobbit", listOf("Tolkien"), lib)?.id)
        assertEquals("a leading article does not matter", 1L, libraryMatch("Hobbit", listOf("J. R. R. Tolkien"), lib)?.id)
        assertNull("same title, a different author", libraryMatch("Dune", listOf("Someone Else"), lib))
        assertEquals("no author known: the title decides", 2L, libraryMatch("Dune", emptyList(), lib)?.id)
        assertNull(libraryMatch("", listOf("X"), lib))
    }
}
