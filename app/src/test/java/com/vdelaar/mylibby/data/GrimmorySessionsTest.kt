package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.ReadingSessionEntity
import com.vdelaar.mylibby.core.network.AppJson
import com.vdelaar.mylibby.core.network.ReadingSessionDto
import com.vdelaar.mylibby.core.network.SessionPageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GrimmorySessionsTest {
    private fun dto(start: String?, end: String? = null, seconds: Int? = 60, type: String? = "EPUB", title: String? = "T", sp: Float? = null, ep: Float? = null) =
        ReadingSessionDto(startTime = start, endTime = end, durationSeconds = seconds, bookType = type, bookTitle = title, startProgress = sp, endProgress = ep)

    private val a = "2026-05-21T21:36:38Z"
    private val aMs = Instant.parse(a).toEpochMilli()

    @Test fun convertsASession() {
        val s = sessionsFromGrimmory(listOf(dto(a, "2026-05-21T22:08:50Z", 1932, sp = 10f, ep = 12.5f)), 3, "Fallback", emptyList()).single()
        assertEquals(3L, s.bookId); assertEquals("T", s.bookTitle); assertEquals("EPUB", s.bookType)
        assertEquals(aMs, s.startTime); assertEquals(Instant.parse("2026-05-21T22:08:50Z").toEpochMilli(), s.endTime)
        assertEquals(1932, s.durationSeconds); assertEquals(10f, s.startProgress, 0f); assertEquals(12.5f, s.endProgress, 0f)
        assertEquals(ReadingSessionEntity.SOURCE_GRIMMORY, s.source); assertTrue(s.synced); assertFalse(s.listening)
        assertEquals(0, s.pagesTurned); assertEquals(0L, s.bytesRead)
    }

    @Test fun missingProgressBecomesZeroAndTitleFallsBack() {
        val s = sessionsFromGrimmory(listOf(dto(a, title = null)), 3, "Fallback", emptyList()).single()
        assertEquals("Fallback", s.bookTitle); assertEquals(0f, s.startProgress, 0f); assertEquals(0f, s.endProgress, 0f)
        assertEquals("", sessionsFromGrimmory(listOf(dto(a, title = null)), 3, null, emptyList()).single().bookTitle)
    }

    @Test fun endTimeIsDerivedFromTheDurationWhenMissing() {
        assertEquals(aMs + 90_000, sessionsFromGrimmory(listOf(dto(a, null, 90)), 1, null, emptyList()).single().endTime)
    }

    @Test fun durationIsDerivedFromTheTimesWhenMissing() {
        assertEquals(120, sessionsFromGrimmory(listOf(dto(a, "2026-05-21T21:38:38Z", null)), 1, null, emptyList()).single().durationSeconds)
    }

    @Test fun sessionsWeAlreadyHaveAreSkipped() {
        assertTrue(sessionsFromGrimmory(listOf(dto(a)), 1, null, listOf(aMs + 2000)).isEmpty())
        assertEquals(1, sessionsFromGrimmory(listOf(dto(a)), 1, null, listOf(aMs + 6000)).size)
    }

    @Test fun theSameSessionTwiceInOnePageCountsOnce() {
        assertEquals(1, sessionsFromGrimmory(listOf(dto(a), dto(a)), 1, null, emptyList()).size)
    }

    @Test fun unusableSessionsAreLeftOut() {
        assertTrue(sessionsFromGrimmory(listOf(dto(null), dto("not a date"), dto(a, seconds = 0), dto(a, seconds = -5), dto("", seconds = 10)), 1, null, emptyList()).isEmpty())
        assertTrue(sessionsFromGrimmory(listOf(dto(a, null, null)), 1, null, emptyList()).isEmpty()) // no duration and no end time
    }

    @Test fun anAudiobookIsListening() {
        assertTrue(sessionsFromGrimmory(listOf(dto(a, type = "AUDIOBOOK")), 1, null, emptyList()).single().listening)
        assertTrue(sessionsFromGrimmory(listOf(dto(a, type = "audiobook")), 1, null, emptyList()).single().listening)
        assertFalse(sessionsFromGrimmory(listOf(dto(a, type = null)), 1, null, emptyList()).single().listening)
    }

    // The answer as Grimmory sends it ------------------------------------------------------------------------------

    @Test fun readsSpringsPagedModelWithoutLastFlag() {
        val json = """{"content":[{"id":10,"bookId":3,"bookTitle":"De waarheid","bookType":"EPUB","startTime":"2026-05-21T21:36:38Z","endTime":"2026-05-21T22:08:50Z","durationSeconds":1932,"startProgress":null,"endProgress":null,"progressDelta":null,"startLocation":null,"endLocation":null,"createdAt":"2026-10-25T15:10:22"}],"page":{"size":100,"number":0,"totalElements":1,"totalPages":1}}"""
        val page = AppJson.decodeFromString(SessionPageDto.serializer(), json)
        assertEquals(1, page.content.size); assertNull(page.last)
        assertEquals(1932, page.content[0].durationSeconds)
    }

    @Test fun readsSpringsClassicPageWithLastFlag() {
        val json = """{"content":[],"pageable":{"pageNumber":0},"last":true,"totalPages":0,"totalElements":0,"first":true,"empty":true,"someNewField":1}"""
        val page = AppJson.decodeFromString(SessionPageDto.serializer(), json)
        assertTrue(page.content.isEmpty()); assertEquals(true, page.last)
    }
}
