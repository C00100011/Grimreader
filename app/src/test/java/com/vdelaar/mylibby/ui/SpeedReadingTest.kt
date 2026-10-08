package com.vdelaar.mylibby.ui

import com.vdelaar.mylibby.core.datastore.SpeedMode
import com.vdelaar.mylibby.ui.reader.SpeedReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedReadingTest {

    private fun words(s: String) = s.split(' ')

    @Test fun orpFollowsWordLength() {
        assertEquals(0, SpeedReading.orpIndex("I"))
        assertEquals(1, SpeedReading.orpIndex("the"))
        assertEquals(2, SpeedReading.orpIndex("reading"))
        assertEquals(4, SpeedReading.orpIndex("extraordinarily"))
    }

    @Test fun orpSkipsLeadingQuote() {
        assertEquals(2, SpeedReading.orpIndex("\"the"))
    }

    @Test fun punctuationAndParagraphsTakeLonger() {
        val plain = SpeedReading.weight("word")
        assertTrue(SpeedReading.weight("word,") > plain)
        assertTrue(SpeedReading.weight("word.") > SpeedReading.weight("word,"))
        assertTrue(SpeedReading.weight("word.\n") > SpeedReading.weight("word."))
        assertTrue(SpeedReading.weight("extraordinarily") > plain)
    }

    @Test fun wordModeGivesOneUnitPerWord() {
        val units = SpeedReading.plan(words("one two three"), SpeedMode.WORD, 3, "en")
        assertEquals(3, units.size)
        assertEquals("two", units[1].text)
    }

    @Test fun phrasesStopAtPunctuation() {
        val units = SpeedReading.plan(words("The old house stood, silent and grey."), SpeedMode.PHRASE, 4, "en")
        assertEquals("The old house stood,", units[0].text)
        assertTrue(units.last().text.endsWith("grey."))
    }

    @Test fun phrasesDoNotEndOnAnArticle() {
        val units = SpeedReading.plan(words("She opened the window and saw a bird"), SpeedMode.PHRASE, 3, "en")
        // "She opened the" would end on "the": the article moves to the next phrase.
        assertEquals("She opened", units[0].text)
        // ...and "the window and" ends on a conjunction, so that moves on as well.
        assertEquals("the window", units[1].text)
        assertEquals("and saw", units[2].text)
    }

    @Test fun dutchFunctionWordsAreRecognised() {
        val units = SpeedReading.plan(words("Hij liep naar de winkel om brood"), SpeedMode.PHRASE, 3, "nl")
        assertEquals("Hij liep", units[0].text)
    }

    @Test fun everyWordIsCoveredExactlyOnce() {
        val w = words("a b c d e f g h i j k l m n o p q r s t")
        for (n in 2..4) {
            val units = SpeedReading.plan(w, SpeedMode.PHRASE, n, "en")
            assertEquals(0, units.first().first)
            assertEquals(w.size - 1, units.last().last)
            units.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        }
    }

    @Test fun planCanStartMidway() {
        val units = SpeedReading.plan(words("one two three four"), SpeedMode.WORD, 3, "en", from = 2)
        assertEquals(2, units.first().first)
    }

    @Test fun sentenceStartJumpsBack() {
        val w = words("Hello there. This is a test. Another one.")
        assertEquals(2, SpeedReading.sentenceStart(w, 5)) // mid second sentence -> its start
        assertEquals(0, SpeedReading.sentenceStart(w, 2)) // at a start -> previous sentence start
        assertEquals(0, SpeedReading.sentenceStart(w, 0))
    }

    @Test fun fasterSpeedMeansShorterUnits() {
        val u = SpeedReading.plan(words("word"), SpeedMode.WORD, 3, "en").first()
        assertTrue(SpeedReading.durationMs(u, 600, 20) < SpeedReading.durationMs(u, 300, 20))
        assertTrue(SpeedReading.durationMs(u, 300, 0) > SpeedReading.durationMs(u, 300, 20)) // ramp-up
        assertEquals(200L, SpeedReading.durationMs(u, 300, 20))
    }
}
