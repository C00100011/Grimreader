package com.vdelaar.mylibby.core.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.vdelaar.mylibby.BuildConfig
import com.vdelaar.mylibby.core.datastore.LibrarySource
import com.vdelaar.mylibby.core.network.ApiProvider
import com.vdelaar.mylibby.core.network.HARDCOVER_ENDPOINT
import com.vdelaar.mylibby.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** One line of a connection test: what was tried, whether it worked, and how long it took. */
data class ProbeStep(val name: String, val ok: Boolean?, val detail: String, val millis: Long = 0)

data class ProbeResult(val target: String, val host: String?, val steps: List<ProbeStep>) {
    /** True when every step that ran worked. */
    val ok: Boolean get() = steps.none { it.ok == false }
}

/** Step-by-step checks of the connection to each service, to see where it breaks: address, name lookup, connection, TLS, sign-in. */
class ConnectionProbe(private val c: AppContainer) {
    /** The services that are set up (or always used, like Hardcover), in the order they appear on screen. */
    fun targets(): List<String> {
        val app = c.settings.app.value
        val server = c.settings.server.value
        return buildList {
            // During onboarding the address is only saved after a successful sign-in; then the last address tried is used.
            if (!app.noServer && (server.grimmoryUrl.isNotBlank() || lastTried(GRIMMORY) != null)) add(GRIMMORY)
            if (server.shelfmarkUrl.isNotBlank()) add(SHELFMARK)
            if (app.opdsUrl.isNotBlank()) add(OPDS)
            add(HARDCOVER)
        }
    }

    /** The server of the latest logged request of [source], as an address, for a sign-in that has not succeeded (yet). */
    private fun lastTried(source: String): String? =
        NetworkLog.entries.value.filter { it.source == source }.let { l -> l.firstOrNull { it.scheme == "https" } ?: l.firstOrNull() }?.let { "${it.scheme}://${it.host}:${it.port}/" }

    suspend fun run(target: String): ProbeResult = withContext(Dispatchers.IO) {
        val server = c.settings.server.value
        val app = c.settings.app.value
        when (target) {
            GRIMMORY -> probe(target, server.grimmoryUrl.takeIf { it.isNotBlank() }?.let { ApiProvider.normalize(it) } ?: lastTried(GRIMMORY).orEmpty(), "api/v1/public-settings", c.api.grimmoryClient) { signedIn ->
                if (signedIn) authStep("Signed in (token still valid)") { c.api.grimmory().continueReading(1) } else null
            }
            SHELFMARK -> probe(target, ApiProvider.normalize(server.shelfmarkUrl), "api/status", c.api.shelfmarkHttp) {
                val sm = c.api.shelfmark()
                if (sm != null && server.shelfmarkAuth != "none") authStep("Signed in (session or key accepted)") { sm.authCheck() } else null
            }
            OPDS -> probe(target, app.opdsUrl.trim(), "", c.opds.client) { null }
            else -> probe(target, HARDCOVER_ENDPOINT, "", hardcoverClient) { null }
        }
    }

    private suspend fun authStep(name: String, call: suspend () -> Unit): ProbeStep {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return try {
            call(); ProbeStep(name, true, "yes", ms())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            ProbeStep(name, false, "HTTP ${e.code()}: ${hint(e)}", ms())
        } catch (e: Throwable) {
            ProbeStep(name, false, "${NetworkLogInterceptor.describe(e)} (${hint(e)})", ms())
        }
    }

    private suspend fun probe(
        target: String,
        address: String,
        path: String,
        client: OkHttpClient,
        extra: suspend (signedIn: Boolean) -> ProbeStep?,
    ): ProbeResult {
        val steps = mutableListOf<ProbeStep>()
        val withScheme = if (address.startsWith("http")) address else "https://$address"
        val base = withScheme.toHttpUrlOrNull()
        if (base == null) {
            steps += ProbeStep("Address is valid", false, "not a web address")
            return ProbeResult(target, null, steps)
        }
        val kind = HostMasker.kind(base.host)
        steps += ProbeStep("Address", true, "${base.scheme}, port ${base.port}, $kind")
        if (base.scheme == "http" && kind == "public domain") steps += ProbeStep("Encrypted (https)", false, "plain http to a public address; Android may block this")

        // 1. Name lookup
        var addresses: List<InetAddress> = emptyList()
        val dnsStart = System.nanoTime()
        val dns = withTimeoutOrNull(8_000) { runCatching { InetAddress.getAllByName(base.host).toList() } }
        val dnsMs = (System.nanoTime() - dnsStart) / 1_000_000
        when {
            dns == null -> steps += ProbeStep("Name lookup", false, "timed out after 8 s", dnsMs)
            dns.isFailure -> steps += ProbeStep("Name lookup", false, "${dns.exceptionOrNull()?.javaClass?.simpleName}: ${hint(dns.exceptionOrNull()!!)}", dnsMs)
            else -> {
                addresses = dns.getOrThrow()
                val families = addresses.map { if (it is java.net.Inet6Address) "IPv6" else "IPv4" }.distinct().joinToString("+")
                steps += ProbeStep("Name lookup", true, "${addresses.size} address(es), $families", dnsMs)
            }
        }
        if (addresses.isEmpty()) return ProbeResult(target, base.host, steps)

        // 2. TCP connection
        val tcpStart = System.nanoTime()
        val tcp = runCatching { Socket().use { it.connect(InetSocketAddress(addresses.first(), base.port), 6_000) } }
        val tcpMs = (System.nanoTime() - tcpStart) / 1_000_000
        steps += if (tcp.isSuccess) ProbeStep("Connection to port ${base.port}", true, "connected", tcpMs)
        else ProbeStep("Connection to port ${base.port}", false, "${tcp.exceptionOrNull()?.javaClass?.simpleName}: ${hint(tcp.exceptionOrNull()!!)}", tcpMs)
        if (tcp.isFailure) return ProbeResult(target, base.host, steps)

        // 3. A real request through the app's own client (so it uses the same certificates, proxy and login)
        val url = if (path.isBlank()) base else base.newBuilder().addPathSegments(path).build()
        val t0 = System.nanoTime()
        val http = runCatching { client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).get().build()).execute().use { it.code to it } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        http.fold(
            onSuccess = { (code, r) ->
                val tls = r.handshake?.tlsVersion?.javaName?.let { ", $it" }.orEmpty()
                val note = when {
                    code < 400 -> "answered"
                    code in 401..403 && target == HARDCOVER -> "answered (reachable; the key is not checked here)"
                    code == 401 || code == 403 -> "reachable, but it wants a sign-in"
                    else -> hintForCode(code)
                }
                steps += ProbeStep("Request answered", code < 400 || (code in 400..499 && target == HARDCOVER) || code == 401 || code == 403, "HTTP $code, ${r.protocol}$tls: $note", ms)
            },
            onFailure = { e -> steps += ProbeStep("Request answered", false, "${NetworkLogInterceptor.describe(e)} (${hint(e)})", ms) },
        )
        if (http.isFailure) return ProbeResult(target, base.host, steps)
        extra(c.tokens.accessToken != null)?.let { steps += it }
        return ProbeResult(target, base.host, steps)
    }

    private val hardcoverClient: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).addInterceptor(NetworkLogInterceptor("Hardcover")).build()
    }

    companion object {
        const val GRIMMORY = "Grimmory"
        const val SHELFMARK = "Shelfmark"
        const val OPDS = "OPDS"
        const val HARDCOVER = "Hardcover"

        /** A short plain-language reason for an error; English on purpose, it ends up in bug reports. */
        fun hint(e: Throwable): String = when {
            e is HttpException -> hintForCode(e.code())
            e is UnknownHostException -> "the name does not resolve: typo, no DNS for this name here (VPN/Tailscale/local DNS), or no internet"
            e is SocketTimeoutException -> "no answer in time: server off, firewall, wrong port, or a very slow link"
            e is SSLHandshakeException || e is SSLPeerUnverifiedException || e.cause is java.security.cert.CertificateException ->
                "the certificate was not accepted: self-signed, expired, or the name does not match; trust it in the app if it is yours"
            e is java.net.ConnectException -> "connection refused or no route: server off, wrong port, or on another network"
            e.message?.contains("CLEARTEXT", ignoreCase = true) == true -> "Android blocks plain http here; use https"
            e is java.io.IOException && e.message?.contains("network", ignoreCase = true) == true -> "the phone reports no network"
            else -> e.javaClass.simpleName
        }

        fun hintForCode(code: Int): String = when (code) {
            400 -> "the server did not understand the request"
            401 -> "not signed in or the token expired"
            403 -> "signed in, but not allowed"
            404 -> "not found: wrong address, a path prefix, or a different app on that port"
            408, 504 -> "the server or a proxy in front of it timed out"
            429 -> "too many requests"
            502, 503 -> "the server or reverse proxy is down or still starting"
            530 -> "a tunnel/proxy cannot reach the server behind it"
            in 500..599 -> "server error"
            else -> "unexpected answer"
        }
    }
}

/** What goes into a bug report next to the request list. Plain data so the report can be tested without a phone. */
data class DiagnosticsSnapshot(
    val appVersion: String,
    val build: String,
    val device: String,
    val android: String,
    val locale: String,
    val screen: String,
    val network: String,
    val setup: List<String>,
    val servers: List<ServerInfo>,
)

data class ServerInfo(val name: String, val scheme: String, val host: String, val port: Int)

object DiagnosticsSnapshots {
    fun capture(context: Context, c: AppContainer, windowDescription: String): DiagnosticsSnapshot {
        val app = c.settings.app.value
        val server = c.settings.server.value
        val cfg = context.resources.configuration
        val servers = buildList {
            fun add(name: String, raw: String) = raw.takeIf { it.isNotBlank() }?.let { ApiProvider.normalize(it) }?.toHttpUrlOrNull()?.let { add(ServerInfo(name, it.scheme, it.host, it.port)) }
            add("Grimmory", server.grimmoryUrl); add("Shelfmark", server.shelfmarkUrl)
            app.opdsUrl.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()?.let { add(ServerInfo("OPDS", it.scheme, it.host, it.port)) }
        }
        val sources = app.librarySources.joinToString("+") { it.name.lowercase() }
        val setup = listOf(
            "Mode: " + when { app.demo -> "demo"; app.localOnly -> "this device only"; else -> "server" } + ", library sources: $sources",
            "Grimmory: ${if (server.grimmoryUrl.isBlank()) "not set" else "set"}, signed in: ${yn(c.tokens.accessToken != null)}, username filled in: ${yn(server.username.isNotBlank())}",
            "Shelfmark: ${if (server.shelfmarkUrl.isBlank()) "not set" else "set, sign-in method ${server.shelfmarkAuth.ifBlank { "unknown" }}"}",
            "OPDS: ${if (app.opdsUrl.isBlank()) "not set" else "set, login filled in: ${yn(app.opdsUsername.isNotBlank())}"}",
            "Hardcover key present: ${yn(c.tokens.hardcoverKey != null)}",
            "Self-signed certificates trusted by the user: ${server.trustedCerts.size}",
        )
        return DiagnosticsSnapshot(
            appVersion = BuildConfig.VERSION_NAME,
            build = "${BuildConfig.APPLICATION_ID}, ${if (BuildConfig.DEBUG) "debug" else "release"}",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            locale = java.util.Locale.getDefault().toLanguageTag(),
            screen = "$windowDescription, ${cfg.densityDpi} dpi, font scale ${"%.2f".format(cfg.fontScale)}, " +
                (if (cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait") +
                (if (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) ", dark" else ", light"),
            network = network(context),
            setup = setup,
            servers = servers,
        )
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"

    private fun network(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val net = cm.activeNetwork ?: return "no active network"
        val caps = cm.getNetworkCapabilities(net) ?: return "no capabilities"
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile data")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }.ifEmpty { listOf("other") }
        val dns = cm.getLinkProperties(net)?.let { if (it.isPrivateDnsActive) ", private DNS on" else "" }.orEmpty()
        return transports.joinToString("+") + ", internet: ${yn(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))}" +
            ", validated: ${yn(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))}" +
            ", metered: ${yn(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))}$dns"
    }
}

/** Builds the text a user sends to the developer. */
object DiagnosticsReport {
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

    fun render(
        snapshot: DiagnosticsSnapshot,
        probes: List<ProbeResult>,
        entries: List<NetEntry>,
        images: NetworkLog.ImageStats,
        hideAddresses: Boolean,
        now: Long = System.currentTimeMillis(),
    ): String {
        val hosts = snapshot.servers.map { it.host }.toSet() + entries.map { it.host } + probes.mapNotNull { it.host }
        val mask = HostMasker(hosts.filter { !isPublicService(it) }.toSet(), hideAddresses)
        fun h(host: String) = if (isPublicService(host)) host else mask.host(host)
        return buildString {
            appendLine("# Grimreader connection report")
            appendLine("Created ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT).format(now)}")
            appendLine("Contains no passwords, tokens, keys, cookies, request headers or bodies, book titles or search terms.")
            appendLine(if (hideAddresses) "Server addresses are replaced by host-N (and what kind of address it is)." else "Server addresses are included.")
            appendLine()
            appendLine("## App and phone")
            appendLine("- App ${snapshot.appVersion} (${snapshot.build})")
            appendLine("- ${snapshot.device}, ${snapshot.android}, ${snapshot.locale}")
            appendLine("- Screen: ${snapshot.screen}")
            appendLine("- Network: ${snapshot.network}")
            appendLine()
            appendLine("## Setup")
            snapshot.setup.forEach { appendLine("- $it") }
            snapshot.servers.forEach { appendLine("- ${it.name} address: ${it.scheme}://${h(it.host)}:${it.port} (${HostMasker.kind(it.host)})") }
            if (probes.isNotEmpty()) {
                appendLine()
                appendLine("## Connection test")
                probes.forEach { p ->
                    appendLine("### ${p.target}${p.host?.let { " (${if (isPublicService(it)) it else h(it)})" }.orEmpty()}: ${if (p.ok) "OK" else "PROBLEM"}")
                    p.steps.forEach { s -> appendLine("- ${mark(s.ok)} ${s.name}: ${mask.apply(s.detail)}${if (s.millis > 0) " [${s.millis} ms]" else ""}") }
                }
            }
            appendLine()
            appendLine("## Latest ${entries.size} requests (newest first)")
            if (entries.isEmpty()) appendLine("(none yet)") else {
                appendLine("time | source | request | result | ms")
                entries.forEach { e ->
                    val port = if (e.port == (if (e.scheme == "https") 443 else 80)) "" else ":${e.port}"
                    val result = e.error?.let { "FAILED " + mask.apply(it) } ?: "HTTP ${e.code}" + listOfNotNull(e.protocol, e.tls).takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty() + if (e.retried) " after sign-in retry" else ""
                    appendLine("${clock.format(e.time)} | ${e.source} | ${e.method} ${e.scheme}://${h(e.host)}$port${e.path} | $result | ${e.millis}")
                }
            }
            appendLine()
            appendLine("Images (covers), not listed above: ${images.ok} loaded, ${images.failed} failed" + (images.lastError?.let { "; last error: ${mask.apply(it)}" } ?: ""))
        }
    }

    private fun mark(ok: Boolean?) = when (ok) { true -> "[ok]"; false -> "[FAIL]"; null -> "[ - ]" }

    /** Public services (Hardcover, Open Library, ...) are the same for everyone and not worth hiding. */
    private fun isPublicService(host: String) = host.endsWith("hardcover.app") || host.endsWith("openlibrary.org") || host.endsWith("gutendex.com") || host.endsWith("huggingface.co")
}
