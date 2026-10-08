package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.BookSessionTotals
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** How fast the user is getting through one book, next to what an average adult would need. */
data class BookPace(
    /** Active reading time, over all sessions of this book. */
    val readSeconds: Long,
    /** Days on which this book was read. */
    val daysRead: Int,
    /** First to last session, in calendar days (1 = all in one day). */
    val spanDays: Int,
    /** The user's pace in this book; null until there is enough text information to know. */
    val wordsPerMinute: Int?,
    /** What an average adult reader would need for the whole book; null when the word count is unknown. */
    val averageSeconds: Long?,
    /** The speed this is compared with (the average adult unless the user chose another reference). */
    val referenceWpm: Int = BookPaceCalculator.AVERAGE_ADULT_WPM,
) {
    /** 25 = 25% faster than the reference, -10 = 10% slower. */
    val percentVsAverage: Int? get() = wordsPerMinute?.let { ((it.toDouble() / referenceWpm - 1) * 100).roundToInt() }
}

object BookPaceCalculator {
    /**
     * Silent reading rate of adults for non-fiction: 238 words per minute (fiction 260), from the meta-analysis
     * of 190 studies by Brysbaert (2019, Journal of Memory and Language). One figure keeps the comparison simple.
     */
    const val AVERAGE_ADULT_WPM = 238

    /** Under five minutes of reading there is too little to say anything about a pace. */
    const val MIN_SECONDS = 300L

    fun compute(
        totals: BookSessionTotals,
        totalWords: Long?,
        totalBytes: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        referenceWpm: Int = AVERAGE_ADULT_WPM,
    ): BookPace? {
        if (totals.seconds < MIN_SECONDS) return null
        val words = totalWords?.takeIf { it > 0 }
        val wpm = if (words != null && totalBytes != null && totalBytes > 0 && totals.bytes > 0) {
            val wordsRead = totals.bytes.toDouble() * words / totalBytes
            (wordsRead / (totals.seconds / 60.0)).roundToInt().takeIf { it in 50..1500 }
        } else null
        val span = if (totals.firstAt > 0 && totals.lastAt >= totals.firstAt) {
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(totals.firstAt).atZone(zone).toLocalDate(), Instant.ofEpochMilli(totals.lastAt).atZone(zone).toLocalDate()).toInt() + 1
        } else 1
        return BookPace(
            readSeconds = totals.seconds,
            daysRead = totals.days.coerceAtLeast(1),
            spanDays = span.coerceAtLeast(1),
            wordsPerMinute = wpm,
            averageSeconds = words?.let { it * 60L / referenceWpm.coerceAtLeast(1) },
            referenceWpm = referenceWpm,
        )
    }
}
