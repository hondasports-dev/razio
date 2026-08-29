package dev.hondasports.razio.audio.preset

/** Session 0 PoC 用の仮 AM カーブ。端末の band 数は仮定しない。 */
object AmPreset {
    const val LOW_CUT_HZ = 250f
    const val MID_LOW_HZ = 1000f
    const val MID_HIGH_HZ = 2000f
    const val HIGH_CUT_HZ = 3500f
    const val LOW_GAIN_DB = -12f
    const val MID_GAIN_DB = 3f
    const val HIGH_GAIN_DB = -12f

    fun gainDbForCenterHz(centerHz: Float): Float {
        val hz = centerHz.coerceAtLeast(1f)
        return when {
            hz <= LOW_CUT_HZ -> LOW_GAIN_DB
            hz < MID_LOW_HZ -> lerp(LOW_GAIN_DB, MID_GAIN_DB, (hz - LOW_CUT_HZ) / (MID_LOW_HZ - LOW_CUT_HZ))
            hz <= MID_HIGH_HZ -> MID_GAIN_DB
            hz < HIGH_CUT_HZ -> lerp(MID_GAIN_DB, HIGH_GAIN_DB, (hz - MID_HIGH_HZ) / (HIGH_CUT_HZ - MID_HIGH_HZ))
            else -> HIGH_GAIN_DB
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
}
