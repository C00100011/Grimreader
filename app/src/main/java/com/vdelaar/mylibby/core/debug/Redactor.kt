package com.vdelaar.mylibby.core.debug

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Makes addresses and messages safe to show in a bug report: no passwords, tokens, API keys, search terms or e-mail
 * addresses. The request log never stores headers or bodies at all; this only cleans what is kept (address and error text).
 */
object Redactor {
    /** Query values that are harmless and useful to a developer (paging, sorting). Everything else becomes `…`. */
    private val SAFE_QUERY_KEYS = setOf("page", "size", "limit", "offset", "sort", "order", "dir", "v", "type", "format", "status")
    private val NUMBER = Regex("""-?\d{1,10}(\.\d{1,3})?""")
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>)\]]+""")
    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val KEY_VALUE = Regex("""(?i)\b(password|passwd|token|secret|api[_-]?key|authorization|cookie|session)\b(["']?\s*[:=]\s*["']?)[^\s"',;&]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val LONG_TOKEN = Regex("""\b(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}\b""")
    private val HEX_TOKEN = Regex("""\b[0-9a-fA-F]{16,}\b""")

    /** A path segment that looks like a secret (token, hash, uuid, e-mail) rather than a name or number. */
    fun looksSecret(segment: String): Boolean {
        if (segment.isEmpty()) return false
        if (segment.contains('@')) return true
        if (segment.length >= 20 && LONG_TOKEN.matches(segment)) return true
        if (segment.length >= 16 && HEX_TOKEN.matches(segment)) return true
        if (Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""").matches(segment)) return true
        return false
    }

    fun path(url: HttpUrl): String {
        val segs = url.pathSegments.map { if (looksSecret(it)) "{token}" else it }
        return "/" + segs.joinToString("/")
    }

    fun query(url: HttpUrl): String {
        if (url.querySize == 0) return ""
        return url.queryParameterNames.joinToString("&", prefix = "?") { name ->
            val values = url.queryParameterValues(name)
            val shown = values.joinToString(",") { v ->
                when {
                    v == null -> ""
                    name.lowercase() in SAFE_QUERY_KEYS && v.length <= 24 && !looksSecret(v) -> v
                    NUMBER.matches(v) -> v
                    else -> "…"
                }
            }
            "$name=$shown"
        }
    }

    /** `https://host:port/path?key=…`: no user info, no fragment, secrets in path and query removed. */
    fun url(url: HttpUrl): String {
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port${path(url)}${query(url)}"
    }

    fun url(raw: String): String = raw.toHttpUrlOrNull()?.let(::url) ?: text(raw)

    /** Cleans free text such as an exception message. */
    fun text(input: String?, maxLength: Int = 300): String {
        if (input.isNullOrBlank()) return ""
        var s = input
        s = URL_IN_TEXT.replace(s) { url(it.value.trimEnd('.', ',', ';', ':')) }
        s = BEARER.replace(s) { "${it.groupValues[1]} …" }
        s = KEY_VALUE.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}…" }
        s = EMAIL.replace(s, "{email}")
        s = LONG_TOKEN.replace(s, "{token}")
        s = HEX_TOKEN.replace(s, "{token}")
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.length > maxLength) s.take(maxLength) + "…" else s
    }
}

/**
 * Optionally hides the addresses of the user's own servers in an export. A masked address keeps what helps a developer
 * (is it an IP address, a `.local` name, a Tailscale/VPN name, a public domain) but not the name itself.
 */
class HostMasker(private val hosts: Set<String>, private val enabled: Boolean) {
    private val names = hosts.sorted().withIndex().associate { (i, h) -> h to label(h, i + 1) }

    fun host(host: String): String = if (!enabled) host else names[host] ?: label(host, names.size + 1)

    fun apply(text: String): String {
        if (!enabled) return text
        var out = text
        // Longest first so "books.example.com" goes before "example.com".
        for (h in hosts.sortedByDescending { it.length }) out = out.replace(h, host(h), ignoreCase = true)
        return out
    }

    companion object {
        /** What kind of address this is, without saying which one. */
        fun kind(host: String): String {
            val h = host.lowercase()
            val v4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(h)
            return when {
                h == "localhost" || h.startsWith("127.") -> "loopback"
                v4 != null -> {
                    val a = v4.groupValues[1].toInt(); val b = v4.groupValues[2].toInt()
                    when {
                        a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) -> "private IPv4"
                        a == 100 && b in 64..127 -> "CGNAT/Tailscale IPv4"
                        else -> "public IPv4"
                    }
                }
                h.contains(':') -> "IPv6"
                h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal") -> "local name"
                h.endsWith(".ts.net") -> "Tailscale name"
                !h.contains('.') -> "single-label name"
                else -> "public domain"
            }
        }

        private fun label(host: String, n: Int) = "host-$n (${kind(host)})"
    }
}
