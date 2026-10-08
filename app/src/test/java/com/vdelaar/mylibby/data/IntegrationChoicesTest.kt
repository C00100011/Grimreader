package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.datastore.AppState
import com.vdelaar.mylibby.core.datastore.BookSearchSource
import com.vdelaar.mylibby.core.datastore.LibrarySource
import com.vdelaar.mylibby.core.datastore.RecommendationSource
import com.vdelaar.mylibby.core.network.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationChoicesTest {
    @Test fun defaultsKeepTheExistingBehaviour() {
        val s = AppState()
        assertTrue(LibrarySource.GRIMMORY in s.librarySources)
        assertFalse(s.noServer)
        assertEquals(BookSearchSource.SHELFMARK, s.effectiveBookSearch)
        assertEquals(RecommendationSource.LOCAL, s.recommendations)
        assertTrue("Discover (Shelfmark search) shows as before", s.showDiscover)
    }

    @Test fun localOnlyUsersKeepTheirTabsUntilTheyChoose() {
        val s = AppState(localOnly = true)
        assertEquals(setOf(LibrarySource.LOCAL), s.librarySources)
        assertTrue(s.noServer)
        assertEquals("local-only never showed Discover", BookSearchSource.NONE, s.effectiveBookSearch)
        assertFalse(s.showDiscover)
        // ... but choosing Shelfmark or Hardcover brings it
        assertTrue(s.copy(bookSearch = BookSearchSource.SHELFMARK).showDiscover)
        assertTrue(s.copy(recommendations = RecommendationSource.HARDCOVER).showDiscover)
    }

    @Test fun anOpdsCatalogWithoutGrimmoryIsALocalLibrary() {
        val s = AppState(opdsActive = true, localOnly = true, opdsUrl = "http://h/opds")
        assertEquals(setOf(LibrarySource.OPDS, LibrarySource.LOCAL), s.librarySources)
        assertTrue("nothing talks to Grimmory", s.noServer)
        assertEquals("without Grimmory the book search is off by default", BookSearchSource.NONE, s.effectiveBookSearch)
    }

    @Test fun anOpdsCatalogNextToGrimmoryKeepsTheServer() {
        val s = AppState(opdsActive = true, opdsUrl = "http://h/opds")
        assertTrue(LibrarySource.GRIMMORY in s.librarySources && LibrarySource.OPDS in s.librarySources)
        assertFalse(s.noServer)
        assertEquals(BookSearchSource.SHELFMARK, s.effectiveBookSearch)
    }

    @Test fun demoIsLocal() {
        val s = AppState(demo = true)
        assertEquals(setOf(LibrarySource.LOCAL), s.librarySources)
        assertTrue(s.noServer)
    }

    @Test fun noSearchAndLibraryRecommendationsHideDiscover() {
        assertFalse(AppState(bookSearch = BookSearchSource.NONE).showDiscover)
        assertTrue(AppState(bookSearch = BookSearchSource.NONE, recommendations = RecommendationSource.HARDCOVER).showDiscover)
    }

    @Test fun oldStoredStateStillLoads() {
        val old = """{"onboardingDone":true,"localOnly":true,"librarySort":"title"}"""
        val s = AppJson.decodeFromString(AppState.serializer(), old)
        assertTrue(s.localOnly)
        assertEquals(setOf(LibrarySource.LOCAL), s.librarySources)
        val round = AppJson.decodeFromString(AppState.serializer(), AppJson.encodeToString(AppState.serializer(), s.copy(opdsUrl = "http://h/opds", recommendations = RecommendationSource.HARDCOVER, bookSearch = BookSearchSource.NONE)))
        assertEquals(RecommendationSource.HARDCOVER, round.recommendations)
        assertEquals(BookSearchSource.NONE, round.bookSearch)
        assertEquals("http://h/opds", round.opdsUrl)
    }
}
