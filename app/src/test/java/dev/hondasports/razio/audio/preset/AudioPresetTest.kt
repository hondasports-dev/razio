package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPresetTest {
    @Test
    fun narrowAm_matchesPracticalAmBroadcastBand() {
        assertEquals(-18f, AudioPreset.NARROW_AM.gainDbForCenterHz(60f), 0.01f)
        assertEquals(-18f, AudioPreset.NARROW_AM.gainDbForCenterHz(250f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(500f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(1_000f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(2_400f), 0.01f)
        assertEquals(-24f, AudioPreset.NARROW_AM.gainDbForCenterHz(3_400f), 0.01f)
    }

    @Test
    fun vintageSpeaker_keepsAUsableMidrange() {
        val preset = AudioPreset.VINTAGE_SPEAKER

        assertEquals(-18f, preset.gainDbForCenterHz(120f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(350f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(3_000f), 0.01f)
        assertEquals(-20f, preset.gainDbForCenterHz(4_800f), 0.01f)
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

        assertEquals(-18f, preset.gainDbForCenterHz(320f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(850f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(1_150f), 0.01f)
        assertEquals(-24f, preset.gainDbForCenterHz(1_450f), 0.01f)
        assertEquals(16f, preset.mbcRatio, 0.01f)
        assertEquals(-30f, preset.mbcThresholdDb, 0.01f)
        assertEquals(8f, preset.mbcPostGainDb, 0.01f)
        assertEquals(10f, preset.makeupGainDb, 0.01f)
        assertEquals(18f, preset.effectiveMbcPostGainDb, 0.01f)
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

        assertEquals(185f, middle.lowCutHz, 0.01f)
        assertEquals(-18f, middle.lowGainDb, 0.01f)
        assertEquals(5f, middle.midGainDb, 0.01f)
        assertEquals(10f, middle.mbcRatio, 0.01f)
        assertEquals(14f, middle.effectiveMbcPostGainDb, 0.01f)
    }
}
