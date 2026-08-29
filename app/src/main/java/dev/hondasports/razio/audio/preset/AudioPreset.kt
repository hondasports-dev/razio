package dev.hondasports.razio.audio.preset

/**
 * Sound curves that can be applied to the session 0 AudioEffect chain.
 *
 * The values are intentionally expressed as a small, platform-independent
 * domain type. The Android Equalizer range is applied only by [millibels].
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
) {
    NARROW_AM(
        id = "narrow_am",
        // Keep the voice band present while trimming the extremes that made
        // the first wider pass sound too hi-fi on modern speakers.
        lowCutHz = 250f,
        midLowHz = 500f,
        midHighHz = 2_400f,
        highCutHz = 3_400f,
        lowGainDb = -18f,
        midGainDb = 6f,
        highGainDb = -24f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
    ),
    VINTAGE_SPEAKER(
        id = "vintage_speaker",
        // A small paper-cone enclosure keeps the vocal band (roughly
        // 350 Hz–3 kHz) forward while rolling off the extremes.
        lowCutHz = 120f,
        midLowHz = 350f,
        midHighHz = 3_000f,
        highCutHz = 4_800f,
        lowGainDb = -18f,
        midGainDb = 4f,
        highGainDb = -20f,
        mbcRatio = 10f,
        mbcThresholdDb = -24f,
        mbcPostGainDb = 6f,
        makeupGainDb = 8f,
    ),
    WEAK_SIGNAL(
        id = "weak_signal",
        lowCutHz = 320f,
        midLowHz = 850f,
        midHighHz = 1_150f,
        highCutHz = 1_450f,
        lowGainDb = -18f,
        midGainDb = 4f,
        highGainDb = -24f,
        mbcRatio = 16f,
        mbcThresholdDb = -30f,
        mbcPostGainDb = 8f,
        makeupGainDb = 10f,
    ),
    SATURATION(
        id = "saturation",
        // DynamicsProcessing has no wave-shaper. A moderate input push into
        // a strong MBC/limiter gives a safe, audible saturation approximation.
        lowCutHz = 100f,
        midLowHz = 300f,
        midHighHz = 3_000f,
        highCutHz = 7_000f,
        lowGainDb = -8f,
        midGainDb = 2f,
        highGainDb = -8f,
        mbcRatio = 20f,
        mbcThresholdDb = -18f,
        mbcPostGainDb = 4f,
        makeupGainDb = 4f,
        inputGainDb = 10f,
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
