package dev.hondasports.razio.ui.screen

import dev.hondasports.razio.audio.SpectrumMath
import dev.hondasports.razio.audio.preset.AudioPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAudioSpectrumTest {
    @Test
    fun idleHoldUsesFortyNineSixthOctaveBands() {
        assertEquals(49, CarAudioSpectrumHold.idle().displayedDb.size)
        assertEquals(SpectrumMath.bandCentersHz.size, CarAudioSpectrumHold.idle().peakDb.size)
    }

    @Test
    fun floorMapsToZeroSegments() {
        assertEquals(0, carAudioLitSegments(SpectrumMath.FLOOR_DB))
    }

    @Test
    fun fullScaleMapsToAllSegments() {
        assertEquals(CAR_AUDIO_SEGMENT_COUNT, carAudioLitSegments(0f))
    }

    @Test
    fun midLevelLightsAboutHalfTheColumn() {
        assertEquals(CAR_AUDIO_SEGMENT_COUNT / 2, carAudioLitSegments(-40f))
    }

    @Test
    fun attackFollowsIncomingImmediately() {
        val next = stepCarAudioSpectrum(
            previous = CarAudioSpectrumHold.idle(),
            incomingDb = List(SpectrumMath.bandCentersHz.size) { -12f },
            dtSeconds = 0.016f,
            running = true,
        )

        assertTrue(next.displayedDb.all { it == -12f })
        assertTrue(next.peakDb.all { it == -12f })
    }

    @Test
    fun displayedLevelDecaysFasterThanThePeakHold() {
        val held = CarAudioSpectrumHold(
            displayedDb = List(SpectrumMath.bandCentersHz.size) { -6f },
            peakDb = List(SpectrumMath.bandCentersHz.size) { -6f },
        )
        val next = stepCarAudioSpectrum(
            previous = held,
            incomingDb = List(SpectrumMath.bandCentersHz.size) { SpectrumMath.FLOOR_DB },
            dtSeconds = 0.2f,
            running = true,
        )

        assertTrue(next.displayedDb[0] < -6f)
        assertTrue(next.displayedDb[0] > SpectrumMath.FLOOR_DB)
        assertTrue(next.peakDb[0] > next.displayedDb[0])
        assertTrue(next.peakDb[0] < -6f)
    }

    @Test
    fun stoppingResetsTheHoldToTheDisplayFloor() {
        val held = CarAudioSpectrumHold(
            displayedDb = List(SpectrumMath.bandCentersHz.size) { -6f },
            peakDb = List(SpectrumMath.bandCentersHz.size) { -6f },
        )

        assertEquals(
            CarAudioSpectrumHold.idle(),
            stepCarAudioSpectrum(
                previous = held,
                incomingDb = List(SpectrumMath.bandCentersHz.size) { -6f },
                dtSeconds = 0.016f,
                running = false,
            ),
        )
    }

    @Test
    fun clipLampOnlyLightsOnALiveNearFullScalePeak() {
        assertTrue(carAudioIsClipping(0f, capturing = true))
        assertTrue(carAudioIsClipping(CAR_AUDIO_CLIP_DB, capturing = true))
        assertFalse(carAudioIsClipping(-12f, capturing = true))
        assertFalse(carAudioIsClipping(0f, capturing = false))
    }

    @Test
    fun stationReadoutIsDecorativePerPreset() {
        assertEquals("1000 kHz", carAudioStationReadout(AudioPreset.NARROW_AM))
        assertEquals("828 kHz", carAudioStationReadout(AudioPreset.VINTAGE_SPEAKER))
        assertEquals("558 kHz", carAudioStationReadout(AudioPreset.WEAK_SIGNAL))
        assertEquals("1314 kHz", carAudioStationReadout(AudioPreset.SATURATION))
        assertEquals("1179 kHz", carAudioStationReadout(AudioPreset.FADING))
        assertEquals("6.09 MHz", carAudioStationReadout(AudioPreset.SHORTWAVE))
    }

    @Test
    fun demoFollowsThePresetVoiceBand() {
        val levels = demoSpectrumLevels(
            tuning = AudioPreset.NARROW_AM.defaultTuning(),
            timeSeconds = 0.4f,
        )
        val voice = levels[SpectrumMath.bandIndex(1_000)]
        val treble = levels[SpectrumMath.bandIndex(16_000)]

        assertEquals(SpectrumMath.bandCentersHz.size, levels.size)
        assertTrue("demo should keep the AM voice band above 16 kHz", voice > treble + 8f)
        assertTrue(levels.all { it <= -4f })
    }

    @Test
    fun demoLevelsMoveOverTime() {
        val tuning = AudioPreset.NARROW_AM.defaultTuning()
        val first = demoSpectrumLevels(tuning, timeSeconds = 0.2f)
        val later = demoSpectrumLevels(tuning, timeSeconds = 1.9f)

        assertNotEquals(first, later)
    }
}
