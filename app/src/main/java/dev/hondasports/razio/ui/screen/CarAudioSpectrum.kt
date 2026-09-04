package dev.hondasports.razio.ui.screen

import dev.hondasports.razio.audio.SpectrumMath
import kotlin.math.max
import kotlin.math.roundToInt

internal const val CAR_AUDIO_SEGMENT_COUNT = 12
internal const val CAR_AUDIO_DECAY_DB_PER_SECOND = 48f
internal const val CAR_AUDIO_PEAK_FALL_DB_PER_SECOND = 14f

internal data class CarAudioSpectrumHold(
    val displayedDb: List<Float>,
    val peakDb: List<Float>,
) {
    companion object {
        fun idle(bandCount: Int = SpectrumMath.bandCentersHz.size): CarAudioSpectrumHold {
            val floor = List(bandCount) { SpectrumMath.FLOOR_DB }
            return CarAudioSpectrumHold(displayedDb = floor, peakDb = floor)
        }
    }
}

internal fun carAudioLitSegments(db: Float): Int {
    val fraction = ((db - SpectrumMath.FLOOR_DB) / -SpectrumMath.FLOOR_DB).coerceIn(0f, 1f)
    return (fraction * CAR_AUDIO_SEGMENT_COUNT).roundToInt().coerceIn(0, CAR_AUDIO_SEGMENT_COUNT)
}

internal fun stepCarAudioSpectrum(
    previous: CarAudioSpectrumHold,
    incomingDb: List<Float>,
    dtSeconds: Float,
    running: Boolean,
): CarAudioSpectrumHold {
    val bandCount = SpectrumMath.bandCentersHz.size
    if (!running) {
        return CarAudioSpectrumHold.idle(bandCount)
    }
    val dt = dtSeconds.coerceAtLeast(0f)
    val displayed = List(bandCount) { index ->
        val incoming = incomingDb.getOrElse(index) { SpectrumMath.FLOOR_DB }
        val previousDb = previous.displayedDb.getOrElse(index) { SpectrumMath.FLOOR_DB }
        val decayed = previousDb - CAR_AUDIO_DECAY_DB_PER_SECOND * dt
        max(incoming, decayed).coerceIn(SpectrumMath.FLOOR_DB, 0f)
    }
    val peaks = List(bandCount) { index ->
        val level = displayed[index]
        val previousPeak = previous.peakDb.getOrElse(index) { SpectrumMath.FLOOR_DB }
        val fallen = previousPeak - CAR_AUDIO_PEAK_FALL_DB_PER_SECOND * dt
        max(level, fallen).coerceIn(SpectrumMath.FLOOR_DB, 0f)
    }
    return CarAudioSpectrumHold(displayedDb = displayed, peakDb = peaks)
}
