package com.vdelaar.mylibby.core.network

import com.vdelaar.mylibby.BuildConfig
import com.vdelaar.mylibby.core.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why a Hardcover request failed, in terms the UI can explain. */
class HardcoverException(val kind: Kind, message: String, val retryAfterSeconds: Int? = null) : Exception(message) {
    enum class Kind { NO_KEY, INVALID_KEY, MISSING_SCOPE, RATE_LIMITED, OFFLINE, SERVER, BAD_QUERY }
}

/** A key as the user pasted it, plus where to send it. */
data class HardcoverCredentials(val token: String, val endpoint: String)

const val HARDCOVER_ENDPOINT = "https://api.hardcover.app/v1/graphql"

/**
 * Turns what a user pastes into a token. Hardcover shows the token as "Bearer eyJ…", people sometimes copy the
 * whole header ("authorization: Bearer …") or wrap it in quotes. Debug builds also accept `<endpoint>|<token>`
 * so the app can talk to the mock server in tools/mock-grimmory.
 */
fun parseHardcoverKey(raw: String?, allowOverride: Boolean = BuildConfig.DEBUG): HardcoverCredentials? {
    var s = raw.orEmpty().trim().trim('"', '\'', '`').trim()
    if (s.isBlank()) return null
    var endpoint = HARDCOVER_ENDPOINT
    if (allowOverride && s.startsWith("http") && '|' in s) {
        endpoint = s.substringBefore('|')
        s = s.substringAfter('|').trim()
    }
    if (s.startsWith("authorization", ignoreCase = true)) s = s.substringAfter(':').trim()
    if (s.startsWith("bearer", ignoreCase = true)) s = s.substring(6).trim()
    if (s.isBlank() || s.any { it.isWhitespace() }) return null
    return HardcoverCredentials(s, endpoint)
}

/** What the repository needs from the network layer (faked in tests). */
interface HardcoverGateway {
    val hasKey: Boolean
    suspend fun query(query: String, variables: JsonObject? = null): JsonObject
}

/** Where the user's key is kept. */
interface HardcoverKeyStore {
    var hardcoverKey: String?
}

/**
 * Minimal GraphQL client for the Hardcover API (https://docs.hardcover.app/api/getting-started/).
 * The user's own key goes in the `authorization` header; limits are 60 requests/min, 5,000/day, 30 s per query.
 */
class HardcoverClient(
    private val tokens: TokenStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build(),
) : HardcoverGateway {
    override val hasKey: Boolean get() = parseHardcoverKey(tokens.hardcoverKey) != null

    /** Runs [query] and returns its `data` object. Throws [HardcoverException]. */
    override suspend fun query(query: String, variables: JsonObject?): JsonObject = withContext(Dispatchers.IO) {
        val creds = parseHardcoverKey(tokens.hardcoverKey) ?: throw HardcoverException(HardcoverException.Kind.NO_KEY, "No Hardcover key")
        try {
            send(creds, query, variables)
        } catch (e: HardcoverException) {
            // A short "slow down" is worth one patient retry (the bucket refills at 1 request/second).
            val wait = e.retryAfterSeconds
            if (e.kind == HardcoverException.Kind.RATE_LIMITED && wait != null && wait in 1..10) {
                delay(wait * 1000L)
                send(creds, query, variables)
            } else throw e
        }
    }

    private fun send(creds: HardcoverCredentials, query: String, variables: JsonObject?): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(creds.endpoint)
            .post(body)
            .header("authorization", "Bearer ${creds.token}")
            .header("User-Agent", "Grimreader/${BuildConfig.VERSION_NAME} (Android e-book reader)")
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val root = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                val message = root?.get("error_description").str() ?: root?.get("message").str() ?: root?.get("error").str()
                when (resp.code) {
                    200 -> Unit
                    401 -> throw HardcoverException(HardcoverException.Kind.INVALID_KEY, message ?: "Hardcover did not accept the key")
                    403 -> throw HardcoverException(
                        if (root?.get("error").str() == "insufficient_scope") HardcoverException.Kind.MISSING_SCOPE else HardcoverException.Kind.INVALID_KEY,
                        message ?: "Hardcover refused this request",
                    )
                    429 -> throw HardcoverException(HardcoverException.Kind.RATE_LIMITED, message ?: "Too many requests", resp.header("Retry-After")?.toIntOrNull())
                    400 -> throw HardcoverException(HardcoverException.Kind.BAD_QUERY, message ?: "Hardcover rejected the request")
                    else -> throw HardcoverException(HardcoverException.Kind.SERVER, message ?: "Hardcover answered ${resp.code}")
                }
                val json = root ?: throw HardcoverException(HardcoverException.Kind.SERVER, "Unreadable answer from Hardcover")
                return parseGraphqlData(json)
            }
        } catch (e: IOException) {
            throw HardcoverException(HardcoverException.Kind.OFFLINE, e.message ?: "Could not reach Hardcover")
        }
    }
}

/** The `data` of a GraphQL answer; GraphQL-level `errors` without data become a [HardcoverException]. */
internal fun parseGraphqlData(root: JsonObject): JsonObject {
    val data = root["data"]
    val errors = root["errors"] as? JsonArray
    if ((data == null || data is JsonNull) && !errors.isNullOrEmpty()) {
        val first = errors.first() as? JsonObject
        val msg = first?.get("message").str() ?: "Hardcover reported an error"
        val code = (first?.get("extensions") as? JsonObject)?.get("code").str().orEmpty()
        val kind = when {
            code.contains("jwt", true) || msg.contains("jwt", true) || msg.contains("token", true) -> HardcoverException.Kind.INVALID_KEY
            code == "access-denied" || msg.contains("permission", true) || msg.contains("scope", true) -> HardcoverException.Kind.MISSING_SCOPE
            else -> HardcoverException.Kind.BAD_QUERY
        }
        throw HardcoverException(kind, msg)
    }
    return data as? JsonObject ?: throw HardcoverException(HardcoverException.Kind.SERVER, "Hardcover sent no data")
}

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
