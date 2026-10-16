package com.vdelaar.mylibby.ui

import com.vdelaar.mylibby.ui.reader.ReadingClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inactivity detection (issue #10): the reading timer pauses when the reader stops interacting. */
class ReadingClockTest {
    private fun clock(enabled: Boolean = true, multiplier: Float = 2f) = ReadingClock({ enabled }, { multiplier })
    private fun ReadingClock.learn(ms: Long, pages: Int = 6) = repeat(pages) { pageTurned(ms) }
    private val s = 1000L

    @Test fun defaultThresholdUntilEnoughPages() {
        val c = clock().apply { start(0) }
        assertNull(c.typicalPageMs())
        c.learn(30 * s, pages = ReadingClock.MIN_SAMPLES - 1)
        assertEquals(ReadingClock.DEFAULT_MS, c.threshold())
    }

    @Test fun thresholdIsMultiplierTimesMedian() {
        val c = clock().apply { start(0) }
        c.learn(40 * s)
        assertEquals(40 * s, c.typicalPageMs())
        assertEquals(80 * s, c.threshold())
        assertEquals(120 * s, clock(multiplier = 3f).apply { learn(40 * s) }.threshold())
    }

    @Test fun thresholdIsKeptBetweenOneAndTenMinutes() {
        assertEquals(ReadingClock.MIN_MS, clock().apply { learn(5 * s) }.threshold())
        assertEquals(ReadingClock.MAX_MS, clock(multiplier = 4f).apply { learn(200 * s) }.threshold())
    }

    @Test fun aSlowReaderWhoConfirmsTeachesTheClock() {
        val c = clock().apply { start(0) }
        var t = 0L
        repeat(ReadingClock.MIN_SAMPLES) {
            t += ReadingClock.DEFAULT_MS + 1
            assertTrue(c.tick(t))      // 3-minute pages: paused at the default 2 minutes ...
            c.resume(t)                // ... "yes, still reading"
            c.pageTurned(180 * s)
        }
        assertEquals(360 * s, c.threshold()) // no more prompts on pages this slow
    }

    @Test fun medianIgnoresOutliers() {
        val c = clock().apply { start(0) }
        listOf(30, 32, 31, 29, 30, 33, 28, 400).forEach { c.pageTurned(it * s) }
        assertEquals(30_500L, c.typicalPageMs()) // mean would be 77 s
    }

    @Test fun onlyTheLastFifteenPagesCount() {
        val c = clock().apply { start(0) }
        repeat(20) { c.pageTurned(100 * s) }
        repeat(15) { c.pageTurned(20 * s) }
        assertEquals(20 * s, c.typicalPageMs())
    }

    @Test fun pagesLeftOpenOrFlippedThroughAreNotSamples() {
        val c = clock().apply { start(0) }
        repeat(10) { c.pageTurned(500) } // flicking through
        repeat(10) { c.pageTurned(20 * 60 * s) } // longer than any sensible page
        assertNull(c.typicalPageMs())
    }

    @Test fun pausesAfterTheThresholdAndNotBefore() {
        val c = clock().apply { start(0); learn(40 * s) } // threshold 80 s
        assertFalse(c.tick(80 * s))
        assertFalse(c.paused)
        assertTrue(c.tick(81 * s))
        assertTrue(c.paused)
        assertFalse("only reports the moment it pauses", c.tick(90 * s))
    }

    @Test fun idleTimeIsNotCounted() {
        val c = clock().apply { start(0); learn(40 * s) }
        c.interaction(30 * s)          // 30 s of reading
        c.tick(120 * s)                // idle: paused
        c.interaction(1_000 * s)       // back much later
        assertEquals(30 * s, c.activeMs)
        assertFalse(c.paused)
    }

    @Test fun nextInteractionResumesAndCountsFromThere() {
        val c = clock().apply { start(0); learn(40 * s) }
        c.tick(100 * s)
        c.interaction(500 * s)
        c.interaction(520 * s)
        assertEquals(20 * s, c.activeMs)
    }

    @Test fun confirmingResumesWithoutCountingIdleTime() {
        val c = clock().apply { start(0); learn(40 * s) }
        c.interaction(10 * s)
        c.tick(200 * s)
        c.resume(300 * s)
        c.interaction(330 * s)
        assertEquals(40 * s, c.activeMs)
    }

    @Test fun endingWhilePausedAddsNothing() {
        val c = clock().apply { start(0); learn(40 * s) }
        c.interaction(20 * s)
        c.tick(150 * s)
        c.finish(900 * s)
        assertEquals(20 * s, c.activeMs)
    }

    @Test fun endingWhileStillReadingCountsTheLastStretch() {
        val c = clock().apply { start(0); learn(40 * s) }
        c.interaction(20 * s)
        c.finish(50 * s)
        assertEquals(50 * s, c.activeMs)
    }

    @Test fun aLongGapWithoutATickOnlyCreditsPartOfAPage() {
        val c = clock().apply { start(0); learn(40 * s) }
        c.interaction(1_000 * s) // the check never ran (process was frozen)
        assertEquals(ReadingClock.PARTIAL_PAGE_MS, c.activeMs)
    }

    @Test fun neverPausesWhenSwitchedOff() {
        val c = clock(enabled = false).apply { start(0) }
        assertFalse(c.tick(10_000 * s))
        assertFalse(c.paused)
        assertFalse(c.wasIdle())
        // the old safety net stays: a gap over two minutes only credits part of a page
        c.interaction(10_000 * s)
        assertEquals(ReadingClock.PARTIAL_PAGE_MS, c.activeMs)
    }

    @Test fun aPageThatWasOpenForTooLongIsIdle() {
        val c = clock().apply { start(0); learn(40 * s) }
        assertFalse(c.wasIdle())
        c.tick(81 * s)
        assertTrue(c.wasIdle())
        c.interaction(500 * s)
        c.pageTurned(500 * s)          // not a sample: the page sat open
        assertEquals(40 * s, c.typicalPageMs())
        assertFalse(c.wasIdle())
    }

    @Test fun thresholdFollowsTheLatestSetting() {
        var m = 2f
        val c = ReadingClock({ true }, { m }).apply { learn(40 * s) }
        assertEquals(80 * s, c.threshold())
        m = 3f
        assertEquals(120 * s, c.threshold())
    }
}
