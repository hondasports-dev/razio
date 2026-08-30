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
}
