package dev.hondasports.razio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generates independent Hiss / Crackle PCM and mixes it through a normal AudioTrack.
 *
 * This is deliberately an overlay PoC: it never captures or replays another app's
 * audio, never requests audio focus, and is disabled until the user explicitly turns
 * one of the noise switches on while RAZIO is active.
 */
class NoiseOverlayController {
    private val lock = Any()
    private val _state = MutableStateFlow(NoiseOverlayUiState())
    val state: StateFlow<NoiseOverlayUiState> = _state.asStateFlow()

    private var powerOn = false
    private var hissEnabled = false
    private var crackleEnabled = false
    private var hissGainDb = NoiseGainRange.DEFAULT_DB
    private var crackleGainDb = NoiseGainRange.DEFAULT_DB
    private var playback: Playback? = null
    private var playbackDetail: String? = null
    private var released = false

    fun setPowerOn(enabled: Boolean) {
        synchronized(lock) {
            if (released) return
            powerOn = enabled
        }
        reconcile()
    }

    /** Applies saved switch and level values before or after power is restored. */
    fun restoreSettings(settings: NoiseOverlaySettings) {
        synchronized(lock) {
            if (released) return
            hissEnabled = settings.hissEnabled
            crackleEnabled = settings.crackleEnabled
            hissGainDb = NoiseGainRange.sanitize(settings.hissGainDb)
            crackleGainDb = NoiseGainRange.sanitize(settings.crackleGainDb)
        }
        reconcile()
    }

    fun currentSettings(): NoiseOverlaySettings {
        return synchronized(lock) {
            NoiseOverlaySettings(
                hissEnabled = hissEnabled,
                crackleEnabled = crackleEnabled,
                hissGainDb = hissGainDb,
                crackleGainDb = crackleGainDb,
            )
        }
    }

    fun setHissEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (released || !powerOn) return
            hissEnabled = enabled
        }
        reconcile()
    }

    fun setCrackleEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (released || !powerOn) return
            crackleEnabled = enabled
        }
        reconcile()
    }

    /** Updates the Hiss mix gain while the overlay is available. */
    fun setHissGainDb(gainDb: Float) {
        synchronized(lock) {
            if (released || !powerOn) return
            hissGainDb = NoiseGainRange.sanitize(gainDb)
        }
        reconcile()
    }

    /** Updates the Crackle mix gain while the overlay is available. */
    fun setCrackleGainDb(gainDb: Float) {
        synchronized(lock) {
            if (released || !powerOn) return
            crackleGainDb = NoiseGainRange.sanitize(gainDb)
        }
        reconcile()
    }

    /**
     * Recreates the overlay track after an output route change. The generated signal
     * is independent, so no source-app capture or replacement is involved.
     */
    fun handleRouteChange() {
        val shouldRestart = synchronized(lock) {
            shouldPlayLocked() && playback != null
        }
        if (!shouldRestart) return
        stopPlayback("route_change")
        reconcile()
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            powerOn = false
            hissEnabled = false
            crackleEnabled = false
        }
        stopPlayback("release")
        publishStableState()
    }

    private fun reconcile() {
        val action = synchronized(lock) {
            when {
                released -> Action.NONE
                shouldPlayLocked() && playback == null -> Action.START
                !shouldPlayLocked() && playback != null -> Action.STOP
                else -> Action.NONE
            }
        }
        when (action) {
            Action.START -> startPlayback()
            Action.STOP -> stopPlayback("disabled")
            Action.NONE -> publishStableState()
        }
    }

    private fun startPlayback() {
        val requested = synchronized(lock) {
            if (released || !shouldPlayLocked() || playback != null) {
                null
            } else {
                NoiseOverlaySettings(
                    hissEnabled = hissEnabled,
                    crackleEnabled = crackleEnabled,
                    hissGainDb = hissGainDb,
                    crackleGainDb = crackleGainDb,
                )
            }
        } ?: return

        publishStartingState(requested)
        var track: AudioTrack? = null
        try {
            val sampleRate = nativeSampleRate()
            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                throw IllegalStateException("AudioTrack buffer size unavailable: $minBufferBytes")
            }
            val bufferBytes = max(minBufferBytes, sampleRate / 5 * BYTES_PER_SAMPLE)
            val candidate = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = candidate
            if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack is not initialized")
            }

            val stopSignal = AtomicBoolean(false)
            val generator = NoiseSignalGenerator(sampleRate)
            val writer = Thread(
                {
                    runWriter(
                        track = checkNotNull(track),
                        stopSignal = stopSignal,
                        generator = generator,
                        bufferSamples = bufferBytes / BYTES_PER_SAMPLE,
                    )
                },
                THREAD_NAME,
            )
            val newPlayback = Playback(
                track = checkNotNull(track),
                stopSignal = stopSignal,
                writer = writer,
                detail = "sampleRate=${sampleRate}Hz buffer=${bufferBytes}B " +
                    "session=${track.audioSessionId} usage=media content=unknown focus=none",
            )
            var started = false
            synchronized(lock) {
                if (!released && shouldPlayLocked() && playback == null) {
                    newPlayback.track.play()
                    playback = newPlayback
                    playbackDetail = newPlayback.detail
                    writer.start()
                    started = true
                }
            }
            if (!started) {
                releaseTrack(newPlayback.track)
                publishStableState()
                return
            }
            AudioEffectLog.i(
                "noise overlay started hiss=${requested.hissEnabled} " +
                    "hissGain=${formatGain(requested.hissGainDb)} " +
                    "crackle=${requested.crackleEnabled} " +
                    "crackleGain=${formatGain(requested.crackleGainDb)} " +
                    newPlayback.detail,
            )
            publishActiveState(newPlayback.detail)
        } catch (throwable: Throwable) {
            track?.let(::releaseTrack)
            AudioEffectLog.e("noise overlay start failed", throwable)
            publishErrorState(throwable)
        }
    }

    private fun runWriter(
        track: AudioTrack,
        stopSignal: AtomicBoolean,
        generator: NoiseSignalGenerator,
        bufferSamples: Int,
    ) {
        val buffer = ShortArray(max(bufferSamples, MIN_BUFFER_SAMPLES))
        var failure: Throwable? = null
        try {
            while (!stopSignal.get()) {
                val requested = synchronized(lock) {
                    if (playback?.track !== track || !shouldPlayLocked()) {
                        null
                    } else {
                        NoiseOverlaySettings(
                            hissEnabled = hissEnabled,
                            crackleEnabled = crackleEnabled,
                            hissGainDb = hissGainDb,
                            crackleGainDb = crackleGainDb,
                        )
                    }
                } ?: break
                generator.fill(
                    buffer,
                    hiss = requested.hissEnabled,
                    crackle = requested.crackleEnabled,
                    hissGainDb = requested.hissGainDb,
                    crackleGainDb = requested.crackleGainDb,
                )
                val written = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    throw IllegalStateException("AudioTrack.write failed: $written")
                }
            }
        } catch (throwable: Throwable) {
            if (!stopSignal.get()) failure = throwable
        } finally {
            releaseTrack(track)
            synchronized(lock) {
                if (playback?.track === track) {
                    playback = null
                    playbackDetail = null
                }
            }
            failure?.let { writeFailure ->
                AudioEffectLog.e("noise overlay write failed", writeFailure)
                publishErrorState(writeFailure)
            } ?: publishStableState()
        }
    }

    private fun stopPlayback(reason: String) {
        val current = synchronized(lock) {
            playback?.also {
                playback = null
                playbackDetail = null
            }
        } ?: run {
            publishStableState()
            return
        }
        current.stopSignal.set(true)
        runCatching { current.track.stop() }
            .onFailure { AudioEffectLog.e("noise overlay stop failed", it) }
        current.writer.interrupt()
        if (current.writer !== Thread.currentThread()) {
            runCatching { current.writer.join(STOP_JOIN_TIMEOUT_MS) }
                .onFailure { AudioEffectLog.e("noise overlay writer join failed", it) }
        }
        AudioEffectLog.i("noise overlay stopped reason=$reason")
        publishStableState()
    }

    private fun publishStartingState(settings: NoiseOverlaySettings) {
        _state.value = NoiseOverlayUiState(
            powerOn = true,
            hissEnabled = settings.hissEnabled,
            crackleEnabled = settings.crackleEnabled,
            hissGainDb = settings.hissGainDb,
            crackleGainDb = settings.crackleGainDb,
            status = NoiseOverlayStatus.Starting,
            detail = "AudioTrackを初期化中（AudioFocusなし）",
        )
    }

    private fun publishActiveState(detail: String) {
        val snapshot = synchronized(lock) {
            NoiseOverlayUiState(
                powerOn = powerOn,
                hissEnabled = hissEnabled,
                crackleEnabled = crackleEnabled,
                hissGainDb = hissGainDb,
                crackleGainDb = crackleGainDb,
                status = NoiseOverlayStatus.Active,
                detail = detail,
            )
        }
        _state.value = snapshot
    }

    private fun publishErrorState(throwable: Throwable) {
        val snapshot = synchronized(lock) {
            NoiseOverlayUiState(
                powerOn = powerOn,
                hissEnabled = hissEnabled,
                crackleEnabled = crackleEnabled,
                hissGainDb = hissGainDb,
                crackleGainDb = crackleGainDb,
                status = NoiseOverlayStatus.Error,
                detail = throwable.message ?: throwable.javaClass.simpleName,
            )
        }
        _state.value = snapshot
    }

    private fun publishStableState() {
        val snapshot = synchronized(lock) {
            when {
                !powerOn -> NoiseOverlayUiState(
                    hissEnabled = hissEnabled,
                    crackleEnabled = crackleEnabled,
                    hissGainDb = hissGainDb,
                    crackleGainDb = crackleGainDb,
                )
                hissEnabled || crackleEnabled -> NoiseOverlayUiState(
                    powerOn = true,
                    hissEnabled = hissEnabled,
                    crackleEnabled = crackleEnabled,
                    hissGainDb = hissGainDb,
                    crackleGainDb = crackleGainDb,
                    status = if (playback == null) {
                        NoiseOverlayStatus.Disabled
                    } else {
                        NoiseOverlayStatus.Active
                    },
                    detail = playbackDetail ?: "AudioTrack停止中",
                )
                else -> NoiseOverlayUiState(
                    powerOn = true,
                    hissGainDb = hissGainDb,
                    crackleGainDb = crackleGainDb,
                    status = NoiseOverlayStatus.Disabled,
                    detail = "Hiss / Crackle はOFF",
                )
            }
        }
        _state.value = snapshot
    }

    private fun shouldPlayLocked(): Boolean = powerOn && (hissEnabled || crackleEnabled)

    private fun nativeSampleRate(): Int {
        return AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            .takeIf { it >= MIN_SAMPLE_RATE }
            ?: DEFAULT_SAMPLE_RATE
    }

    private fun formatGain(gainDb: Float): String =
        String.format(java.util.Locale.US, "%.1fdB", gainDb)

    private fun releaseTrack(track: AudioTrack) {
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private enum class Action {
        START,
        STOP,
        NONE,
    }

    private data class Playback(
        val track: AudioTrack,
        val stopSignal: AtomicBoolean,
        val writer: Thread,
        val detail: String,
    )

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MIN_SAMPLE_RATE = 8_000
        const val BYTES_PER_SAMPLE = 2
        const val MIN_BUFFER_SAMPLES = 1_024
        const val STOP_JOIN_TIMEOUT_MS = 500L
        const val THREAD_NAME = "RazioNoiseOverlay"
    }
}

enum class NoiseOverlayStatus {
    Idle,
    Starting,
    Active,
    Disabled,
    Error,
}

data class NoiseOverlaySettings(
    val hissEnabled: Boolean = false,
    val crackleEnabled: Boolean = false,
    val hissGainDb: Float = NoiseGainRange.DEFAULT_DB,
    val crackleGainDb: Float = NoiseGainRange.DEFAULT_DB,
)

data class NoiseOverlayUiState(
    val powerOn: Boolean = false,
    val hissEnabled: Boolean = false,
    val crackleEnabled: Boolean = false,
    val hissGainDb: Float = NoiseGainRange.DEFAULT_DB,
    val crackleGainDb: Float = NoiseGainRange.DEFAULT_DB,
    val status: NoiseOverlayStatus = NoiseOverlayStatus.Idle,
    val detail: String = "",
)

internal object NoiseGainRange {
    const val MIN_DB = -24f
    const val MAX_DB = 12f
    const val DEFAULT_DB = 0f

    fun sanitize(gainDb: Float): Float {
        if (!gainDb.isFinite()) return DEFAULT_DB
        return gainDb.roundToInt().toFloat().coerceIn(MIN_DB, MAX_DB)
    }

    fun toLinear(gainDb: Float): Float = 10f.pow(sanitize(gainDb) / 20f)

    fun fromLegacyLevel(level: Float): Float {
        if (!level.isFinite() || level <= 0f) return MIN_DB
        return sanitize(20f * log10(level))
    }
}

/** Pure PCM source used by the AudioTrack writer and unit-tested without a device. */
internal class NoiseSignalGenerator(
    sampleRate: Int,
    seed: Long = DEFAULT_SEED,
) {
    private val sampleRate = sampleRate.coerceAtLeast(MIN_SAMPLE_RATE)
    private var randomState = if (seed == 0L) DEFAULT_SEED else seed
    private var lowPass = 0f
    private var crackleRemaining = 0
    private var crackleValue = 0f

    fun fill(
        buffer: ShortArray,
        hiss: Boolean,
        crackle: Boolean,
        hissGainDb: Float = NoiseGainRange.DEFAULT_DB,
        crackleGainDb: Float = NoiseGainRange.DEFAULT_DB,
    ) {
        val hissLinear = NoiseGainRange.toLinear(hissGainDb)
        val crackleLinear = NoiseGainRange.toLinear(crackleGainDb)
        for (index in buffer.indices) {
            val white = nextSigned()
            lowPass += (white - lowPass) * HISS_LOW_PASS_ALPHA
            var sample = if (hiss) {
                (white - lowPass) * HISS_GAIN * hissLinear
            } else {
                0f
            }
            if (crackle) {
                if (crackleRemaining == 0 &&
                    nextUnit() < CRACKLE_EVENTS_PER_SECOND / sampleRate.toFloat()
                ) {
                    crackleRemaining = 1 + (nextLong().ushr(62).toInt() and 0x3)
                    crackleValue = nextSigned() * CRACKLE_GAIN * crackleLinear
                }
                if (crackleRemaining > 0) {
                    sample += crackleValue
                    crackleValue *= CRACKLE_DECAY
                    crackleRemaining -= 1
                }
            }
            buffer[index] = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .toInt()
                .toShort()
        }
    }

    private fun nextSigned(): Float = nextUnit() * 2f - 1f

    private fun nextUnit(): Float {
        val value = nextLong().ushr(11).toDouble() / RANDOM_SCALE
        return value.toFloat()
    }

    private fun nextLong(): Long {
        var value = randomState
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        randomState = value
        return value
    }

    private companion object {
        const val DEFAULT_SEED = 0x4D595DF4D0F33173L
        const val MIN_SAMPLE_RATE = 8_000
        const val RANDOM_SCALE = 9_007_199_254_740_992.0
        const val HISS_LOW_PASS_ALPHA = 0.02f
        // Keep the generated hiss audible beside normal media while leaving
        // enough headroom for Crackle and the final full-scale clamp.
        const val HISS_GAIN = 0.04f
        const val CRACKLE_EVENTS_PER_SECOND = 2f
        const val CRACKLE_GAIN = 0.20f
        const val CRACKLE_DECAY = 0.45f
    }
}
