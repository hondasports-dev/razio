package dev.hondasports.razio.ui.screen

import dev.hondasports.razio.audio.SpectrumMath
import dev.hondasports.razio.audio.preset.AudioPreset
import dev.hondasports.razio.audio.preset.AudioPresetTuning
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val CAR_AUDIO_SEGMENT_COUNT = 12
internal const val CAR_AUDIO_DECAY_DB_PER_SECOND = 48f
internal const val CAR_AUDIO_PEAK_FALL_DB_PER_SECOND = 14f
internal const val CAR_AUDIO_CLIP_DB = -1.5f
private const val DEMO_CEILING_DB = -4f
private const val DEMO_GAIN_FLOOR_DB = -48f
private const val DEMO_GAIN_SPAN_DB = 54f

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

internal fun carAudioIsClipping(peakDb: Float, capturing: Boolean): Boolean =
    capturing && peakDb >= CAR_AUDIO_CLIP_DB

internal fun carAudioStationReadout(preset: AudioPreset): String = when (preset) {
    AudioPreset.NARROW_AM -> "1000 kHz"
    AudioPreset.VINTAGE_SPEAKER -> "828 kHz"
    AudioPreset.WEAK_SIGNAL -> "558 kHz"
    AudioPreset.SATURATION -> "1314 kHz"
    AudioPreset.FADING -> "1179 kHz"
    AudioPreset.SHORTWAVE -> "6.09 MHz"
}

internal fun demoSpectrumLevels(
    tuning: AudioPresetTuning,
    timeSeconds: Float,
    bandCentersHz: List<Int> = SpectrumMath.bandCentersHz,
): List<Float> {
    val sanitized = tuning.sanitized()
    val span = -SpectrumMath.FLOOR_DB
    val count = bandCentersHz.size.coerceAtLeast(1)
    val travelPos = ((timeSeconds * 0.42f).mod(1f)) * (count - 1).coerceAtLeast(0)
    return bandCentersHz.mapIndexed { index, centerHz ->
        val gain = sanitized.gainDbForCenterHz(centerHz.toFloat())
        val envelope = ((gain - DEMO_GAIN_FLOOR_DB) / DEMO_GAIN_SPAN_DB).coerceIn(0.06f, 1f)
        val wander = 0.42f + 0.58f * (
            0.5f + 0.5f * sin((timeSeconds * 1.7f) + index * 0.23f)
            )
        val distance = abs(index - travelPos)
        val bump = 0.62f + 0.38f * exp(-(distance * distance) / 22.0).toFloat()
        val fraction = (envelope * wander * bump).coerceIn(0.04f, 1f)
        (SpectrumMath.FLOOR_DB + span * fraction).coerceIn(SpectrumMath.FLOOR_DB, DEMO_CEILING_DB)
    }
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
