package com.vdelaar.mylibby.core

import com.vdelaar.mylibby.core.datastore.AppState
import com.vdelaar.mylibby.core.datastore.LibrarySource
import com.vdelaar.mylibby.core.datastore.canSwitchOffDevice
import com.vdelaar.mylibby.core.datastore.migratedLibraryModel
import com.vdelaar.mylibby.core.datastore.withDevice
import com.vdelaar.mylibby.core.datastore.withGrimmory
import com.vdelaar.mylibby.core.datastore.withOpds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySourcesTest {
    @Test fun aNewReaderHasGrimmoryAndTheDevice() {
        assertEquals(setOf(LibrarySource.GRIMMORY, LibrarySource.LOCAL), AppState().librarySources)
    }

    @Test fun allThreeCanBeOn() {
        val s = AppState().withOpds(true)
        assertEquals(setOf(LibrarySource.GRIMMORY, LibrarySource.OPDS, LibrarySource.LOCAL), s.librarySources)
        assertFalse("an OPDS catalog no longer means no server", s.noServer)
    }

    @Test fun grimmoryOffMeansNoServerAndTheDeviceHoldsTheBooks() {
        val s = AppState(deviceBooks = false).withGrimmory(false)
        assertTrue(s.noServer)
        assertTrue(s.deviceOn)
        assertEquals(setOf(LibrarySource.LOCAL), s.librarySources)
    }

    @Test fun grimmoryComesBackWithoutLosingTheOthers() {
        val s = AppState().withOpds(true).withGrimmory(false).withGrimmory(true)
        assertEquals(setOf(LibrarySource.GRIMMORY, LibrarySource.OPDS, LibrarySource.LOCAL), s.librarySources)
    }

    @Test fun anOpdsCatalogSwitchesTheDeviceOnBecauseDownloadsLandThere() {
        assertTrue(AppState(deviceBooks = false).withOpds(true).deviceBooks)
    }

    @Test fun theDeviceCanBeSwitchedOffOnlyWhenGrimmoryIsOnAndNoCatalogNeedsIt() {
        assertTrue(AppState().canSwitchOffDevice())
        assertFalse(AppState().withOpds(true).canSwitchOffDevice())
        assertFalse(AppState(localOnly = true).canSwitchOffDevice())
        assertFalse(AppState(demo = true).canSwitchOffDevice())
    }

    @Test fun aRefusedSwitchOffLeavesTheDeviceOn() {
        assertTrue(AppState(localOnly = true).withDevice(false).deviceOn)
        assertTrue(AppState().withOpds(true).withDevice(false).deviceOn)
    }

    @Test fun theDeviceCanBeSwitchedOffWhenAllowed() {
        val s = AppState().withDevice(false)
        assertFalse(s.deviceOn)
        assertEquals(setOf(LibrarySource.GRIMMORY), s.librarySources)
    }

    @Test fun demoIsTheDevice() {
        val s = AppState(demo = true)
        assertTrue(s.noServer)
        assertEquals(setOf(LibrarySource.LOCAL), s.librarySources)
    }

    @Test fun oldOpdsSettingsKeepTheirMeaningAfterTheUpdate() {
        // Before: OPDS on meant "no Grimmory". After the update that is Grimmory off, OPDS on, device on.
        val s = AppState(opdsActive = true, localOnly = false, libraryModel = 0).migratedLibraryModel()
        assertTrue(s.noServer)
        assertEquals(setOf(LibrarySource.OPDS, LibrarySource.LOCAL), s.librarySources)
        assertEquals(2, s.libraryModel)
    }

    @Test fun otherOldSettingsAreLeftAlone() {
        val s = AppState(libraryModel = 0).migratedLibraryModel()
        assertFalse(s.noServer)
        assertEquals(2, s.libraryModel)
        assertEquals(s, s.migratedLibraryModel())
    }
}
