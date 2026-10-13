package com.vdelaar.mylibby.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookChatControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private fun controller(ai: LocalAi) = BookChatController(
        engine = BookChatEngine(MemoryChunks(), ai), ai = ai, bookId = 1, scope = scope,
        position = { ReadPosition(5, 0f, 40) }, bookInfo = { "Book" to "Author" }, pollMs = 10,
    )

    private suspend fun until(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; delay(10) }
        return cond()
    }

    @Test fun offeredOnlyWhenTheModelExistsAndTheBookIsAnEpub() = runBlocking {
        val none = controller(FakeLocalAi(status = AiStatus.UNAVAILABLE)).also { it.attach(TestBook.epub(tmp.newFolder())) }
        val mobi = controller(FakeLocalAi()).also { it.attach(tmp.newFile("a.mobi")) }
        val ok = controller(FakeLocalAi(status = AiStatus.DOWNLOADABLE)).also { it.attach(TestBook.epub(tmp.newFolder())) }
        assertTrue(until { ok.state.value.offered })
        delay(100)
        assertEquals(false, none.state.value.offered)
        assertEquals(false, mobi.state.value.offered)
    }

    @Test fun aSilentDownloadStillEndsInAChatThatWorks() = runBlocking {
        // AICore style: no size, no progress, no "done": only the status changes after a while.
        val c = controller(FakeLocalAi(status = AiStatus.DOWNLOADABLE, silentDownload = true, silentMs = 150))
        c.attach(TestBook.epub(tmp.newFolder()))
        assertTrue(until { c.state.value.offered })
        c.download()
        assertTrue(until { c.state.value.download != null })
        assertNull(c.state.value.download?.fraction) // shown as an unknown-length bar, not as 0%
        assertTrue("download should finish", until { c.state.value.download == null && c.state.value.status == AiStatus.AVAILABLE })
        assertTrue("notes are made once it is ready", until { c.state.value.indexTotal > 0 })
    }

    @Test fun aDownloadStartedElsewhereIsFollowedWhenTheSheetOpens() = runBlocking {
        var status = AiStatus.DOWNLOADING
        val base = FakeLocalAi()
        val ai = object : LocalAi by base { override suspend fun status() = status }
        val c = controller(ai)
        c.attach(TestBook.epub(tmp.newFolder()))
        assertTrue(until { c.state.value.offered })
        c.open()
        assertTrue(until { c.state.value.download != null })
        status = AiStatus.AVAILABLE // finished in the background
        assertTrue(until { c.state.value.download == null && c.state.value.status == AiStatus.AVAILABLE })
    }

    @Test fun aFailedDownloadShowsTheReasonAndOffersARetry() = runBlocking {
        val base = FakeLocalAi(status = AiStatus.DOWNLOADABLE)
        val ai = object : LocalAi by base {
            override fun download() = kotlinx.coroutines.flow.flowOf<AiDownload>(AiDownload.Failed("no space left"))
        }
        val c = controller(ai)
        c.attach(TestBook.epub(tmp.newFolder()))
        assertTrue(until { c.state.value.offered })
        c.download()
        assertTrue(until { c.state.value.error == "no space left" })
        // the poller then sees the model is still not there and the button comes back
        assertTrue(until { c.state.value.download == null })
        assertNotNull(c.state.value.error)
    }
}
