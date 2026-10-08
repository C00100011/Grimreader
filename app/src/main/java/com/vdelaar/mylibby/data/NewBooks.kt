package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.model.Book
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.text.Normalizer
import java.util.Locale

/** A book idea from a public catalogue (Open Library), before it is in anyone's library. */
data class BookIdea(
    /** Open Library work key, e.g. "/works/OL45101987W": the identity of the idea. */
    val key: String,
    val title: String,
    val authors: List<String>,
    val year: Int? = null,
    val coverId: Long? = null,
    val isbn: String? = null,
    /** ISO 639-1 codes ("en", "nl"). */
    val languages: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
) {
    val authorLine: String get() = authors.joinToString(", ")

    /** A cover picture on Open Library; null when the book has none. */
    fun coverUrl(size: Char = 'M'): String? = coverId?.let { "https://covers.openlibrary.org/b/id/$it-$size.jpg" }

    /** Where to read about the book: its Open Library page. */
    val pageUrl: String get() = "https://openlibrary.org$key"
}

/** A free book from Project Gutenberg (public domain) with an EPUB to download. */
data class FreeBook(
    val id: Int,
    val title: String,
    val authors: List<String>,
    val language: String?,
    val epubUrl: String,
    val coverUrl: String?,
    val subjects: List<String> = emptyList(),
)

// ---- languages ----------------------------------------------------------------------------------

/** Open Library tags editions with MARC codes; the app talks ISO 639-1. */
private val MARC = mapOf("en" to "eng", "nl" to "dut", "de" to "ger", "fr" to "fre", "es" to "spa", "it" to "ita", "pt" to "por", "sv" to "swe", "da" to "dan", "no" to "nor", "pl" to "pol")

fun marcOf(iso: String): String? = MARC[iso.lowercase().take(2)]
fun isoOf(marc: String): String? = MARC.entries.firstOrNull { it.value == marc.lowercase() }?.key

/** The ISO code of a library book's language ("EN-GB", "nl", "dut" ...), or null when unknown. */
fun isoLanguageOf(raw: String?): String? {
    val s = raw?.trim()?.lowercase().orEmpty()
    if (s.isEmpty()) return null
    isoOf(s)?.let { return it }
    return s.take(2).takeIf { it in MARC }
}

fun languageName(iso: String): String = Locale(iso).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() }

/**
 * The languages to look for new books in: the app language first, then the languages the library holds most of.
 * At most [max], only those Open Library knows.
 */
internal fun readingLanguages(books: List<Book>, appLanguage: String, max: Int = 3): List<String> {
    val byCount = books.mapNotNull { isoLanguageOf(it.language) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    return (listOf(appLanguage.lowercase().take(2)) + byCount).filter { it in MARC }.distinct().take(max).ifEmpty { listOf("en") }
}

// ---- genres and taste ---------------------------------------------------------------------------

private val GENERIC_GENRES = setOf(
    "fiction", "general", "general fiction", "books", "book", "ebook", "ebooks", "literature", "novel", "novels", "adult",
    "juvenile fiction", "nonfiction", "non-fiction", "other", "unknown",
)

/** "Fiction / Fantasy / General" -> "fantasy"; null when nothing specific is left. */
fun normalizeGenre(raw: String): String? =
    raw.split('/', ',', ';').flatMap { it.split(" -- ") }.map { it.trim().lowercase() }.firstOrNull { it.isNotEmpty() && it !in GENERIC_GENRES }

/**
 * How much the user likes each genre (by the names books carry): finished books and profile picks count,
 * favourites most, well-rated books up, badly rated ones down.
 */
internal fun genreWeights(books: List<Book>, favouriteIds: Set<Long>, profileGenres: List<String>): Map<String, Double> {
    val weights = HashMap<String, Double>()
    fun add(genres: List<String>, w: Double) = genres.forEach { weights.merge(it, w, Double::plus) }
    profileGenres.forEach { weights.merge(it, 3.0, Double::plus) }
    for (b in books) {
        if (b.readStatus == "READ") add(b.categories, 2.0)
        if (b.readStatus == "READING" || b.readStatus == "RE_READING") add(b.categories, 1.0)
        if (b.id in favouriteIds) add(b.categories, 3.0)
        val r = b.rating
        if (r != null) add(b.categories, if (r >= 8) 3.0 else if (r <= 4) -3.0 else 0.0)
    }
    return weights
}

/** The genres to look for new books in, most liked first, as search terms (merged by normalised name). */
internal fun searchGenres(weights: Map<String, Double>, n: Int = 3): List<String> {
    val merged = HashMap<String, Double>()
    for ((raw, w) in weights) normalizeGenre(raw)?.let { merged.merge(it, w, Double::plus) }
    return merged.filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(n).map { it.key }
}

// ---- is it already mine? ------------------------------------------------------------------------

private fun lastNames(authors: List<String>): Set<String> =
    authors.mapNotNull { a -> a.substringBefore(',').trim().split(' ').lastOrNull()?.lowercase()?.takeIf { it.isNotEmpty() } }.toSet()

/** The library book that is this idea, if any: same normalised title, and no contradicting author. */
fun libraryMatch(title: String, authors: List<String>, library: List<Book>): Book? {
    val t = normalizeTitle(title)
    if (t.isEmpty()) return null
    val names = lastNames(authors)
    return library.firstOrNull { b ->
        normalizeTitle(b.title) == t && (names.isEmpty() || b.authors.isEmpty() || lastNames(b.authors).any { it in names })
    }
}

// ---- Open Library -------------------------------------------------------------------------------

object OpenLibraryQuery {
    const val BASE = "https://openlibrary.org/search.json"
    const val FIELDS = "key,title,author_name,cover_i,first_publish_year,language,subject,isbn,edition_count"

    /** Recent books, most wanted by readers first: "newest first" fills up with obscure listings and publisher bundles. */
    const val SORT = "want_to_read"

    /**
     * New books in a genre and language: `subject:"fantasy" language:dut first_publish_year:[2023 TO *]`.
     * Only the genre and the language are sent, never what you read.
     */
    fun newBooks(genre: String, marc: String, sinceYear: Int): String =
        """subject:"${genre.replace("\"", "")}" language:$marc first_publish_year:[$sinceYear TO *]"""
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.strings(): List<String> = (this as? JsonArray).orEmpty().mapNotNull { it.str() }

/** Words that mark a title or subject as explicit. A public catalogue is not curated, so the obvious cases stay out of an all-ages app. */
private val EXPLICIT = listOf("erotic", "erotica", "porn", "bdsm", "hentai", "smut", "sexually explicit", "explicit sex", "xxx")

/** False for books whose title or subjects are marked explicit. */
fun BookIdea.isSuitable(): Boolean {
    val text = (listOf(title) + subjects).joinToString(" | ").lowercase()
    return EXPLICIT.none { it in text }
}

/** Reads an Open Library search answer. Books without a title, an author or a cover picture, and explicit ones, are left out. */
fun parseOpenLibrary(root: JsonObject): List<BookIdea> =
    (root["docs"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val key = o["key"].str() ?: return@mapNotNull null
        val title = o["title"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val authors = o["author_name"].strings().map { it.trim() }.filter { it.isNotEmpty() }
        val cover = (o["cover_i"] as? JsonPrimitive)?.longOrNull
        if (authors.isEmpty() || cover == null) return@mapNotNull null
        BookIdea(
            key = key,
            title = title,
            authors = authors.take(3),
            year = (o["first_publish_year"] as? JsonPrimitive)?.intOrNull,
            coverId = cover,
            isbn = o["isbn"].strings().firstOrNull { it.length == 13 } ?: o["isbn"].strings().firstOrNull(),
            languages = o["language"].strings().mapNotNull(::isoOf).distinct(),
            subjects = o["subject"].strings().take(8),
        ).takeIf { it.isSuitable() }
    }

/** The same work often shows up twice (editions): keep the first of each title. */
fun List<BookIdea>.distinctWorks(): List<BookIdea> = distinctBy { normalizeTitle(it.title) + "|" + lastNames(it.authors).sorted().joinToString(",") }

// ---- Gutendex (Project Gutenberg) ---------------------------------------------------------------

/** "Austen, Jane" -> "Jane Austen". */
fun displayAuthor(name: String): String = name.split(",", limit = 2).map { it.trim() }.let { if (it.size == 2) "${it[1]} ${it[0]}" else it[0] }

/** Gutenberg catalogue titles carry MARC sub-field codes: "Mathias Sandorf [1] : $b Een samenzwering" -> "Mathias Sandorf [1]: Een samenzwering". */
fun cleanCatalogTitle(raw: String): String = raw.replace(Regex("""\s*\$[a-z]\s*"""), " ").replace(Regex("""\s+:"""), ":").replace(Regex("""\s{2,}"""), " ").trim()

fun parseGutendex(root: JsonObject): List<FreeBook> =
    (root["results"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val title = o["title"].str()?.let(::cleanCatalogTitle)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val formats = o["formats"] as? JsonObject ?: return@mapNotNull null
        // Prefer the EPUB with images; every Gutenberg book has an EPUB, but the key differs.
        val epub = formats.entries.firstOrNull { it.key.startsWith("application/epub+zip") }?.value.str() ?: return@mapNotNull null
        FreeBook(
            id = id,
            title = title,
            authors = (o["authors"] as? JsonArray).orEmpty().mapNotNull { a -> (a as? JsonObject)?.get("name").str()?.let(::displayAuthor) },
            language = o["languages"].strings().firstOrNull(),
            epubUrl = epub,
            coverUrl = formats["image/jpeg"].str(),
            subjects = o["subjects"].strings().take(6),
        )
    }

// ---- an Open Library work page ------------------------------------------------------------------

/** The description of a work: a plain string or {"value": "..."}; the part before a "----------" rule, links reduced to their text. */
fun parseWorkDescription(root: JsonObject): String? {
    val raw = when (val d = root["description"]) {
        is JsonPrimitive -> d.contentOrNull
        is JsonObject -> d["value"].str()
        else -> null
    } ?: return null
    return raw.substringBefore("----------").replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1").replace(Regex("""(\r?\n){2,}"""), "\n\n").trim().takeIf { it.isNotEmpty() }
}
