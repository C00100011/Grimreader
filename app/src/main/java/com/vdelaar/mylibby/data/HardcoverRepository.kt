package com.vdelaar.mylibby.data

import androidx.annotation.StringRes
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.core.network.HardcoverGateway
import com.vdelaar.mylibby.core.network.HardcoverKeyStore
import com.vdelaar.mylibby.core.network.HardcoverException
import com.vdelaar.mylibby.core.network.parseHardcoverKey
import com.vdelaar.mylibby.core.network.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/** How far back "trending" looks (Hardcover's TrendingDuration). */
enum class HardcoverDuration(val api: String, @StringRes val label: Int) {
    WEEK("week", R.string.hc_week),
    MONTH("month", R.string.hc_month),
    YEAR("one_year", R.string.hc_year),
    ALL("all", R.string.hc_all),
}

data class HardcoverBook(
    val id: Int,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val imageUrl: String? = null,
    val rating: Double? = null,
    val ratingsCount: Int = 0,
    val readers: Int = 0,
    val year: Int? = null,
    val pages: Int? = null,
    val description: String? = null,
    val series: String? = null,
    val seriesPosition: Double? = null,
    val slug: String? = null,
) {
    val authorLine: String get() = authors.joinToString(", ")
    val url: String? get() = slug?.takeIf { it.isNotBlank() }?.let { "https://hardcover.app/books/$it" }
}

data class HardcoverSuggestions(val books: List<HardcoverBook>, val because: List<String>)

/** Queries were checked against hardcoverapp/hardcover-docs schema.graphql (see tools/ notes in the plan). */
internal object HardcoverQueries {
    const val TRENDING = """query Trending(${'$'}duration: TrendingDuration, ${'$'}limit: Int, ${'$'}offset: Int) {
  books_trending(duration: ${'$'}duration, limit: ${'$'}limit, offset: ${'$'}offset) { ids error } }"""

    const val BOOKS = """query BooksByIds(${'$'}ids: [Int!]) {
  books(where: {id: {_in: ${'$'}ids}}, limit: 60) {
    id title subtitle slug rating ratings_count users_count release_year pages description
    image { url }
    contributions(limit: 3, order_by: {id: asc}) { author { name } }
    book_series(limit: 1) { position series { name } }
  } }"""

    const val SIMILAR = """query Similar(${'$'}ids: [Int!]) {
  books(where: {id: {_in: ${'$'}ids}}) { id cached_similar_book_ids } }"""

    const val SEARCH = """query Find(${'$'}q: String!) {
  search(query: ${'$'}q, query_type: "books", per_page: 3, page: 1) { ids error } }"""
}

/** Disk cache for Hardcover answers: trending and details change slowly and the daily request quota is limited. */
class HardcoverCache(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    fun read(key: String, ttlMs: Long): JsonObject? {
        val f = File(dir, key)
        if (!f.exists() || now() - f.lastModified() > ttlMs) return null
        return runCatching { AppJson.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
    }

    fun write(key: String, data: JsonObject) {
        runCatching {
            dir.mkdirs()
            val f = File(dir, key)
            f.writeText(data.toString())
            f.setLastModified(now())
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** What Hardcover suggestions need to know about the user's library. */
interface HardcoverLibrary {
    suspend fun tasteSeeds(limit: Int): List<Book>
    suspend fun libraryBooks(): List<Book>
}

class HardcoverRepository(
    private val tokens: HardcoverKeyStore,
    private val client: HardcoverGateway,
    private val library: HardcoverLibrary,
    private val cache: HardcoverCache,
) {
    val hasKey: Boolean get() = client.hasKey

    /** What other readers are into. Falls back to the last answer when offline or rate limited. */
    suspend fun trending(duration: HardcoverDuration, limit: Int = 20): List<HardcoverBook> {
        val vars = buildJsonObject { put("duration", duration.api); put("limit", limit); put("offset", 0) }
        val data = cachedQuery(HardcoverQueries.TRENDING, vars, TTL_TRENDING)
        return booksByIds(idsOf(data["books_trending"]))
    }

    /** Hardcover's "similar books" of the books you finished or rated well, minus what you already have. */
    suspend fun suggestions(limit: Int = 24): HardcoverSuggestions {
        val seeds = library.tasteSeeds(5)
        val matched = seeds.mapNotNull { s -> hardcoverIdFor(s)?.let { s to it } }
        if (matched.isEmpty()) return HardcoverSuggestions(emptyList(), emptyList())

        val vars = buildJsonObject { put("ids", buildJsonArray { matched.forEach { add(JsonPrimitive(it.second)) } }) }
        val rows = (cachedQuery(HardcoverQueries.SIMILAR, vars, TTL_BOOK)["books"] as? JsonArray).orEmpty()
        val similarById = rows.mapNotNull { it as? JsonObject }.associate { o ->
            (o["id"] as? JsonPrimitive)?.intOrNull to ((o["cached_similar_book_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull })
        }
        val lists = matched.mapNotNull { (_, hcId) -> similarById[hcId]?.takeIf { it.isNotEmpty() } }
        val ranked = mergeSimilar(lists, exclude = matched.map { it.second }.toSet(), limit = 60)
        val index = library.libraryBooks()
        val books = booksByIds(ranked).filter { inLibrary(it, index) == null }.take(limit)
        return HardcoverSuggestions(books, matched.map { it.first.title })
    }

    /** Checks the key with the cheapest catalogue request. */
    suspend fun testKey() {
        val vars = buildJsonObject { put("duration", "week"); put("limit", 1); put("offset", 0) }
        client.query(HardcoverQueries.TRENDING, vars)
    }

    /** Stores [raw] as the key if Hardcover accepts it; otherwise the previous key stays. */
    suspend fun connect(raw: String): Result<Unit> {
        val previous = tokens.hardcoverKey
        if (parseHardcoverKey(raw) == null) return Result.failure(HardcoverException(HardcoverException.Kind.INVALID_KEY, "That does not look like an API key"))
        tokens.hardcoverKey = raw.trim()
        return runCatching { testKey() }.onFailure { tokens.hardcoverKey = previous }
    }

    fun disconnect() {
        tokens.hardcoverKey = null
        cache.clear()
    }

    fun clearCache() = cache.clear()

    // ---------------------------------------------------------------------------------------------

    private suspend fun booksByIds(ids: List<Int>): List<HardcoverBook> {
        if (ids.isEmpty()) return emptyList()
        val vars = buildJsonObject { put("ids", buildJsonArray { ids.take(60).forEach { add(JsonPrimitive(it)) } }) }
        val rows = (cachedQuery(HardcoverQueries.BOOKS, vars, TTL_BOOK)["books"] as? JsonArray).orEmpty()
        val byId = rows.mapNotNull { (it as? JsonObject)?.let(::parseHardcoverBook) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] } // the order of ids is the ranking
    }

    /** The Hardcover book that is [book], found by title and author; null when unsure (a wrong seed is worse than none). */
    private suspend fun hardcoverIdFor(book: Book): Int? {
        val q = listOfNotNull(book.title, book.authors.firstOrNull()).joinToString(" ")
        val vars = buildJsonObject { put("q", q) }
        val hits = idsOf(cachedQuery(HardcoverQueries.SEARCH, vars, TTL_MAPPING)["search"]).take(3)
        return pickBestMatch(book, booksByIds(hits))?.id
    }

    private suspend fun cachedQuery(query: String, vars: JsonObject, ttlMs: Long): JsonObject {
        val key = sha1(query + vars.toString())
        cache.read(key, ttlMs)?.let { return it }
        return try {
            client.query(query, vars).also { cache.write(key, it) }
        } catch (e: HardcoverException) {
            // Offline, rate limited or a hiccup on their side: an old answer beats an error.
            if (e.kind == HardcoverException.Kind.OFFLINE || e.kind == HardcoverException.Kind.RATE_LIMITED || e.kind == HardcoverException.Kind.SERVER) {
                cache.read(key, Long.MAX_VALUE)?.let { return it }
            }
            throw e
        }
    }

    companion object {
        const val TTL_TRENDING = 6 * 3_600_000L
        const val TTL_BOOK = 24 * 3_600_000L
        const val TTL_MAPPING = 30 * 24 * 3_600_000L
    }
}

// ---- pure helpers (unit tested) ------------------------------------------------------------------

internal fun idsOf(node: JsonElement?): List<Int> =
    ((node as? JsonObject)?.get("ids") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }

internal fun parseHardcoverBook(o: JsonObject): HardcoverBook? {
    val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return null
    val title = o["title"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val authors = (o["contributions"] as? JsonArray).orEmpty().mapNotNull { c ->
        ((c as? JsonObject)?.get("author") as? JsonObject)?.get("name").str()?.trim()?.takeIf { it.isNotEmpty() }
    }.distinct()
    val series = ((o["book_series"] as? JsonArray)?.firstOrNull() as? JsonObject)
    return HardcoverBook(
        id = id,
        title = title,
        subtitle = o["subtitle"].str()?.takeIf { it.isNotBlank() },
        authors = authors,
        imageUrl = (o["image"] as? JsonObject)?.get("url").str()?.takeIf { it.isNotBlank() },
        rating = (o["rating"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull,
        ratingsCount = (o["ratings_count"] as? JsonPrimitive)?.intOrNull ?: 0,
        readers = (o["users_count"] as? JsonPrimitive)?.intOrNull ?: 0,
        year = (o["release_year"] as? JsonPrimitive)?.intOrNull,
        pages = (o["pages"] as? JsonPrimitive)?.intOrNull,
        description = o["description"].str()?.takeIf { it.isNotBlank() },
        series = (series?.get("series") as? JsonObject)?.get("name").str(),
        seriesPosition = (series?.get("position") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull,
        slug = o["slug"].str(),
    )
}

/**
 * Combines the "similar books" lists of several books into one ranking: a book scores higher the nearer
 * the top of each list it sits, and the more lists it appears in. Ties keep the order of first appearance.
 */
internal fun mergeSimilar(lists: List<List<Int>>, exclude: Set<Int>, limit: Int): List<Int> {
    val score = LinkedHashMap<Int, Double>()
    for (list in lists) list.forEachIndexed { rank, id -> if (id !in exclude) score.merge(id, 1.0 / (rank + 1), Double::plus) }
    return score.entries.sortedByDescending { it.value }.take(limit).map { it.key }
}

private val LEADING_ARTICLES = listOf("the ", "a ", "an ", "de ", "het ", "een ", "der ", "die ", "das ", "le ", "la ", "les ", "l'")

/** Lower case, no accents, no punctuation, no leading article, no ": subtitle". */
internal fun normalizeTitle(raw: String): String {
    var s = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    s = s.substringBefore(':').substringBefore(" - ").trim()
    LEADING_ARTICLES.firstOrNull { s.startsWith(it) }?.let { s = s.removePrefix(it) }
    return s.replace(Regex("[^a-z0-9]+"), " ").trim()
}

private fun lastNames(authors: List<String>): Set<String> =
    authors.mapNotNull { a -> normalizeTitle(a).split(' ').lastOrNull()?.takeIf { it.isNotBlank() } }.toSet()

/** Does [h] look like the same work as [b]? Same normalised title, and no contradicting author. */
internal fun sameWork(b: Book, h: HardcoverBook): Boolean {
    val t = normalizeTitle(b.title)
    if (t.isEmpty() || t != normalizeTitle(h.title)) return false
    val a = lastNames(b.authors)
    val ha = lastNames(h.authors)
    return a.isEmpty() || ha.isEmpty() || a.any { it in ha }
}

internal fun pickBestMatch(book: Book, candidates: List<HardcoverBook>): HardcoverBook? = candidates.firstOrNull { sameWork(book, it) }

/** The library book that [h] already is, if any. */
fun inLibrary(h: HardcoverBook, library: List<Book>): Book? = library.firstOrNull { sameWork(it, h) }

private fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
