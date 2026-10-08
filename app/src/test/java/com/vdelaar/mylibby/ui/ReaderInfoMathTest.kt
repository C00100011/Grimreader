package com.vdelaar.mylibby.ui

import com.vdelaar.mylibby.core.datastore.InfoItem
import com.vdelaar.mylibby.core.datastore.ProgressScope
import com.vdelaar.mylibby.core.datastore.ReaderSettings
import com.vdelaar.mylibby.ui.reader.BridgeJson
import com.vdelaar.mylibby.ui.reader.ReaderInfoMath
import com.vdelaar.mylibby.ui.reader.RelocateEvent
import com.vdelaar.mylibby.ui.reader.usesClockOrBattery
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInfoMathTest {
    private fun at(page: Int, pages: Int) = RelocateEvent(pageInSection = page, pagesInSection = pages)

    @Test fun chapterSliderSpansFirstToLastPage() {
        assertEquals(0f, ReaderInfoMath.chapterSliderFraction(at(1, 14))!!, 1e-6f)
        assertEquals(1f, ReaderInfoMath.chapterSliderFraction(at(14, 14))!!, 1e-6f)
        assertEquals(0.5f, ReaderInfoMath.chapterSliderFraction(at(8, 15))!!, 1e-6f)
    }

    @Test fun oneLongPageAndUnknownCounts() {
        assertEquals(0f, ReaderInfoMath.chapterSliderFraction(at(1, 1))!!, 1e-6f)
        assertNull(ReaderInfoMath.chapterSliderFraction(at(0, 0)))
        assertNull(ReaderInfoMath.chapterSliderFraction(null))
        assertNull(ReaderInfoMath.chapterPercent(null))
    }

    @Test fun chapterPercentCountsTheCurrentPageAsRead() {
        assertEquals(100, ReaderInfoMath.chapterPercent(at(14, 14)))
        assertEquals(21, ReaderInfoMath.chapterPercent(at(3, 14)))
        assertEquals(100, ReaderInfoMath.chapterPercent(at(1, 1)))
    }

    @Test fun sliderPositionPointsAtAPage() {
        assertEquals(1, ReaderInfoMath.pageAt(0f, 14))
        assertEquals(14, ReaderInfoMath.pageAt(1f, 14))
        assertEquals(8, ReaderInfoMath.pageAt(0.5f, 15))
        assertEquals(1, ReaderInfoMath.pageAt(0.7f, 1))
        // dragging past the ends stays on a real page
        assertEquals(1, ReaderInfoMath.pageAt(-3f, 14))
        assertEquals(14, ReaderInfoMath.pageAt(9f, 14))
    }

    @Test fun defaultsKeepTheOriginalLook() {
        val d = ReaderSettings()
        assertEquals(InfoItem.CHAPTER_TITLE, d.headerCenter)
        assertEquals(InfoItem.NONE, d.headerLeft)
        assertEquals(InfoItem.NONE, d.headerRight)
        assertEquals(InfoItem.TIME_LEFT_CHAPTER, d.footerLeft)
        assertEquals(InfoItem.PERCENT_BOOK, d.footerRight)
        assertEquals(ProgressScope.BOOK, d.progressScope)
        assertFalse(d.usesClockOrBattery)
        assertTrue(d.copy(footerCenter = InfoItem.CLOCK).usesClockOrBattery)
    }

    @Test fun oldStoredSettingsStillLoadWithDefaults() {
        // Settings saved before the header/footer options existed must keep working.
        val old = """{"fontSizePx":21,"justify":false}"""
        val s = com.vdelaar.mylibby.core.network.AppJson.decodeFromString(ReaderSettings.serializer(), old)
        assertEquals(21, s.fontSizePx)
        assertEquals(InfoItem.CHAPTER_TITLE, s.headerCenter)
        val round = com.vdelaar.mylibby.core.network.AppJson.decodeFromString(ReaderSettings.serializer(), com.vdelaar.mylibby.core.network.AppJson.encodeToString(s.copy(headerLeft = InfoItem.CLOCK)))
        assertEquals(InfoItem.CLOCK, round.headerLeft)
    }

    @Test fun newRelocateFieldsAreOptionalInTheBridgeJson() {
        val oldEvent = BridgeJson.decodeFromString(RelocateEvent.serializer(), """{"cfi":"x","fraction":0.4}""")
        assertEquals(0, oldEvent.pagesInSection)
        val ev = BridgeJson.decodeFromString(RelocateEvent.serializer(), """{"fraction":0.4,"pageInSection":3,"pagesInSection":14,"bookPage":112,"bookPages":540}""")
        assertEquals(112, ev.bookPage)
        assertEquals(540, ev.bookPages)
    }
}
