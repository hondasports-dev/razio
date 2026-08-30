package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmPresetTest {
    @Test
    fun lowShelf_isStrongCut() {
        assertEquals(-30f, AmPreset.gainDbForCenterHz(60f), 0.01f)
        assertEquals(-30f, AmPreset.gainDbForCenterHz(300f), 0.01f)
    }

    @Test
    fun midrange_isBoosted() {
        assertEquals(6f, AmPreset.gainDbForCenterHz(550f), 0.01f)
        assertEquals(6f, AmPreset.gainDbForCenterHz(2200f), 0.01f)
    }

    @Test
    fun upperMids_startRollingOff() {
        val at2500 = AmPreset.gainDbForCenterHz(2500f)
        assertTrue(at2500 < AmPreset.MID_GAIN_DB)
        assertTrue(at2500 > AmPreset.HIGH_GAIN_DB)
    }

    @Test
    fun highShelf_isStrongCut() {
        assertEquals(-40f, AmPreset.gainDbForCenterHz(3000f), 0.01f)
        assertEquals(-40f, AmPreset.gainDbForCenterHz(8000f), 0.01f)
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
