package com.vdelaar.mylibby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Builds blobs the way the Kobo (Qt's QDataStream) writes them. */
private class Blob {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)
    fun str(s: String?) { if (s == null) out.writeInt(-1) else { val b = s.toByteArray(Charsets.UTF_16BE); out.writeInt(b.size); out.write(b) } }
    fun variant(type: Int, isNull: Boolean = false, body: () -> Unit) { out.writeInt(type); out.writeByte(if (isNull) 1 else 0); body() }
    fun int(v: Int) = variant(2) { out.writeInt(v) }
    fun text(v: String?) = variant(10) { str(v) }
    fun nothing() = variant(0, isNull = true) {}
    fun ints(values: List<Int>) = variant(9) { out.writeInt(values.size); values.forEach { out.writeInt(2); out.writeByte(0); out.writeInt(it) } }
    fun longs(values: List<Long>) = variant(9) { out.writeInt(values.size); values.forEach { out.writeInt(4); out.writeByte(0); out.writeLong(it) } }
    fun count(n: Int) = out.writeInt(n)
    fun key(k: String) = str(k)
    fun bytes(): ByteArray = bytes.toByteArray()
}

/** The layout of a type-46 event of a Kobo Libra/Clara: counts, word counts, timestamps, then view information. */
private fun eventBlob(turns: List<Long>, timestampsAsInts: Boolean = true) = Blob().apply {
    count(6)
    key("wordsRead"); int(turns.size)
    key("wordCounts"); ints(turns.map { 120 })
    key("eventTimestamps"); if (timestampsAsInts) ints(turns.map { it.toInt() }) else longs(turns)
    key("ViewType"); text("ReadingView")
    key("StartPercentage"); nothing()
    key("PagesTurnedThisSession"); int(turns.size)
}.bytes()

class KoboSessionsTest {
    private val t0 = 1_777_649_549L

    // The blob ----------------------------------------------------------------------------------------------------

    @Test fun readsPageTurnsFromTheBlob() {
        val turns = listOf(t0, t0 + 2, t0 + 5, t0 + 300)
        assertEquals(turns, koboPageTurns(eventBlob(turns)))
    }

    @Test fun readsTurnsStoredAs64Bit() {
        val turns = listOf(t0, t0 + 10)
        assertEquals(turns, koboPageTurns(eventBlob(turns, timestampsAsInts = false)))
    }

    @Test fun millisecondTimestampsAreConvertedToSeconds() {
        assertEquals(listOf(t0, t0 + 3), koboPageTurns(eventBlob(listOf(t0 * 1000, (t0 + 3) * 1000), timestampsAsInts = false)))
    }

    @Test fun turnsComeBackSorted() {
        assertEquals(listOf(t0, t0 + 1, t0 + 9), koboPageTurns(eventBlob(listOf(t0 + 9, t0, t0 + 1))))
    }

    @Test fun nullStringsAndEmptyListsDoNotBreakReading() {
        val blob = Blob().apply {
            count(3)
            key("ViewType"); text(null)
            key("wordCounts"); ints(emptyList())
            key("eventTimestamps"); ints(listOf(t0.toInt(), t0.toInt() + 4))
        }.bytes()
        assertEquals(listOf(t0, t0 + 4), koboPageTurns(blob))
    }

    @Test fun otherBlobsGiveNothing() {
        assertTrue(koboPageTurns(null).isEmpty())
        assertTrue(koboPageTurns(ByteArray(0)).isEmpty())
        assertTrue(koboPageTurns(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(koboPageTurns(ByteArray(64) { 0x7f }).isEmpty())
        // A map without timestamps (another event type).
        assertTrue(koboPageTurns(Blob().apply { count(1); key("ViewType"); text("x") }.bytes()).isEmpty())
    }

    @Test fun aCutOffBlobGivesNothingInsteadOfCrashing() {
        val full = eventBlob(listOf(t0, t0 + 1, t0 + 2))
        for (n in full.indices step 7) koboPageTurns(full.copyOf(n)) // must not throw
        assertTrue(koboPageTurns(full.copyOf(full.size / 3)).isEmpty())
    }

    @Test fun anUnknownValueTypeGivesNothing() {
        val blob = Blob().apply { count(1); key("x"); variant(99) {} }.bytes()
        assertTrue(koboPageTurns(blob).isEmpty())
    }

    // Sessions ----------------------------------------------------------------------------------------------------

    @Test fun closePageTurnsAreOneSession() {
        val s = koboSessions(listOf(t0, t0 + 60, t0 + 130, t0 + 200))
        assertEquals(listOf(KoboSession(t0, t0 + 200, 4)), s)
        assertEquals(200, s[0].seconds)
    }

    @Test fun aLongPauseStartsANewSession() {
        val s = koboSessions(listOf(t0, t0 + 60, t0 + 60 + 601, t0 + 60 + 700))
        assertEquals(2, s.size)
        assertEquals(KoboSession(t0, t0 + 60, 2), s[0])
        assertEquals(KoboSession(t0 + 661, t0 + 760, 2), s[1])
    }

    @Test fun aPauseOfExactlyTheGapStaysInTheSession() {
        assertEquals(1, koboSessions(listOf(t0, t0 + 600, t0 + 1200)).size)
    }

    @Test fun aGlanceAtAPageIsNotASession() {
        assertTrue(koboSessions(listOf(t0)).isEmpty())
        assertTrue(koboSessions(listOf(t0, t0 + 10)).isEmpty())
        assertEquals(1, koboSessions(listOf(t0, t0 + 30)).size)
    }

    @Test fun noTurnsNoSessions() = assertTrue(koboSessions(emptyList()).isEmpty())

    @Test fun unsortedTurnsAreHandled() {
        assertEquals(listOf(KoboSession(t0, t0 + 100, 3)), koboSessions(listOf(t0 + 100, t0, t0 + 50)))
    }

    @Test fun everyPageTurnIsCountedExactlyOnce() {
        val turns = listOf(t0, t0 + 40, t0 + 80, t0 + 2000, t0 + 2040, t0 + 5000, t0 + 5050)
        val s = koboSessions(turns, minSeconds = 0)
        assertEquals(turns.size, s.sumOf { it.pages })
        assertEquals(3, s.size)
    }

    // Matching ----------------------------------------------------------------------------------------------------

    private fun known(vararg b: Triple<Long, String, String>) = b.map { KnownBook(it.first, it.second, it.third, "EPUB") }
    private fun kobo(title: String, author: String? = null) = KoboBook(title, author, null, listOf(1))

    @Test fun matchesOnTitleIgnoringCaseAccentsAndPunctuation() {
        val lib = known(Triple(1, "De Waarheid", "A"), Triple(2, "Café Society!", "B"))
        assertEquals(1L, matchKoboBook(kobo("de waarheid"), lib)?.id)
        assertEquals(2L, matchKoboBook(kobo("Cafe Society"), lib)?.id)
    }

    @Test fun matchesWhenOnlyTheSubtitleDiffers() {
        val lib = known(Triple(7, "Clean Architecture", "Robert C. Martin"))
        assertEquals(7L, matchKoboBook(kobo("Clean Architecture: A Craftsman's Guide to Software Structure"), lib)?.id)
        assertEquals(7L, matchKoboBook(kobo("Clean Architecture - A Guide"), lib)?.id)
    }

    @Test fun unknownBookIsNotMatched() {
        assertNull(matchKoboBook(kobo("Artemis"), known(Triple(1, "Andy Weir", "x"))))
        assertNull(matchKoboBook(kobo(""), known(Triple(1, "", "x"))))
    }

    @Test fun sameTitleIsSettledByAuthor() {
        val lib = known(Triple(1, "Het Huis", "Anna de Vries"), Triple(2, "Het Huis", "Jan Smit, Piet Pot"))
        assertEquals(2L, matchKoboBook(kobo("Het huis", "Piet Pot"), lib)?.id)
        assertEquals(1L, matchKoboBook(kobo("Het huis", "Anna de Vries"), lib)?.id)
    }

    @Test fun sameTitleWithoutAnAuthorMatchIsNotGuessed() {
        val lib = known(Triple(1, "Het Huis", "A. Een"), Triple(2, "Het Huis", "B. Twee"))
        assertNull(matchKoboBook(kobo("Het huis", "Someone Else"), lib))
        assertNull(matchKoboBook(kobo("Het huis", null), lib))
    }

    @Test fun anAmbiguousExactTitleDoesNotFallBackToALooserMatch() {
        val lib = known(Triple(1, "Het Huis", "A"), Triple(2, "Het Huis", "B"), Triple(3, "Het Huis: Deel 2", "C"))
        assertNull(matchKoboBook(kobo("Het Huis", null), lib))
    }

    @Test fun veryShortMainTitlesAreNotMatchedOnTheSubtitleRule() {
        assertNull(matchKoboBook(kobo("It: A Novel"), known(Triple(1, "It", "King"))))
    }

    // Duplicates --------------------------------------------------------------------------------------------------

    @Test fun duplicateIsAStartWithinFiveSeconds() {
        val have = listOf(1_000_000L, 5_000_000L)
        assertTrue(isDuplicateSession(have, 1_000_000))
        assertTrue(isDuplicateSession(have, 1_004_999))
        assertTrue(isDuplicateSession(have, 995_000))
        assertFalse(isDuplicateSession(have, 1_005_001))
        assertFalse(isDuplicateSession(emptyList(), 1_000_000))
    }
}
