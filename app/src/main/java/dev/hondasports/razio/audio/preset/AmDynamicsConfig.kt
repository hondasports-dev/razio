package dev.hondasports.razio.audio.preset

import android.media.audiofx.DynamicsProcessing

internal object AmDynamicsConfig {
    // DynamicsProcessing.EqBand uses each cutoff as the top of that band. The
    // old list stopped at 4.5 kHz, so everything above it bypassed the curve.
    // Keep the voice-focused bands and extend the final band through the
    // useful 44.1/48 kHz audible range so the high cut is actually applied.
    private val eqCutoffsHz = floatArrayOf(
        90f,
        250f,
        800f,
        1_500f,
        2_500f,
        4_500f,
        9_000f,
        18_000f,
        // Keep the upper cutoff valid on common 44.1/48 kHz output routes.
        20_000f,
    )
    private val mbcCutoffsHz = floatArrayOf(250f, 2_000f, 8_000f)
    private const val MBC_ATTACK_MS = 5f
    private const val MBC_RELEASE_MS = 120f
    private const val MBC_KNEE_DB = 6f
    // Both backends now use a gentler compressor for non-saturation presets
    // after the user reported a faint edge on AM/Vintage/Weak/Fading. The
    // Dynamics-only path also puts its final Post-EQ after MBC. The resulting
    // non-saturation profiles are approximately Narrow/Fading 1.2:1 / 0 dB,
    // Vintage 1.5:1 / 2 dB, and Weak 4:1 / 9 dB. Saturation keeps its strong
    // backend-specific character instead of being softened by this change.
    private const val DP_ONLY_MBC_RATIO_BASE_SCALE = 0.12f
    private const val DP_ONLY_MBC_RATIO_WIDE_SCALE = 0.03f
    private const val DP_ONLY_MBC_RATIO_STRONG_SCALE = 0.13f
    private const val DP_ONLY_MBC_RATIO_MAX = 8f
    private const val DP_ONLY_MBC_RATIO_MIN = 1f
    private const val DP_ONLY_MBC_THRESHOLD_FLOOR_DB = -18f
    private const val DP_ONLY_MBC_POST_GAIN_MAX_DB = 9f
    private const val DP_ONLY_INPUT_GAIN_MAX_DB = 6f
    // Keep the voice lift below the limiter headroom. Saturation's native
    // +2 dB mid boost is unchanged; the cap mainly limits the +4 to +6 dB
    // lift used by the other presets that was causing a faint limiter edge.
    private const val DP_ONLY_POST_EQ_MAX_BOOST_DB = 3f
    private const val DP_ONLY_MBC_ATTACK_MS = 20f
    private const val DP_ONLY_MBC_RELEASE_MS = 230f
    private const val DP_ONLY_MBC_KNEE_DB = 12f

    fun build(
        channelCount: Int,
        preset: AudioPreset,
        usePreEqCurve: Boolean = true,
        usePostEqCurve: Boolean = false,
    ): DynamicsProcessing.Config {
        val parameters = preset.parameters()
        val preEq = buildEq(parameters, useCurve = usePreEqCurve)
        val postEq = buildEq(
            parameters,
            useCurve = usePostEqCurve,
            limitBoost = usePostEqCurve,
        )
        val mbc = buildMbc(parameters, gentle = usePostEqCurve)
        val limiter = buildLimiter()

        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channelCount,
            true,
            eqCutoffsHz.size,
            true,
            mbcCutoffsHz.size,
            usePostEqCurve,
            if (usePostEqCurve) eqCutoffsHz.size else 0,
            true,
        )
            .setInputGainAllChannelsTo(
                inputGainDb(
                    parameters,
                    gentle = usePostEqCurve || !isSaturation(parameters),
                ),
            )
            .setPreEqAllChannelsTo(preEq)
            .setMbcAllChannelsTo(mbc)
            .let { builder ->
                if (usePostEqCurve) builder.setPostEqAllChannelsTo(postEq) else builder
            }
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
        usePostEqCurve: Boolean = false,
    ) {
        applyPresetAtProgress(
            dynamics = dynamics,
            from = preset.parameters(),
            to = preset.parameters(),
            progress = 1f,
            usePreEqCurve = usePreEqCurve,
            usePostEqCurve = usePostEqCurve,
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
        usePostEqCurve: Boolean = false,
    ) {
        val t = progress.coerceIn(0f, 1f)
        dynamics.setInputGainAllChannelsTo(
            lerp(
                inputGainDb(
                    from,
                    gentle = usePostEqCurve || !isSaturation(from),
                ),
                inputGainDb(
                    to,
                    gentle = usePostEqCurve || !isSaturation(to),
                ),
                t,
            ),
        )
        val config = dynamics.config
        val preEqBandCount = config.preEqBandCount.coerceAtMost(eqCutoffsHz.size)
        for (index in 0 until preEqBandCount) {
            val cutoffHz = eqCutoffsHz[index]
            val previous = if (index == 0) 20f else eqCutoffsHz[index - 1]
            val centerHz = kotlin.math.sqrt(previous * cutoffHz)
            val gainDb = if (usePreEqCurve) {
                lerp(
                    from.gainDbForCenterHz(centerHz),
                    to.gainDbForCenterHz(centerHz),
                    t,
                )
            } else {
                0f
            }
            dynamics.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(true, cutoffHz, gainDb),
            )
        }
        val postEqBandCount = config.postEqBandCount.coerceAtMost(eqCutoffsHz.size)
        for (index in 0 until postEqBandCount) {
            val cutoffHz = eqCutoffsHz[index]
            val previous = if (index == 0) 20f else eqCutoffsHz[index - 1]
            val centerHz = kotlin.math.sqrt(previous * cutoffHz)
            val gainDb = if (usePostEqCurve) {
                lerp(
                    postEqGainDb(from, centerHz),
                    postEqGainDb(to, centerHz),
                    t,
                )
            } else {
                0f
            }
            dynamics.setPostEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(true, cutoffHz, gainDb),
            )
        }
        val mbcBandCount = config.mbcBandCount.coerceAtMost(mbcCutoffsHz.size)
        for (index in 0 until mbcBandCount) {
            val cutoffHz = mbcCutoffsHz[index]
            val fromGentle = usePostEqCurve || !isSaturation(from)
            val toGentle = usePostEqCurve || !isSaturation(to)
            dynamics.setMbcBandAllChannelsTo(
                index,
                DynamicsProcessing.MbcBand(
                    true,
                    cutoffHz,
                    lerp(attackMs(fromGentle), attackMs(toGentle), t),
                    lerp(releaseMs(fromGentle), releaseMs(toGentle), t),
                    lerp(
                        mbcRatio(from, gentle = fromGentle),
                        mbcRatio(to, gentle = toGentle),
                        t,
                    ),
                    lerp(
                        mbcThresholdDb(from, gentle = fromGentle),
                        mbcThresholdDb(to, gentle = toGentle),
                        t,
                    ),
                    lerp(kneeDb(fromGentle), kneeDb(toGentle), t),
                    -80f,
                    1f,
                    0f,
                    lerp(
                        mbcPostGainDb(from, gentle = fromGentle),
                        mbcPostGainDb(to, gentle = toGentle),
                        t,
                    ),
                ),
            )
        }
    }

    private fun buildEq(
        parameters: AudioPresetParameters,
        useCurve: Boolean,
        limitBoost: Boolean = false,
    ): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, eqCutoffsHz.size)
        eqCutoffsHz.forEachIndexed { index, cutoffHz ->
            val previous = if (index == 0) 20f else eqCutoffsHz[index - 1]
            val centerHz = kotlin.math.sqrt(previous * cutoffHz)
            val gainDb = if (useCurve) {
                val target = parameters.gainDbForCenterHz(centerHz)
                if (limitBoost) target.coerceAtMost(DP_ONLY_POST_EQ_MAX_BOOST_DB) else target
            } else {
                0f
            }
            eq.setBand(index, DynamicsProcessing.EqBand(true, cutoffHz, gainDb))
        }
        return eq
    }

    private fun buildMbc(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): DynamicsProcessing.Mbc {
        val mbc = DynamicsProcessing.Mbc(true, true, mbcCutoffsHz.size)
        mbcCutoffsHz.forEachIndexed { index, cutoffHz ->
            mbc.setBand(
                index,
                createMbcBand(
                    cutoffHz,
                    parameters,
                    gentle = gentle || !isSaturation(parameters),
                ),
            )
        }
        return mbc
    }

    private fun createMbcBand(
        cutoffHz: Float,
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): DynamicsProcessing.MbcBand {
        return DynamicsProcessing.MbcBand(
            true,
            cutoffHz,
            attackMs(gentle),
            releaseMs(gentle),
            mbcRatio(parameters, gentle),
            mbcThresholdDb(parameters, gentle),
            kneeDb(gentle),
            -80f,
            1f,
            0f,
            mbcPostGainDb(parameters, gentle),
        )
    }

    private fun inputGainDb(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        return if (gentle) {
            parameters.inputGainDb.coerceAtMost(DP_ONLY_INPUT_GAIN_MAX_DB)
        } else {
            parameters.inputGainDb
        }
    }

    private fun mbcRatio(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        return if (gentle) {
            (parameters.mbcRatio * dpOnlyRatioScale(parameters))
                .coerceIn(DP_ONLY_MBC_RATIO_MIN, DP_ONLY_MBC_RATIO_MAX)
        } else {
            parameters.mbcRatio
        }
    }

    private fun mbcThresholdDb(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        return if (gentle) {
            parameters.mbcThresholdDb.coerceAtLeast(DP_ONLY_MBC_THRESHOLD_FLOOR_DB)
        } else {
            parameters.mbcThresholdDb
        }
    }

    private fun mbcPostGainDb(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        return if (gentle) {
            val reductionDb = if (isSaturation(parameters)) {
                8f
            } else {
                14f -
                    2f * dpOnlyWideShape(parameters) -
                    5f * dpOnlyStrongShape(parameters)
            }
            (parameters.effectiveMbcPostGainDb - reductionDb)
                .coerceIn(0f, DP_ONLY_MBC_POST_GAIN_MAX_DB)
        } else {
            parameters.effectiveMbcPostGainDb
        }
    }

    private fun dpOnlyRatioScale(parameters: AudioPresetParameters): Float {
        if (isSaturation(parameters)) return 0.4f
        return DP_ONLY_MBC_RATIO_BASE_SCALE +
            DP_ONLY_MBC_RATIO_WIDE_SCALE * dpOnlyWideShape(parameters) +
            DP_ONLY_MBC_RATIO_STRONG_SCALE * dpOnlyStrongShape(parameters)
    }

    private fun dpOnlyWideShape(parameters: AudioPresetParameters): Float =
        ((parameters.highCutHz - 3_000f) / 1_000f).coerceIn(0f, 1f)

    private fun dpOnlyStrongShape(parameters: AudioPresetParameters): Float =
        ((parameters.mbcRatio - 10f) / 6f).coerceIn(0f, 1f)

    private fun postEqGainDb(
        parameters: AudioPresetParameters,
        centerHz: Float,
    ): Float = parameters.gainDbForCenterHz(centerHz)
        .coerceAtMost(DP_ONLY_POST_EQ_MAX_BOOST_DB)

    private fun isSaturation(parameters: AudioPresetParameters): Boolean =
        parameters.inputGainDb >= 5f

    private fun attackMs(gentle: Boolean): Float =
        if (gentle) DP_ONLY_MBC_ATTACK_MS else MBC_ATTACK_MS

    private fun releaseMs(gentle: Boolean): Float =
        if (gentle) DP_ONLY_MBC_RELEASE_MS else MBC_RELEASE_MS

    private fun kneeDb(gentle: Boolean): Float =
        if (gentle) DP_ONLY_MBC_KNEE_DB else MBC_KNEE_DB

    private fun buildLimiter(): DynamicsProcessing.Limiter {
        return DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
    }

    private fun lerp(
        start: Float,
        end: Float,
        progress: Float,
    ): Float = start + (end - start) * progress.coerceIn(0f, 1f)
}
