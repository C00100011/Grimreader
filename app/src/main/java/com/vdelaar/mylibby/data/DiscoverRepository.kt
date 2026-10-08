package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/** Where "new books for you" looks: your most liked genres and the languages you read. */
/** [personal] = the genres come from your own reading, not from the starter list. */
data class Taste(val genres: List<String>, val languages: List<String>, val personal: Boolean = true)

/** One row of new books in a genre. */
data class GenreRow(val genre: String, val ideas: List<BookIdea>)

class DiscoverException(message: String, val offline: Boolean) : Exception(message)

/**
 * New and free books from public catalogues that need no account: Open Library (new books by genre and language)
 * and Project Gutenberg through Gutendex (public-domain books with an EPUB). What is sent is a genre and a language,
 * never what you read. Answers are kept on disk and an old answer is shown when the catalogue is not reachable.
 */
class DiscoverRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val library: HardcoverLibrary,
    private val cache: HardcoverCache,
    /** Fetches a URL as JSON; replaceable in tests. */
    private val fetch: suspend (String) -> JsonObject = { url -> httpGetJson(url) },
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    suspend fun taste(): Taste = withContext(Dispatchers.IO) {
        val books = library.libraryBooks()
        val weights = genreWeights(books, db.favourites().ids().toSet(), settings.app.value.profile.favouriteGenres)
        val genres = searchGenres(weights, 3)
        Taste(
            genres = genres.ifEmpty { STARTER_GENRES },
            languages = readingLanguages(books, appLanguage()),
            personal = genres.isNotEmpty(),
        )
    }

    /** New books in [genre] for each of [languages], newest first, one language after the other so none drowns the rest. */
    suspend fun newBooks(genre: String, languages: List<String>, limit: Int = 20): List<BookIdea> = withContext(Dispatchers.IO) {
        val since = today().year - 2
        val perLanguage = languages.mapNotNull { marcOf(it) }.map { marc ->
            val url = OpenLibraryQuery.BASE.toHttpUrl().newBuilder()
                .addQueryParameter("q", OpenLibraryQuery.newBooks(genre, marc, since))
                .addQueryParameter("sort", OpenLibraryQuery.SORT)
                .addQueryParameter("limit", "40")
                .addQueryParameter("fields", OpenLibraryQuery.FIELDS)
                .build().toString()
            parseOpenLibrary(cached(url, TTL_NEW))
        }
        interleave(perLanguage).distinctWorks().take(limit)
    }

    /** Rows of new books for each of your top genres, skipping what you already have. */
    suspend fun newInGenres(taste: Taste, languages: List<String> = taste.languages): List<GenreRow> = withContext(Dispatchers.IO) {
        val owned = library.libraryBooks()
        taste.genres.map { genre ->
            GenreRow(genre, newBooks(genre, languages).filter { libraryMatch(it.title, it.authors, owned) == null }.take(14))
        }.filter { it.ideas.isNotEmpty() }
    }

    /** Free public-domain books in a language, most downloaded first, each with an EPUB. */
    suspend fun freeToRead(language: String, limit: Int = 16): List<FreeBook> = withContext(Dispatchers.IO) {
        val url = "https://gutendex.com/books/".toHttpUrl().newBuilder()
            .addQueryParameter("languages", language.lowercase().take(2))
            .addQueryParameter("sort", "popular")
            .build().toString()
        parseGutendex(cached(url, TTL_FREE)).take(limit)
    }

    /** The description of a book, from its Open Library page; null when there is none or it cannot be reached. */
    suspend fun description(key: String): String? = withContext(Dispatchers.IO) {
        runCatching { parseWorkDescription(cached("https://openlibrary.org$key.json", TTL_WORK)) }.getOrNull()
    }

    /** Downloads a free EPUB to [file]. */
    suspend fun download(url: String, file: java.io.File) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("Empty answer")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /** Where in the library an idea already is, if it is. */
    suspend fun inLibrary(idea: BookIdea): Book? = libraryMatch(idea.title, idea.authors, library.libraryBooks())

    fun appLanguage(): String = settings.app.value.language.takeIf { it != "system" } ?: Locale.getDefault().language

    private suspend fun cached(url: String, ttlMs: Long): JsonObject {
        val key = sha1(url)
        cache.read(key, ttlMs)?.let { return it }
        return try {
            fetch(url).also { cache.write(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not reachable or in a bad mood: an old answer beats an empty screen.
            cache.read(key, Long.MAX_VALUE)?.let { return it }
            throw DiscoverException(e.message ?: "Could not reach the catalogue", offline = e is java.io.IOException)
        }
    }

    companion object {
        const val TTL_NEW = 12 * 3_600_000L
        const val TTL_FREE = 7 * 24 * 3_600_000L
        const val TTL_WORK = 30 * 24 * 3_600_000L

        /** Identifies the app to the free services we ask (Open Library asks for this). Nothing personal in it. */
        const val USER_AGENT = "Grimreader/0.9 (Android e-reader)"

        internal val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                .build()
        }

        internal fun httpGetJson(url: String): JsonObject {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                return AppJson.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject ?: throw java.io.IOException("Unexpected answer")
            }
        }
    }
}

/** Takes one from each list in turn: [[a1,a2],[b1,b2,b3]] -> a1,b1,a2,b2,b3. */
internal fun <T> interleave(lists: List<List<T>>): List<T> {
    val out = ArrayList<T>()
    var i = 0
    while (lists.any { i < it.size }) {
        lists.forEach { if (i < it.size) out += it[i] }
        i++
    }
    return out
}

private fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
