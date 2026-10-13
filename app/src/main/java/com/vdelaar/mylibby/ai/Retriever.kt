package com.vdelaar.mylibby.ai

import com.vdelaar.mylibby.core.database.BookChunkEntity
import java.text.Normalizer
import kotlin.math.ln

/**
 * Where the reader is: the section the reader reports and how far through that section the *current page starts* (0..1).
 * Counting from the start of the page keeps the page being read out of the chat's material, which is the safe side.
 */
data class ReadPosition(val spineIndex: Int, val share: Float, val percent: Int) {
    /** True when the whole chunk lies before the reader. */
    fun passed(c: BookChunkEntity) = c.spineIndex < spineIndex || (c.spineIndex == spineIndex && c.endShare <= share - SECTION_MARGIN)

    companion object {
        /** Pages are not all the same length, so a page's share of its section is only roughly its share of the text. */
        const val SECTION_MARGIN = 0.02f
    }
}

/** BM25 over the pieces of the book the reader has already read. Plain Kotlin: a book has at most a few hundred pieces. */
object Retriever {
    /** Everything that lies fully before the reader. */
    fun readable(chunks: List<BookChunkEntity>, at: ReadPosition): List<BookChunkEntity> = chunks.filter { at.passed(it) }

    /** The part of the current section's text the reader has passed but that is not in a finished piece yet; empty when there is none. */
    fun readPartOfCurrent(chunks: List<BookChunkEntity>, at: ReadPosition): String {
        val limit = at.share - ReadPosition.SECTION_MARGIN
        val current = chunks.firstOrNull { it.spineIndex == at.spineIndex && it.startShare < limit && it.endShare > limit } ?: return ""
        val share = ((limit - current.startShare) / (current.endShare - current.startShare)).coerceIn(0f, 1f)
        val end = (current.text.length * share).toInt()
        val cut = current.text.lastIndexOf(' ', end).takeIf { it > 0 } ?: end
        return current.text.substring(0, cut)
    }

    fun top(chunks: List<BookChunkEntity>, question: String, k: Int): List<BookChunkEntity> {
        val q = tokens(question).toSet()
        if (q.isEmpty() || chunks.isEmpty()) return emptyList()
        val docs = chunks.map { tokens(it.section + " " + (it.summary ?: "") + " " + it.text) }
        val avg = docs.sumOf { it.size }.toDouble() / docs.size
        val n = docs.size
        val df = q.associateWith { t -> docs.count { t in it } }
        val scored = chunks.indices.map { i ->
            val tf = docs[i].groupingBy { it }.eachCount()
            var score = 0.0
            for (t in q) {
                val f = tf[t] ?: continue
                val idf = ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * docs[i].size / avg))
            }
            i to score
        }
        return scored.filter { it.second > 0 }.sortedByDescending { it.second }.take(k).map { chunks[it.first] }
    }

    private const val K1 = 1.2
    private const val B = 0.75

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "is", "are", "was", "were", "be", "did", "do", "does", "who", "what", "where",
        "when", "why", "how", "which", "that", "this", "it", "he", "she", "they", "his", "her", "with", "for", "from", "by", "as", "about",
        "de", "het", "een", "en", "van", "in", "op", "is", "zijn", "was", "waren", "wie", "wat", "waar", "wanneer", "waarom", "hoe", "welke",
        "dat", "dit", "te", "hij", "zij", "ze", "met", "voor", "uit", "door", "als", "over", "bij", "ook", "niet", "heeft", "had", "worden",
    )

    internal fun tokens(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in STOP }
}
