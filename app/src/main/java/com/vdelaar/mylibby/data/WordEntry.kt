package com.vdelaar.mylibby.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One meaning of a word: its [text] (a definition, or the translations), a [gloss] explaining it, and an example when there is one. */
data class WordSense(val text: String, val example: String? = null, val gloss: String? = null)

/** The senses of a word as one part of speech, with the synonyms and antonyms the dictionary ties to them. */
data class WordMeaning(
    val partOfSpeech: String,
    val senses: List<WordSense>,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
)

/**
 * What one downloaded dictionary says about a word. [id] tells dictionaries apart (the reader's choice is kept by it), [label]
 * names it ("English → Dutch"), [matched] is the form that was found. [translations] is true for a translation dictionary
 * and false for one with definitions; [source] is whose text it is, to be credited.
 */
data class PackEntry(
    val id: String,
    val label: String,
    val matched: String,
    val phonetic: String?,
    val meanings: List<WordMeaning>,
    val translations: Boolean,
    val source: String? = null,
)

/** What a lookup found: the word and what each dictionary that knows it says, the one to show first first. */
data class WordEntry(val word: String, val language: String, val packs: List<PackEntry>) {
    val phonetic: String? get() = packs.firstNotNullOfOrNull { it.phonetic }
}

/** Fetches a URL (only the list of FreeDict dictionaries goes through this; words are never sent anywhere): the body, or null when the server says it has nothing (404). Throws [IOException] when it cannot be reached. */
fun interface DictionaryHttp {
    suspend fun get(url: String): String?
}

/** The word as the dictionary wants it: no quotes or punctuation round it, lower case, curly apostrophes made straight. */
fun cleanWord(raw: String): String =
    raw.replace('’', '\'').replace('‘', '\'').trim().trim { !it.isLetterOrDigit() }.lowercase()

/** One selected word, as opposed to a phrase: the popover opens straight away only for these. */
fun isSingleWord(text: String): Boolean {
    val t = text.trim()
    return t.isNotEmpty() && t.length <= 40 && t.none { it.isWhitespace() } && t.any { it.isLetter() }
}

/** The two-letter code of a book's language as the dictionaries use it; English when it says nothing. */
fun dictionaryLanguage(raw: String?): String {
    val l = raw?.trim()?.lowercase().orEmpty()
    return when {
        l.isBlank() -> "en"
        l.startsWith("nl") || l == "dut" || l == "nld" || "dutch" in l || "nederlands" in l -> "nl"
        l.startsWith("en") || l == "eng" || "english" in l -> "en"
        l.startsWith("es") || l == "spa" || "spanish" in l || "español" in l -> "es"
        l.startsWith("fr") || l == "fra" || l == "fre" || "french" in l -> "fr"
        l.startsWith("de") || l == "deu" || l == "ger" || "german" in l -> "de"
        l.startsWith("it") || l == "ita" || "italian" in l -> "it"
        l.startsWith("pt") || l == "por" || "portuguese" in l -> "pt"
        else -> l.substringBefore('-').takeIf { it.length == 2 } ?: "en"
    }
}

/** The real network; separate from the Grimmory clients because this is a public site that must never get a login. */
class OkDictionaryHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(com.vdelaar.mylibby.core.debug.NetworkLogInterceptor("Dictionary"))
        .build(),
) : DictionaryHttp {
    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Grimreader (Android e-book reader)").build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                resp.isSuccessful -> resp.body?.string()
                else -> throw IOException("Dictionary answered ${resp.code}")
            }
        }
    }
}
