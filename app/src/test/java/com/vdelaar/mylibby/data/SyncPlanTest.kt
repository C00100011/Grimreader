package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.datastore.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    @Test fun coversWaitForWifiOnMobileData() {
        assertFalse(shouldFetchCovers(forced = false, metered = true))
    }

    @Test fun coversAreFetchedOnWifi() {
        assertTrue(shouldFetchCovers(forced = false, metered = false))
    }

    @Test fun aForcedSyncFetchesCoversEvenOnMobileData() {
        assertTrue(shouldFetchCovers(forced = true, metered = true))
    }

    @Test fun nothingHasBeenSynchronisedByDefault() {
        val s = SyncSettings()
        assertEquals(0L, s.lastAt)
        assertTrue(s.lastOk)
        assertEquals(0, s.books)
        assertEquals(0, s.covers)
    }
}
