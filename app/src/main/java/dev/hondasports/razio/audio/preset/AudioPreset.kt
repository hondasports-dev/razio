package dev.hondasports.razio.audio.preset

/**
 * Sound curves that can be applied to the session 0 AudioEffect chain.
 *
 * The values are intentionally expressed as a small, platform-independent
 * domain type and applied by DynamicsProcessing's Post-EQ.
 */
enum class AudioPreset(
    val id: String,
    val lowCutHz: Float,
    val lowTransitionHz: Float,
    val midLowHz: Float,
    val midHighHz: Float,
    val highTransitionHz: Float,
    val highCutHz: Float,
    val lowGainDb: Float,
    val lowTransitionGainDb: Float,
    val midGainDb: Float,
    val highTransitionGainDb: Float,
    val highGainDb: Float,
    val mbcRatio: Float,
    val mbcThresholdDb: Float,
    val mbcPostGainDb: Float,
    val makeupGainDb: Float,
    val inputGainDb: Float = 0f,
    /** 0 keeps the existing character; 1 applies the strongest safe softening. */
    val distortionRelief: Float = 0f,
    val fadeDepthDb: Float = 0f,
    val fadePeriodMs: Long = 0L,
) {
    NARROW_AM(
        id = "narrow_am",
        // Keep the voice band present while trimming the extremes that made
        // the first wider pass sound too hi-fi on modern speakers. Keep the
        // upper band substantially below the voice range. The
        // DynamicsProcessing-only path can retain this deeper target instead
        // of being limited by a device Equalizer's shallow floor.
        lowCutHz = 300f,
        lowTransitionHz = 420f,
        midLowHz = 550f,
        midHighHz = 2_200f,
        highTransitionHz = 2_600f,
        highCutHz = 3_000f,
        lowGainDb = -30f,
        lowTransitionGainDb = -13f,
        midGainDb = 6f,
        highTransitionGainDb = -21f,
        highGainDb = -48f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
        distortionRelief = 1f,
    ),
    VINTAGE_SPEAKER(
        id = "vintage_speaker",
        // A small paper-cone enclosure keeps the vocal band (roughly
        // 450 Hz–2.6 kHz) forward while rolling off the extremes.
        lowCutHz = 180f,
        lowTransitionHz = 320f,
        midLowHz = 450f,
        midHighHz = 2_600f,
        highTransitionHz = 3_300f,
        highCutHz = 4_000f,
        lowGainDb = -30f,
        lowTransitionGainDb = -12f,
        midGainDb = 5f,
        highTransitionGainDb = -22f,
        highGainDb = -48f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
        distortionRelief = 0.8f,
    ),
    WEAK_SIGNAL(
        id = "weak_signal",
        lowCutHz = 380f,
        lowTransitionHz = 640f,
        midLowHz = 900f,
        midHighHz = 1_100f,
        highTransitionHz = 1_220f,
        highCutHz = 1_350f,
        lowGainDb = -30f,
        lowTransitionGainDb = -13f,
        midGainDb = 5f,
        highTransitionGainDb = -20f,
        highGainDb = -48f,
        mbcRatio = 16f,
        mbcThresholdDb = -30f,
        mbcPostGainDb = 8f,
        makeupGainDb = 10f,
        distortionRelief = 0f,
    ),
    SATURATION(
        id = "saturation",
        // DynamicsProcessing has no wave-shaper. A moderate input push into
        // a strong MBC/limiter gives a safe, audible saturation approximation.
        lowCutHz = 180f,
        lowTransitionHz = 320f,
        midLowHz = 450f,
        midHighHz = 2_400f,
        highTransitionHz = 3_700f,
        highCutHz = 5_000f,
        lowGainDb = -24f,
        lowTransitionGainDb = -11f,
        midGainDb = 2f,
        highTransitionGainDb = -23f,
        highGainDb = -48f,
        mbcRatio = 20f,
        mbcThresholdDb = -18f,
        mbcPostGainDb = 4f,
        makeupGainDb = 4f,
        inputGainDb = 10f,
        distortionRelief = 0.75f,
    ),
    FADING(
        id = "fading",
        // Keep the Narrow AM spectrum; the audible distinction is the
        // slow input-gain movement that approximates reception fading.
        lowCutHz = 300f,
        lowTransitionHz = 420f,
        midLowHz = 550f,
        midHighHz = 2_200f,
        highTransitionHz = 2_600f,
        highCutHz = 3_000f,
        lowGainDb = -30f,
        lowTransitionGainDb = -13f,
        midGainDb = 6f,
        highTransitionGainDb = -21f,
        highGainDb = -48f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
        distortionRelief = 1f,
        fadeDepthDb = 3f,
        fadePeriodMs = 3_200L,
    ),
    SHORTWAVE(
        id = "shortwave",
        // Narrower than Weak signal, with faster fading so the band feels like
        // a distant shortwave voice rather than a local AM station.
        lowCutHz = 500f,
        lowTransitionHz = 680f,
        midLowHz = 850f,
        midHighHz = 1_150f,
        highTransitionHz = 1_200f,
        highCutHz = 1_250f,
        lowGainDb = -30f,
        lowTransitionGainDb = -14f,
        midGainDb = 4f,
        highTransitionGainDb = -22f,
        highGainDb = -48f,
        mbcRatio = 14f,
        mbcThresholdDb = -28f,
        mbcPostGainDb = 8f,
        makeupGainDb = 10f,
        inputGainDb = -1f,
        distortionRelief = 0.35f,
        fadeDepthDb = 6f,
        fadePeriodMs = 2_400L,
    ),
    ;

    val effectiveMbcPostGainDb: Float
        get() = mbcPostGainDb + makeupGainDb

    fun gainDbForCenterHz(centerHz: Float): Float {
        val hz = centerHz.coerceAtLeast(1f)
        return when {
            hz <= lowCutHz -> lowGainDb
            hz < lowTransitionHz -> lerp(
                lowGainDb,
                lowTransitionGainDb,
                (hz - lowCutHz) / (lowTransitionHz - lowCutHz),
            )
            hz < midLowHz -> lerp(
                lowTransitionGainDb,
                midGainDb,
                (hz - lowTransitionHz) / (midLowHz - lowTransitionHz),
            )
            hz <= midHighHz -> midGainDb
            hz < highTransitionHz -> lerp(
                midGainDb,
                highTransitionGainDb,
                (hz - midHighHz) / (highTransitionHz - midHighHz),
            )
            hz < highCutHz -> lerp(
                highTransitionGainDb,
                highGainDb,
                (hz - highTransitionHz) / (highCutHz - highTransitionHz),
            )
            else -> highGainDb
        }
    }

    fun millibels(
        centerHz: Float,
        minMillibels: Short,
        maxMillibels: Short,
    ): Short {
        return millibelsForGainDb(
            gainDb = gainDbForCenterHz(centerHz),
            minMillibels = minMillibels,
            maxMillibels = maxMillibels,
        )
    }

    private fun lerp(
        start: Float,
        end: Float,
        t: Float,
    ): Float = start + (end - start) * t.coerceIn(0f, 1f)

    companion object {
        internal fun millibelsForGainDb(
            gainDb: Float,
            minMillibels: Short,
            maxMillibels: Short,
        ): Short {
            val millibels = (gainDb * 100f).toInt()
            return millibels.coerceIn(minMillibels.toInt(), maxMillibels.toInt()).toShort()
        }

        fun fromId(id: String?): AudioPreset {
            return entries.firstOrNull { it.id == id } ?: NARROW_AM
        }
    }

    /** Returns the editable runtime defaults shown by the tuning panel. */
    fun defaultTuning(): AudioPresetTuning = AudioPresetTuning(
        lowCutHz = lowCutHz,
        lowTransitionHz = lowTransitionHz,
        midLowHz = midLowHz,
        midHighHz = midHighHz,
        highTransitionHz = highTransitionHz,
        highCutHz = highCutHz,
        lowGainDb = lowGainDb,
        lowTransitionGainDb = lowTransitionGainDb,
        midGainDb = midGainDb,
        highTransitionGainDb = highTransitionGainDb,
        highGainDb = highGainDb,
        mbcRatio = mbcRatio,
        mbcThresholdDb = mbcThresholdDb,
        mbcPostGainDb = mbcPostGainDb,
        makeupGainDb = makeupGainDb,
        inputGainDb = inputGainDb,
        distortionRelief = distortionRelief,
        fadeDepthDb = fadeDepthDb,
        fadePeriodMs = fadePeriodMs,
    )

    internal fun parameters(): AudioPresetParameters {
        return defaultTuning().toParameters()
    }
}

/** Runtime-adjustable values for the selected preset. */
data class AudioPresetTuning(
    val lowCutHz: Float,
    val lowTransitionHz: Float,
    val midLowHz: Float,
    val midHighHz: Float,
    val highTransitionHz: Float,
    val highCutHz: Float,
    val lowGainDb: Float,
    val lowTransitionGainDb: Float,
    val midGainDb: Float,
    val highTransitionGainDb: Float,
    val highGainDb: Float,
    val mbcRatio: Float,
    val mbcThresholdDb: Float,
    val mbcPostGainDb: Float,
    val makeupGainDb: Float,
    val inputGainDb: Float,
    val distortionRelief: Float,
    val fadeDepthDb: Float,
    val fadePeriodMs: Long,
) {
    fun gainDbForCenterHz(centerHz: Float): Float {
        val hz = centerHz.coerceAtLeast(1f)
        return when {
            hz <= lowCutHz -> lowGainDb
            hz < lowTransitionHz -> lerp(
                lowGainDb,
                lowTransitionGainDb,
                (hz - lowCutHz) / (lowTransitionHz - lowCutHz),
            )
            hz < midLowHz -> lerp(
                lowTransitionGainDb,
                midGainDb,
                (hz - lowTransitionHz) / (midLowHz - lowTransitionHz),
            )
            hz <= midHighHz -> midGainDb
            hz < highTransitionHz -> lerp(
                midGainDb,
                highTransitionGainDb,
                (hz - midHighHz) / (highTransitionHz - midHighHz),
            )
            hz < highCutHz -> lerp(
                highTransitionGainDb,
                highGainDb,
                (hz - highTransitionHz) / (highCutHz - highTransitionHz),
            )
            else -> highGainDb
        }
    }

    /** Keeps sliders inside safe ranges and preserves the frequency ordering. */
    fun sanitized(): AudioPresetTuning {
        val safeLowCutHz = lowCutHz.coerceIn(MIN_LOW_CUT_HZ, MAX_LOW_CUT_HZ)
        val safeLowTransitionHz = lowTransitionHz.coerceIn(
            safeLowCutHz + FREQUENCY_GUARD_HZ,
            MAX_LOW_TRANSITION_HZ,
        )
        val safeMidLowHz = midLowHz.coerceIn(
            safeLowTransitionHz + FREQUENCY_GUARD_HZ,
            MAX_MID_LOW_HZ,
        )
        val safeMidHighHz = midHighHz.coerceIn(
            safeMidLowHz + FREQUENCY_GUARD_HZ,
            MAX_MID_HIGH_HZ,
        )
        val safeHighTransitionHz = highTransitionHz.coerceIn(
            safeMidHighHz + FREQUENCY_GUARD_HZ,
            MAX_HIGH_TRANSITION_HZ,
        )
        val safeHighCutHz = highCutHz.coerceIn(
            safeHighTransitionHz + FREQUENCY_GUARD_HZ,
            MAX_HIGH_CUT_HZ,
        )
        return copy(
            lowCutHz = safeLowCutHz,
            lowTransitionHz = safeLowTransitionHz,
            midLowHz = safeMidLowHz,
            midHighHz = safeMidHighHz,
            highTransitionHz = safeHighTransitionHz,
            highCutHz = safeHighCutHz,
            lowGainDb = lowGainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
            lowTransitionGainDb = lowTransitionGainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
            midGainDb = midGainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
            highTransitionGainDb = highTransitionGainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
            highGainDb = highGainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
            mbcRatio = mbcRatio.coerceIn(MIN_MBC_RATIO, MAX_MBC_RATIO),
            mbcThresholdDb = mbcThresholdDb.coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB),
            mbcPostGainDb = mbcPostGainDb.coerceIn(MIN_POST_GAIN_DB, MAX_POST_GAIN_DB),
            makeupGainDb = makeupGainDb.coerceIn(MIN_MAKEUP_GAIN_DB, MAX_MAKEUP_GAIN_DB),
            inputGainDb = inputGainDb.coerceIn(MIN_INPUT_GAIN_DB, MAX_INPUT_GAIN_DB),
            distortionRelief = distortionRelief.coerceIn(0f, 1f),
            fadeDepthDb = fadeDepthDb.coerceIn(0f, MAX_FADE_DEPTH_DB),
            fadePeriodMs = fadePeriodMs.coerceIn(0L, MAX_FADE_PERIOD_MS),
        )
    }

    fun encoded(): String {
        val tuning = sanitized()
        return listOf(
            ENCODING_VERSION,
            tuning.lowCutHz,
            tuning.lowTransitionHz,
            tuning.midLowHz,
            tuning.midHighHz,
            tuning.highTransitionHz,
            tuning.highCutHz,
            tuning.lowGainDb,
            tuning.lowTransitionGainDb,
            tuning.midGainDb,
            tuning.highTransitionGainDb,
            tuning.highGainDb,
            tuning.mbcRatio,
            tuning.mbcThresholdDb,
            tuning.mbcPostGainDb,
            tuning.makeupGainDb,
            tuning.inputGainDb,
            tuning.distortionRelief,
            tuning.fadeDepthDb,
            tuning.fadePeriodMs,
        ).joinToString(ENCODING_SEPARATOR)
    }

    private fun lerp(
        start: Float,
        end: Float,
        progress: Float,
    ): Float = start + (end - start) * progress.coerceIn(0f, 1f)

    companion object {
        const val ENCODING_VERSION = "v1"
        const val ENCODING_SEPARATOR = "|"
        const val ENCODED_FIELD_COUNT = 20
        const val MIN_LOW_CUT_HZ = 20f
        const val MAX_LOW_CUT_HZ = 2_000f
        const val MAX_LOW_TRANSITION_HZ = 3_000f
        const val MAX_MID_LOW_HZ = 4_000f
        const val MAX_MID_HIGH_HZ = 10_000f
        const val MAX_HIGH_TRANSITION_HZ = 15_000f
        const val MAX_HIGH_CUT_HZ = 20_000f
        const val FREQUENCY_GUARD_HZ = 10f
        const val MIN_GAIN_DB = -48f
        const val MAX_GAIN_DB = 12f
        const val MIN_MBC_RATIO = 1f
        const val MAX_MBC_RATIO = 20f
        const val MIN_THRESHOLD_DB = -60f
        const val MAX_THRESHOLD_DB = 0f
        const val MIN_POST_GAIN_DB = -24f
        const val MAX_POST_GAIN_DB = 24f
        const val MIN_MAKEUP_GAIN_DB = -24f
        const val MAX_MAKEUP_GAIN_DB = 24f
        const val MIN_INPUT_GAIN_DB = -12f
        const val MAX_INPUT_GAIN_DB = 12f
        const val MAX_FADE_DEPTH_DB = 12f
        const val MAX_FADE_PERIOD_MS = 10_000L

        fun decode(raw: String?, fallback: AudioPresetTuning): AudioPresetTuning {
            if (raw.isNullOrBlank()) return fallback.sanitized()
            val parts = raw.split(ENCODING_SEPARATOR)
            if (parts.size != ENCODED_FIELD_COUNT || parts[0] != ENCODING_VERSION) {
                return fallback.sanitized()
            }
            return runCatching {
                AudioPresetTuning(
                    lowCutHz = parts[1].toFloat(),
                    lowTransitionHz = parts[2].toFloat(),
                    midLowHz = parts[3].toFloat(),
                    midHighHz = parts[4].toFloat(),
                    highTransitionHz = parts[5].toFloat(),
                    highCutHz = parts[6].toFloat(),
                    lowGainDb = parts[7].toFloat(),
                    lowTransitionGainDb = parts[8].toFloat(),
                    midGainDb = parts[9].toFloat(),
                    highTransitionGainDb = parts[10].toFloat(),
                    highGainDb = parts[11].toFloat(),
                    mbcRatio = parts[12].toFloat(),
                    mbcThresholdDb = parts[13].toFloat(),
                    mbcPostGainDb = parts[14].toFloat(),
                    makeupGainDb = parts[15].toFloat(),
                    inputGainDb = parts[16].toFloat(),
                    distortionRelief = parts[17].toFloat(),
                    fadeDepthDb = parts[18].toFloat(),
                    fadePeriodMs = parts[19].toLong(),
                ).sanitized()
            }.getOrElse { fallback.sanitized() }
        }
    }
}

internal fun AudioPresetTuning.toParameters(): AudioPresetParameters {
    val tuning = sanitized()
    return AudioPresetParameters(
        lowCutHz = tuning.lowCutHz,
        lowTransitionHz = tuning.lowTransitionHz,
        midLowHz = tuning.midLowHz,
        midHighHz = tuning.midHighHz,
        highTransitionHz = tuning.highTransitionHz,
        highCutHz = tuning.highCutHz,
        lowGainDb = tuning.lowGainDb,
        lowTransitionGainDb = tuning.lowTransitionGainDb,
        midGainDb = tuning.midGainDb,
        highTransitionGainDb = tuning.highTransitionGainDb,
        highGainDb = tuning.highGainDb,
        mbcRatio = tuning.mbcRatio,
        mbcThresholdDb = tuning.mbcThresholdDb,
        effectiveMbcPostGainDb = tuning.mbcPostGainDb + tuning.makeupGainDb,
        inputGainDb = tuning.inputGainDb,
        distortionRelief = tuning.distortionRelief,
        fadeDepthDb = tuning.fadeDepthDb,
        fadePeriodMs = tuning.fadePeriodMs,
    )
}

/**
 * Interpolatable effect parameters used while a preset is changing.
 *
 * Keeping the current frame as parameters instead of an enum value lets a rapid
 * preset change continue from the value already written to the native effect.
 */
internal data class AudioPresetParameters(
    val lowCutHz: Float,
    val lowTransitionHz: Float,
    val midLowHz: Float,
    val midHighHz: Float,
    val highTransitionHz: Float,
    val highCutHz: Float,
    val lowGainDb: Float,
    val lowTransitionGainDb: Float,
    val midGainDb: Float,
    val highTransitionGainDb: Float,
    val highGainDb: Float,
    val mbcRatio: Float,
    val mbcThresholdDb: Float,
    val effectiveMbcPostGainDb: Float,
    val inputGainDb: Float,
    val distortionRelief: Float,
    val fadeDepthDb: Float,
    val fadePeriodMs: Long,
) {
    fun gainDbForCenterHz(centerHz: Float): Float {
        val hz = centerHz.coerceAtLeast(1f)
        return when {
            hz <= lowCutHz -> lowGainDb
            hz < lowTransitionHz -> lerp(
                lowGainDb,
                lowTransitionGainDb,
                (hz - lowCutHz) / (lowTransitionHz - lowCutHz),
            )
            hz < midLowHz -> lerp(
                lowTransitionGainDb,
                midGainDb,
                (hz - lowTransitionHz) / (midLowHz - lowTransitionHz),
            )
            hz <= midHighHz -> midGainDb
            hz < highTransitionHz -> lerp(
                midGainDb,
                highTransitionGainDb,
                (hz - midHighHz) / (highTransitionHz - midHighHz),
            )
            hz < highCutHz -> lerp(
                highTransitionGainDb,
                highGainDb,
                (hz - highTransitionHz) / (highCutHz - highTransitionHz),
            )
            else -> highGainDb
        }
    }

    companion object {
        fun interpolate(
            from: AudioPresetParameters,
            to: AudioPresetParameters,
            progress: Float,
        ): AudioPresetParameters {
            val t = progress.coerceIn(0f, 1f)
            return AudioPresetParameters(
                lowCutHz = lerp(from.lowCutHz, to.lowCutHz, t),
                lowTransitionHz = lerp(from.lowTransitionHz, to.lowTransitionHz, t),
                midLowHz = lerp(from.midLowHz, to.midLowHz, t),
                midHighHz = lerp(from.midHighHz, to.midHighHz, t),
                highTransitionHz = lerp(from.highTransitionHz, to.highTransitionHz, t),
                highCutHz = lerp(from.highCutHz, to.highCutHz, t),
                lowGainDb = lerp(from.lowGainDb, to.lowGainDb, t),
                lowTransitionGainDb = lerp(from.lowTransitionGainDb, to.lowTransitionGainDb, t),
                midGainDb = lerp(from.midGainDb, to.midGainDb, t),
                highTransitionGainDb = lerp(from.highTransitionGainDb, to.highTransitionGainDb, t),
                highGainDb = lerp(from.highGainDb, to.highGainDb, t),
                mbcRatio = lerp(from.mbcRatio, to.mbcRatio, t),
                mbcThresholdDb = lerp(from.mbcThresholdDb, to.mbcThresholdDb, t),
                effectiveMbcPostGainDb = lerp(
                    from.effectiveMbcPostGainDb,
                    to.effectiveMbcPostGainDb,
                    t,
                ),
                inputGainDb = lerp(from.inputGainDb, to.inputGainDb, t),
                distortionRelief = lerp(from.distortionRelief, to.distortionRelief, t),
                fadeDepthDb = lerp(from.fadeDepthDb, to.fadeDepthDb, t),
                fadePeriodMs = lerp(
                    from.fadePeriodMs.toFloat(),
                    to.fadePeriodMs.toFloat(),
                    t,
                ).toLong(),
            )
        }

        private fun lerp(
            start: Float,
            end: Float,
            progress: Float,
        ): Float = start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private fun lerp(
        start: Float,
        end: Float,
        progress: Float,
    ): Float = start + (end - start) * progress.coerceIn(0f, 1f)
}
