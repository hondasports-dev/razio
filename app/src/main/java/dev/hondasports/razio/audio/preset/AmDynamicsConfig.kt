package dev.hondasports.razio.audio.preset

import android.media.audiofx.DynamicsProcessing

internal object AmDynamicsConfig {
    private val preEqCutoffsHz = floatArrayOf(90f, 250f, 800f, 1_500f, 2_500f, 4_500f)
    private val mbcCutoffsHz = floatArrayOf(250f, 2_000f, 8_000f)
    private const val MBC_ATTACK_MS = 5f
    private const val MBC_RELEASE_MS = 120f
    private const val MBC_KNEE_DB = 6f

    fun build(
        channelCount: Int,
        preset: AudioPreset,
        usePreEqCurve: Boolean = true,
    ): DynamicsProcessing.Config {
        val parameters = preset.parameters()
        val preEq = buildPreEq(parameters, usePreEqCurve)
        val mbc = buildMbc(parameters)
        val limiter = buildLimiter()

        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channelCount,
            true,
            preEqCutoffsHz.size,
            true,
            mbcCutoffsHz.size,
            false,
            0,
            true,
        )
            .setInputGainAllChannelsTo(parameters.inputGainDb)
            .setPreEqAllChannelsTo(preEq)
            .setMbcAllChannelsTo(mbc)
            .setLimiterAllChannelsTo(limiter)
            .build()
    }

    /**
     * Updates an existing DynamicsProcessing instance without releasing its native effect.
     * The caller can use this for a no-gap preset transition.
     */
    fun applyPreset(
        dynamics: DynamicsProcessing,
        preset: AudioPreset,
        usePreEqCurve: Boolean,
    ) {
        applyPresetAtProgress(
            dynamics = dynamics,
            from = preset.parameters(),
            to = preset.parameters(),
            progress = 1f,
            usePreEqCurve = usePreEqCurve,
        )
    }

    /**
     * Applies an interpolated preset. Equalizer and MBC parameters are updated in place so
     * callers can spread a transition over several audio frames instead of dropping the chain.
     */
    fun applyPresetAtProgress(
        dynamics: DynamicsProcessing,
        from: AudioPresetParameters,
        to: AudioPresetParameters,
        progress: Float,
        usePreEqCurve: Boolean,
    ) {
        val t = progress.coerceIn(0f, 1f)
        dynamics.setInputGainAllChannelsTo(
            lerp(from.inputGainDb, to.inputGainDb, t),
        )
        val config = dynamics.config
        val preEqBandCount = config.preEqBandCount.coerceAtMost(preEqCutoffsHz.size)
        for (index in 0 until preEqBandCount) {
            val cutoffHz = preEqCutoffsHz[index]
            val previous = if (index == 0) 20f else preEqCutoffsHz[index - 1]
            val centerHz = kotlin.math.sqrt(previous * cutoffHz)
            val gainDb = if (usePreEqCurve) {
                lerp(from.gainDbForCenterHz(centerHz), to.gainDbForCenterHz(centerHz), t)
            } else {
                0f
            }
            dynamics.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(true, cutoffHz, gainDb),
            )
        }
        val mbcBandCount = config.mbcBandCount.coerceAtMost(mbcCutoffsHz.size)
        for (index in 0 until mbcBandCount) {
            val cutoffHz = mbcCutoffsHz[index]
            dynamics.setMbcBandAllChannelsTo(
                index,
                DynamicsProcessing.MbcBand(
                    true,
                    cutoffHz,
                    MBC_ATTACK_MS,
                    MBC_RELEASE_MS,
                    lerp(from.mbcRatio, to.mbcRatio, t),
                    lerp(from.mbcThresholdDb, to.mbcThresholdDb, t),
                    MBC_KNEE_DB,
                    -80f,
                    1f,
                    0f,
                    lerp(from.effectiveMbcPostGainDb, to.effectiveMbcPostGainDb, t),
                ),
            )
        }
    }

    private fun buildPreEq(
        parameters: AudioPresetParameters,
        usePreEqCurve: Boolean,
    ): DynamicsProcessing.Eq {
        val preEq = DynamicsProcessing.Eq(true, true, preEqCutoffsHz.size)
        preEqCutoffsHz.forEachIndexed { index, cutoffHz ->
            val previous = if (index == 0) 20f else preEqCutoffsHz[index - 1]
            val centerHz = kotlin.math.sqrt(previous * cutoffHz)
            val gainDb = if (usePreEqCurve) parameters.gainDbForCenterHz(centerHz) else 0f
            preEq.setBand(index, DynamicsProcessing.EqBand(true, cutoffHz, gainDb))
        }
        return preEq
    }

    private fun buildMbc(parameters: AudioPresetParameters): DynamicsProcessing.Mbc {
        val mbc = DynamicsProcessing.Mbc(true, true, mbcCutoffsHz.size)
        mbcCutoffsHz.forEachIndexed { index, cutoffHz ->
            mbc.setBand(index, createMbcBand(cutoffHz, parameters))
        }
        return mbc
    }

    private fun createMbcBand(
        cutoffHz: Float,
        parameters: AudioPresetParameters,
    ): DynamicsProcessing.MbcBand {
        return DynamicsProcessing.MbcBand(
            true,
            cutoffHz,
            MBC_ATTACK_MS,
            MBC_RELEASE_MS,
            parameters.mbcRatio,
            parameters.mbcThresholdDb,
            MBC_KNEE_DB,
            -80f,
            1f,
            0f,
            parameters.effectiveMbcPostGainDb,
        )
    }

    private fun buildLimiter(): DynamicsProcessing.Limiter {
        return DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
    }

    private fun lerp(
        start: Float,
        end: Float,
        progress: Float,
    ): Float = start + (end - start) * progress.coerceIn(0f, 1f)
}
