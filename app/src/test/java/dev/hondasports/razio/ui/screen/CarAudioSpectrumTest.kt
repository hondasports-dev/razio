package dev.hondasports.razio.ui.screen

import dev.hondasports.razio.audio.SpectrumMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAudioSpectrumTest {
    @Test
    fun idleHoldUsesTwentyFiveThirdOctaveBands() {
        assertEquals(25, CarAudioSpectrumHold.idle().displayedDb.size)
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
}
