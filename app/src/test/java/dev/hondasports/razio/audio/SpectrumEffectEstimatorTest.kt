package dev.hondasports.razio.audio

import dev.hondasports.razio.audio.preset.AudioPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumEffectEstimatorTest {
    @Test
    fun disabledProfileLeavesTheReferenceFrameUntouched() {
        val frame = referenceFrame()

        val result = SpectrumEffectEstimator.apply(
            frame = frame,
            profile = SpectrumEffectProfile(enabled = false),
        )

        assertEquals(frame, result)
    }

    @Test
    fun enabledProfileCutsHighBandAndKeepsVoiceBandAboveIt() {
        val frame = referenceFrame()

        val result = SpectrumEffectEstimator.apply(
            frame = frame,
            profile = SpectrumEffectProfile(
                enabled = true,
                tuning = AudioPreset.NARROW_AM.defaultTuning(),
            ),
        )

        assertTrue(result.levelsDb[4] > result.levelsDb[8] + 20f)
        assertTrue(result.levelsDb[8] < frame.levelsDb[8] - 20f)
        assertTrue(result.rmsDb <= 0f)
        assertTrue(result.peakDb <= -1f)
    }

    private fun referenceFrame(): SpectrumFrame = SpectrumFrame(
        levelsDb = SpectrumMath.bandCentersHz.map { -18f },
        rmsDb = -18f,
        peakDb = -6f,
    )
}
