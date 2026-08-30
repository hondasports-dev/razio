package dev.hondasports.razio.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A single, normalized spectrum snapshot used by both capture paths and the UI. */
internal data class SpectrumFrame(
    val levelsDb: List<Float>,
    val rmsDb: Float,
    val peakDb: Float,
)

/**
 * Pure FFT and level mapping for the analyzer.
 *
 * The visualizer callback is only an 8-bit waveform tap and AudioRecord delivers PCM16.
 * Both are normalized here so the two charts use the same -80..0 dBFS display range.
 */
internal object SpectrumMath {
    const val FFT_SIZE = 1_024
    const val FLOOR_DB = -80f

    val bandCentersHz: List<Int> = listOf(
        80,
        160,
        315,
        630,
        1_250,
        2_500,
        4_000,
        6_300,
        10_000,
        16_000,
    )

    fun fromPcm16(
        samples: ShortArray,
        sampleCount: Int = samples.size,
        sampleRateHz: Int,
    ): SpectrumFrame {
        val usableCount = sampleCount.coerceIn(0, samples.size)
        val normalized = FloatArray(FFT_SIZE)
        val copyCount = usableCount.coerceAtMost(FFT_SIZE)
        for (index in 0 until copyCount) {
            normalized[index] = samples[index].toFloat() / Short.MAX_VALUE.toFloat()
        }
        return fromNormalized(normalized, copyCount, sampleRateHz)
    }

    fun fromVisualizerWaveform(
        waveform: ByteArray,
        samplingRate: Int,
    ): SpectrumFrame {
        val normalized = FloatArray(FFT_SIZE)
        val copyCount = waveform.size.coerceAtMost(FFT_SIZE)
        for (index in 0 until copyCount) {
            // Visualizer waveform data is unsigned 8-bit PCM centered at 128.
            val unsignedSample = waveform[index].toInt() and 0xFF
            normalized[index] = (unsignedSample - 128).toFloat() / 128f
        }
        val sampleRateHz = if (samplingRate > 100_000) {
            samplingRate / 1_000
        } else {
            samplingRate
        }
        return fromNormalized(normalized, copyCount, sampleRateHz)
    }

    private fun fromNormalized(
        samples: FloatArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): SpectrumFrame {
        val fftReal = FloatArray(FFT_SIZE)
        val fftImaginary = FloatArray(FFT_SIZE)
        var sumSquares = 0.0
        var peak = 0f
        val usableCount = sampleCount.coerceIn(0, samples.size)
        for (index in 0 until usableCount) {
            val value = samples[index]
            sumSquares += value.toDouble() * value.toDouble()
            peak = max(peak, abs(value))
            val window = 0.5f - 0.5f * cos(2f * PI.toFloat() * index / (FFT_SIZE - 1))
            fftReal[index] = value * window
        }
        fft(fftReal, fftImaginary)

        val levels = bandCentersHz.map { centerHz ->
            val lowHz = (centerHz * 0.75f).coerceAtLeast(1f)
            val highHz = centerHz * 1.33f
            val firstBin = (lowHz / sampleRateHz.coerceAtLeast(1) * FFT_SIZE)
                .toInt()
                .coerceIn(1, FFT_SIZE / 2)
            val lastBin = (highHz / sampleRateHz.coerceAtLeast(1) * FFT_SIZE)
                .toInt()
                .coerceIn(firstBin, FFT_SIZE / 2)
            var magnitudeSquares = 0.0
            var binCount = 0
            for (bin in firstBin..lastBin) {
                val magnitude = sqrt(
                    fftReal[bin].toDouble() * fftReal[bin].toDouble() +
                        fftImaginary[bin].toDouble() * fftImaginary[bin].toDouble(),
                ) * 2.0 / FFT_SIZE
                magnitudeSquares += magnitude * magnitude
                binCount += 1
            }
            val rmsMagnitude = if (binCount == 0) {
                0.0
            } else {
                sqrt(magnitudeSquares / binCount)
            }
            toDb(rmsMagnitude)
        }
        val rms = if (usableCount == 0) {
            0.0
        } else {
            sqrt(sumSquares / usableCount)
        }
        return SpectrumFrame(
            levelsDb = levels,
            rmsDb = toDb(rms),
            peakDb = toDb(peak.toDouble()),
        )
    }

    private fun toDb(linear: Double): Float {
        if (!linear.isFinite() || linear <= 0.00001) return FLOOR_DB
        return (20.0 * (ln(linear) / LN_10)).toFloat().coerceIn(FLOOR_DB, 0f)
    }

    private fun fft(real: FloatArray, imaginary: FloatArray) {
        var j = 0
        for (index in 1 until real.size) {
            var bit = real.size shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (index < j) {
                val realValue = real[index]
                real[index] = real[j]
                real[j] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[j]
                imaginary[j] = imaginaryValue
            }
        }

        var length = 2
        while (length <= real.size) {
            val angle = -2.0 * PI / length
            val stepCos = cos(angle).toFloat()
            val stepSin = sin(angle).toFloat()
            var start = 0
            while (start < real.size) {
                var currentCos = 1f
                var currentSin = 0f
                val half = length / 2
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val oddReal = real[odd] * currentCos - imaginary[odd] * currentSin
                    val oddImaginary = real[odd] * currentSin + imaginary[odd] * currentCos
                    val evenReal = real[even]
                    val evenImaginary = imaginary[even]
                    real[even] = evenReal + oddReal
                    imaginary[even] = evenImaginary + oddImaginary
                    real[odd] = evenReal - oddReal
                    imaginary[odd] = evenImaginary - oddImaginary
                    val nextCos = currentCos * stepCos - currentSin * stepSin
                    currentSin = currentCos * stepSin + currentSin * stepCos
                    currentCos = nextCos
                }
                start += length
            }
            length = length shl 1
        }
    }

    private const val LN_10 = 2.302585092994046
}

enum class SpectrumAnalyzerStatus {
    Idle,
    Starting,
    Active,
    Partial,
    Stopped,
    Error,
}

data class SpectrumSnapshot(
    val available: Boolean = false,
    val levelsDb: List<Float> = SpectrumMath.bandCentersHz.map { SpectrumMath.FLOOR_DB },
    val rmsDb: Float = SpectrumMath.FLOOR_DB,
    val peakDb: Float = SpectrumMath.FLOOR_DB,
    val frameCount: Long = 0L,
    val detail: String = "",
)

data class SpectrumAnalyzerUiState(
    val running: Boolean = false,
    val status: SpectrumAnalyzerStatus = SpectrumAnalyzerStatus.Idle,
    val input: SpectrumSnapshot = SpectrumSnapshot(),
    val output: SpectrumSnapshot = SpectrumSnapshot(),
    val detail: String = "",
)
