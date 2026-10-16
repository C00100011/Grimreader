package com.vdelaar.mylibby.core

import com.vdelaar.mylibby.core.network.BookDetailDto
import com.vdelaar.mylibby.core.network.BookProgressResponseDto
import com.vdelaar.mylibby.core.network.BookSummaryDto
import com.vdelaar.mylibby.core.network.EpubProgressDto
import com.vdelaar.mylibby.core.network.GrimmoryApi
import com.vdelaar.mylibby.core.network.KoreaderFixedApi
import com.vdelaar.mylibby.core.network.KoreaderProgressDto
import com.vdelaar.mylibby.core.network.koreaderIsFraction
import com.vdelaar.mylibby.core.network.withPercentages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Issue #4: Grimmory's /app routes hand on KOReader's fraction (0.55) as the book's progress; the app showed 0%. */
class KoreaderProgressFixTest {
    private fun progress(overall: Float?, ko: Float?, web: Float?) = BookProgressResponseDto(
        readProgress = overall,
        koreaderProgress = ko?.let { KoreaderProgressDto(percentage = it) },
        epubProgress = web?.let { EpubProgressDto(percentage = it) },
    )

    @Test fun koreaderFractionBecomesAPercentage() {
        // The real answer of Grimmory for a book at 55% in KOReader.
        assertEquals(55f, progress(0.55f, 0.55f, 55f).withPercentages().readProgress!!, 0.01f)
        assertEquals(55f, progress(0.55f, 0.55f, null).withPercentages().readProgress!!, 0.01f)
        assertEquals(100f, progress(1f, 1f, 100f).withPercentages().readProgress!!, 0.01f)
    }

    @Test fun otherSourcesAreLeftAlone() {
        assertEquals(42f, progress(42f, null, 42f).withPercentages().readProgress!!, 0f)        // no KOReader
        assertEquals(55f, progress(55f, 55f, 55f).withPercentages().readProgress!!, 0f)          // a server that already converts
        assertEquals(0.4f, progress(0.4f, null, 0.4f).withPercentages().readProgress!!, 0f)      // 0.4% in the web reader
        assertEquals(0.5f, progress(0.5f, 0.5f, 0.5f).withPercentages().readProgress!!, 0f)      // converting server, book just started
        assertEquals(null, progress(null, null, null).withPercentages().readProgress)
    }

    @Test fun theDoubtfulCaseIsDecidedByTheWebReader() {
        assertTrue(koreaderIsFraction(0.3f, 0.3f, 30f))
        assertFalse(koreaderIsFraction(0.3f, 0.3f, 0.3f))
        assertTrue(koreaderIsFraction(0.3f, 0.3f, 12f)) // the web reader is somewhere else entirely: KOReader's number is a fraction
    }

    @Test fun detailGetsTheSameTreatment() {
        val d = BookDetailDto(id = 1, readProgress = 0.4f, koreaderProgress = KoreaderProgressDto(percentage = 0.4f), epubProgress = EpubProgressDto(percentage = 40f))
        assertEquals(40f, d.withPercentages().readProgress!!, 0.01f)
    }

    @Test fun listsAreFixedByLookingAtTheBooksThatAreDoubtful() = runBlocking {
        val asked = ArrayList<Long>()
        val raw = object : GrimmoryApi by unimplementedApi {
            override suspend fun continueReading(limit: Int) = listOf(
                BookSummaryDto(id = 1, readProgress = 0.55f), // KOReader at 55%
                BookSummaryDto(id = 2, readProgress = 0.4f),  // the web reader at 0.4%
                BookSummaryDto(id = 3, readProgress = 61f),   // an ordinary percentage
                BookSummaryDto(id = 4, readProgress = null),
            )
            override suspend fun bookProgress(id: Long): BookProgressResponseDto { asked += id; return if (id == 1L) progress(0.55f, 0.55f, 55f) else progress(0.4f, null, 0.4f) }
        }
        val api = KoreaderFixedApi(raw)
        val out = api.continueReading(10)
        assertEquals(listOf(55f, 0.4f, 61f, null), out.map { it.readProgress }.map { it?.let { v -> Math.round(v * 100) / 100f } })
        assertEquals(listOf(1L, 2L), asked.sorted()) // only the doubtful ones were looked up
        api.continueReading(10)
        assertEquals(listOf(1L, 2L), asked.sorted()) // and not again
    }

    @Test fun aFailedLookupKeepsTheServersNumber() = runBlocking {
        val raw = object : GrimmoryApi by unimplementedApi {
            override suspend fun recentlyAdded(limit: Int) = listOf(BookSummaryDto(id = 1, readProgress = 0.55f))
            override suspend fun bookProgress(id: Long): BookProgressResponseDto = throw java.io.IOException("offline")
        }
        assertEquals(0.55f, KoreaderFixedApi(raw).recentlyAdded(5).single().readProgress!!, 0f)
    }
}

private val unimplementedApi: GrimmoryApi = Proxy.newProxyInstance(
    GrimmoryApi::class.java.classLoader, arrayOf(GrimmoryApi::class.java),
    InvocationHandler { _, m, _ -> throw NotImplementedError(m.name) },
) as GrimmoryApi
