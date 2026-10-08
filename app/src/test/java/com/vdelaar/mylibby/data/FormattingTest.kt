package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.network.ApiProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun durations() {
        assertEquals("45s", formatDuration(45))
        assertEquals("2m 5s", formatDuration(125))
        assertEquals("1h 1m", formatDuration(3660))
        assertEquals("<1m", formatShortDuration(30))
        assertEquals("12m", formatShortDuration(720))
        assertEquals("3h 5m", formatShortDuration(3 * 3600 + 300))
    }

    @Test
    fun minutes() {
        assertEquals("< 1 min", formatMinutes(0.0))
        assertEquals("1 min", formatMinutes(0.4))
        assertEquals("12 min", formatMinutes(12.7))
        assertEquals("2 h", formatMinutes(120.0))
        assertEquals("2 h 5 min", formatMinutes(125.0))
    }

    @Test
    fun serverAddressNormalisation() {
        assertEquals("https://books.example.com/", ApiProvider.normalize("books.example.com"))
        assertEquals("http://192.168.1.10:6060/", ApiProvider.normalize("http://192.168.1.10:6060"))
        assertEquals("https://x.nl/grimmory/", ApiProvider.normalize(" https://x.nl/grimmory/ "))
        assertEquals("", ApiProvider.normalize("  "))
    }
}
