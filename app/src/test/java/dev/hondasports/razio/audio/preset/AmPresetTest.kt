package dev.hondasports.razio.audio.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmPresetTest {
    @Test
    fun lowShelf_isStrongCut() {
        assertEquals(-15f, AmPreset.gainDbForCenterHz(60f), 0.01f)
        assertEquals(-15f, AmPreset.gainDbForCenterHz(250f), 0.01f)
    }

    @Test
    fun midrange_isBoosted() {
        assertEquals(6f, AmPreset.gainDbForCenterHz(1000f), 0.01f)
        assertEquals(6f, AmPreset.gainDbForCenterHz(1500f), 0.01f)
        assertEquals(6f, AmPreset.gainDbForCenterHz(2000f), 0.01f)
    }

    @Test
    fun highShelf_isStrongCut() {
        assertEquals(-15f, AmPreset.gainDbForCenterHz(3500f), 0.01f)
        assertEquals(-15f, AmPreset.gainDbForCenterHz(8000f), 0.01f)
    }

    @Test
    fun millibels_clampsToDeviceRange() {
        val value = AmPreset.millibels(60f, -600, 600)
        assertEquals(-600, value.toInt())
    }

    @Test
    fun transition_movesTowardMidBoost() {
        val betweenLowAndMid = AmPreset.gainDbForCenterHz(625f)
        assertTrue(betweenLowAndMid > AmPreset.LOW_GAIN_DB)
        assertTrue(betweenLowAndMid < AmPreset.MID_GAIN_DB)
    }
}
