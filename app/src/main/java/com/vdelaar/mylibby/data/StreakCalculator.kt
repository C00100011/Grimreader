package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.DayTotal
import com.vdelaar.mylibby.core.datastore.GoalSettings
import com.vdelaar.mylibby.core.datastore.GoalType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class DayState { MET, PARTIAL, FROZEN, MISSED, FUTURE }

data class StreakInfo(
    val current: Int = 0,
    val longest: Int = 0,
    val todayValue: Long = 0,
    val target: Int = 20,
    val goalType: GoalType = GoalType.MINUTES,
    val todayMet: Boolean = false,
    /** Day -> state, for the calendar heatmap. */
    val days: Map<LocalDate, DayState> = emptyMap(),
    /** Day -> minutes read, for the heatmap intensity. */
    val minutes: Map<LocalDate, Long> = emptyMap(),
    val freezesLeftThisWeek: Int = 0,
) {
    val todayFraction: Float get() = if (target <= 0) 1f else (todayValue.toFloat() / target).coerceIn(0f, 1f)
}

/** Pure streak logic so it can be unit tested. */
object StreakCalculator {

    fun valueFor(day: DayTotal?, goal: GoalSettings): Long = when (goal.type) {
        GoalType.MINUTES -> (day?.seconds ?: 0L) / 60
        GoalType.PAGES -> day?.pages ?: 0L
    }

    fun compute(totals: List<DayTotal>, goal: GoalSettings, today: LocalDate = LocalDate.now()): StreakInfo {
        val byDay = totals.mapNotNull { t -> runCatching { LocalDate.parse(t.localDate) to t }.getOrNull() }.toMap()
        val met = { d: LocalDate -> valueFor(byDay[d], goal) >= goal.dailyTarget }
        val todayValue = valueFor(byDay[today], goal)
        val todayMet = todayValue >= goal.dailyTarget

        // States for every day since the first recorded session (max one year back).
        val first = byDay.keys.minOrNull()?.coerceAtLeast(today.minusDays(365)) ?: today
        val states = LinkedHashMap<LocalDate, DayState>()
        val freezesUsed = HashMap<LocalDate, Int>() // week start -> used
        var run = 0
        var longest = 0
        var d = first
        while (!d.isAfter(today)) {
            val week = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val state = when {
                met(d) -> DayState.MET
                d == today -> if (todayValue > 0) DayState.PARTIAL else DayState.FUTURE
                run > 0 && (freezesUsed[week] ?: 0) < goal.freezesPerWeek -> {
                    freezesUsed[week] = (freezesUsed[week] ?: 0) + 1
                    DayState.FROZEN
                }
                valueFor(byDay[d], goal) > 0 -> DayState.PARTIAL
                else -> DayState.MISSED
            }
            when (state) {
                DayState.MET -> { run++; longest = maxOf(longest, run) }
                DayState.FROZEN, DayState.FUTURE -> Unit // keeps the streak alive
                DayState.PARTIAL -> if (d != today) run = 0
                DayState.MISSED -> run = 0
            }
            states[d] = state
            d = d.plusDays(1)
        }
        // A frozen day only counts as a freeze if the streak continued afterwards; trailing
        // freezes before today are fine (today may still be met).
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val minutes = byDay.mapValues { it.value.seconds / 60 }
        return StreakInfo(
            current = run,
            longest = longest,
            todayValue = todayValue,
            target = goal.dailyTarget,
            goalType = goal.type,
            todayMet = todayMet,
            days = states,
            minutes = minutes,
            freezesLeftThisWeek = (goal.freezesPerWeek - (freezesUsed[weekStart] ?: 0)).coerceAtLeast(0),
        )
    }

    /** Milestones that trigger a celebration. */
    val milestones = listOf(3, 7, 14, 30, 50, 100, 150, 200, 365, 500, 1000)
}
