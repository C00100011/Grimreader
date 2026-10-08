package com.vdelaar.mylibby.data

/** What to do when Grimmory's overall progress for a book differs from what this app has. */
enum class ProgressAction {
    /** Nothing differs (or nothing can be done). */
    NONE,

    /** Another reader (a Kobo, KOReader) is further than this app: offer to jump to it. */
    OFFER_JUMP,

    /** This app is further than the other reader: send our progress again, newer, so Grimmory hands it on. */
    REASSERT,
}

/** Differences smaller than this (in percentage points) are rounding, not reading. */
const val PROGRESS_TOLERANCE = 1.5f

/** Telling the server again about the same position at most this often per book (it stamps "last read" each time). */
const val REASSERT_EVERY_MS = 12L * 3_600_000L

/**
 * Grimmory keeps progress per source: the web reader (what Grimreader writes), a Kobo, KOReader. What it reports as the
 * book's `readProgress` is the first source that has one, in the order KOReader, Kobo, web reader, PDF. So when that
 * differs from the web reader's own value, another reader holds a position, and the furthest of the two should win:
 *
 *  - another reader is further: [ProgressAction.OFFER_JUMP] (a Kobo knows a percentage, not an exact spot, so ask first);
 *  - this app is further and has nothing waiting to be sent: [ProgressAction.REASSERT], at most every [REASSERT_EVERY_MS]
 *    per book. Grimmory's two-way sync (a Kobo setting on the server) then passes the newer web-reader position on.
 *
 * All percentages are 0..100.
 */
fun reconcileProgress(
    localPercent: Float?,
    localDirty: Boolean,
    canPush: Boolean,
    serverOverall: Float?,
    serverWeb: Float?,
    lastReassertAt: Long?,
    now: Long,
): ProgressAction {
    val overall = serverOverall ?: return ProgressAction.NONE
    val ownBest = maxOf(localPercent ?: 0f, serverWeb ?: 0f)
    if (overall > ownBest + PROGRESS_TOLERANCE) return ProgressAction.OFFER_JUMP
    val local = localPercent ?: return ProgressAction.NONE
    val due = lastReassertAt == null || now - lastReassertAt >= REASSERT_EVERY_MS
    if (canPush && !localDirty && local > overall + PROGRESS_TOLERANCE && due) return ProgressAction.REASSERT
    return ProgressAction.NONE
}
