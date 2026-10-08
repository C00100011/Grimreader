package com.vdelaar.mylibby.ui

import com.vdelaar.mylibby.core.network.ShelfmarkBook
import com.vdelaar.mylibby.ui.discover.DiscoverState
import com.vdelaar.mylibby.ui.discover.withQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverQueryTest {
    private val searched = DiscoverState(
        query = "dune",
        searched = true,
        results = listOf(ShelfmarkBook(provider = "p", provider_id = "1", title = "Dune")),
        error = "boom",
    )

    @Test fun typingKeepsTheResultsUntilTheNextSearch() {
        val s = searched.withQuery("dune messiah")
        assertEquals("dune messiah", s.query)
        assertTrue(s.searched)
        assertEquals(1, s.results.size)
    }

    @Test fun clearingTheTextBringsBackTheOverview() {
        val s = searched.withQuery("")
        assertEquals("", s.query)
        assertFalse("not in search mode any more, so Discover shows the recommendations again", s.searched)
        assertTrue(s.results.isEmpty())
        assertNull(s.error)
        assertFalse(s.searching)
    }

    @Test fun whitespaceOnlyCountsAsCleared() {
        assertFalse(searched.withQuery("   ").searched)
    }

    @Test fun clearingWhileASearchIsRunningStopsIt() {
        val s = searched.copy(searching = true).withQuery("")
        assertFalse(s.searching)
    }
}
