package com.vdelaar.mylibby.debug

import com.vdelaar.mylibby.core.debug.DiagnosticsReport
import com.vdelaar.mylibby.core.debug.DiagnosticsSnapshot
import com.vdelaar.mylibby.core.debug.HostMasker
import com.vdelaar.mylibby.core.debug.NetEntry
import com.vdelaar.mylibby.core.debug.NetworkLog
import com.vdelaar.mylibby.core.debug.NetworkLogInterceptor
import com.vdelaar.mylibby.core.debug.ProbeResult
import com.vdelaar.mylibby.core.debug.ProbeStep
import com.vdelaar.mylibby.core.debug.Redactor
import com.vdelaar.mylibby.core.debug.ServerInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class DiagnosticsTest {
    @Before fun reset() = NetworkLog.clear()

    // ---- redaction ----

    @Test fun urlLosesCredentialsQueryValuesAndFragment() {
        val s = Redactor.url("https://user:hunter2@books.example.com:8443/api/v1/search?q=my+secret+book&apikey=abc123&page=2#frag".toHttpUrl())
        assertEquals("https://books.example.com:8443/api/v1/search?q=…&apikey=…&page=2", s)
        assertFalse("hunter2" in s || "secret" in s || "abc123" in s)
    }

    @Test fun tokensInThePathAreHidden_numbersAndNamesStay() {
        val s = Redactor.url("https://h.example/opds/9f8e7d6c5b4a39281706f5e4d3c2b1a0/books/42/cover".toHttpUrl())
        assertEquals("https://h.example/opds/{token}/books/42/cover", s)
        assertEquals("/api/v1/app/books/7", Redactor.path("https://h/api/v1/app/books/7".toHttpUrl()))
    }

    @Test fun errorTextLosesUrlsTokensAndEmails() {
        val t = Redactor.text("Failed to connect to https://me:pw@x.example/opds/aaaaaaaaaaaaaaaaaaaa1111?token=zzz for jan@example.com with Bearer eyJhbGciOiJIUzI1NiJ9.abc.def and password=hunter2")
        assertFalse(t, "pw@" in t || "jan@" in t || "eyJ" in t || "hunter2" in t || "zzz" in t)
        assertTrue(t, "x.example" in t)
    }

    // ---- masking ----

    @Test fun hostMaskerKeepsTheKindButNotTheName() {
        val m = HostMasker(setOf("books.example.com", "192.168.1.20"), enabled = true)
        assertEquals("public domain", HostMasker.kind("books.example.com"))
        assertEquals("private IPv4", HostMasker.kind("192.168.1.20"))
        assertEquals("CGNAT/Tailscale IPv4", HostMasker.kind("100.101.102.103"))
        assertEquals("local name", HostMasker.kind("nas.local"))
        val out = m.apply("Unable to resolve host \"books.example.com\" and 192.168.1.20")
        assertFalse(out, "books.example.com" in out || "192.168.1.20" in out)
        assertTrue(out, "host-" in out)
        assertEquals("books.example.com", HostMasker(setOf("books.example.com"), enabled = false).host("books.example.com"))
    }

    // ---- request log ----

    private fun client(answer: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor(NetworkLogInterceptor("Grimmory"))
        .addInterceptor { answer(it.request()) }
        .build()

    private fun reply(req: Request, code: Int) = Response.Builder().request(req).protocol(Protocol.HTTP_2).code(code).message("x").body("".toResponseBody()).build()

    @Test fun successAndFailureAreRecordedWithoutHeadersOrBodies() {
        val ok = client { reply(it, 200) }
        ok.newCall(Request.Builder().url("https://h.example/api/v1/app/books?size=1&q=secret").header("Authorization", "Bearer SECRETTOKEN123").build()).execute().close()
        val bad = client { throw UnknownHostException("Unable to resolve host \"h.example\"") }
        try { bad.newCall(Request.Builder().url("https://h.example/api/v1/public-settings").build()).execute() } catch (_: IOException) {}
        val e = NetworkLog.entries.value
        assertEquals(2, e.size)
        assertEquals("newest first", "/api/v1/public-settings", e[0].path)
        assertTrue(e[0].error!!.startsWith("UnknownHostException"))
        assertEquals(200, e[1].code)
        assertEquals("/api/v1/app/books?size=1&q=…", e[1].path)
        assertFalse(e.toString().contains("SECRETTOKEN123"))
    }

    @Test fun keepsOnlyTheLatest100_andCountsCoversInstead() {
        val c = client { reply(it, 200) }
        repeat(130) { c.newCall(Request.Builder().url("https://h.example/api/v1/app/books/$it").build()).execute().close() }
        repeat(50) { c.newCall(Request.Builder().url("https://h.example/api/v1/media/book/$it/cover").build()).execute().close() }
        assertEquals(100, NetworkLog.entries.value.size)
        assertEquals("/api/v1/app/books/129", NetworkLog.entries.value.first().path)
        assertEquals(50, NetworkLog.images.value.ok)
    }

    // ---- report ----

    private val snapshot = DiagnosticsSnapshot(
        "0.10.2", "com.vdelaar.mylibby, release", "Google Pixel", "Android 16 (API 36)", "nl-NL", "phone, 411x914 dp", "Wi-Fi, internet: yes",
        listOf("Mode: server", "Grimmory: set, signed in: yes, username filled in: yes"), listOf(ServerInfo("Grimmory", "https", "books.example.com", 443)),
    )

    @Test fun reportHidesServerAddressesAndNeverContainsSecrets() {
        val entry = NetEntry(1, 0, "Grimmory", "GET", "https", "books.example.com", 443, "/api/v1/public-settings", null, 120, null, null, false, "UnknownHostException: Unable to resolve host \"books.example.com\"")
        val probe = ProbeResult("Grimmory", "books.example.com", listOf(ProbeStep("Name lookup", false, "UnknownHostException: no DNS for books.example.com", 40)))
        val text = DiagnosticsReport.render(snapshot, listOf(probe), listOf(entry), NetworkLog.ImageStats(3, 1, "timeout"), hideAddresses = true)
        assertFalse(text, "books.example.com" in text)
        assertTrue(text, "host-1 (public domain)" in text)
        assertTrue(text, "UnknownHostException" in text && "Name lookup" in text && "PROBLEM" in text)
        val open = DiagnosticsReport.render(snapshot, listOf(probe), listOf(entry), NetworkLog.ImageStats(), hideAddresses = false)
        assertTrue(open, "books.example.com" in open)
    }
}
