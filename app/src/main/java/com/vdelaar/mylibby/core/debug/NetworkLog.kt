package com.vdelaar.mylibby.core.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** One finished (or failed) network request, kept only in memory. Never holds headers or bodies. */
data class NetEntry(
    val id: Long,
    val time: Long,
    /** Which part of the app asked: Grimmory, Shelfmark, OPDS, Hardcover, Open Library, Voices, Sign-in, Other. */
    val source: String,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    /** Path and query with secrets already removed by [Redactor]. */
    val path: String,
    val code: Int?,
    val millis: Long,
    val protocol: String?,
    val tls: String?,
    val retried: Boolean,
    /** Exception class and cleaned message when no answer came back. */
    val error: String?,
) {
    val ok: Boolean get() = error == null && code != null && code < 400
}

/**
 * The last [CAPACITY] requests the app made, for the connection diagnostics screen and bug reports. Lives in memory only:
 * gone when the app is closed. Image (cover) requests are counted instead of listed, so a library full of covers does not
 * push out the requests that matter.
 */
object NetworkLog {
    const val CAPACITY = 100

    private val _entries = MutableStateFlow<List<NetEntry>>(emptyList())
    val entries: StateFlow<List<NetEntry>> = _entries.asStateFlow()

    data class ImageStats(val ok: Int = 0, val failed: Int = 0, val lastError: String? = null, val lastCode: Int? = null)

    private val _images = MutableStateFlow(ImageStats())
    val images: StateFlow<ImageStats> = _images.asStateFlow()

    /** Results of the last connection test; kept here so they survive rotating or folding the phone. */
    val probes = MutableStateFlow<List<ProbeResult>>(emptyList())

    private var nextId = 1L
    private val lock = Any()

    fun record(entry: NetEntry) {
        synchronized(lock) {
            _entries.update { (listOf(entry.copy(id = nextId++)) + it).take(CAPACITY) }
        }
    }

    fun recordImage(ok: Boolean, code: Int?, error: String?) = _images.update {
        if (ok) it.copy(ok = it.ok + 1) else it.copy(failed = it.failed + 1, lastError = error ?: it.lastError, lastCode = code ?: it.lastCode)
    }

    fun clear() {
        _entries.value = emptyList()
        _images.value = ImageStats()
        probes.value = emptyList()
    }

    /** Every host that appears in the log; the export can hide these. */
    fun hosts(): Set<String> = _entries.value.map { it.host }.toSet()

    fun isImage(url: HttpUrl): Boolean {
        val p = url.encodedPath.lowercase()
        return "/media/book/" in p || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp") || p.endsWith("/cover") || p.endsWith("/thumbnail")
    }
}

/** Records every call of the client it is added to. Add it before any interceptor that can throw, so those failures show up too. */
class NetworkLogInterceptor(private val sourceOf: (HttpUrl) -> String) : Interceptor {
    constructor(source: String) : this({ source })

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        val start = System.nanoTime()
        fun millis() = (System.nanoTime() - start) / 1_000_000
        val image = req.method == "GET" && NetworkLog.isImage(url)
        try {
            val resp = chain.proceed(req)
            val ok = resp.code < 400
            if (image) NetworkLog.recordImage(ok, resp.code, if (ok) null else "HTTP ${resp.code}")
            else NetworkLog.record(
                NetEntry(
                    0, System.currentTimeMillis(), sourceOf(url), req.method, url.scheme, url.host, url.port, Redactor.path(url) + Redactor.query(url),
                    resp.code, millis(), resp.protocol.toString(), resp.handshake?.tlsVersion?.javaName, resp.priorResponse != null, null,
                )
            )
            return resp
        } catch (e: IOException) {
            val msg = describe(e)
            if (image) NetworkLog.recordImage(false, null, msg)
            else NetworkLog.record(
                NetEntry(0, System.currentTimeMillis(), sourceOf(url), req.method, url.scheme, url.host, url.port, Redactor.path(url) + Redactor.query(url), null, millis(), null, null, false, msg)
            )
            throw e
        }
    }

    companion object {
        fun describe(e: Throwable): String {
            val text = Redactor.text(e.message)
            return if (text.isEmpty()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $text"
        }
    }
}
