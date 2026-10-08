package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.BookSessionTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BookPaceTest {
    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** 6 hours of reading across three days, covering 60% of a 90,000-word book: 54,000 words in 360 min = 150 wpm. */
    private val totals = BookSessionTotals(seconds = 6 * 3600L, bytes = 600L, firstAt = at("2026-10-01"), lastAt = at("2026-10-03") + 3_600_000, days = 3)

    @Test fun paceFromTimeBytesAndWordCount() {
        val p = BookPaceCalculator.compute(totals, totalWords = 90_000, totalBytes = 1_000, zone = ZoneOffset.UTC)!!
        assertEquals(150, p.wordsPerMinute)
        assertEquals(3, p.daysRead)
        assertEquals(3, p.spanDays)
        assertEquals(6 * 3600L, p.readSeconds)
    }

    @Test fun comparesWithTheAverageAdult() {
        val p = BookPaceCalculator.compute(totals, 90_000, 1_000, ZoneOffset.UTC)!!
        // 150 wpm against 238: about 37% slower
        assertEquals(-37, p.percentVsAverage)
        // 90,000 words at 238 wpm
        assertEquals(90_000L * 60 / 238, p.averageSeconds)
    }

    @Test fun fasterReaderGetsAPositivePercentage() {
        val fast = totals.copy(bytes = 1_000, seconds = 3 * 3600L) // 90,000 words in 180 min = 500 wpm
        assertEquals(500, BookPaceCalculator.compute(fast, 90_000, 1_000, ZoneOffset.UTC)!!.wordsPerMinute)
        assertEquals(110, BookPaceCalculator.compute(fast, 90_000, 1_000, ZoneOffset.UTC)!!.percentVsAverage)
    }

    @Test fun tooLittleReadingSaysNothing() {
        assertNull(BookPaceCalculator.compute(totals.copy(seconds = 200), 90_000, 1_000, ZoneOffset.UTC))
    }

    @Test fun withoutWordCountThereIsTimeButNoPace() {
        val p = BookPaceCalculator.compute(totals, totalWords = null, totalBytes = null, zone = ZoneOffset.UTC)
        assertNotNull(p)
        assertNull(p!!.wordsPerMinute)
        assertNull(p.averageSeconds)
        assertNull(p.percentVsAverage)
    }

    @Test fun implausiblePaceIsLeftOut() {
        // 10 minutes for 60% of a book: not real reading (jumped through it)
        val skim = totals.copy(seconds = 600)
        assertNull(BookPaceCalculator.compute(skim, 90_000, 1_000, ZoneOffset.UTC)!!.wordsPerMinute)
    }

    @Test fun theReferenceCanBeChanged() {
        // 150 wpm against a relaxed 200: 25% slower; the "average reader" needs 90,000 / 200 words per minute.
        val p = BookPaceCalculator.compute(totals, 90_000, 1_000, ZoneOffset.UTC, referenceWpm = 200)!!
        assertEquals(200, p.referenceWpm)
        assertEquals(-25, p.percentVsAverage)
        assertEquals(90_000L * 60 / 200, p.averageSeconds)
    }

    @Test fun oneDayIsOneCalendarDay() {
        val p = BookPaceCalculator.compute(totals.copy(firstAt = at("2026-10-05"), lastAt = at("2026-10-05") + 7_200_000, days = 1), 90_000, 1_000, ZoneOffset.UTC)!!
        assertEquals(1, p.spanDays)
    }
}
