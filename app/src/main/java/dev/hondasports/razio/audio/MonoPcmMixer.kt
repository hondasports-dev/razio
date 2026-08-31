package dev.hondasports.razio.audio

/**
 * Small, allocation-free PCM helper for the Mono passthrough PoC.
 *
 * Stereo input is averaged in Int space and duplicated to a stereo output so the
 * experiment can keep the device's normal output route while making the left and
 * right samples identical. A mono capture fallback is copied to both channels but
 * is reported as a partial capture by the controller because it cannot prove L/R
 * mixing.
 */
internal object MonoPcmMixer {
    const val STEREO_CHANNELS = 2

    /** Returns the number of complete stereo frames written to [output]. */
    fun downmixStereoToStereo(
        input: ShortArray,
        inputSampleCount: Int,
        output: ShortArray,
    ): Int {
        val frameCount = (inputSampleCount.coerceIn(0, input.size) / STEREO_CHANNELS)
            .coerceAtMost(output.size / STEREO_CHANNELS)
        for (frame in 0 until frameCount) {
            val inputOffset = frame * STEREO_CHANNELS
            val mixed = (input[inputOffset].toInt() + input[inputOffset + 1].toInt()) / 2
            val outputOffset = frame * STEREO_CHANNELS
            output[outputOffset] = mixed.toShort()
            output[outputOffset + 1] = mixed.toShort()
        }
        return frameCount
    }

    /** Returns the number of mono samples copied to stereo [output]. */
    fun copyMonoToStereo(
        input: ShortArray,
        inputSampleCount: Int,
        output: ShortArray,
    ): Int {
        val sampleCount = inputSampleCount.coerceIn(0, input.size)
            .coerceAtMost(output.size / STEREO_CHANNELS)
        for (sample in 0 until sampleCount) {
            val value = input[sample]
            val outputOffset = sample * STEREO_CHANNELS
            output[outputOffset] = value
            output[outputOffset + 1] = value
        }
        return sampleCount
    }
}
