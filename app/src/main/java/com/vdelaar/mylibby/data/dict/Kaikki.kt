package com.vdelaar.mylibby.data.dict

import com.vdelaar.mylibby.data.WordMeaning
import com.vdelaar.mylibby.data.WordSense
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wiktionary as Kaikki.org publishes it: one JSON object per line, one line per word and part of speech. Only what the popover
 * shows is read; the rest of each line (etymology, translations, categories, ...) is skipped without being parsed.
 */
@Serializable private class KRel(val word: String = "")
@Serializable private class KExample(val text: String = "")
@Serializable private class KSense(
    val glosses: List<String> = emptyList(),
    val examples: List<KExample> = emptyList(),
    val synonyms: List<KRel> = emptyList(),
    val antonyms: List<KRel> = emptyList(),
    @SerialName("form_of") val formOf: List<KRel> = emptyList(),
    @SerialName("alt_of") val altOf: List<KRel> = emptyList(),
)
@Serializable private class KSound(val ipa: String = "")
@Serializable private class KLine(
    val word: String = "",
    val pos: String = "",
    val senses: List<KSense> = emptyList(),
    val sounds: List<KSound> = emptyList(),
    val synonyms: List<KRel> = emptyList(),
    val antonyms: List<KRel> = emptyList(),
)

private val kaikkiJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/** One sense as it is stored: [g]loss, an [e]xample, [y] synonyms, [a]ntonyms, and [f], the word this one is a form of. */
@Serializable
class WiktSense(val g: String, val e: String? = null, val y: List<String> = emptyList(), val a: List<String> = emptyList(), val f: String? = null)

/** One part of speech of a word as it is stored: [p]os, [i]pa, its [s]enses and the [y] synonyms and [a]ntonyms of the whole entry. */
@Serializable
class WiktEntry(val p: String, val i: String? = null, val s: List<WiktSense>, val y: List<String> = emptyList(), val a: List<String> = emptyList())

/** A parsed line: the headword and what is kept of it. */
class WiktLine(val word: String, val entry: WiktEntry)

const val WIKT_MAX_SENSES = 8
private const val MAX_RELATED = 8
private const val MAX_GLOSS = 400

/** The line as the entry to store, or null when it is not an entry with something to say. */
fun parseKaikkiLine(line: String): WiktLine? {
    if (line.isBlank()) return null
    val k = runCatching { kaikkiJson.decodeFromString<KLine>(line) }.getOrNull() ?: return null
    val word = k.word.trim()
    if (word.isEmpty()) return null
    val senses = k.senses.mapNotNull { s ->
        // Nested glosses go from the general ("verb") to the specific; the last one says what this sense is.
        val gloss = s.glosses.lastOrNull()?.trim().orEmpty().take(MAX_GLOSS)
        if (gloss.isEmpty()) return@mapNotNull null
        WiktSense(
            gloss,
            s.examples.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_GLOSS),
            s.synonyms.related(), s.antonyms.related(),
            (s.formOf + s.altOf).firstOrNull()?.word?.trim()?.takeIf { it.isNotEmpty() && !it.equals(word, true) },
        )
    }.take(WIKT_MAX_SENSES)
    if (senses.isEmpty()) return null
    return WiktLine(word, WiktEntry(k.pos.ifBlank { "?" }, k.sounds.firstOrNull { it.ipa.isNotBlank() }?.ipa, senses, k.synonyms.related(), k.antonyms.related()))
}

private fun List<KRel>.related(): List<String> = mapNotNull { it.word.trim().takeIf { w -> w.isNotEmpty() } }.distinct().take(MAX_RELATED)

/** Everything the popover wants from the entries found for a word, as one list of meanings (a meaning per part of speech). */
fun List<WiktEntry>.toMeanings(maxMeanings: Int = 6, maxSenses: Int = 5): List<WordMeaning> =
    groupBy { it.p.lowercase() }.entries.take(maxMeanings).map { (pos, entries) ->
        WordMeaning(
            pos,
            entries.flatMap { e -> e.s.map { WordSense(it.g, it.e) } }.take(maxSenses),
            (entries.flatMap { e -> e.y + e.s.flatMap { it.y } }).distinctBy { it.lowercase() }.take(12),
            (entries.flatMap { e -> e.a + e.s.flatMap { it.a } }).distinctBy { it.lowercase() }.take(12),
        )
    }
