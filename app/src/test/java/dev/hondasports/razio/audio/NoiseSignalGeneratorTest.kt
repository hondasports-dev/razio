package dev.hondasports.razio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseSignalGeneratorTest {
    @Test
    fun disabledSourcesProduceSilence() {
        val buffer = ShortArray(2_048) { Short.MAX_VALUE }

        NoiseSignalGenerator(sampleRate = 48_000, seed = 1L)
            .fill(buffer, hiss = false, crackle = false)

        assertTrue(buffer.all { it == 0.toShort() })
    }

    @Test
    fun hissIsAudibleButStaysBelowFullScale() {
        val buffer = ShortArray(48_000)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 2L)
            .fill(buffer, hiss = true, crackle = false)

        val peak = buffer.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak > 100)
        assertTrue(peak < Short.MAX_VALUE)
    }

    @Test
    fun crackleAddsBoundedTransientPeaks() {
        val buffer = ShortArray(48_000 * 4)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 3L)
            .fill(buffer, hiss = false, crackle = true)

        val peak = buffer.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak > 2_000)
        assertTrue(peak < Short.MAX_VALUE)
        assertTrue(buffer.any { it != 0.toShort() })
    }

    @Test
    fun increasingHissGainIncreasesNoiseEnergy() {
        val quiet = ShortArray(48_000)
        val loud = ShortArray(48_000)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 5L)
            .fill(quiet, hiss = true, crackle = false, hissGainDb = -6f)
        NoiseSignalGenerator(sampleRate = 48_000, seed = 5L)
            .fill(loud, hiss = true, crackle = false, hissGainDb = 6f)

        val quietEnergy = quiet.sumOf { kotlin.math.abs(it.toInt()).toLong() }
        val loudEnergy = loud.sumOf { kotlin.math.abs(it.toInt()).toLong() }
        assertTrue(loudEnergy > quietEnergy * 3L)
    }

    @Test
    fun increasingCrackleGainIncreasesTransientPeaks() {
        val quiet = ShortArray(48_000 * 4)
        val loud = ShortArray(48_000 * 4)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 6L)
            .fill(quiet, hiss = false, crackle = true, crackleGainDb = -6f)
        NoiseSignalGenerator(sampleRate = 48_000, seed = 6L)
            .fill(loud, hiss = false, crackle = true, crackleGainDb = 6f)

        val quietPeak = quiet.maxOf { kotlin.math.abs(it.toInt()) }
        val loudPeak = loud.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(loudPeak > quietPeak * 3 / 2)
    }

    @Test
    fun maxGainProducesMeaningfulOutputWithoutClipping() {
        val buffer = ShortArray(48_000 * 4)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 7L)
            .fill(
                buffer,
                hiss = true,
                crackle = true,
                hissGainDb = NoiseGainRange.MAX_DB,
                crackleGainDb = NoiseGainRange.MAX_DB,
            )

        val peak = buffer.maxOf { kotlin.math.abs(it.toInt()) }
        val sumSquares = buffer.sumOf { sample ->
            val value = sample.toLong()
            value * value
        }
        val rms = kotlin.math.sqrt(sumSquares.toDouble() / buffer.size)
        assertTrue(rms > 1_000.0)
        assertTrue(peak < Short.MAX_VALUE)
    }

    @Test
    fun legacyPercentLevelsMapOntoGainDb() {
        assertEquals(0f, NoiseGainRange.fromLegacyLevel(1f), 0.01f)
        assertEquals(6f, NoiseGainRange.fromLegacyLevel(2f), 0.01f)
        assertEquals(12f, NoiseGainRange.fromLegacyLevel(4f), 0.01f)
        assertEquals(NoiseGainRange.MIN_DB, NoiseGainRange.fromLegacyLevel(0f), 0.01f)
    }

    @Test
    fun defaultGainIsUnityLinear() {
        assertEquals(1f, NoiseGainRange.toLinear(0f), 0.01f)
        assertEquals(2f, NoiseGainRange.toLinear(6f), 0.02f)
    }
}
