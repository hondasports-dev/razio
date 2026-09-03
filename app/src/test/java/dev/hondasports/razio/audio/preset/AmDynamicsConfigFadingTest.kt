package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmDynamicsConfigFadingTest {
    @Test
    fun shortwave_fadesAroundMappedInputGainNotRawMinusOne() {
        val parameters = AudioPreset.SHORTWAVE.parameters()
        val mappedBase = AmDynamicsConfig.resolvedInputGainDb(
            parameters = parameters,
            usePostEqCurve = true,
        )

        assertEquals(-0.65f, mappedBase, 0.01f)
        assertTrue(mappedBase > parameters.inputGainDb)

        val atStart = AmDynamicsConfig.fadingInputGainDb(parameters, elapsedMs = 0L)
        val atPeak = AmDynamicsConfig.fadingInputGainDb(
            parameters,
            elapsedMs = parameters.fadePeriodMs / 4,
        )
        val atTrough = AmDynamicsConfig.fadingInputGainDb(
            parameters,
            elapsedMs = parameters.fadePeriodMs * 3 / 4,
        )

        assertEquals(mappedBase, atStart, 0.01f)
        assertEquals(mappedBase + parameters.fadeDepthDb, atPeak, 0.01f)
        assertEquals(mappedBase - parameters.fadeDepthDb, atTrough, 0.01f)
        assertTrue(atTrough > parameters.inputGainDb - parameters.fadeDepthDb)
        assertTrue(atTrough >= AudioPresetTuning.MIN_INPUT_GAIN_DB)
        assertTrue(atPeak <= AudioPresetTuning.MAX_INPUT_GAIN_DB)
    }

    @Test
    fun fadingPreset_keepsPlusMinusThreeAroundZero() {
        val parameters = AudioPreset.FADING.parameters()

        assertEquals(
            0f,
            AmDynamicsConfig.resolvedInputGainDb(parameters, usePostEqCurve = true),
            0.01f,
        )
        assertEquals(
            3f,
            AmDynamicsConfig.fadingInputGainDb(
                parameters,
                elapsedMs = parameters.fadePeriodMs / 4,
            ),
            0.01f,
        )
        assertEquals(
            -3f,
            AmDynamicsConfig.fadingInputGainDb(
                parameters,
                elapsedMs = parameters.fadePeriodMs * 3 / 4,
            ),
            0.01f,
        )
    }

    @Test
    fun fadingFailures_backOffInsteadOfAborting() {
        assertEquals(100L, AmDynamicsConfig.nextFadingDelayMs(consecutiveFailures = 0))
        assertEquals(500L, AmDynamicsConfig.nextFadingDelayMs(consecutiveFailures = 1))
        assertEquals(500L, AmDynamicsConfig.nextFadingDelayMs(consecutiveFailures = 3))
        assertFalse(AmDynamicsConfig.shouldLogFadingFailure(0))
        assertTrue(AmDynamicsConfig.shouldLogFadingFailure(1))
        assertTrue(AmDynamicsConfig.shouldLogFadingFailure(3))
        assertFalse(AmDynamicsConfig.shouldLogFadingFailure(4))
        assertTrue(AmDynamicsConfig.shouldLogFadingFailure(10))
    }

    @Test
    fun fadingPresets_keepConfigInputGainAtZeroSoTheHandlerOwnsIt() {
        assertEquals(
            0f,
            AmDynamicsConfig.configInputGainDb(
                AudioPreset.SHORTWAVE.parameters(),
                usePostEqCurve = true,
            ),
            0.01f,
        )
        assertEquals(
            0f,
            AmDynamicsConfig.configInputGainDb(
                AudioPreset.FADING.parameters(),
                usePostEqCurve = true,
            ),
            0.01f,
        )
        assertTrue(
            AmDynamicsConfig.configInputGainDb(
                AudioPreset.SATURATION.parameters(),
                usePostEqCurve = true,
            ) > 0f,
        )
    }

    @Test
    fun shortwave_mbcPostFallback_staysInsideEngineRange() {
        val parameters = AudioPreset.SHORTWAVE.parameters()
        val atPeak = AmDynamicsConfig.fadingMbcPostGainDb(
            parameters,
            elapsedMs = parameters.fadePeriodMs / 4,
        )
        val atTrough = AmDynamicsConfig.fadingMbcPostGainDb(
            parameters,
            elapsedMs = parameters.fadePeriodMs * 3 / 4,
        )
        assertTrue(atPeak >= atTrough)
        assertTrue(atPeak in 0f..9f)
        assertTrue(atTrough in 0f..9f)
        assertTrue(atPeak > atTrough)
    }
}
