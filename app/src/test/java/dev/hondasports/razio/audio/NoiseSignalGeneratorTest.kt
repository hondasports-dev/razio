package dev.hondasports.razio.audio

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
    fun zeroLevelsMuteBothSources() {
        val buffer = ShortArray(48_000)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 4L)
            .fill(
                buffer,
                hiss = true,
                crackle = true,
                hissLevel = 0f,
                crackleLevel = 0f,
            )

        assertTrue(buffer.all { it == 0.toShort() })
    }

    @Test
    fun increasingHissLevelIncreasesNoiseEnergy() {
        val quiet = ShortArray(48_000)
        val loud = ShortArray(48_000)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 5L)
            .fill(quiet, hiss = true, crackle = false, hissLevel = 0.5f)
        NoiseSignalGenerator(sampleRate = 48_000, seed = 5L)
            .fill(loud, hiss = true, crackle = false, hissLevel = 2f)

        val quietEnergy = quiet.sumOf { kotlin.math.abs(it.toInt()).toLong() }
        val loudEnergy = loud.sumOf { kotlin.math.abs(it.toInt()).toLong() }
        assertTrue(loudEnergy > quietEnergy * 3L)
    }

    @Test
    fun increasingCrackleLevelIncreasesTransientPeaks() {
        val quiet = ShortArray(48_000 * 4)
        val loud = ShortArray(48_000 * 4)

        NoiseSignalGenerator(sampleRate = 48_000, seed = 6L)
            .fill(quiet, hiss = false, crackle = true, crackleLevel = 0.5f)
        NoiseSignalGenerator(sampleRate = 48_000, seed = 6L)
            .fill(loud, hiss = false, crackle = true, crackleLevel = 2f)

        val quietPeak = quiet.maxOf { kotlin.math.abs(it.toInt()) }
        val loudPeak = loud.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(loudPeak > quietPeak * 3 / 2)
    }
}
