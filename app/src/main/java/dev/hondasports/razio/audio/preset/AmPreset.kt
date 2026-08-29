package dev.hondasports.razio.audio.preset

/** Backward-compatible name for the default Narrow AM curve. */
object AmPreset {
    const val LOW_CUT_HZ = 250f
    const val MID_LOW_HZ = 500f
    const val MID_HIGH_HZ = 2400f
    const val HIGH_CUT_HZ = 3400f
    const val LOW_GAIN_DB = -18f
    const val MID_GAIN_DB = 6f
    const val HIGH_GAIN_DB = -24f

    fun gainDbForCenterHz(centerHz: Float): Float {
        return AudioPreset.NARROW_AM.gainDbForCenterHz(centerHz)
    }

    fun millibels(
        centerHz: Float,
        minMillibels: Short,
        maxMillibels: Short,
    ): Short {
        return AudioPreset.NARROW_AM.millibels(centerHz, minMillibels, maxMillibels)
    }
}
