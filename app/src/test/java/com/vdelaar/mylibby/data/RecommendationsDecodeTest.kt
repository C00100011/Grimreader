package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.model.toBook
import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.core.network.RecommendationDto
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape follows grimmory-tools/grimmory: BookController#getRecommendations -> List<BookRecommendation>. */
class RecommendationsDecodeTest {
    private val json = """
        [
          {"book": {"id": 12, "libraryId": 1, "libraryName": "Main", "title": "Foundation and Empire",
                    "primaryFile": {"id": 112, "bookId": 12, "fileName": "f.epub", "bookType": "EPUB", "extension": "epub", "someNewField": 1},
                    "addedOn": "2026-09-22T10:00:00Z", "lastReadTime": null, "readStatus": "UNREAD", "personalRating": 8,
                    "metadataMatchScore": 0.7, "epubProgress": {"cfi": "x", "percentage": 12.5}, "shelves": [{"id": 1, "name": "Favorites"}],
                    "metadata": {"bookId": 12, "title": "Foundation and Empire", "subtitle": null, "authors": ["Isaac Asimov"],
                                 "categories": ["Science Fiction", "Classic"], "seriesName": "Foundation", "seriesNumber": 2.0,
                                 "language": "en", "pageCount": 250, "coverUpdatedOn": "2026-09-01T10:00:00Z", "rating": 4.2,
                                 "publishedDate": "1952-01-01", "isbn13": "123", "amazonRating": 4.4}},
           "similarityScore": 0.81},
          {"book": {"id": 3, "title": "Bare minimum"}, "similarityScore": 0.2},
          {"book": null, "similarityScore": 0.1}
        ]
    """.trimIndent()

    private val decoded get() = AppJson.decodeFromString(ListSerializer(RecommendationDto.serializer()), json)

    @Test fun decodesTheNestedBookAndIgnoresUnknownFields() {
        assertEquals(3, decoded.size)
        assertEquals(0.81, decoded[0].similarityScore, 1e-9)
    }

    @Test fun mapsMetadataToTheAppBook() {
        val b = decoded[0].book!!.toBook()
        assertEquals(12L, b.id)
        assertEquals("Foundation and Empire", b.title)
        assertEquals(listOf("Isaac Asimov"), b.authors)
        assertEquals("Foundation", b.seriesName)
        assertEquals(2f, b.seriesNumber)
        assertEquals("EPUB", b.primaryFileType)
        assertEquals(112L, b.primaryFileId)
        assertEquals(8, b.rating)
        assertTrue(b.isReadable)
        assertNull("recommendations carry no progress", b.progress)
    }

    @Test fun sparseBooksStillMap() {
        val b = decoded[1].book!!.toBook()
        assertEquals("Bare minimum", b.title)
        assertTrue(b.authors.isEmpty())
        assertNull(decoded[2].book)
    }
}
