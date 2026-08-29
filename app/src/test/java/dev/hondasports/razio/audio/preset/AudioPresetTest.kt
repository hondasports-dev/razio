package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPresetTest {
    @Test
    fun narrowAm_matchesTheExistingDefaultCurve() {
        assertEquals(-15f, AudioPreset.NARROW_AM.gainDbForCenterHz(300f), 0.01f)
        assertEquals(6f, AudioPreset.NARROW_AM.gainDbForCenterHz(1_000f), 0.01f)
        assertEquals(-15f, AudioPreset.NARROW_AM.gainDbForCenterHz(1_600f), 0.01f)
    }

    @Test
    fun vintageSpeaker_keepsAUsableMidrange() {
        val preset = AudioPreset.VINTAGE_SPEAKER

        assertEquals(-12f, preset.gainDbForCenterHz(220f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(700f), 0.01f)
        assertEquals(4f, preset.gainDbForCenterHz(1_350f), 0.01f)
        assertEquals(-12f, preset.gainDbForCenterHz(2_800f), 0.01f)
        assertTrue(preset.gainDbForCenterHz(2_000f) < preset.midGainDb)
    }

    @Test
    fun unknownId_fallsBackToNarrowAm() {
        assertEquals(AudioPreset.NARROW_AM, AudioPreset.fromId("future_preset"))
        assertEquals(AudioPreset.NARROW_AM, AudioPreset.fromId(null))
    }

    @Test
    fun millibels_clampsEachPresetToTheDeviceRange() {
        val value = AudioPreset.VINTAGE_SPEAKER.millibels(100f, -600, 600)

        assertEquals(-600, value.toInt())
    }
}
