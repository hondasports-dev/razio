package dev.hondasports.razio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
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
    private var hissLevel = NoiseLevelRange.DEFAULT
    private var crackleLevel = NoiseLevelRange.DEFAULT
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
            hissLevel = sanitizeLevel(settings.hissLevel)
            crackleLevel = sanitizeLevel(settings.crackleLevel)
        }
        reconcile()
    }

    fun currentSettings(): NoiseOverlaySettings {
        return synchronized(lock) {
            NoiseOverlaySettings(
                hissEnabled = hissEnabled,
                crackleEnabled = crackleEnabled,
                hissLevel = hissLevel,
                crackleLevel = crackleLevel,
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

    /** Updates the Hiss amplitude multiplier while the overlay is available. */
    fun setHissLevel(level: Float) {
        synchronized(lock) {
            if (released || !powerOn) return
            hissLevel = sanitizeLevel(level)
        }
        reconcile()
    }

    /** Updates the Crackle amplitude multiplier while the overlay is available. */
    fun setCrackleLevel(level: Float) {
        synchronized(lock) {
            if (released || !powerOn) return
            crackleLevel = sanitizeLevel(level)
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
                    hissLevel = hissLevel,
                    crackleLevel = crackleLevel,
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
                    "hissLevel=${formatLevel(requested.hissLevel)} " +
                    "crackle=${requested.crackleEnabled} " +
                    "crackleLevel=${formatLevel(requested.crackleLevel)} " +
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
                            hissLevel = hissLevel,
                            crackleLevel = crackleLevel,
                        )
                    }
                } ?: break
                generator.fill(
                    buffer,
                    hiss = requested.hissEnabled,
                    crackle = requested.crackleEnabled,
                    hissLevel = requested.hissLevel,
                    crackleLevel = requested.crackleLevel,
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
            hissLevel = settings.hissLevel,
            crackleLevel = settings.crackleLevel,
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
                hissLevel = hissLevel,
                crackleLevel = crackleLevel,
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
                hissLevel = hissLevel,
                crackleLevel = crackleLevel,
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
                    hissLevel = hissLevel,
                    crackleLevel = crackleLevel,
                )
                hissEnabled || crackleEnabled -> NoiseOverlayUiState(
                    powerOn = true,
                    hissEnabled = hissEnabled,
                    crackleEnabled = crackleEnabled,
                    hissLevel = hissLevel,
                    crackleLevel = crackleLevel,
                    status = if (playback == null) {
                        NoiseOverlayStatus.Disabled
                    } else {
                        NoiseOverlayStatus.Active
                    },
                    detail = playbackDetail ?: "AudioTrack停止中",
                )
                else -> NoiseOverlayUiState(
                    powerOn = true,
                    hissLevel = hissLevel,
                    crackleLevel = crackleLevel,
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

    private fun sanitizeLevel(level: Float): Float {
        return if (level.isFinite()) {
            level.coerceIn(NoiseLevelRange.MIN, NoiseLevelRange.MAX)
        } else {
            NoiseLevelRange.DEFAULT
        }
    }

    private fun formatLevel(level: Float): String {
        return "${(level * 100f).toInt()}%"
    }

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
    val hissLevel: Float = NoiseLevelRange.DEFAULT,
    val crackleLevel: Float = NoiseLevelRange.DEFAULT,
)

data class NoiseOverlayUiState(
    val powerOn: Boolean = false,
    val hissEnabled: Boolean = false,
    val crackleEnabled: Boolean = false,
    val hissLevel: Float = NoiseLevelRange.DEFAULT,
    val crackleLevel: Float = NoiseLevelRange.DEFAULT,
    val status: NoiseOverlayStatus = NoiseOverlayStatus.Idle,
    val detail: String = "",
)

internal object NoiseLevelRange {
    const val MIN = 0f
    const val MAX = 4f
    const val DEFAULT = 1f
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
        hissLevel: Float = NoiseLevelRange.DEFAULT,
        crackleLevel: Float = NoiseLevelRange.DEFAULT,
    ) {
        val safeHissLevel = sanitizeLevel(hissLevel)
        val safeCrackleLevel = sanitizeLevel(crackleLevel)
        for (index in buffer.indices) {
            val white = nextSigned()
            lowPass += (white - lowPass) * HISS_LOW_PASS_ALPHA
            var sample = if (hiss) {
                (white - lowPass) * HISS_GAIN * safeHissLevel
            } else {
                0f
            }
            if (crackle) {
                if (crackleRemaining == 0 &&
                    nextUnit() < CRACKLE_EVENTS_PER_SECOND / sampleRate.toFloat()
                ) {
                    crackleRemaining = 1 + (nextLong().ushr(62).toInt() and 0x3)
                    crackleValue = nextSigned() * CRACKLE_GAIN * safeCrackleLevel
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

    private fun sanitizeLevel(level: Float): Float {
        return if (level.isFinite()) {
            level.coerceIn(NoiseLevelRange.MIN, NoiseLevelRange.MAX)
        } else {
            NoiseLevelRange.DEFAULT
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
