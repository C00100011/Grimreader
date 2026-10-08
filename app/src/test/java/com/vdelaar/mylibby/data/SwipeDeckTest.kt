package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.SwipeCardEntity
import com.vdelaar.mylibby.core.model.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SwipeDeckTest {
    private fun idea(n: Int, title: String = "Book $n", author: String = "Author $n") = BookIdea("/works/OL${n}W", title, listOf(author), coverId = n.toLong())

    @Test fun aNewDeckAppearsEveryMonday() {
        assertEquals("2026-W41", weekKey(LocalDate.of(2026, 10, 5)))   // Monday
        assertEquals("2026-W41", weekKey(LocalDate.of(2026, 10, 11)))  // Sunday
        assertEquals("2026-W42", weekKey(LocalDate.of(2026, 10, 12)))  // next Monday
    }

    @Test fun theWeekFollowsTheIsoYearAtTheTurnOfTheYear() {
        assertEquals("2026-W53", weekKey(LocalDate.of(2027, 1, 1)))   // 1 Jan 2027 is still ISO week 53 of 2026
        assertEquals("2025-W01", weekKey(LocalDate.of(2024, 12, 30))) // 30 Dec 2024 is ISO week 1 of 2025
    }

    @Test fun everyGenreIsInTheDeckInTurn() {
        val fantasy = (1..5).map { idea(it) }
        val thriller = (11..15).map { idea(it) }
        val deck = buildDeck(listOf(fantasy, thriller), emptySet(), emptyList(), size = 6)
        assertEquals(listOf(1, 11, 2, 12, 3, 13), deck.map { it.key.removePrefix("/works/OL").removeSuffix("W").toInt() })
    }

    @Test fun whatYouHaveSawOrWantIsLeftOut() {
        val library = listOf(Book(id = 1, title = "Book 2", authors = listOf("Author 2")))
        val deck = buildDeck(listOf((1..5).map { idea(it) }), seenKeys = setOf("/works/OL1W", "/works/OL3W"), library = library)
        assertEquals(listOf("Book 4", "Book 5"), deck.map { it.title })
    }

    @Test fun theSameWorkTwiceCountsOnce() {
        val deck = buildDeck(listOf(listOf(idea(1, "Twin", "A. Writer"), idea(2, "Twin", "A. Writer"), idea(3))), emptySet(), emptyList())
        assertEquals(listOf("Twin", "Book 3"), deck.map { it.title })
    }

    @Test fun aDeckHasAtMostTwelveCardsByDefault() {
        assertEquals(12, buildDeck(listOf((1..40).map { idea(it) }), emptySet(), emptyList()).size)
    }

    @Test fun noIdeasGiveAnEmptyDeck() {
        assertTrue(buildDeck(emptyList(), emptySet(), emptyList()).isEmpty())
        assertTrue(buildDeck(listOf(emptyList()), emptySet(), emptyList()).isEmpty())
    }

    @Test fun interleavingKeepsTheOrderWithinEachList() {
        assertEquals(listOf("a1", "b1", "a2", "b2", "b3"), interleave(listOf(listOf("a1", "a2"), listOf("b1", "b2", "b3"))))
        assertEquals(emptyList<String>(), interleave(emptyList<List<String>>()))
    }

    @Test fun aStoredCardBecomesTheIdeaAgain() {
        val card = SwipeCardEntity(
            week = "2026-W41", key = "/works/OL9W", position = 0, title = "T", authors = "Ann|Bob", year = 2026,
            coverId = 7, isbn = "9781234567890", languages = "nl,en", subjects = "Fantasy|Magic",
        )
        val i = card.toIdea()
        assertEquals(listOf("Ann", "Bob"), i.authors)
        assertEquals(listOf("nl", "en"), i.languages)
        assertEquals(listOf("Fantasy", "Magic"), i.subjects)
        assertEquals(7L, i.coverId)
    }

    @Test fun swipeCodesRoundTrip() {
        Swipe.entries.forEach { assertEquals(it, Swipe.of(it.code)) }
        assertEquals(null, Swipe.of(null))
    }
}
