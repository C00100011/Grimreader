package com.vdelaar.mylibby.core.network

import android.annotation.SuppressLint
import com.vdelaar.mylibby.BuildConfig
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.security.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import kotlinx.coroutines.launch
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

val AppJson = Json {
    ignoreUnknownKeys = true
    // Always send fields, even empty lists: Grimmory rejects requests with missing collections.
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

class NotLoggedInException : Exception("Not signed in to Grimmory")

/**
 * Builds HTTP clients for Grimmory and Shelfmark. Base URLs come from user settings, so the
 * Retrofit instances are rebuilt whenever the configured server changes.
 */
class ApiProvider(
    private val context: android.content.Context,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) {
    /** True when the device has any network (Wi-Fi, mobile, ethernet, VPN). */
    fun isOnline(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val shelfmarkCookies = PersistentCookieJar(tokens)

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .applyPinnedTrust()
            // Fail fast without a network instead of waiting for connect timeouts.
            .addInterceptor { chain ->
                if (!isOnline() && !isLoopback(chain.request().url)) throw java.io.IOException(com.vdelaar.mylibby.core.str(com.vdelaar.mylibby.R.string.err_no_network))
                chain.proceed(chain.request())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()
    }

    /** Client that adds the Grimmory bearer token and refreshes it on 401. Also used for covers. */
    val grimmoryClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor(AuthInterceptor())
            .authenticator(TokenAuthenticator())
            .build()
    }

    /**
     * Cover requests: a cover URL carries the cover's version, so its answer never changes. Telling the cache so keeps
     * covers on the device for offline use instead of asking the server again.
     */
    private val coverClient: OkHttpClient by lazy {
        grimmoryClient.newBuilder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val url = chain.request().url
            if (response.isSuccessful && url.encodedPath.contains("/api/v1/media/book/")) {
                val maxAge = if (url.queryParameter("v") != null) 31_536_000 else 604_800
                response.newBuilder().removeHeader("Pragma").removeHeader("Expires").header("Cache-Control", "public, max-age=$maxAge").build()
            } else response
        }.build()
    }

    /** Long-running downloads. */
    val downloadClient: OkHttpClient by lazy {
        grimmoryClient.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
    }

    private val shelfmarkClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            // Searching the direct-download source (and queueing from it) can take well over a minute.
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.SECONDS)
            .cookieJar(shelfmarkCookies)
            .addInterceptor { chain ->
                val key = tokens.shelfmarkApiKey
                val req = chain.request()
                if (key.isNullOrBlank()) chain.proceed(req)
                else chain.proceed(req.newBuilder().header("X-Api-Key", key).build())
            }
            .build()
    }

    /** Set by the container: a client that adds the OPDS catalog's login for covers from that catalog. */
    @Volatile var opdsImageClient: OkHttpClient? = null

    /** Images: Shelfmark covers need the Shelfmark session, OPDS covers the catalog's login, the rest goes to Grimmory. */
    val imageCallFactory: okhttp3.Call.Factory = okhttp3.Call.Factory { request ->
        val sm = normalize(settings.server.value.shelfmarkUrl).toHttpUrlOrNull()
        val opds = settings.app.value.opdsUrl.toHttpUrlOrNull()
        val opdsClient = opdsImageClient
        when {
            sm != null && request.url.host == sm.host && request.url.port == sm.port -> shelfmarkClient.newCall(request)
            opds != null && opdsClient != null && request.url.host == opds.host && request.url.port == opds.port -> opdsClient.newCall(request)
            else -> coverClient.newCall(request)
        }
    }

    private val grimmoryCache = ConcurrentHashMap<String, GrimmoryApi>()
    private val shelfmarkCache = ConcurrentHashMap<String, ShelfmarkApi>()

    val grimmoryBaseUrl: String get() = normalize(settings.server.value.grimmoryUrl)

    fun grimmory(): GrimmoryApi = grimmoryFor(grimmoryBaseUrl)

    fun grimmoryFor(baseUrl: String): GrimmoryApi {
        val url = normalize(baseUrl)
        require(url.toHttpUrlOrNull() != null) { com.vdelaar.mylibby.core.str(com.vdelaar.mylibby.R.string.err_invalid_address) }
        return grimmoryCache.getOrPut(url) { retrofit(url, grimmoryClient).create(GrimmoryApi::class.java) }
    }

    fun shelfmark(): ShelfmarkApi? {
        val url = normalize(settings.server.value.shelfmarkUrl)
        if (url.toHttpUrlOrNull() == null) return null
        return shelfmarkCache.getOrPut(url) { retrofit(url, shelfmarkClient).create(ShelfmarkApi::class.java) }
    }

    fun shelfmarkFor(baseUrl: String): ShelfmarkApi {
        val url = normalize(baseUrl)
        require(url.toHttpUrlOrNull() != null) { "Invalid Shelfmark address" }
        return shelfmarkCache.getOrPut(url) { retrofit(url, shelfmarkClient).create(ShelfmarkApi::class.java) }
    }

    fun clearShelfmarkSession() = shelfmarkCookies.clear()

    /** Imports cookies captured from the in-app OIDC login (a "name=value; name2=value2" header). */
    fun importShelfmarkCookies(baseUrl: String, cookieHeader: String) {
        val url = normalize(baseUrl).toHttpUrlOrNull() ?: return
        shelfmarkCookies.importHeader(url, cookieHeader)
    }

    /** Absolute URL for a path on the Grimmory server (e.g. a cover). */
    fun absolute(path: String): String =
        if (path.startsWith("http")) path else grimmoryBaseUrl + path.removePrefix("/")

    fun coverUrl(bookId: Long, version: String? = null, thumbnail: Boolean = false): String {
        val kind = if (thumbnail) "thumbnail" else "cover"
        val v = version?.let { "?v=" + it.hashCode() } ?: ""
        return "${grimmoryBaseUrl}api/v1/media/book/$bookId/$kind$v"
    }

    fun shelfmarkAbsolute(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return normalize(settings.server.value.shelfmarkUrl) + path.removePrefix("/")
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()

    private val pinnedTrust = PinnedTrust { settings.server.value.trustedCerts }

    private fun OkHttpClient.Builder.applyPinnedTrust(): OkHttpClient.Builder =
        sslSocketFactory(pinnedTrust.sslSocketFactory, pinnedTrust.trustManager).hostnameVerifier(pinnedTrust.hostnameVerifier)

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** A server presented a certificate the system does not trust; the UI asks the user whether to trust it. */
    val untrustedCert = kotlinx.coroutines.flow.MutableStateFlow<CertInfo?>(null)

    /** Look at the certificate of an https server that failed to verify, so the user can review it. */
    fun reportUntrusted(url: String) {
        val http = normalize(url).toHttpUrlOrNull() ?: return
        if (!http.isHttps) return
        scope.launch {
            val cert = fetchServerCertificate(http.host, http.port) ?: return@launch
            if (cert.sha256 !in settings.server.value.trustedCerts) untrustedCert.value = cert
        }
    }

    suspend fun trust(cert: CertInfo) {
        settings.updateServer { it.copy(trustedCerts = it.trustedCerts + cert.sha256) }
        untrustedCert.value = null
    }

    fun dismissUntrusted() { untrustedCert.value = null }

    // Emulator testing via `adb reverse` reaches the server on localhost even without a network.
    private fun isLoopback(url: HttpUrl) = url.host == "localhost" || url.host == "127.0.0.1"

    private fun isGrimmoryHost(url: HttpUrl): Boolean {
        val base = grimmoryBaseUrl.toHttpUrlOrNull() ?: return false
        return url.host == base.host && url.port == base.port
    }

    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val token = tokens.accessToken
            val path = req.url.encodedPath
            if (token == null || !isGrimmoryHost(req.url) || path.endsWith("/auth/login") || path.endsWith("/auth/refresh")) {
                return chain.proceed(req)
            }
            return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
        }
    }

    private inner class TokenAuthenticator : Authenticator {
        private val lock = Any()

        override fun authenticate(route: Route?, response: Response): Request? {
            val req = response.request
            if (!isGrimmoryHost(req.url) || req.url.encodedPath.endsWith("/auth/refresh")) return null
            if (responseCount(response) >= 2) return null
            synchronized(lock) {
                val current = tokens.accessToken
                val sent = req.header("Authorization")?.removePrefix("Bearer ")
                // Another request already refreshed the token.
                if (current != null && current != sent) {
                    return req.newBuilder().header("Authorization", "Bearer $current").build()
                }
                val refresh = tokens.refreshToken ?: return null
                val fresh = runCatching {
                    runBlocking {
                        retrofit(grimmoryBaseUrl, baseClient).create(GrimmoryApi::class.java)
                            .refresh(RefreshRequest(refresh))
                    }
                }.getOrNull()
                if (fresh == null) {
                    tokens.clearTokens()
                    return null
                }
                tokens.saveTokens(fresh.accessToken, fresh.refreshToken)
                return req.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
            }
        }

        private fun responseCount(response: Response): Int {
            var r: Response? = response
            var count = 1
            while (r?.priorResponse != null) { count++; r = r.priorResponse }
            return count
        }
    }

    companion object {
        fun normalize(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            if (!u.endsWith("/")) u += "/"
            return u
        }
    }
}

/**
 * Cookie jar for Shelfmark that survives app restarts (stored encrypted in [TokenStore]).
 * Shelfmark sessions are signed Flask cookies valid for 7 days.
 */
private class PersistentCookieJar(private val tokens: TokenStore) : CookieJar {

    @kotlinx.serialization.Serializable
    private data class Stored(val name: String, val value: String, val host: String, val path: String, val expiresAt: Long, val secure: Boolean)

    private val lock = Any()
    private val cookies: MutableList<Cookie> = load()

    private fun load(): MutableList<Cookie> = runCatching {
        val json = tokens.shelfmarkCookies ?: return@runCatching mutableListOf<Cookie>()
        AppJson.decodeFromString<List<Stored>>(json)
            .filter { it.expiresAt > System.currentTimeMillis() }
            .map {
                Cookie.Builder().name(it.name).value(it.value).hostOnlyDomain(it.host).path(it.path)
                    .expiresAt(it.expiresAt).apply { if (it.secure) secure() }.build()
            }
            .toMutableList()
    }.getOrDefault(mutableListOf())

    private fun persist() {
        val list = cookies.filter { it.persistent || it.expiresAt > System.currentTimeMillis() }.map {
            Stored(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure)
        }
        tokens.shelfmarkCookies = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Stored.serializer()), list)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (c in cookies) {
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain }
                if (c.expiresAt > System.currentTimeMillis()) this.cookies.add(c)
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
        cookies.filter { it.matches(url) }
    }

    /** Session cookies from the WebView have no expiry info; Shelfmark sessions last 7 days. */
    fun importHeader(url: HttpUrl, header: String) {
        val expires = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
        val parsed = header.split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val name = part.substring(0, i).trim()
            val value = part.substring(i + 1).trim()
            Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).path("/").expiresAt(expires)
                .apply { if (url.isHttps) secure() }.build()
        }
        saveFromResponse(url, parsed)
    }

    fun clear() = synchronized(lock) {
        cookies.clear()
        tokens.shelfmarkCookies = null
    }
}
