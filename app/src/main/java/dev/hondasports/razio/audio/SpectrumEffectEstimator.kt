package dev.hondasports.razio.audio

import dev.hondasports.razio.audio.preset.AudioPreset
import dev.hondasports.razio.audio.preset.AudioPresetTuning
import kotlin.math.log10
import kotlin.math.pow

/**
 * The playback-capture API does not expose the PCM immediately after a global
 * DynamicsProcessing effect. This profile describes the current effect state
 * so the analyzer can derive a transparent post-effect estimate from the same
 * input frame instead of presenting an unrelated or silent Visualizer tap as
 * measured post-DSP audio.
 */
data class SpectrumEffectProfile(
    val enabled: Boolean = false,
    val tuning: AudioPresetTuning = AudioPreset.NARROW_AM.defaultTuning(),
)

/**
 * Applies the observable part of the production DynamicsProcessing topology
 * to a spectrum frame. The result is intentionally an estimate: the native
 * MBC envelope and output-stage/HAL processing are not available to apps.
 */
internal object SpectrumEffectEstimator {
    fun apply(
        frame: SpectrumFrame,
        profile: SpectrumEffectProfile,
    ): SpectrumFrame {
        if (!profile.enabled || frame.levelsDb.isEmpty()) return frame

        val tuning = profile.tuning.sanitized()
        val gainsDb = frame.levelsDb.indices.map { index ->
            val centerHz = SpectrumMath.bandCentersHz
                .getOrNull(index)
                ?.toFloat()
                ?: SpectrumMath.bandCentersHz.last().toFloat()
            tuning.gainDbForCenterHz(centerHz)
                .coerceAtMost(postEqBoostLimitDb(tuning))
        }
        val inputPower = frame.levelsDb.sumOf { dbToPower(it) }
        val equalizedPower = frame.levelsDb.indices.sumOf { index ->
            dbToPower(frame.levelsDb[index] + gainsDb[index])
        }
        val equalizerOffsetDb = if (inputPower <= 0.0 || equalizedPower <= 0.0) {
            0f
        } else {
            (10.0 * log10(equalizedPower / inputPower)).toFloat()
        }
        val dynamicOffsetDb = estimateDynamicsOffsetDb(tuning, frame.rmsDb)
        val levelsDb = frame.levelsDb.indices.map { index ->
            (frame.levelsDb[index] + gainsDb[index] + dynamicOffsetDb)
                .coerceIn(SpectrumMath.FLOOR_DB, LIMITER_CEILING_DB)
        }
        return SpectrumFrame(
            levelsDb = levelsDb,
            rmsDb = (frame.rmsDb + equalizerOffsetDb + dynamicOffsetDb)
                .coerceIn(SpectrumMath.FLOOR_DB, LIMITER_CEILING_DB),
            peakDb = (frame.peakDb + gainsDb.maxOrNull().orZero() + dynamicOffsetDb)
                .coerceIn(SpectrumMath.FLOOR_DB, LIMITER_CEILING_DB),
        )
    }

    private fun estimateDynamicsOffsetDb(
        tuning: AudioPresetTuning,
        inputRmsDb: Float,
    ): Float {
        val relief = tuning.distortionRelief.coerceIn(0f, 1f)
        val inputGainDb = tuning.inputGainDb
            .coerceAtMost(DP_ONLY_INPUT_GAIN_MAX_DB)
            .let { lerp(it, 0f, relief) }
        val wideShape = ((tuning.highCutHz - 3_000f) / 1_000f).coerceIn(0f, 1f)
        val strongShape = ((tuning.mbcRatio - 10f) / 6f).coerceIn(0f, 1f)
        val saturation = tuning.inputGainDb >= 5f
        val effectivePostGainDb = tuning.mbcPostGainDb + tuning.makeupGainDb
        val reductionDb = if (saturation) {
            8f
        } else {
            14f - 2f * wideShape - 5f * strongShape
        }
        val postGainDb = lerp(
            (effectivePostGainDb - reductionDb).coerceIn(0f, DP_ONLY_MBC_POST_GAIN_MAX_DB),
            0f,
            relief,
        )
        val ratioScale = if (saturation) {
            0.4f
        } else {
            0.12f + 0.03f * wideShape + 0.13f * strongShape
        }
        val baseRatio = (tuning.mbcRatio * ratioScale)
            .coerceIn(DP_ONLY_MBC_RATIO_MIN, DP_ONLY_MBC_RATIO_MAX)
        val targetRatio = if (saturation) 4f else 1f
        val ratio = lerp(baseRatio, targetRatio, relief)
            .coerceIn(DP_ONLY_MBC_RATIO_MIN, DP_ONLY_MBC_RATIO_MAX)
        val thresholdDb = lerp(
            tuning.mbcThresholdDb.coerceAtLeast(DP_ONLY_MBC_THRESHOLD_FLOOR_DB),
            DP_ONLY_RELAXED_THRESHOLD_DB,
            relief,
        )
        val levelAfterInputGainDb = inputRmsDb + inputGainDb
        val overThresholdDb = (levelAfterInputGainDb - thresholdDb).coerceAtLeast(0f)
        val compressionReductionDb = overThresholdDb * (1f - 1f / ratio)
        return inputGainDb + postGainDb - compressionReductionDb
    }

    private fun postEqBoostLimitDb(tuning: AudioPresetTuning): Float =
        lerp(DP_ONLY_POST_EQ_MAX_BOOST_DB, DP_ONLY_RELAXED_POST_EQ_MAX_BOOST_DB, tuning.distortionRelief)

    private fun dbToPower(db: Float): Double {
        if (db <= SpectrumMath.FLOOR_DB) return 0.0
        return 10.0.pow(db.toDouble() / 10.0)
    }

    private fun Float?.orZero(): Float = this ?: 0f

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private const val LIMITER_CEILING_DB = -1f
    private const val DP_ONLY_POST_EQ_MAX_BOOST_DB = 3f
    private const val DP_ONLY_RELAXED_POST_EQ_MAX_BOOST_DB = 2f
    private const val DP_ONLY_MBC_RATIO_MIN = 1f
    private const val DP_ONLY_MBC_RATIO_MAX = 8f
    private const val DP_ONLY_MBC_THRESHOLD_FLOOR_DB = -18f
    private const val DP_ONLY_RELAXED_THRESHOLD_DB = -12f
    private const val DP_ONLY_MBC_POST_GAIN_MAX_DB = 9f
    private const val DP_ONLY_INPUT_GAIN_MAX_DB = 6f
}
