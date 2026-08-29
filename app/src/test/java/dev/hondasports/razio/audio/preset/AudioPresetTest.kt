package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPresetTest {
    @Test
    fun narrowAm_matchesPracticalAmBroadcastBand() {
        assertEquals(-24f, AudioPreset.NARROW_AM.gainDbForCenterHz(60f), 0.01f)
        assertEquals(-24f, AudioPreset.NARROW_AM.gainDbForCenterHz(300f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(550f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(1_000f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(2_200f), 0.01f)
        assertEquals(-30f, AudioPreset.NARROW_AM.gainDbForCenterHz(3_000f), 0.01f)
    }

    @Test
    fun vintageSpeaker_keepsAUsableMidrange() {
        val preset = AudioPreset.VINTAGE_SPEAKER

        assertEquals(-24f, preset.gainDbForCenterHz(180f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(450f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(2_600f), 0.01f)
        assertEquals(-26f, preset.gainDbForCenterHz(4_000f), 0.01f)
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

        assertEquals(-24f, preset.gainDbForCenterHz(380f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(900f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(1_100f), 0.01f)
        assertEquals(-30f, preset.gainDbForCenterHz(1_350f), 0.01f)
        assertEquals(16f, preset.mbcRatio, 0.01f)
        assertEquals(-30f, preset.mbcThresholdDb, 0.01f)
        assertEquals(8f, preset.mbcPostGainDb, 0.01f)
        assertEquals(10f, preset.makeupGainDb, 0.01f)
        assertEquals(18f, preset.effectiveMbcPostGainDb, 0.01f)
    }

    @Test
    fun saturation_pushesInputIntoStrongCompression() {
        val preset = AudioPreset.SATURATION

        assertEquals(-18f, preset.gainDbForCenterHz(180f), 0.01f)
        assertEquals(2f, preset.gainDbForCenterHz(450f), 0.01f)
        assertEquals(-18f, preset.gainDbForCenterHz(5_000f), 0.01f)
        assertEquals(10f, preset.inputGainDb, 0.01f)
        assertEquals(20f, preset.mbcRatio, 0.01f)
        assertEquals(-18f, preset.mbcThresholdDb, 0.01f)
        assertEquals(8f, preset.effectiveMbcPostGainDb, 0.01f)
    }

    @Test
    fun fading_keepsNarrowBandAndDefinesSlowGainMovement() {
        val preset = AudioPreset.FADING

        assertEquals(-24f, preset.gainDbForCenterHz(300f), 0.01f)
        assertEquals(6f, preset.gainDbForCenterHz(1_000f), 0.01f)
        assertEquals(-30f, preset.gainDbForCenterHz(3_000f), 0.01f)
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
    fun interpolatedParameters_stayBetweenPresetEndpoints() {
        val from = AudioPreset.NARROW_AM.parameters()
        val to = AudioPreset.VINTAGE_SPEAKER.parameters()

        val middle = AudioPresetParameters.interpolate(from, to, 0.5f)

        assertEquals(240f, middle.lowCutHz, 0.01f)
        assertEquals(-24f, middle.lowGainDb, 0.01f)
        assertEquals(5f, middle.midGainDb, 0.01f)
        assertEquals(10f, middle.mbcRatio, 0.01f)
        assertEquals(14f, middle.effectiveMbcPostGainDb, 0.01f)
    }
}
