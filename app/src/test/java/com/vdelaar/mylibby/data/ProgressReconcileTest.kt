package com.vdelaar.mylibby.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressReconcileTest {
    private val now = 1_000_000_000L

    private fun act(
        local: Float? = 40f, dirty: Boolean = false, canPush: Boolean = true,
        overall: Float? = 40f, web: Float? = 40f, last: Long? = null,
    ) = reconcileProgress(local, dirty, canPush, overall, web, last, now)

    @Test fun everyoneAgreesSoNothingHappens() {
        assertEquals(ProgressAction.NONE, act())
        assertEquals("a rounding difference is not reading", ProgressAction.NONE, act(local = 40.4f, overall = 39.5f, web = 40.4f))
    }

    @Test fun theKoboIsFurtherSoOfferToJump() {
        assertEquals(ProgressAction.OFFER_JUMP, act(local = 40f, web = 40f, overall = 65f))
    }

    @Test fun aBookNeverOpenedHereButReadOnTheKoboOffersTheJump() {
        assertEquals(ProgressAction.OFFER_JUMP, act(local = null, web = null, overall = 35f))
    }

    @Test fun thisAppIsFurtherSoSendOurProgressAgain() {
        assertEquals(ProgressAction.REASSERT, act(local = 70f, web = 70f, overall = 40f))
    }

    @Test fun thisAppIsFurtherThanTheServerKnowsAndAKoboAsWell() {
        // the server's overall figure is the Kobo's 40; our 70 is on the server too, but older than the Kobo's
        assertEquals(ProgressAction.REASSERT, act(local = 70f, web = 70f, overall = 40f))
    }

    @Test fun aSmallLeadIsNotWorthTellingTheServerAbout() {
        assertEquals(ProgressAction.NONE, act(local = 41f, web = 41f, overall = 40f))
    }

    @Test fun nothingIsResentWhileOurOwnChangeIsStillWaiting() {
        assertEquals(ProgressAction.NONE, act(local = 70f, dirty = true, web = 40f, overall = 40f))
    }

    @Test fun nothingIsSentWithoutAFileToWriteTo() {
        assertEquals(ProgressAction.NONE, act(local = 70f, web = 70f, overall = 40f, canPush = false))
    }

    @Test fun theSameBookIsNotResentAllDay() {
        // the server may keep reporting the Kobo's lower figure when two-way sync is off: do not nag it on every sync
        assertEquals(ProgressAction.NONE, act(local = 70f, web = 70f, overall = 40f, last = now - 3_600_000L))
        assertEquals(ProgressAction.REASSERT, act(local = 70f, web = 70f, overall = 40f, last = now - REASSERT_EVERY_MS - 1))
    }

    @Test fun noFigureFromTheServerMeansNothingToCompare() {
        assertEquals(ProgressAction.NONE, act(overall = null))
    }

    @Test fun theWebReaderBeingFurtherThanTheOverallFigureIsOurOwnPositionToKeep() {
        // another Grimreader device is at 80 on the web side; the Kobo says 40; local is 40: the existing server-newer
        // logic offers the web position, and this check sees nothing further than our best
        assertEquals(ProgressAction.NONE, act(local = 40f, web = 80f, overall = 40f))
    }

    @Test fun theFurthestAlwaysWinsOverTheNewest() {
        // Kobo read more recently but is behind: ours is the one that gets passed on
        assertEquals(ProgressAction.REASSERT, act(local = 90f, web = 90f, overall = 55f))
        // Kobo is ahead: we offer, we never write the lower number over theirs
        assertEquals(ProgressAction.OFFER_JUMP, act(local = 55f, web = 55f, overall = 90f))
    }
}
