package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPresetTest {
    @Test
    fun narrowAm_matchesPracticalAmBroadcastBand() {
        assertEquals(-30f, AudioPreset.NARROW_AM.gainDbForCenterHz(60f), 0.01f)
        assertEquals(-30f, AudioPreset.NARROW_AM.gainDbForCenterHz(300f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(550f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(1_000f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(2_200f), 0.01f)
        assertEquals(-48f, AudioPreset.NARROW_AM.gainDbForCenterHz(3_000f), 0.01f)
    }

    @Test
    fun vintageSpeaker_keepsAUsableMidrange() {
        val preset = AudioPreset.VINTAGE_SPEAKER

        assertEquals(-30f, preset.gainDbForCenterHz(180f), 0.01f)
        assertEquals(5f, preset.gainDbForCenterHz(450f), 0.01f)
        assertEquals(5f, preset.gainDbForCenterHz(2_600f), 0.01f)
        assertEquals(-48f, preset.gainDbForCenterHz(4_000f), 0.01f)
        assertTrue(preset.gainDbForCenterHz(6_000f) < preset.midGainDb)
    }

    @Test
    fun unknownId_fallsBackToNarrowAm() {
        assertEquals(AudioPreset.NARROW_AM, AudioPreset.fromId("future_preset"))
        assertEquals(AudioPreset.NARROW_AM, AudioPreset.fromId(null))
    }

    @Test
    fun weakSignal_reducesMidBoostAndPostGain() {
        val preset = AudioPreset.WEAK_SIGNAL

        assertEquals(-30f, preset.gainDbForCenterHz(380f), 0.01f)
        assertEquals(5f, preset.gainDbForCenterHz(900f), 0.01f)
        assertEquals(5f, preset.gainDbForCenterHz(1_100f), 0.01f)
        assertEquals(-48f, preset.gainDbForCenterHz(1_350f), 0.01f)
        assertEquals(16f, preset.mbcRatio, 0.01f)
        assertEquals(-30f, preset.mbcThresholdDb, 0.01f)
        assertEquals(8f, preset.mbcPostGainDb, 0.01f)
        assertEquals(10f, preset.makeupGainDb, 0.01f)
        assertEquals(18f, preset.effectiveMbcPostGainDb, 0.01f)
    }

    @Test
    fun weakSignal_keepsDistortionReliefDisabled() {
        assertEquals(0f, AudioPreset.WEAK_SIGNAL.distortionRelief, 0.01f)
        assertTrue(AudioPreset.NARROW_AM.distortionRelief > 0f)
        assertTrue(AudioPreset.VINTAGE_SPEAKER.distortionRelief > 0f)
        assertTrue(AudioPreset.SATURATION.distortionRelief > 0f)
        assertTrue(AudioPreset.FADING.distortionRelief > 0f)
    }

    @Test
    fun saturation_pushesInputIntoStrongCompression() {
        val preset = AudioPreset.SATURATION

        assertEquals(-24f, preset.gainDbForCenterHz(180f), 0.01f)
        assertEquals(2f, preset.gainDbForCenterHz(450f), 0.01f)
        assertEquals(-48f, preset.gainDbForCenterHz(5_000f), 0.01f)
        assertEquals(10f, preset.inputGainDb, 0.01f)
        assertEquals(20f, preset.mbcRatio, 0.01f)
        assertEquals(-18f, preset.mbcThresholdDb, 0.01f)
        assertEquals(8f, preset.effectiveMbcPostGainDb, 0.01f)
    }

    @Test
    fun fading_keepsNarrowBandAndDefinesSlowGainMovement() {
        val preset = AudioPreset.FADING

        assertEquals(-30f, preset.gainDbForCenterHz(300f), 0.01f)
        assertEquals(6f, preset.gainDbForCenterHz(1_000f), 0.01f)
        assertEquals(-48f, preset.gainDbForCenterHz(3_000f), 0.01f)
        assertEquals(3f, preset.fadeDepthDb, 0.01f)
        assertEquals(3_200L, preset.fadePeriodMs)
        assertEquals(0f, preset.inputGainDb, 0.01f)
    }

    @Test
    fun millibels_clampsEachPresetToTheDeviceRange() {
        val value = AudioPreset.VINTAGE_SPEAKER.millibels(100f, -600, 600)

        assertEquals(-600, value.toInt())
    }

    @Test
    fun allPresets_useDeeperTenKilohertzCut() {
        AudioPreset.entries.forEach { preset ->
            assertEquals(-48f, preset.gainDbForCenterHz(10_000f), 0.01f)
        }
    }

    @Test
    fun interpolatedParameters_stayBetweenPresetEndpoints() {
        val from = AudioPreset.NARROW_AM.parameters()
        val to = AudioPreset.VINTAGE_SPEAKER.parameters()

        val middle = AudioPresetParameters.interpolate(from, to, 0.5f)

        assertEquals(240f, middle.lowCutHz, 0.01f)
        assertEquals(370f, middle.lowTransitionHz, 0.01f)
        assertEquals(2_950f, middle.highTransitionHz, 0.01f)
        assertEquals(-12.5f, middle.lowTransitionGainDb, 0.01f)
        assertEquals(-21.5f, middle.highTransitionGainDb, 0.01f)
        assertEquals(-30f, middle.lowGainDb, 0.01f)
        assertEquals(5.5f, middle.midGainDb, 0.01f)
        assertEquals(10f, middle.mbcRatio, 0.01f)
        assertEquals(14f, middle.effectiveMbcPostGainDb, 0.01f)
        assertEquals(0.9f, middle.distortionRelief, 0.01f)
    }

    @Test
    fun tuning_sanitizesFrequencyOrderAndValueRanges() {
        val safe = AudioPreset.NARROW_AM.defaultTuning().copy(
            lowCutHz = 50_000f,
            midLowHz = 100f,
            midHighHz = 80f,
            highCutHz = 50_000f,
            lowGainDb = -100f,
            distortionRelief = 2f,
            fadePeriodMs = 99_999L,
        ).sanitized()

        assertTrue(safe.lowCutHz < safe.midLowHz)
        assertTrue(safe.lowCutHz < safe.lowTransitionHz)
        assertTrue(safe.lowTransitionHz < safe.midLowHz)
        assertTrue(safe.midLowHz < safe.midHighHz)
        assertTrue(safe.midHighHz < safe.highTransitionHz)
        assertTrue(safe.highTransitionHz < safe.highCutHz)
        assertTrue(safe.midHighHz < safe.highCutHz)
        assertEquals(AudioPresetTuning.MAX_HIGH_CUT_HZ, safe.highCutHz, 0.01f)
        assertEquals(AudioPresetTuning.MIN_GAIN_DB, safe.lowGainDb, 0.01f)
        assertEquals(1f, safe.distortionRelief, 0.01f)
        assertEquals(AudioPresetTuning.MAX_FADE_PERIOD_MS, safe.fadePeriodMs)
    }

    @Test
    fun tuning_toParametersIncludesMakeupAndFadingValues() {
        val tuning = AudioPreset.FADING.defaultTuning().copy(
            mbcPostGainDb = 2f,
            makeupGainDb = 3f,
            fadeDepthDb = 5f,
            fadePeriodMs = 4_000L,
        )

        val parameters = tuning.toParameters()

        assertEquals(5f, parameters.effectiveMbcPostGainDb, 0.01f)
        assertEquals(5f, parameters.fadeDepthDb, 0.01f)
        assertEquals(4_000L, parameters.fadePeriodMs)
    }

    @Test
    fun tuning_curveReflectsFrequencyCutBoundaries() {
        val tuning = AudioPreset.NARROW_AM.defaultTuning()

        assertEquals(tuning.lowGainDb, tuning.gainDbForCenterHz(100f), 0.01f)
        assertEquals(tuning.lowTransitionGainDb, tuning.gainDbForCenterHz(tuning.lowTransitionHz), 0.01f)
        assertEquals(tuning.midGainDb, tuning.gainDbForCenterHz(1_000f), 0.01f)
        assertEquals(tuning.highTransitionGainDb, tuning.gainDbForCenterHz(tuning.highTransitionHz), 0.01f)
        assertEquals(tuning.highGainDb, tuning.gainDbForCenterHz(10_000f), 0.01f)
    }
}
