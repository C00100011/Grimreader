package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.DayTotal
import com.vdelaar.mylibby.core.datastore.GoalSettings
import com.vdelaar.mylibby.core.datastore.GoalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakCalculatorTest {

    // A Wednesday, so a Monday-based week starts two days earlier.
    private val today = LocalDate.of(2026, 10, 7)
    private val goal = GoalSettings(type = GoalType.MINUTES, dailyTarget = 20, freezesPerWeek = 0)

    private fun day(daysAgo: Long, minutes: Long, pages: Long = 0) =
        DayTotal(today.minusDays(daysAgo).toString(), minutes * 60, pages)

    @Test
    fun `no sessions means no streak`() {
        val s = StreakCalculator.compute(emptyList(), goal, today)
        assertEquals(0, s.current)
        assertEquals(0, s.longest)
        assertFalse(s.todayMet)
    }

    @Test
    fun `consecutive met days count including today`() {
        val s = StreakCalculator.compute(listOf(day(2, 25), day(1, 30), day(0, 20)), goal, today)
        assertEquals(3, s.current)
        assertTrue(s.todayMet)
        assertEquals(1f, s.todayFraction)
    }

    @Test
    fun `today not yet met keeps yesterday's streak alive`() {
        val s = StreakCalculator.compute(listOf(day(2, 25), day(1, 30), day(0, 5)), goal, today)
        assertEquals(2, s.current)
        assertFalse(s.todayMet)
        assertEquals(5L, s.todayValue)
        assertEquals(DayState.PARTIAL, s.days[today])
    }

    @Test
    fun `a missed day breaks the streak`() {
        val s = StreakCalculator.compute(listOf(day(4, 25), day(3, 25), day(1, 30)), goal, today)
        assertEquals(1, s.current)
        assertEquals(2, s.longest)
    }

    @Test
    fun `partial day before today breaks the streak`() {
        val s = StreakCalculator.compute(listOf(day(2, 25), day(1, 10)), goal, today)
        assertEquals(0, s.current)
    }

    @Test
    fun `freeze bridges one missed day per week`() {
        val g = goal.copy(freezesPerWeek = 1)
        // Mon met, Tue missed (frozen), Wed met
        val s = StreakCalculator.compute(listOf(day(2, 25), day(0, 25)), g, today)
        assertEquals(2, s.current)
        assertEquals(DayState.FROZEN, s.days[today.minusDays(1)])
        assertEquals(0, s.freezesLeftThisWeek)
    }

    @Test
    fun `pages goal uses pages turned`() {
        val g = GoalSettings(type = GoalType.PAGES, dailyTarget = 10, freezesPerWeek = 0)
        val s = StreakCalculator.compute(listOf(day(0, 1, pages = 12)), g, today)
        assertTrue(s.todayMet)
        assertEquals(12L, s.todayValue)
    }
}
