package com.vdelaar.mylibby.data

import java.nio.ByteBuffer
import java.text.Normalizer

/**
 * Reading sessions from a Kobo e-reader.
 *
 * The Kobo keeps no list of sessions, but per book (table `Event`, type 46) a blob with the time of every page turn
 * (`eventTimestamps`, a Qt `QVariantMap`). Page turns that follow each other closely form one session.
 */

/** A book on the Kobo and the time (unix seconds) of each page turn in it. */
data class KoboBook(val title: String, val author: String?, val isbn: String?, val turns: List<Long>)

/** One stretch of reading: first and last page turn (unix seconds) and how many pages were turned. */
data class KoboSession(val start: Long, val end: Long, val pages: Int) {
    val seconds: Int get() = (end - start).toInt()
}

internal const val KOBO_GAP_SECONDS = 10 * 60L
internal const val KOBO_MIN_SESSION_SECONDS = 30

/** Page turns closer than [gap] seconds are one session; sessions shorter than [minSeconds] (a glance at a page) are dropped. */
fun koboSessions(turns: List<Long>, gap: Long = KOBO_GAP_SECONDS, minSeconds: Int = KOBO_MIN_SESSION_SECONDS): List<KoboSession> {
    if (turns.isEmpty()) return emptyList()
    val sorted = turns.sorted()
    val out = ArrayList<KoboSession>()
    var start = sorted[0]
    var prev = sorted[0]
    var pages = 1
    for (t in sorted.drop(1)) {
        if (t - prev > gap) {
            out += KoboSession(start, prev, pages)
            start = t
            pages = 0
        }
        pages++
        prev = t
    }
    out += KoboSession(start, prev, pages)
    return out.filter { it.seconds >= minSeconds }
}

/**
 * The page-turn times in an `Event.ExtraData` blob, or an empty list for any other layout (other event types, damaged data).
 * The blob is a Qt `QDataStream`: a count, then per entry a UTF-16 key and a `QVariant` (type, null flag, value).
 */
fun koboPageTurns(blob: ByteArray?): List<Long> {
    if (blob == null || blob.size < 4) return emptyList()
    return try {
        val b = ByteBuffer.wrap(blob) // big endian, like Qt
        val entries = b.int
        if (entries !in 0..1000) return emptyList()
        repeat(entries) {
            val key = readQString(b)
            val value = readVariant(b)
            if (key == "eventTimestamps") {
                @Suppress("UNCHECKED_CAST")
                val list = value as? List<Any?> ?: return emptyList()
                return list.mapNotNull { (it as? Number)?.toLong() }
                    .map { if (it > 100_000_000_000L) it / 1000 else it } // milliseconds on some firmware
                    .filter { it > 0 }
                    .sorted()
            }
        }
        emptyList()
    } catch (_: Exception) { // truncated or foreign layout: BufferUnderflow, IllegalArgument, ...
        emptyList()
    }
}

private fun readQString(b: ByteBuffer): String? {
    val len = b.int
    if (len == -1) return null
    require(len in 0..b.remaining() && len % 2 == 0)
    val bytes = ByteArray(len).also { b.get(it) }
    return String(bytes, Charsets.UTF_16BE)
}

private fun readVariant(b: ByteBuffer): Any? {
    val type = b.int
    b.get() // isNull flag; the value follows either way
    return when (type) {
        0 -> null
        1 -> b.get().toInt() != 0
        2 -> b.int
        3 -> b.int.toLong() and 0xffffffffL
        4 -> b.long
        5 -> b.long
        6 -> b.double
        10 -> readQString(b)
        9 -> {
            val n = b.int
            require(n in 0..1_000_000)
            List(n) { readVariant(b) }
        }
        else -> throw IllegalArgumentException("QVariant type $type")
    }
}

// Matching -------------------------------------------------------------------------------------------------------

/** A book Grimreader knows (from Grimmory or added on the device) that a Kobo book can be matched to. */
data class KnownBook(val id: Long, val title: String, val authors: String, val fileType: String?)

internal fun normTitle(s: String?): String =
    Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace(Regex("[^a-z0-9]+"), "")

/** The part before a subtitle: "Clean Architecture: A Craftsman's Guide" -> "clean architecture". */
private fun mainTitle(s: String?): String = normTitle(s.orEmpty().substringBefore(':').substringBefore(" - "))

private fun authorOverlap(kobo: String?, known: String): Boolean {
    val a = normTitle(kobo)
    if (a.isEmpty()) return false
    return known.split(',', ';', '&').map { normTitle(it) }.filter { it.length > 2 }.any { a.contains(it) || it.contains(a) }
}

/**
 * Finds the book a Kobo book belongs to: the same title (a subtitle may differ). When several books share the title the
 * author decides; when that does not settle it either, nothing is matched rather than guessing.
 */
fun matchKoboBook(book: KoboBook, known: List<KnownBook>): KnownBook? {
    val exact = normTitle(book.title)
    if (exact.isEmpty()) return null
    fun pick(candidates: List<KnownBook>): KnownBook? = when {
        candidates.isEmpty() -> null
        candidates.size == 1 -> candidates[0]
        else -> candidates.filter { authorOverlap(book.author, it.authors) }.singleOrNull()
    }
    pick(known.filter { normTitle(it.title) == exact })?.let { return it }
    if (known.any { normTitle(it.title) == exact }) return null // ambiguous on the exact title: do not fall through to a looser match
    val main = mainTitle(book.title)
    if (main.length < 4) return null
    return pick(known.filter { mainTitle(it.title) == main })
}

/** Sessions already present (same book, start within [windowMs]) are not added again. */
fun isDuplicateSession(existing: Collection<Long>, startMs: Long, windowMs: Long = 5_000): Boolean =
    existing.any { kotlin.math.abs(it - startMs) <= windowMs }
