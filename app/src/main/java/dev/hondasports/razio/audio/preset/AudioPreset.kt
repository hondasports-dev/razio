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
) {
    NARROW_AM(
        id = "narrow_am",
        lowCutHz = 300f,
        midLowHz = 950f,
        midHighHz = 1_050f,
        highCutHz = 1_600f,
        lowGainDb = -15f,
        midGainDb = 6f,
        highGainDb = -15f,
    ),
    VINTAGE_SPEAKER(
        id = "vintage_speaker",
        lowCutHz = 220f,
        midLowHz = 700f,
        midHighHz = 1_350f,
        highCutHz = 2_800f,
        lowGainDb = -12f,
        midGainDb = 4f,
        highGainDb = -12f,
    ),
    ;

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
        val millibels = (gainDbForCenterHz(centerHz) * 100f).toInt()
        return millibels.coerceIn(minMillibels.toInt(), maxMillibels.toInt()).toShort()
    }

    private fun lerp(
        start: Float,
        end: Float,
        t: Float,
    ): Float = start + (end - start) * t.coerceIn(0f, 1f)

    companion object {
        fun fromId(id: String?): AudioPreset {
            return entries.firstOrNull { it.id == id } ?: NARROW_AM
        }
    }
}
