package com.vdelaar.mylibby.ui.reader

/**
 * Counts the time a reader is really reading. The clock runs between interactions (page turn, scroll, tap); when
 * nothing has happened for longer than [threshold] it pauses itself, and the time since the last interaction is not
 * counted. Pure and time-injected so the rules are testable without a phone.
 *
 * The threshold is [multiplier] times the median time of the last [WINDOW] page turns, kept between [MIN_MS] and
 * [MAX_MS]. Until there are [MIN_SAMPLES] page turns it is [DEFAULT_MS].
 */
class ReadingClock(private val enabled: () -> Boolean, private val multiplier: () -> Float) {
    var activeMs = 0L
        private set

    /** True from the moment the idle threshold passes until the next interaction. */
    var paused = false
        private set

    private var lastActivity = 0L

    /** The current page sat open until the clock paused (or longer), and nobody said they were still reading. */
    private var idlePage = false
    private val pageTimes = ArrayDeque<Long>()

    fun start(now: Long) {
        activeMs = 0
        lastActivity = now
        paused = false
        idlePage = false
    }

    /** Time per page that the threshold is based on, or null while there is too little data. */
    fun typicalPageMs(): Long? {
        if (pageTimes.size < MIN_SAMPLES) return null
        val sorted = pageTimes.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** How long nothing may happen before the clock pauses. With detection off this is the old fixed limit. */
    fun threshold(): Long {
        if (!enabled()) return LEGACY_LIMIT_MS
        val typical = typicalPageMs() ?: return DEFAULT_MS
        return (typical * multiplier()).toLong().coerceIn(MIN_MS, MAX_MS)
    }

    /**
     * A page was turned after [ms] on the previous one. Pages that were left open (the clock paused and the reader did
     * not confirm) or flicked through say nothing about reading speed. Slow pages do count when the reader confirmed
     * they were reading, so a slow reader's threshold grows to fit instead of pausing on every page.
     */
    fun pageTurned(ms: Long) {
        val idle = idlePage
        idlePage = false
        if (idle || ms < MIN_PAGE_MS || ms > MAX_MS) return
        pageTimes.addLast(ms)
        while (pageTimes.size > WINDOW) pageTimes.removeFirst()
    }

    /** True when the page now being left sat open so long that the reader was probably not reading. Ask before [pageTurned]. */
    fun wasIdle() = enabled() && idlePage

    /** The reader did something. Time since the last interaction counts, unless it was idle time. */
    fun interaction(now: Long) {
        val gap = now - lastActivity
        if (!paused && gap > 0) {
            val limit = threshold()
            // Over the limit without us noticing (no tick yet): the reader probably read part of that page.
            activeMs += if (gap <= limit) gap else minOf(PARTIAL_PAGE_MS, limit)
            if (gap > limit && enabled()) idlePage = true
        }
        paused = false
        lastActivity = now
    }

    /** Called every few seconds while the reader is open. Returns true at the moment the clock pauses. */
    fun tick(now: Long): Boolean {
        if (!enabled() || paused || now - lastActivity <= threshold()) return false
        paused = true
        idlePage = true
        return true
    }

    /** The reader confirmed they are still reading: continue without counting the idle time. */
    fun resume(now: Long) {
        paused = false
        idlePage = false
        lastActivity = now
    }

    /** The session ends. Time after the last interaction counts only when it was not idle time. */
    fun finish(now: Long) {
        val gap = now - lastActivity
        if (!paused && gap in 1..threshold()) activeMs += gap
        lastActivity = now
    }

    companion object {
        const val MIN_MS = 60_000L
        const val MAX_MS = 600_000L
        const val DEFAULT_MS = 120_000L
        const val LEGACY_LIMIT_MS = 120_000L
        const val WINDOW = 15
        const val MIN_SAMPLES = 5
        const val MIN_PAGE_MS = 1_500L
        const val PARTIAL_PAGE_MS = 15_000L
        const val DEFAULT_MULTIPLIER = 2f
    }
}
