package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmPresetTest {
    @Test
    fun lowShelf_isStrongCut() {
        assertEquals(-18f, AmPreset.gainDbForCenterHz(60f), 0.01f)
        assertEquals(-18f, AmPreset.gainDbForCenterHz(250f), 0.01f)
    }

    @Test
    fun midrange_isBoosted() {
        assertEquals(6f, AmPreset.gainDbForCenterHz(500f), 0.01f)
        assertEquals(6f, AmPreset.gainDbForCenterHz(2400f), 0.01f)
    }

    @Test
    fun upperMids_startRollingOff() {
        val at3000 = AmPreset.gainDbForCenterHz(3000f)
        assertTrue(at3000 < AmPreset.MID_GAIN_DB)
        assertTrue(at3000 > AmPreset.HIGH_GAIN_DB)
    }

    @Test
    fun highShelf_isStrongCut() {
        assertEquals(-24f, AmPreset.gainDbForCenterHz(3400f), 0.01f)
        assertEquals(-24f, AmPreset.gainDbForCenterHz(8000f), 0.01f)
    }

    @Test
    fun millibels_clampsToDeviceRange() {
        val value = AmPreset.millibels(60f, -600, 600)
        assertEquals(-600, value.toInt())
    }

    @Test
    fun transition_movesTowardMidBoost() {
        val betweenLowAndMid = AmPreset.gainDbForCenterHz(375f)
        assertTrue(betweenLowAndMid > AmPreset.LOW_GAIN_DB)
        assertTrue(betweenLowAndMid < AmPreset.MID_GAIN_DB)
    }
}
