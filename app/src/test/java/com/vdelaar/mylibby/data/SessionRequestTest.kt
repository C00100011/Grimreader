package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.core.network.ReadingSessionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grimmory records reading-session progress with two decimals (issue #5). */
class SessionRequestTest {
    private fun session(start: Float, end: Float) = ReadingSessionEntity(
        bookId = 1, bookTitle = "T", bookType = "EPUB", startTime = 0, endTime = 60_000, durationSeconds = 60,
        startProgress = start, endProgress = end, startCfi = null, endCfi = null,
        bytesRead = 0, pagesTurned = 0, localDate = "2026-10-12",
    )

    @Test fun roundsToTwoDecimals() {
        val r = session(12.345678f, 12.717283f).toRequest()
        assertEquals(12.35f, r.startProgress!!, 0f)
        assertEquals(12.72f, r.endProgress!!, 0f)
        assertEquals(0.37f, r.progressDelta!!, 0f)
    }

    @Test fun serializedJsonHasAtMostTwoDecimals() {
        val json = AppJson.encodeToString(ReadingSessionRequest.serializer(), session(33.333332f, 34.987654f).toRequest())
        val numbers = Regex("\"(startProgress|endProgress|progressDelta)\":(-?[0-9.]+)").findAll(json).map { it.groupValues[2] }.toList()
        assertEquals(3, numbers.size)
        numbers.forEach { assertTrue("$it has too many decimals", it.substringAfter('.', "").length <= 2) }
    }

    @Test fun aKoboSessionWithoutProgressSendsNoProgress() {
        val r = session(0f, 0f).copy(source = ReadingSessionEntity.SOURCE_KOBO, pagesTurned = 12).toRequest()
        assertEquals(null, r.startProgress)
        assertEquals(null, r.endProgress)
        assertEquals(null, r.progressDelta)
        assertEquals("EPUB", r.bookType)
        val json = AppJson.encodeToString(ReadingSessionRequest.serializer(), r)
        assertTrue(json, !json.contains("Progress") || json.contains("\"startProgress\":null") || !json.contains("startProgress"))
    }

    @Test fun aSessionReadHereKeepsItsZeroProgress() {
        val r = session(0f, 0f).toRequest()
        assertEquals(0f, r.startProgress!!, 0f)
    }
}
