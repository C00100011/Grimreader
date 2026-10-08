package com.vdelaar.mylibby.ui

import com.vdelaar.mylibby.tts.neural.NeuralCapability
import com.vdelaar.mylibby.tts.neural.NeuralFit
import com.vdelaar.mylibby.tts.neural.NeuralVoiceStore
import com.vdelaar.mylibby.ui.onboarding.OnboardingStep
import com.vdelaar.mylibby.ui.onboarding.stepAfterGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {
    private val gb = 1_000_000_000L

    @Test fun capablePhoneGetsTheVoicesStep() {
        assertEquals(OnboardingStep.VOICES, stepAfterGoal(NeuralFit.OK))
    }

    @Test fun otherPhonesSkipToTheThankYou() {
        listOf(NeuralFit.LOW_MEMORY, NeuralFit.LOW_STORAGE, NeuralFit.UNSUPPORTED_CPU).forEach {
            assertEquals(OnboardingStep.THANKS, stepAfterGoal(it))
        }
    }

    @Test fun modernPhoneIsFit() {
        assertEquals(NeuralFit.OK, NeuralCapability.assess(listOf("arm64-v8a", "armeabi-v7a"), 8 * gb, 50 * gb))
    }

    @Test fun fourGigabytePhoneStillPasses() {
        // A "4 GB" phone reports about 3.7 GB.
        assertEquals(NeuralFit.OK, NeuralCapability.assess(listOf("arm64-v8a"), 3_700_000_000L, 20 * gb))
    }

    @Test fun twoGigabytePhoneIsNot() {
        assertEquals(NeuralFit.LOW_MEMORY, NeuralCapability.assess(listOf("arm64-v8a"), 2 * gb, 20 * gb))
    }

    @Test fun thirtyTwoBitOnlyPhoneIsNot() {
        assertEquals(NeuralFit.UNSUPPORTED_CPU, NeuralCapability.assess(listOf("armeabi-v7a"), 6 * gb, 20 * gb))
    }

    @Test fun fullPhoneIsNot() {
        assertEquals(NeuralFit.LOW_STORAGE, NeuralCapability.assess(listOf("arm64-v8a"), 6 * gb, NeuralVoiceStore.TOTAL_BYTES))
    }

    @Test fun unknownValuesDoNotBlockTheVoices() {
        // If the system gives no answer, do not take the choice away from the user.
        assertEquals(NeuralFit.OK, NeuralCapability.assess(listOf("arm64-v8a"), 0, -1))
    }

    @Test fun progressSlotsRunFromWelcomeToThanks() {
        assertEquals(0, OnboardingStep.WELCOME.slot)
        assertEquals(OnboardingStep.SLOTS + 0, OnboardingStep.THANKS.slot.also { assertTrue(it > OnboardingStep.TRENDS.slot) })
        // the three set-up branches share a slot
        assertEquals(OnboardingStep.SYNC.slot, OnboardingStep.CATALOG.slot)
        assertEquals(OnboardingStep.SYNC.slot, OnboardingStep.LOCAL.slot)
    }
}
