package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.model.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDerivationTest {
    private val books = listOf(
        Book(1, "Foundation", listOf("Isaac Asimov"), seriesName = "Foundation", seriesNumber = 1f, readStatus = "READ"),
        Book(2, "Foundation and Empire", listOf("Isaac Asimov"), seriesName = "Foundation", seriesNumber = 2f),
        Book(3, "Frankenstein", listOf("Mary Shelley")),
        Book(-4, "Local one", listOf("Mary Shelley", "Co Author")),
    )

    @Test fun authorsAreGroupedCountedAndSorted() {
        val a = authorsOf(books, "")
        assertEquals(listOf("Co Author", "Isaac Asimov", "Mary Shelley"), a.map { it.name })
        assertEquals(2, a.first { it.name == "Mary Shelley" }.bookCount)
        assertEquals(a.size, a.map { it.id }.toSet().size)
    }

    @Test fun authorSearchIsCaseInsensitiveAndEmptyWhenNothingMatches() {
        assertEquals(listOf("Isaac Asimov"), authorsOf(books, "asim").map { it.name })
        assertTrue(authorsOf(books, "zzzz").isEmpty())
    }

    @Test fun seriesAreDerivedWithCountsAndCovers() {
        val s = seriesOf(books, "")
        assertEquals(1, s.size)
        assertEquals("Foundation", s[0].name)
        assertEquals(2, s[0].bookCount)
        assertEquals(1, s[0].booksRead)
        assertEquals(listOf(1L, 2L), s[0].coverBookIds)
        assertTrue(seriesOf(books, "nothing").isEmpty())
    }

    @Test fun localBooksHaveNoServerCovers() {
        val local = listOf(Book(-1, "A", listOf("X"), seriesName = "S"))
        assertTrue(seriesOf(local, "")[0].coverBookIds.isEmpty())
    }
}
