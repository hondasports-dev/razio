package dev.hondasports.razio.audio.preset

import android.media.audiofx.DynamicsProcessing

internal object AmDynamicsConfig {
    private val preEqCutoffsHz = floatArrayOf(90f, 250f, 800f, 1_500f, 2_500f, 4_500f)
    private val mbcCutoffsHz = floatArrayOf(250f, 2_000f, 8_000f)
    private const val MBC_ATTACK_MS = 5f
    private const val MBC_RELEASE_MS = 120f
    private const val MBC_RATIO = 10f
    private const val MBC_THRESHOLD_DB = -24f
    private const val MBC_KNEE_DB = 6f
    private const val MBC_POST_GAIN_DB = 4f

    fun build(
        channelCount: Int,
        preset: AudioPreset,
    ): DynamicsProcessing.Config {
        val preEq = DynamicsProcessing.Eq(true, true, preEqCutoffsHz.size)
        preEqCutoffsHz.forEachIndexed { index, cutoffHz ->
            val previous = if (index == 0) 20f else preEqCutoffsHz[index - 1]
            val centerHz = kotlin.math.sqrt(previous * cutoffHz)
            preEq.setBand(
                index,
                DynamicsProcessing.EqBand(true, cutoffHz, preset.gainDbForCenterHz(centerHz)),
            )
        }

        val mbc = DynamicsProcessing.Mbc(true, true, mbcCutoffsHz.size)
        mbcCutoffsHz.forEachIndexed { index, cutoffHz ->
            mbc.setBand(
                index,
                DynamicsProcessing.MbcBand(
                    true,
                    cutoffHz,
                    MBC_ATTACK_MS,
                    MBC_RELEASE_MS,
                    MBC_RATIO,
                    MBC_THRESHOLD_DB,
                    MBC_KNEE_DB,
                    -80f,
                    1f,
                    0f,
                    MBC_POST_GAIN_DB,
                ),
            )
        }

        val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)

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
            .setPreEqAllChannelsTo(preEq)
            .setMbcAllChannelsTo(mbc)
            .setLimiterAllChannelsTo(limiter)
            .build()
    }
}
