package dev.hondasports.razio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MonoPcmMixerTest {
    @Test
    fun downmixStereoAveragesAndDuplicatesBothChannels() {
        val output = ShortArray(6)

        val frames = MonoPcmMixer.downmixStereoToStereo(
            input = shortArrayOf(10_000, -2_000, -30_001, 30_000, 12, 14),
            inputSampleCount = 6,
            output = output,
        )

        assertEquals(3, frames)
        assertArrayEquals(shortArrayOf(4_000, 4_000, 0, 0, 13, 13), output)
    }

    @Test
    fun downmixIgnoresIncompleteTrailingSample() {
        val output = ShortArray(4)

        val frames = MonoPcmMixer.downmixStereoToStereo(
            input = shortArrayOf(1, 3, 5),
            inputSampleCount = 3,
            output = output,
        )

        assertEquals(1, frames)
        assertArrayEquals(shortArrayOf(2, 2, 0, 0), output)
    }

    @Test
    fun monoFallbackDuplicatesSamples() {
        val output = ShortArray(6)

        val samples = MonoPcmMixer.copyMonoToStereo(
            input = shortArrayOf(-5, 0, 7, 11),
            inputSampleCount = 3,
            output = output,
        )

        assertEquals(3, samples)
        assertArrayEquals(shortArrayOf(-5, -5, 0, 0, 7, 7), output)
    }
}
