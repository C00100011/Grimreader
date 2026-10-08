package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.AppDatabase
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.network.LoginRequest
import com.vdelaar.mylibby.core.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@Serializable
private data class OidcPending(val serverUrl: String, val state: String, val verifier: String, val nonce: String)

@Serializable
private data class OidcDiscovery(val authorization_endpoint: String)

class AuthRepository(
    private val api: ApiProvider,
    private val tokens: TokenStore,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) {
    val loggedIn = tokens.loggedIn

    private val _oidcError = MutableStateFlow<String?>(null)
    /** Set when a single sign-on attempt fails after the browser handed control back. */
    val oidcError: StateFlow<String?> = _oidcError.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val plainClient by lazy { OkHttpClient() }

    /**
     * Starts single sign-on (OIDC with PKCE) against Grimmory. Returns the address to open in the browser,
     * or an error message. The browser comes back through [completeOidc] via the grimmory://oauth2-callback link.
     */
    suspend fun startOidc(serverUrl: String): OidcStart = withContext(Dispatchers.IO) {
        var url = ApiProvider.normalize(serverUrl)
        if (url.isBlank()) return@withContext OidcStart(error = str(R.string.err_enter_server))
        val schemeGiven = serverUrl.trim().startsWith("http://") || serverUrl.trim().startsWith("https://")
        try {
            val publicSettings = try {
                api.grimmoryFor(url).publicSettings()
            } catch (e: IOException) {
                if (schemeGiven) throw e
                url = url.replaceFirst("https://", "http://")
                api.grimmoryFor(url).publicSettings()
            }
            val provider = publicSettings.oidcProviderDetails
            val issuer = provider?.issuerUri
            val clientId = provider?.clientId
            if (!publicSettings.oidcEnabled || issuer.isNullOrBlank() || clientId.isNullOrBlank()) {
                return@withContext OidcStart(error = str(R.string.err_sso_not_enabled))
            }
            val discoveryUrl = issuer.trimEnd('/') + "/.well-known/openid-configuration"
            val discovery = plainClient.newCall(Request.Builder().url(discoveryUrl).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                json.decodeFromString<OidcDiscovery>(r.body.string())
            }
            val state = api.grimmoryFor(url).oidcState().state
            val verifier = randomUrlSafe(64)
            val nonce = randomUrlSafe(16)
            val challenge = android.util.Base64.encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                BASE64_URL,
            )
            val authUrl = android.net.Uri.parse(discovery.authorization_endpoint).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", OIDC_REDIRECT_URI)
                .appendQueryParameter("scope", provider.scopes?.takeIf { it.isNotBlank() } ?: "openid profile email")
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build().toString()
            tokens.oidcPending = json.encodeToString(OidcPending.serializer(), OidcPending(url, state, verifier, nonce))
            _oidcError.value = null
            OidcStart(url = authUrl)
        } catch (e: HttpException) {
            OidcStart(error = if (e.code() == 404) str(R.string.err_grimmory_404) else str(R.string.err_server_http, e.code()))
        } catch (_: UnknownHostException) {
            OidcStart(error = str(R.string.err_server_not_found))
        } catch (e: SSLException) {
            api.reportUntrusted(serverUrl)
            OidcStart(error = str(R.string.err_ssl_untrusted))
        } catch (e: IOException) {
            OidcStart(error = str(R.string.err_connect_generic, e.message ?: e.javaClass.simpleName))
        } catch (e: Exception) {
            OidcStart(error = str(R.string.err_login_generic, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Finishes single sign-on with the redirect the browser handed back (grimmory://oauth2-callback?code=…&state=…). */
    suspend fun completeOidc(redirect: android.net.Uri) = withContext(Dispatchers.IO) {
        val pending = tokens.oidcPending?.let { runCatching { json.decodeFromString(OidcPending.serializer(), it) }.getOrNull() }
        tokens.oidcPending = null
        val providerError = redirect.getQueryParameter("error_description") ?: redirect.getQueryParameter("error")
        val code = redirect.getQueryParameter("code")
        when {
            pending == null -> _oidcError.value = str(R.string.err_sso_expired)
            providerError != null -> _oidcError.value = str(R.string.err_sso_failed, providerError)
            code.isNullOrBlank() || redirect.getQueryParameter("state") != pending.state -> _oidcError.value = str(R.string.err_sso_failed, "state")
            else -> try {
                val result = api.grimmoryFor(pending.serverUrl).oidcMobileCallback(code, pending.verifier, OIDC_REDIRECT_URI, pending.nonce, pending.state)
                settings.updateServer { it.copy(grimmoryUrl = pending.serverUrl, username = usernameFromToken(result.accessToken) ?: it.username) }
                tokens.saveTokens(result.accessToken, result.refreshToken)
            } catch (e: HttpException) {
                _oidcError.value = str(R.string.err_sso_failed, "HTTP ${e.code()}")
            } catch (e: Exception) {
                _oidcError.value = str(R.string.err_sso_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun usernameFromToken(jwt: String): String? = runCatching {
        val payload = String(android.util.Base64.decode(jwt.split('.')[1], BASE64_URL))
        val obj = json.parseToJsonElement(payload).jsonObject
        (obj["username"] ?: obj["preferred_username"] ?: obj["sub"])?.jsonPrimitive?.content
    }.getOrNull()

    private fun randomUrlSafe(bytes: Int): String =
        android.util.Base64.encodeToString(ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }, BASE64_URL)

    /** Signs in to Grimmory. Returns null on success or a human readable error. */
    suspend fun login(serverUrl: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        var url = ApiProvider.normalize(serverUrl)
        if (url.isBlank()) return@withContext str(R.string.err_enter_server)
        val schemeGiven = serverUrl.trim().startsWith("http://") || serverUrl.trim().startsWith("https://")
        try {
            val request = LoginRequest(username.trim(), password)
            val result = try {
                api.grimmoryFor(url).login(request)
            } catch (e: IOException) {
                // No scheme typed: home servers often only speak plain HTTP.
                if (schemeGiven) throw e
                url = url.replaceFirst("https://", "http://")
                api.grimmoryFor(url).login(request)
            }
            settings.updateServer { it.copy(grimmoryUrl = url, username = username.trim()) }
            tokens.saveTokens(result.accessToken, result.refreshToken)
            null
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> str(R.string.err_wrong_login)
                404 -> str(R.string.err_grimmory_404)
                429 -> str(R.string.err_too_many)
                else -> str(R.string.err_server_http, e.code())
            }
        } catch (_: UnknownHostException) {
            str(R.string.err_server_not_found)
        } catch (e: SSLException) {
            api.reportUntrusted(serverUrl)
            str(R.string.err_ssl_untrusted)
        } catch (e: IOException) {
            str(R.string.err_connect_generic, e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            e.message ?: str(R.string.err_invalid_address)
        } catch (e: Exception) {
            str(R.string.err_login_generic, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Changes that exist only on this device and would be lost on sign-out (Grimmory books only). */
    suspend fun unsyncedChanges(): Int = withContext(Dispatchers.IO) {
        db.progress().dirty().count { it.bookId > 0 } +
            db.annotations().pending().count { it.bookId > 0 } +
            db.bookmarks().pending().count { it.bookId > 0 } +
            db.outbox().all().size
    }

    /**
     * Signs out and removes everything that belongs to the Grimmory account: progress, highlights,
     * bookmarks and queued changes. Whatever was synced comes back on the next sign-in. Local
     * (imported) books, downloads and reading stats stay.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        tokens.clearTokens()
        db.progress().clearRemote()
        db.annotations().clearRemote()
        db.bookmarks().clearRemote()
        db.outbox().clear()
        settings.updateApp { it.copy(favouritesShelfId = null) }
        db.books().clear()
        db.favourites().clear()
    }
}

data class OidcStart(val url: String? = null, val error: String? = null)

private const val OIDC_REDIRECT_URI = "grimmory://oauth2-callback"
private const val BASE64_URL = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
