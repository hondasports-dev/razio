package dev.hondasports.razio.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumMathTest {
    @Test
    fun silenceStaysAtTheDisplayFloor() {
        val frame = SpectrumMath.fromPcm16(
            samples = ShortArray(SpectrumMath.FFT_SIZE),
            sampleRateHz = 48_000,
        )

        assertTrue(frame.levelsDb.all { it <= SpectrumMath.FLOOR_DB })
        assertTrue(frame.rmsDb <= SpectrumMath.FLOOR_DB)
        assertTrue(frame.peakDb <= SpectrumMath.FLOOR_DB)
        assertFalse(SpectrumMath.hasUsableSignal(frame))
    }

    @Test
    fun analyzerUsesFortyNineSixthOctaveBands() {
        assertEquals(49, SpectrumMath.bandCentersHz.size)
        assertEquals(63, SpectrumMath.bandCentersHz.first())
        assertEquals(71, SpectrumMath.bandCentersHz[1])
        assertEquals(1_000, SpectrumMath.bandCentersHz[SpectrumMath.bandIndex(1_000)])
        assertEquals(16_000, SpectrumMath.bandCentersHz.last())
    }

    @Test
    fun oneKilohertzToneDominatesItsBand() {
        val samples = ShortArray(SpectrumMath.FFT_SIZE) { index ->
            (sin(2.0 * PI * 1_000.0 * index / 48_000.0) * Short.MAX_VALUE * 0.7)
                .toInt()
                .toShort()
        }
        val frame = SpectrumMath.fromPcm16(samples, sampleRateHz = 48_000)
        val oneKilohertzBand = frame.levelsDb[SpectrumMath.bandIndex(1_000)]
        val highBand = frame.levelsDb[SpectrumMath.bandIndex(6_300)]

        assertTrue("1 kHz should be above 6.3 kHz", oneKilohertzBand > highBand + 12f)
        assertTrue(frame.rmsDb < 0f)
        assertTrue(frame.peakDb < 0f)
        assertTrue(SpectrumMath.hasUsableSignal(frame))
    }

    @Test
    fun visualizerUnsignedBytesAreCentered() {
        val waveform = ByteArray(SpectrumMath.FFT_SIZE) { index ->
            // Visualizer delivers unsigned PCM in a signed ByteArray: 192 == +64,
            // 64 == -64 around the unsigned center value 128.
            if (index % 2 == 0) 192.toByte() else 64.toByte()
        }
        val frame = SpectrumMath.fromVisualizerWaveform(waveform, samplingRate = 48_000)

        assertTrue(frame.peakDb < 0f)
        assertTrue(frame.rmsDb > SpectrumMath.FLOOR_DB)
        assertTrue(SpectrumMath.hasUsableSignal(frame))
    }

    @Test
    fun veryQuietFrameIsReportedAsUnavailableSignal() {
        val samples = ShortArray(SpectrumMath.FFT_SIZE) { 1 }
        val frame = SpectrumMath.fromPcm16(samples, sampleRateHz = 48_000)

        assertTrue(frame.peakDb <= SpectrumMath.FLOOR_DB + 1f)
        assertFalse(SpectrumMath.hasUsableSignal(frame))
    }
}
