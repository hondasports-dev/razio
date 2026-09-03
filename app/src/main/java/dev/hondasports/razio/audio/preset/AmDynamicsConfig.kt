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
    internal const val POST_EQ_BAND_COUNT = 9
    internal const val POST_EQ_HIGH_CUT_BAND_START_INDEX = 6
    internal const val POST_EQ_FINAL_CUTOFF_HZ = 20_000f
    internal const val POST_EQ_HIGH_CUT_GAIN_DB = -48f
    private val mbcCutoffsHz = floatArrayOf(250f, 2_000f, 8_000f)
    private const val MBC_ATTACK_MS = 5f
    private const val MBC_RELEASE_MS = 120f
    private const val MBC_KNEE_DB = 6f
    // The single DynamicsProcessing backend uses a gentler compressor for
    // non-saturation presets after the first distortion report. A second
    // relief pass is controlled by AudioPresetParameters.distortionRelief:
    // Weak signal is deliberately 0 (unchanged), while the other presets get
    // extra headroom. The Dynamics-only path also puts its final Post-EQ after
    // MBC, so the relief is applied before the final tone curve.
    private const val DP_ONLY_MBC_RATIO_BASE_SCALE = 0.12f
    private const val DP_ONLY_MBC_RATIO_WIDE_SCALE = 0.03f
    private const val DP_ONLY_MBC_RATIO_STRONG_SCALE = 0.13f
    private const val DP_ONLY_MBC_RATIO_MAX = 8f
    private const val DP_ONLY_MBC_RATIO_MIN = 1f
    private const val DP_ONLY_MBC_THRESHOLD_FLOOR_DB = -18f
    private const val DP_ONLY_MBC_POST_GAIN_MAX_DB = 9f
    private const val DP_ONLY_INPUT_GAIN_MAX_DB = 6f
    private const val DP_ONLY_RELAXED_THRESHOLD_DB = -12f
    private const val DP_ONLY_RELAXED_MBC_RATIO_TARGET = 1f
    private const val DP_ONLY_RELAXED_SATURATION_RATIO_TARGET = 4f
    private const val DP_ONLY_RELAXED_INPUT_GAIN_TARGET_DB = 0f
    private const val DP_ONLY_RELAXED_MBC_POST_GAIN_TARGET_DB = 0f
    // Keep the voice lift below the limiter headroom. The relief pass lowers
    // the +3 dB cap toward +2 dB without changing Weak signal's +3 dB target.
    private const val DP_ONLY_POST_EQ_MAX_BOOST_DB = 3f
    private const val DP_ONLY_RELAXED_POST_EQ_MAX_BOOST_DB = 2f
    private const val DP_ONLY_MBC_ATTACK_MS = 20f
    private const val DP_ONLY_MBC_RELEASE_MS = 230f
    private const val DP_ONLY_MBC_KNEE_DB = 12f
    private const val DP_ONLY_RELAXED_MBC_ATTACK_MS = 30f
    private const val DP_ONLY_RELAXED_MBC_RELEASE_MS = 300f
    private const val DP_ONLY_RELAXED_MBC_KNEE_DB = 18f

    fun build(
        channelCount: Int,
        preset: AudioPreset,
        usePreEqCurve: Boolean = true,
        usePostEqCurve: Boolean = false,
        parameters: AudioPresetParameters = preset.parameters(),
    ): DynamicsProcessing.Config {
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
            POST_EQ_BAND_COUNT,
            true,
            mbcCutoffsHz.size,
            usePostEqCurve,
            if (usePostEqCurve) POST_EQ_BAND_COUNT else 0,
            true,
        )
            .setInputGainAllChannelsTo(
                configInputGainDb(
                    parameters,
                    usePostEqCurve = usePostEqCurve,
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
        parameters: AudioPresetParameters = preset.parameters(),
    ) {
        applyPresetAtProgress(
            dynamics = dynamics,
            from = parameters,
            to = parameters,
            progress = 1f,
            usePreEqCurve = usePreEqCurve,
            usePostEqCurve = usePostEqCurve,
        )
    }

    /**
     * Applies an interpolated preset. Post-EQ and MBC parameters are updated in place so
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
                configInputGainDb(from, usePostEqCurve),
                configInputGainDb(to, usePostEqCurve),
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
                    lerp(attackMs(from, fromGentle), attackMs(to, toGentle), t),
                    lerp(releaseMs(from, fromGentle), releaseMs(to, toGentle), t),
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
                    lerp(kneeDb(from, fromGentle), kneeDb(to, toGentle), t),
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
        postGainOverrideDb: Float? = null,
    ): DynamicsProcessing.MbcBand {
        return DynamicsProcessing.MbcBand(
            true,
            cutoffHz,
            attackMs(parameters, gentle),
            releaseMs(parameters, gentle),
            mbcRatio(parameters, gentle),
            mbcThresholdDb(parameters, gentle),
            kneeDb(parameters, gentle),
            -80f,
            1f,
            0f,
            postGainOverrideDb ?: mbcPostGainDb(parameters, gentle),
        )
    }

    /**
     * Input gain written into the DynamicsProcessing Config / apply path.
     * Pixel session 0 rejects later setInputGain on some configs
     * (Shortwave), so fading presets leave 0 here. The Handler writes
     * input gain when the HAL allows it, otherwise MBC post-gain.
     */
    fun configInputGainDb(
        parameters: AudioPresetParameters,
        usePostEqCurve: Boolean,
    ): Float {
        if (parameters.fadeDepthDb > 0f) return 0f
        return resolvedInputGainDb(parameters, usePostEqCurve)
    }

    fun resolvedInputGainDb(
        parameters: AudioPresetParameters,
        usePostEqCurve: Boolean,
    ): Float = inputGainDb(
        parameters,
        gentle = usePostEqCurve || !isSaturation(parameters),
    )

    /**
     * Writes the current fading wander. Pixel session 0 accepts later
     * `setInputGain` on Fading but rejects it after a Shortwave Config;
     * MBC post-gain stays writable and is the fallback level path.
     */
    fun applyFadingGain(
        dynamics: DynamicsProcessing,
        parameters: AudioPresetParameters,
        elapsedMs: Long,
        usePostEqCurve: Boolean,
        preferMbcPost: Boolean = false,
    ): FadingApplyMode {
        if (!preferMbcPost) {
            val gainDb = fadingInputGainDb(
                parameters = parameters,
                elapsedMs = elapsedMs,
                usePostEqCurve = usePostEqCurve,
            )
            val inputWrite = runCatching {
                dynamics.setInputGainAllChannelsTo(gainDb)
            }
            if (inputWrite.isSuccess) return FadingApplyMode.INPUT_GAIN
        }
        applyFadingToMbcPost(
            dynamics = dynamics,
            parameters = parameters,
            elapsedMs = elapsedMs,
            usePostEqCurve = usePostEqCurve,
        )
        return FadingApplyMode.MBC_POST
    }

    private fun applyFadingToMbcPost(
        dynamics: DynamicsProcessing,
        parameters: AudioPresetParameters,
        elapsedMs: Long,
        usePostEqCurve: Boolean,
    ) {
        val gentle = usePostEqCurve || !isSaturation(parameters)
        val postGainDb = fadingMbcPostGainDb(
            parameters = parameters,
            elapsedMs = elapsedMs,
            usePostEqCurve = usePostEqCurve,
        )
        val bandCount = dynamics.config.mbcBandCount.coerceAtMost(mbcCutoffsHz.size)
        for (index in 0 until bandCount) {
            dynamics.setMbcBandAllChannelsTo(
                index,
                createMbcBand(
                    cutoffHz = mbcCutoffsHz[index],
                    parameters = parameters,
                    gentle = gentle,
                    postGainOverrideDb = postGainDb,
                ),
            )
        }
    }

    fun fadingInputGainDb(
        parameters: AudioPresetParameters,
        elapsedMs: Long,
        usePostEqCurve: Boolean = true,
    ): Float {
        val base = resolvedInputGainDb(parameters, usePostEqCurve)
        if (parameters.fadeDepthDb <= 0f || parameters.fadePeriodMs <= 0L) {
            return base
        }
        val phase = (elapsedMs.toDouble() / parameters.fadePeriodMs.toDouble()) *
            (2.0 * kotlin.math.PI)
        val gainDb = base + kotlin.math.sin(phase).toFloat() * parameters.fadeDepthDb
        return gainDb.coerceIn(
            AudioPresetTuning.MIN_INPUT_GAIN_DB,
            AudioPresetTuning.MAX_INPUT_GAIN_DB,
        )
    }

    fun fadingMbcPostGainDb(
        parameters: AudioPresetParameters,
        elapsedMs: Long,
        usePostEqCurve: Boolean = true,
    ): Float {
        val gentle = usePostEqCurve || !isSaturation(parameters)
        val offsetDb = fadingInputGainDb(parameters, elapsedMs, usePostEqCurve) -
            resolvedInputGainDb(parameters, usePostEqCurve)
        return (mbcPostGainDb(parameters, gentle) + offsetDb)
            .coerceIn(0f, DP_ONLY_MBC_POST_GAIN_MAX_DB)
    }

    fun shouldLogFadingFailure(consecutiveFailures: Int): Boolean =
        consecutiveFailures in 1..3 ||
            (consecutiveFailures > 0 && consecutiveFailures % 10 == 0)

    fun nextFadingDelayMs(
        consecutiveFailures: Int,
        tickMs: Long = 100L,
        retryMs: Long = 500L,
    ): Long = if (consecutiveFailures > 0) retryMs else tickMs

    private fun inputGainDb(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        if (!gentle) return parameters.inputGainDb
        val baseGain = parameters.inputGainDb.coerceAtMost(DP_ONLY_INPUT_GAIN_MAX_DB)
        return lerp(
            baseGain,
            DP_ONLY_RELAXED_INPUT_GAIN_TARGET_DB,
            parameters.distortionRelief,
        )
    }

    private fun mbcRatio(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        if (!gentle) return parameters.mbcRatio
        val baseRatio = (parameters.mbcRatio * dpOnlyRatioScale(parameters))
            .coerceIn(DP_ONLY_MBC_RATIO_MIN, DP_ONLY_MBC_RATIO_MAX)
        val targetRatio = if (isSaturation(parameters)) {
            DP_ONLY_RELAXED_SATURATION_RATIO_TARGET
        } else {
            DP_ONLY_RELAXED_MBC_RATIO_TARGET
        }
        return lerp(baseRatio, targetRatio, parameters.distortionRelief)
            .coerceIn(DP_ONLY_MBC_RATIO_MIN, DP_ONLY_MBC_RATIO_MAX)
    }

    private fun mbcThresholdDb(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        if (!gentle) return parameters.mbcThresholdDb
        val baseThreshold = parameters.mbcThresholdDb
            .coerceAtLeast(DP_ONLY_MBC_THRESHOLD_FLOOR_DB)
        return lerp(baseThreshold, DP_ONLY_RELAXED_THRESHOLD_DB, parameters.distortionRelief)
    }

    private fun mbcPostGainDb(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float {
        if (!gentle) return parameters.effectiveMbcPostGainDb
        val reductionDb = if (isSaturation(parameters)) {
            8f
        } else {
            14f -
                2f * dpOnlyWideShape(parameters) -
                5f * dpOnlyStrongShape(parameters)
        }
        val basePostGain = (parameters.effectiveMbcPostGainDb - reductionDb)
            .coerceIn(0f, DP_ONLY_MBC_POST_GAIN_MAX_DB)
        return lerp(
            basePostGain,
            DP_ONLY_RELAXED_MBC_POST_GAIN_TARGET_DB,
            parameters.distortionRelief,
        )
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
    ): Float {
        val maxBoost = lerp(
            DP_ONLY_POST_EQ_MAX_BOOST_DB,
            DP_ONLY_RELAXED_POST_EQ_MAX_BOOST_DB,
            parameters.distortionRelief,
        )
        return parameters.gainDbForCenterHz(centerHz).coerceAtMost(maxBoost)
    }

    private fun isSaturation(parameters: AudioPresetParameters): Boolean =
        parameters.inputGainDb >= 5f

    private fun attackMs(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float = if (gentle) {
        lerp(DP_ONLY_MBC_ATTACK_MS, DP_ONLY_RELAXED_MBC_ATTACK_MS, parameters.distortionRelief)
    } else {
        MBC_ATTACK_MS
    }

    private fun releaseMs(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float = if (gentle) {
        lerp(DP_ONLY_MBC_RELEASE_MS, DP_ONLY_RELAXED_MBC_RELEASE_MS, parameters.distortionRelief)
    } else {
        MBC_RELEASE_MS
    }

    private fun kneeDb(
        parameters: AudioPresetParameters,
        gentle: Boolean,
    ): Float = if (gentle) {
        lerp(DP_ONLY_MBC_KNEE_DB, DP_ONLY_RELAXED_MBC_KNEE_DB, parameters.distortionRelief)
    } else {
        MBC_KNEE_DB
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

internal enum class FadingApplyMode {
    INPUT_GAIN,
    MBC_POST,
}
