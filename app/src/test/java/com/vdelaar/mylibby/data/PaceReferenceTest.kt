package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.datastore.AppState
import com.vdelaar.mylibby.core.datastore.PaceReference
import org.junit.Assert.assertEquals
import org.junit.Test

class PaceReferenceTest {
    @Test fun theAverageAdultIsTheDefault() {
        assertEquals(PaceReference.ADULT, AppState().paceReference)
        assertEquals(238, AppState().paceWpm)
    }

    @Test fun presetsGiveTheirOwnSpeed() {
        assertEquals(260, AppState(paceReference = PaceReference.FICTION).paceWpm)
        assertEquals(200, AppState(paceReference = PaceReference.RELAXED).paceWpm)
        assertEquals(320, AppState(paceReference = PaceReference.BRISK).paceWpm)
    }

    @Test fun anOwnSpeedIsKeptWithinSensibleBounds() {
        assertEquals(450, AppState(paceReference = PaceReference.CUSTOM, paceCustomWpm = 450).paceWpm)
        assertEquals(PaceReference.MIN_CUSTOM, AppState(paceReference = PaceReference.CUSTOM, paceCustomWpm = 10).paceWpm)
        assertEquals(PaceReference.MAX_CUSTOM, AppState(paceReference = PaceReference.CUSTOM, paceCustomWpm = 5000).paceWpm)
    }

    @Test fun theOwnSpeedDoesNotAffectPresets() {
        assertEquals(238, AppState(paceReference = PaceReference.ADULT, paceCustomWpm = 600).paceWpm)
    }
}
