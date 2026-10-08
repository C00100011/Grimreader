package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.core.network.ShelfmarkApi
import com.vdelaar.mylibby.core.network.ShelfmarkBook
import com.vdelaar.mylibby.core.network.ShelfmarkLoginRequest
import com.vdelaar.mylibby.core.network.ShelfmarkTask
import com.vdelaar.mylibby.core.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import retrofit2.HttpException

data class Release(
    val raw: JsonObject,
    val title: String,
    val format: String?,
    val language: String?,
    val size: String?,
    val source: String?,
    val indexer: String?,
    val seeders: Int?,
)

sealed interface DownloadOutcome {
    data object Queued : DownloadOutcome
    data object Requested : DownloadOutcome
    data class Failed(val message: String) : DownloadOutcome
}

data class ShelfmarkActivity(val status: String, val task: ShelfmarkTask)

/** What a Shelfmark server tells us about how to sign in (from /api/auth/check). */
data class ShelfmarkProbe(
    val url: String,
    val authMode: String,
    val authenticated: Boolean,
    val hideLocalAuth: Boolean,
    val oidcLabel: String?,
) {
    val usesOidc: Boolean get() = authMode == "oidc"
    val allowsPassword: Boolean get() = authMode == "builtin" || (authMode == "oidc" && !hideLocalAuth)
}

/** The stored Shelfmark session is gone (e.g. the 7-day SSO session expired). */
class ShelfmarkSessionExpired : Exception(str(R.string.err_session_expired))

class ShelfmarkRepository(
    private val api: ApiProvider,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) {
    val configured: Boolean get() = settings.server.value.shelfmarkUrl.isNotBlank()

    /** Finds the server (trying https, then http if no scheme was typed) and its login method. */
    suspend fun probe(rawUrl: String): Result<ShelfmarkProbe> = withContext(Dispatchers.IO) {
        val schemeGiven = rawUrl.trim().startsWith("http://") || rawUrl.trim().startsWith("https://")
        val candidates = buildList {
            add(ApiProvider.normalize(rawUrl))
            if (!schemeGiven) add(ApiProvider.normalize(rawUrl).replaceFirst("https://", "http://"))
        }
        var lastError: String = str(R.string.err_reach_shelfmark)
        for (url in candidates) {
            try {
                val o = api.shelfmarkFor(url).authCheck()
                return@withContext Result.success(
                    ShelfmarkProbe(
                        url = url,
                        authMode = o.str("auth_mode") ?: "builtin",
                        authenticated = o.bool("authenticated") ?: false,
                        hideLocalAuth = o.bool("hide_local_auth") ?: false,
                        oidcLabel = o.str("oidc_button_label"),
                    )
                )
            } catch (e: Exception) {
                lastError = friendlyError(e, url)
            }
        }
        Result.failure(Exception(lastError))
    }

    /** Saves a server that needs no login (auth disabled), or is already signed in. */
    suspend fun connectOpen(probe: ShelfmarkProbe): String? = finishConnect(probe.url, if (probe.authMode == "none") "none" else "oidc")

    suspend fun connect(url: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            api.clearShelfmarkSession()
            tokens.shelfmarkApiKey = null
            val r = api.shelfmarkFor(url).login(ShelfmarkLoginRequest(username.trim(), password))
            if (!r.isSuccessful) {
                val body = r.errorBody()?.string().orEmpty()
                return@withContext when {
                    r.code() == 401 -> AUTH_ERROR
                    r.code() == 403 -> errorMessage(body) ?: str(R.string.err_password_disabled)
                    else -> errorMessage(body) ?: str(R.string.err_login_http, r.code())
                }
            }
            tokens.shelfmarkUsername = username.trim()
            tokens.shelfmarkPassword = password
            finishConnect(url, "password")
        } catch (e: Exception) {
            friendlyError(e)
        }
    }

    /** Called after the in-app SSO login finished; [cookieHeader] comes from the WebView. */
    suspend fun completeOidc(url: String, cookieHeader: String): String? = withContext(Dispatchers.IO) {
        api.clearShelfmarkSession()
        tokens.shelfmarkApiKey = null
        tokens.shelfmarkUsername = null
        tokens.shelfmarkPassword = null
        api.importShelfmarkCookies(url, cookieHeader)
        finishConnect(url, "oidc")
    }

    /** API keys act as a Shelfmark admin; useful when SSO can't run inside the app. */
    suspend fun connectWithApiKey(url: String, key: String): String? = withContext(Dispatchers.IO) {
        api.clearShelfmarkSession()
        tokens.shelfmarkApiKey = key.trim()
        try {
            api.shelfmarkFor(url).status() // /api/auth/check ignores the key, /api/status doesn't
            finishConnect(url, "apikey", verify = false)
        } catch (e: Exception) {
            tokens.shelfmarkApiKey = null
            if (e is HttpException && e.code() == 401) str(R.string.err_api_key) else friendlyError(e, url)
        }
    }

    private suspend fun finishConnect(url: String, method: String, verify: Boolean = true): String? = try {
        if (verify) {
            val check = api.shelfmarkFor(url).authCheck()
            if (check.bool("authenticated") == false) throw ShelfmarkSessionExpired()
        }
        settings.updateServer { it.copy(shelfmarkUrl = url, shelfmarkAuth = method) }
        null
    } catch (_: ShelfmarkSessionExpired) {
        if (method == "oidc") str(R.string.err_sso_incomplete) else AUTH_ERROR
    } catch (e: Exception) {
        friendlyError(e, url)
    }

    private fun friendlyError(e: Exception, url: String? = null): String = when (e) {
        is HttpException -> if (e.code() == 401) AUTH_ERROR else str(R.string.err_sm_http, e.code())
        is java.net.UnknownHostException -> str(R.string.err_server_not_found)
        is javax.net.ssl.SSLException -> { url?.let { api.reportUntrusted(it) }; str(R.string.err_ssl_untrusted) }
        is java.io.IOException -> str(R.string.err_reach_shelfmark)
        else -> str(R.string.err_connect_generic, e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        val AUTH_ERROR: String get() = str(R.string.err_wrong_login)
    }

    suspend fun disconnect() {
        settings.updateServer { it.copy(shelfmarkUrl = "", shelfmarkAuth = "") }
        tokens.shelfmarkUsername = null
        tokens.shelfmarkPassword = null
        tokens.shelfmarkApiKey = null
        api.clearShelfmarkSession()
    }

    private suspend fun <T> withSession(block: suspend (ShelfmarkApi) -> T): T {
        val sm = api.shelfmark() ?: error("Shelfmark is not configured")
        return try {
            block(sm)
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            val user = tokens.shelfmarkUsername
            val pass = tokens.shelfmarkPassword
            if (user != null && pass != null && sm.login(ShelfmarkLoginRequest(user, pass)).isSuccessful) {
                block(sm)
            } else {
                // SSO sessions can't be renewed silently: ask the user to sign in again.
                throw ShelfmarkSessionExpired()
            }
        }
    }

    suspend fun search(query: String, page: Int = 1): Pair<List<ShelfmarkBook>, Boolean> = withContext(Dispatchers.IO) {
        val r = withSession { it.search(query, page = page) }
        r.books.map { b -> b.copy(cover_url = api.shelfmarkAbsolute(b.cover_url)) } to r.has_more
    }

    /** Enabled release sources that can find ebooks (e.g. Prowlarr, Direct download). */
    suspend fun sources(): List<com.vdelaar.mylibby.core.network.ShelfmarkSource> = withContext(Dispatchers.IO) {
        withSession { it.releaseSources() }.filter { s ->
            s.enabled && (s.supported_content_types == null || "ebook" in s.supported_content_types)
        }
    }

    suspend fun releases(
        book: ShelfmarkBook,
        source: String? = null,
        languages: String? = null,
        manualQuery: String? = null,
    ): List<Release> = withContext(Dispatchers.IO) {
        withSession {
            it.releases(book.provider, book.provider_id, source = source, languages = languages, manualQuery = manualQuery?.ifBlank { null })
        }.releases.map { o ->
            Release(
                raw = o,
                title = o.str("title") ?: book.title,
                format = o.str("format"),
                language = o.str("language"),
                size = o.str("size"),
                source = o.str("source"),
                indexer = o.str("indexer"),
                seeders = o.str("seeders")?.toIntOrNull(),
            )
        }
    }

    /** Queue a download; if policy requires approval, submit a request instead. */
    suspend fun download(book: ShelfmarkBook, release: Release): DownloadOutcome = withContext(Dispatchers.IO) {
        try {
            val r = withSession { it.download(release.raw) }
            if (r.isSuccessful) return@withContext DownloadOutcome.Queued
            val body = r.errorBody()?.string().orEmpty()
            val code = runCatching { AppJson.parseToJsonElement(body).jsonObject.str("code") }.getOrNull()
            if (r.code() == 403 && code == "policy_requires_request") {
                return@withContext request(book, release)
            }
            DownloadOutcome.Failed(errorMessage(body) ?: str(R.string.err_download_http, r.code()))
        } catch (e: Exception) {
            DownloadOutcome.Failed(e.message ?: str(R.string.err_download))
        }
    }

    private suspend fun request(book: ShelfmarkBook, release: Release?): DownloadOutcome {
        val payload = buildJsonObject {
            put("book_data", buildJsonObject {
                put("title", book.title)
                put("author", book.authors.joinToString(", "))
                put("provider", book.provider)
                put("provider_id", book.provider_id)
                put("preview", book.cover_url?.let { JsonPrimitive(it) } ?: JsonNull)
                put("year", book.publish_year?.let { JsonPrimitive(it) } ?: JsonNull)
                put("content_type", "ebook")
            })
            put("release_data", release?.raw ?: JsonNull)
            put("context", buildJsonObject {
                put("source", release?.source?.let { JsonPrimitive(it) } ?: JsonNull)
                put("content_type", "ebook")
                put("request_level", if (release == null) "book" else "release")
            })
        }
        val r = withSession { it.createRequest(payload) }
        return if (r.isSuccessful) DownloadOutcome.Requested
        else DownloadOutcome.Failed(errorMessage(r.errorBody()?.string().orEmpty()) ?: str(R.string.err_request_http, r.code()))
    }

    suspend fun requestBook(book: ShelfmarkBook): DownloadOutcome = withContext(Dispatchers.IO) {
        runCatching { request(book, null) }.getOrElse { DownloadOutcome.Failed(it.message ?: str(R.string.err_request)) }
    }

    suspend fun activity(): List<ShelfmarkActivity> = withContext(Dispatchers.IO) {
        val status = withSession { it.status() }
        status.flatMap { (state, tasks) -> tasks.values.map { ShelfmarkActivity(state, it.copy(preview = api.shelfmarkAbsolute(it.preview))) } }
            .sortedBy { order(it.status) }
    }

    private fun order(status: String) = when (status) {
        "downloading" -> 0
        "locating", "resolving" -> 1
        "queued" -> 2
        "complete" -> 3
        else -> 4
    }

    private fun errorMessage(body: String): String? = runCatching {
        val o = AppJson.parseToJsonElement(body).jsonObject
        o.str("message") ?: o.str("error")
    }.getOrNull()
}

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
