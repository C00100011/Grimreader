package com.vdelaar.mylibby.data

import android.net.Uri
import com.vdelaar.mylibby.BuildConfig
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.model.Book
import com.vdelaar.mylibby.core.network.OpdsAcquisition
import com.vdelaar.mylibby.core.network.OpdsEntry
import com.vdelaar.mylibby.core.network.OpdsFeed
import com.vdelaar.mylibby.core.network.fillSearchTemplate
import com.vdelaar.mylibby.core.network.parseOpdsFeed
import com.vdelaar.mylibby.core.network.parseOpenSearchTemplate
import com.vdelaar.mylibby.core.network.resolveOpdsUrl
import com.vdelaar.mylibby.core.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpdsException(message: String, val unauthorized: Boolean = false) : Exception(message)

/**
 * An OPDS catalog (the open e-book catalog standard used by Calibre, Calibre-Web, Kavita, Komga, Standard Ebooks,
 * Project Gutenberg, ...) as a library source: browse and search it, and download books to this device, where they
 * become local books that read, listen and sync stats like any other.
 */
class OpdsRepository(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val local: LocalBooksRepository,
    private val cacheDir: File,
) {
    /** Adds the catalog's credentials to requests for the catalog's own host (and nobody else's). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val app = settings.app.value
            val base = app.opdsUrl.toHttpUrlOrNull()
            val sameHost = base != null && req.url.host == base.host && req.url.port == base.port
            if (sameHost && app.opdsUsername.isNotBlank() && req.header("Authorization") == null) {
                chain.proceed(req.newBuilder().header("Authorization", Credentials.basic(app.opdsUsername, tokens.opdsPassword.orEmpty(), Charsets.UTF_8)).build())
            } else chain.proceed(req)
        }
        .build()

    val configured: Boolean get() = settings.app.value.opdsUrl.isNotBlank()

    suspend fun root(): OpdsFeed = fetch(settings.app.value.opdsUrl)

    suspend fun fetch(url: String): OpdsFeed = withContext(Dispatchers.IO) {
        parseOpdsFeed(get(url), url)
    }

    /** The URL that searches [query] in this catalog, or null when the catalog has no search. */
    suspend fun searchUrl(root: OpdsFeed, query: String): String? = withContext(Dispatchers.IO) {
        val template = root.searchTemplate
            ?: root.searchDescriptionUrl?.let { desc -> runCatching { parseOpenSearchTemplate(get(desc)) }.getOrNull()?.let { resolveOpdsUrl(desc, it.replace("{", "%7B").replace("}", "%7D")).replace("%7B", "{").replace("%7D", "}") } }
        template?.let { fillSearchTemplate(it, query) }
    }

    /** The address that worked, and its feed. */
    data class Tested(val url: String, val feed: OpdsFeed)

    /**
     * Checks an address (and optional login) by reading its feed; nothing is saved. A bare address is tried with
     * https:// first and then http:// (home servers often have no certificate), like the Grimmory sign-in does.
     */
    suspend fun test(url: String, username: String, password: String): Result<Tested> = withContext(Dispatchers.IO) {
        runCatching {
            val s = url.trim()
            val candidates = if (s.startsWith("http://", true) || s.startsWith("https://", true)) listOf(s) else listOf("https://$s", "http://$s")
            var last: OpdsException? = null
            for (u in candidates) {
                try {
                    val req = Request.Builder().url(u).header("Accept", ACCEPT).apply {
                        if (username.isNotBlank()) header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                    }.build()
                    val feed = parseOpdsFeed(execute(req), u)
                    if (feed.entries.isEmpty() && feed.nextUrl == null) throw OpdsException("This address did not return an OPDS catalog")
                    return@runCatching Tested(u, feed)
                } catch (e: OpdsException) {
                    if (e.unauthorized) throw e
                    last = e
                } catch (e: org.xml.sax.SAXException) {
                    last = OpdsException("This address did not return an OPDS catalog")
                }
            }
            throw last ?: OpdsException("Could not reach the catalog")
        }
    }

    /** Downloads [acquisition] and adds it to the local library. Returns the new local book. */
    suspend fun download(entry: OpdsEntry, acquisition: OpdsAcquisition): Book = withContext(Dispatchers.IO) {
        val ext = (acquisition.format ?: "epub").lowercase().let { if (it == "cbz") "cbz" else it }
        val safe = entry.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(60).ifBlank { "book" }
        val file = File(cacheDir.apply { mkdirs() }, "$safe.$ext")
        try {
            val req = Request.Builder().url(acquisition.url).build()
            client.newBuilder().readTimeout(5, TimeUnit.MINUTES).build().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw unauthorizedOr(resp.code)
                val body = resp.body ?: throw OpdsException("Empty download")
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            local.import(Uri.fromFile(file))
        } catch (e: IOException) {
            throw OpdsException(e.message ?: "Download failed")
        } finally {
            file.delete()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun get(url: String): String = execute(Request.Builder().url(url).header("Accept", ACCEPT).build())

    private fun execute(req: Request): String = try {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw unauthorizedOr(resp.code)
            resp.body?.string().orEmpty()
        }
    } catch (e: IOException) {
        throw OpdsException(e.message ?: "Could not reach the catalog")
    }

    private fun unauthorizedOr(code: Int) =
        if (code == 401 || code == 403) OpdsException("The catalog asked for a login that was not accepted", unauthorized = true) else OpdsException("The catalog answered $code")

    companion object {
        const val ACCEPT = "application/atom+xml;profile=opds-catalog, application/atom+xml, application/xml;q=0.9, */*;q=0.5"

        /** Adds https:// when the user typed a bare address. */
        fun normalizeUrl(raw: String): String {
            val s = raw.trim()
            return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "https://$s"
        }
    }
}
