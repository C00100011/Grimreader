package com.vdelaar.mylibby.ui.reader

import com.vdelaar.mylibby.core.datastore.SpeedMode
import kotlin.math.max
import kotlin.math.min

/** What is on screen at one moment: one word, or a short phrase of [first]..[last] (inclusive word indexes). */
data class SpeedUnit(val first: Int, val last: Int, val text: String, val weight: Double)

/**
 * Word and phrase logic for speed reading (RSVP). Words come from the book with a trailing '\n'
 * on the last word of a paragraph/heading; everything here is plain Kotlin so it can be unit tested.
 */
object SpeedReading {

    const val MIN_WPM = 100
    const val MAX_WPM = 1000
    private const val MAX_PHRASE_CHARS = 26
    private const val WORDS_PER_PAGE = 275

    fun clean(word: String): String = word.trimEnd('\n')

    fun endsParagraph(word: String): Boolean = word.endsWith('\n')

    private fun lastMark(word: String): Char? = clean(word).trimEnd('"', '\'', '”', '’', '»', ')', ']').lastOrNull()

    fun endsSentence(word: String): Boolean = endsParagraph(word) || lastMark(word) in SENTENCE_END

    private fun endsClause(word: String): Boolean = endsSentence(word) || lastMark(word) in CLAUSE_END

    /** Index of the letter to focus on (the optimal recognition point), counted in characters of [word]. */
    fun orpIndex(word: String): Int {
        val w = clean(word)
        val lead = w.indexOfFirst { it.isLetterOrDigit() }.let { if (it < 0) 0 else it }
        val core = w.trim { !it.isLetterOrDigit() }.length
        val inside = when {
            core <= 1 -> 0
            core <= 5 -> 1
            core <= 9 -> 2
            core <= 13 -> 3
            else -> 4
        }
        return min(lead + inside, max(0, w.length - 1))
    }

    /** Relative time a word needs: 1.0 for an ordinary word, more for long words and punctuation. */
    fun weight(word: String): Double {
        val w = clean(word)
        val core = w.trim { !it.isLetterOrDigit() }.length
        var weight = 1.0 + max(0, core - 6) * 0.08
        weight += when (lastMark(word)) {
            '.', '!', '?', '…' -> 1.2
            ',', ';', ':', '—', '–' -> 0.6
            else -> 0.0
        }
        if (endsParagraph(word)) weight += 1.8
        return weight
    }

    /** Splits the words into what is shown at once: single words, or grammar-aware phrases of up to [maxWords]. */
    fun plan(words: List<String>, mode: SpeedMode, maxWords: Int, language: String?, from: Int = 0): List<SpeedUnit> {
        val out = ArrayList<SpeedUnit>(words.size)
        if (mode == SpeedMode.WORD) {
            for (i in from until words.size) out += SpeedUnit(i, i, clean(words[i]), weight(words[i]))
            return out
        }
        val limit = maxWords.coerceIn(2, 4)
        var i = from
        while (i < words.size) {
            var j = i
            var chars = 0
            while (j < words.size && j - i < limit) {
                val len = clean(words[j]).length
                if (j > i && chars + 1 + len > MAX_PHRASE_CHARS) break
                chars += len + if (j > i) 1 else 0
                j++
                if (endsClause(words[j - 1])) break
            }
            // Don't leave an article, preposition or conjunction dangling at the end of a phrase.
            if (j - i > 1 && j < words.size && !endsClause(words[j - 1]) && isFunctionWord(words[j - 1], language)) j--
            out += SpeedUnit(i, j - 1, (i until j).joinToString(" ") { clean(words[it]) }, (i until j).sumOf { weight(words[it]) })
            i = j
        }
        return out
    }

    /** How long a unit stays on screen. [sinceStart] counts units since (re)starting, for a gentle ramp-up. */
    fun durationMs(unit: SpeedUnit, wpm: Int, sinceStart: Int): Long {
        val base = 60_000.0 / wpm.coerceIn(MIN_WPM, MAX_WPM)
        val ramp = 1.0 + 0.8 * (1.0 - min(sinceStart, RAMP_UNITS).toDouble() / RAMP_UNITS)
        return max(60L, (base * unit.weight * ramp).toLong())
    }

    /** Where to jump to for "back one sentence": the start of this sentence, or of the previous one when already at a start. */
    fun sentenceStart(words: List<String>, index: Int): Int {
        var i = min(index, words.size) - 1
        if (i >= 0 && endsSentence(words[i])) i--
        while (i >= 0 && !endsSentence(words[i])) i--
        return i + 1
    }

    /** Minutes left to read [remainingWords] at [wpm], following the same timing as playback. */
    fun minutesLeft(remainingWords: Int, wpm: Int): Double = remainingWords * 1.15 / wpm.coerceIn(MIN_WPM, MAX_WPM)

    /** Words converted to the "page" unit used for the daily goal. */
    fun pagesFor(words: Int): Int = words / WORDS_PER_PAGE

    private fun isFunctionWord(word: String, language: String?): Boolean {
        val w = clean(word).trim { !it.isLetter() }.lowercase()
        return when (language?.take(2)) {
            "nl" -> w in NL_FUNCTION
            "en" -> w in EN_FUNCTION
            else -> w in NL_FUNCTION || w in EN_FUNCTION
        }
    }

    private const val RAMP_UNITS = 10
    private val SENTENCE_END = setOf('.', '!', '?', '…')
    private val CLAUSE_END = setOf(',', ';', ':', '—', '–')

    private val EN_FUNCTION = setOf(
        "a", "an", "the", "and", "or", "but", "nor", "of", "in", "on", "at", "to", "for", "with", "by", "from", "as", "if",
        "so", "than", "that", "this", "these", "those", "his", "her", "its", "their", "my", "your", "our", "i", "he", "she",
        "we", "they", "it", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "not", "no", "into", "onto",
        "over", "under", "about", "after", "before", "between", "through", "during", "without", "within", "who", "which",
    )
    private val NL_FUNCTION = setOf(
        "de", "het", "een", "en", "of", "maar", "want", "dat", "die", "dit", "deze", "van", "in", "op", "aan", "te", "voor",
        "met", "door", "uit", "bij", "als", "dan", "zo", "ik", "je", "hij", "zij", "ze", "wij", "we", "jullie", "u", "is",
        "zijn", "was", "waren", "niet", "geen", "naar", "om", "over", "onder", "tot", "tegen", "zonder", "tijdens", "mijn",
        "haar", "zijn", "hun", "ons", "onze", "wie", "welke",
    )
}
