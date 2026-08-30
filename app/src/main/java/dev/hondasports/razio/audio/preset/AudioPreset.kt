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
    val midLowHz: Float,
    val midHighHz: Float,
    val highCutHz: Float,
    val lowGainDb: Float,
    val midGainDb: Float,
    val highGainDb: Float,
    val mbcRatio: Float,
    val mbcThresholdDb: Float,
    val mbcPostGainDb: Float,
    val makeupGainDb: Float,
    val inputGainDb: Float = 0f,
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
        midLowHz = 550f,
        midHighHz = 2_200f,
        highCutHz = 3_000f,
        lowGainDb = -30f,
        midGainDb = 6f,
        highGainDb = -48f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
    ),
    VINTAGE_SPEAKER(
        id = "vintage_speaker",
        // A small paper-cone enclosure keeps the vocal band (roughly
        // 450 Hz–2.6 kHz) forward while rolling off the extremes.
        lowCutHz = 180f,
        midLowHz = 450f,
        midHighHz = 2_600f,
        highCutHz = 4_000f,
        lowGainDb = -30f,
        midGainDb = 5f,
        highGainDb = -48f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
    ),
    WEAK_SIGNAL(
        id = "weak_signal",
        lowCutHz = 380f,
        midLowHz = 900f,
        midHighHz = 1_100f,
        highCutHz = 1_350f,
        lowGainDb = -30f,
        midGainDb = 5f,
        highGainDb = -48f,
        mbcRatio = 16f,
        mbcThresholdDb = -30f,
        mbcPostGainDb = 8f,
        makeupGainDb = 10f,
    ),
    SATURATION(
        id = "saturation",
        // DynamicsProcessing has no wave-shaper. A moderate input push into
        // a strong MBC/limiter gives a safe, audible saturation approximation.
        lowCutHz = 180f,
        midLowHz = 450f,
        midHighHz = 2_400f,
        highCutHz = 5_000f,
        lowGainDb = -24f,
        midGainDb = 2f,
        highGainDb = -48f,
        mbcRatio = 20f,
        mbcThresholdDb = -18f,
        mbcPostGainDb = 4f,
        makeupGainDb = 4f,
        inputGainDb = 10f,
    ),
    FADING(
        id = "fading",
        // Keep the Narrow AM spectrum; the audible distinction is the
        // slow input-gain movement that approximates reception fading.
        lowCutHz = 300f,
        midLowHz = 550f,
        midHighHz = 2_200f,
        highCutHz = 3_000f,
        lowGainDb = -30f,
        midGainDb = 6f,
        highGainDb = -48f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
        fadeDepthDb = 3f,
        fadePeriodMs = 3_200L,
    ),
    ;

    val effectiveMbcPostGainDb: Float
        get() = mbcPostGainDb + makeupGainDb

    fun gainDbForCenterHz(centerHz: Float): Float {
        val hz = centerHz.coerceAtLeast(1f)
        return when {
            hz <= lowCutHz -> lowGainDb
            hz < midLowHz -> lerp(lowGainDb, midGainDb, (hz - lowCutHz) / (midLowHz - lowCutHz))
            hz <= midHighHz -> midGainDb
            hz < highCutHz -> lerp(midGainDb, highGainDb, (hz - midHighHz) / (highCutHz - midHighHz))
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

    internal fun parameters(): AudioPresetParameters {
        return AudioPresetParameters(
            lowCutHz = lowCutHz,
            midLowHz = midLowHz,
            midHighHz = midHighHz,
            highCutHz = highCutHz,
            lowGainDb = lowGainDb,
            midGainDb = midGainDb,
            highGainDb = highGainDb,
            mbcRatio = mbcRatio,
            mbcThresholdDb = mbcThresholdDb,
            effectiveMbcPostGainDb = effectiveMbcPostGainDb,
            inputGainDb = inputGainDb,
        )
    }
}

/**
 * Interpolatable effect parameters used while a preset is changing.
 *
 * Keeping the current frame as parameters instead of an enum value lets a rapid
 * preset change continue from the value already written to the native effect.
 */
internal data class AudioPresetParameters(
    val lowCutHz: Float,
    val midLowHz: Float,
    val midHighHz: Float,
    val highCutHz: Float,
    val lowGainDb: Float,
    val midGainDb: Float,
    val highGainDb: Float,
    val mbcRatio: Float,
    val mbcThresholdDb: Float,
    val effectiveMbcPostGainDb: Float,
    val inputGainDb: Float,
) {
    fun gainDbForCenterHz(centerHz: Float): Float {
        val hz = centerHz.coerceAtLeast(1f)
        return when {
            hz <= lowCutHz -> lowGainDb
            hz < midLowHz -> lerp(lowGainDb, midGainDb, (hz - lowCutHz) / (midLowHz - lowCutHz))
            hz <= midHighHz -> midGainDb
            hz < highCutHz -> lerp(midGainDb, highGainDb, (hz - midHighHz) / (highCutHz - midHighHz))
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
                midLowHz = lerp(from.midLowHz, to.midLowHz, t),
                midHighHz = lerp(from.midHighHz, to.midHighHz, t),
                highCutHz = lerp(from.highCutHz, to.highCutHz, t),
                lowGainDb = lerp(from.lowGainDb, to.lowGainDb, t),
                midGainDb = lerp(from.midGainDb, to.midGainDb, t),
                highGainDb = lerp(from.highGainDb, to.highGainDb, t),
                mbcRatio = lerp(from.mbcRatio, to.mbcRatio, t),
                mbcThresholdDb = lerp(from.mbcThresholdDb, to.mbcThresholdDb, t),
                effectiveMbcPostGainDb = lerp(
                    from.effectiveMbcPostGainDb,
                    to.effectiveMbcPostGainDb,
                    t,
                ),
                inputGainDb = lerp(from.inputGainDb, to.inputGainDb, t),
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
