package dev.hondasports.razio.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicitly opt-in Mono passthrough experiment.
 *
 * This is intentionally not the product backend. It copies another app's playback
 * with AudioPlaybackCapture, averages each stereo frame, duplicates the mono sample
 * to a stereo AudioTrack, and leaves the source player running. Android does not give
 * a normal app a supported way to mute that source, so a second audible copy is a
 * known outcome of this experiment and is shown in the UI/documentation.
 */
class MonoPlaybackPocController(
    context: Context,
    private val onProjectionStopped: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val _state = MutableStateFlow(MonoPlaybackPocUiState())
    val state: StateFlow<MonoPlaybackPocUiState> = _state.asStateFlow()

    private var released = false
    private var running = false
    private var generation = 0L
    private var playback: Playback? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: Callback? = null
    private var capturedFrames = 0L
    private var playedFrames = 0L

    /** Starts the capture/downmix/replay chain after a fresh MediaProjection consent. */
    fun start(projection: MediaProjection?) {
        stop()
        val token = synchronized(lock) {
            if (released) return
            generation += 1L
            running = true
            capturedFrames = 0L
            playedFrames = 0L
            tokenForGeneration()
        }
        _state.value = MonoPlaybackPocUiState(
            running = true,
            status = MonoPlaybackPocStatus.Starting,
            detail = "AudioPlaybackCapture と AudioTrack を初期化中",
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            failStart(token, projection, "Android 10未満ではAudioPlaybackCapture非対応")
            return
        }
        if (projection == null) {
            failStart(token, null, "MediaProjectionの同意が必要です")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failStart(token, projection, "RECORD_AUDIO permission not granted")
            return
        }

        // Register the projection callback before allocating native resources. A user can
        // revoke the consent token while AudioRecord/AudioTrack are being initialized; the
        // callback then invalidates this generation and the commit below is rejected.
        val projectionRegistered = synchronized(lock) {
            if (!released && running && generation == token) {
                this.projection = projection
                runCatching {
                    registerProjectionCallbackLocked(token, projection)
                }.isSuccess
            } else {
                false
            }
        }
        if (!projectionRegistered) {
            runCatching { projection.stop() }
            failStart(token, projection, "MediaProjectionが停止したため開始できません")
            return
        }

        var candidate: Playback? = null
        try {
            candidate = createPlayback(projection, token)
            candidate.track.play()
            candidate.record.startRecording()
            if (candidate.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter recording state")
            }
        } catch (throwable: Throwable) {
            AudioEffectLog.e("mono playback PoC start failed", throwable)
            releasePlayback(candidate)
            failStart(token, projection, throwable.message ?: throwable.javaClass.simpleName)
            return
        }

        val started = synchronized(lock) {
            if (!released && running && generation == token) {
                playback = candidate
                true
            } else {
                false
            }
        }
        if (!started) {
            releasePlayback(candidate)
            runCatching { projection.stop() }
            return
        }

        val status = if (candidate.inputChannels == MonoPcmMixer.STEREO_CHANNELS) {
            MonoPlaybackPocStatus.Active
        } else {
            MonoPlaybackPocStatus.Partial
        }
        val detail = buildString {
            append("capture=${candidate.inputChannels}ch")
            append(" / L/R→(L+R)/2→stereo")
            if (candidate.inputChannels != MonoPcmMixer.STEREO_CHANNELS) {
                append(" / mono fallback（L/R混合は未証明）")
            }
            append(" / 元音声のミュート非保証・二重再生の可能性あり")
            append(" / output capture policy=none")
        }
        val published = synchronized(lock) {
            if (!released && running && generation == token && playback === candidate) {
                _state.value = MonoPlaybackPocUiState(
                    running = true,
                    status = status,
                    detail = detail,
                    inputChannels = candidate.inputChannels,
                    outputChannels = OUTPUT_CHANNELS,
                    sampleRateHz = candidate.sampleRateHz,
                    bufferFrames = candidate.workBufferFrames,
                    estimatedLatencyMs = candidate.estimatedLatencyMs,
                )
                // Keep publication and writer start in the same critical section as the
                // projection-stop check so onStop cannot leave a detached writer thread.
                candidate.writer.start()
                true
            } else {
                false
            }
        }
        if (!published) return
        AudioEffectLog.i(
            "mono playback PoC started status=$status " +
                "inputChannels=${candidate.inputChannels} sampleRate=${candidate.sampleRateHz} " +
                "estimatedLatencyMs=${candidate.estimatedLatencyMs}",
        )
    }

    /** Records a user-visible denial without pretending that capture started. */
    fun reportConsentDenied(reason: String) {
        stop()
        _state.value = MonoPlaybackPocUiState(
            status = MonoPlaybackPocStatus.Error,
            detail = reason,
        )
        // A projection FGS may already be owned by RazioApp when setup fails.
        // Ask the owner to release that lease so a failed PoC cannot leave a
        // mediaProjection notification running in the background.
        onProjectionStopped()
    }

    /** AudioTrack normally follows a route change; keep that fact visible in logcat. */
    fun handleRouteChange() {
        val isRunning = synchronized(lock) { running && !released }
        if (isRunning) {
            AudioEffectLog.i("mono playback PoC route change: keep capture and output track")
        }
    }

    fun stop() {
        val resources = synchronized(lock) {
            if (!running && playback == null && projection == null) return
            running = false
            generation += 1L
            val oldPlayback = playback
            val oldProjection = projection
            val oldCallback = projectionCallback
            playback = null
            projection = null
            projectionCallback = null
            Triple(oldPlayback, oldProjection, oldCallback)
        }
        val oldPlayback = resources.first
        val oldProjection = resources.second
        val oldCallback = resources.third
        if (oldProjection != null && oldCallback != null) {
            runCatching { oldProjection.unregisterCallback(oldCallback) }
        }
        releasePlayback(oldPlayback)
        oldProjection?.let { runCatching { it.stop() } }
        val last = synchronized(lock) { capturedFrames to playedFrames }
        _state.value = MonoPlaybackPocUiState(
            status = MonoPlaybackPocStatus.Stopped,
            detail = "停止（capture=${last.first} frames / output=${last.second} frames）",
            capturedFrames = last.first,
            playedFrames = last.second,
        )
        AudioEffectLog.i("mono playback PoC stopped captured=${last.first} played=${last.second}")
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
        }
        stop()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun createPlayback(
        projection: MediaProjection,
        token: Long,
    ): Playback {
        val sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            .takeIf { it >= MIN_SAMPLE_RATE }
            ?: DEFAULT_SAMPLE_RATE
        val input = createInputRecord(projection, sampleRate)
        var track: AudioTrack? = null
        try {
            val minOutputBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minOutputBytes <= 0) {
                throw IllegalStateException("AudioTrack buffer size unavailable: $minOutputBytes")
            }
            val outputBufferBytes = max(
                minOutputBytes,
                WORK_BUFFER_FRAMES * OUTPUT_CHANNELS * BYTES_PER_SAMPLE * 4,
            )
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    }
                }
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(outputBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack is not initialized")
            }
            val inputBufferFrames = input.bufferBytes / (input.channels * BYTES_PER_SAMPLE)
            val outputBufferFrames = outputBufferBytes / (OUTPUT_CHANNELS * BYTES_PER_SAMPLE)
            val estimatedLatencyMs = ((inputBufferFrames + outputBufferFrames) * 1_000L / sampleRate)
                .toInt()
            return Playback(
                record = input.record,
                track = track,
                inputChannels = input.channels,
                sampleRateHz = sampleRate,
                workBufferFrames = WORK_BUFFER_FRAMES,
                estimatedLatencyMs = estimatedLatencyMs,
                stopSignal = AtomicBoolean(false),
                writer = Thread({ runPlayback(token) }, THREAD_NAME),
                token = token,
            )
        } catch (throwable: Throwable) {
            runCatching { track?.release() }
            runCatching { input.record.release() }
            throw throwable
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun createInputRecord(
        projection: MediaProjection,
        sampleRate: Int,
    ): InputSpec {
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        var lastFailure: Throwable? = null
        val candidates = listOf(
            AudioFormat.CHANNEL_IN_STEREO to MonoPcmMixer.STEREO_CHANNELS,
            AudioFormat.CHANNEL_IN_MONO to 1,
        )
        for ((channelMask, channels) in candidates) {
            var record: AudioRecord? = null
            try {
                val minBufferBytes = AudioRecord.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBufferBytes <= 0) {
                    throw IllegalStateException("AudioRecord buffer size unavailable: $minBufferBytes")
                }
                val bufferBytes = max(
                    minBufferBytes,
                    WORK_BUFFER_FRAMES * channels * BYTES_PER_SAMPLE * 4,
                )
                record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord is not initialized (${channels}ch)")
                }
                return InputSpec(record, channels, bufferBytes)
            } catch (throwable: Throwable) {
                lastFailure = throwable
                runCatching { record?.release() }
            }
        }
        throw IllegalStateException(
            "AudioRecord unavailable: ${lastFailure?.message ?: "unknown"}",
            lastFailure,
        )
    }

    private fun runPlayback(token: Long) {
        val current = synchronized(lock) {
            playback?.takeIf { running && generation == token && it.token == token }
        } ?: return
        val inputBuffer = ShortArray(current.workBufferFrames * current.inputChannels)
        val outputBuffer = ShortArray(current.workBufferFrames * OUTPUT_CHANNELS)
        var failure: Throwable? = null
        var lastPublishNs = 0L
        try {
            while (!current.stopSignal.get()) {
                val count = current.record.read(
                    inputBuffer,
                    0,
                    inputBuffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (count <= 0) {
                    throw IllegalStateException("AudioRecord.read failed: $count")
                }
                val frames = if (current.inputChannels == MonoPcmMixer.STEREO_CHANNELS) {
                    MonoPcmMixer.downmixStereoToStereo(inputBuffer, count, outputBuffer)
                } else {
                    MonoPcmMixer.copyMonoToStereo(inputBuffer, count, outputBuffer)
                }
                if (frames == 0) continue
                var offset = 0
                val sampleCount = frames * OUTPUT_CHANNELS
                while (offset < sampleCount && !current.stopSignal.get()) {
                    val written = current.track.write(
                        outputBuffer,
                        offset,
                        sampleCount - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) {
                        throw IllegalStateException("AudioTrack.write failed: $written")
                    }
                    offset += written
                }
                if (offset == sampleCount) {
                    val now = System.nanoTime()
                    val shouldPublish = now - lastPublishNs >= PROGRESS_INTERVAL_NS
                    updateProgress(token, frames.toLong(), frames.toLong(), shouldPublish)
                    if (shouldPublish) lastPublishNs = now
                }
            }
        } catch (throwable: Throwable) {
            if (!current.stopSignal.get()) failure = throwable
        } finally {
            if (failure != null) {
                AudioEffectLog.e("mono playback PoC loop failed", failure)
                stop()
                _state.value = MonoPlaybackPocUiState(
                    status = MonoPlaybackPocStatus.Error,
                    detail = failure.message ?: failure.javaClass.simpleName,
                    capturedFrames = capturedFrames,
                    playedFrames = playedFrames,
                )
            }
        }
    }

    private fun updateProgress(
        token: Long,
        capturedDelta: Long,
        playedDelta: Long,
        publish: Boolean,
    ) {
        if (!publish) {
            synchronized(lock) {
                if (running && generation == token) {
                    capturedFrames += capturedDelta
                    playedFrames += playedDelta
                }
            }
            return
        }
        val snapshot = synchronized(lock) {
            if (!running || generation != token) return
            capturedFrames += capturedDelta
            playedFrames += playedDelta
            _state.value.copy(
                capturedFrames = capturedFrames,
                playedFrames = playedFrames,
            )
        }
        _state.value = snapshot
    }

    private fun failStart(
        token: Long,
        currentProjection: MediaProjection?,
        reason: String,
    ) {
        val isCurrent = synchronized(lock) {
            generation == token && (running || projection === currentProjection)
        }
        if (!isCurrent) return
        // stop() unregisters the early projection callback and releases any resource that
        // became current before the failure was observed.
        stop()
        _state.value = MonoPlaybackPocUiState(
            status = MonoPlaybackPocStatus.Error,
            detail = reason,
        )
        onProjectionStopped()
    }

    private fun registerProjectionCallbackLocked(
        token: Long,
        currentProjection: MediaProjection,
    ) {
        val callback = object : Callback() {
            override fun onStop() {
                val shouldStop = synchronized(lock) { running && generation == token }
                if (!shouldStop) return
                stop()
                _state.value = MonoPlaybackPocUiState(
                    status = MonoPlaybackPocStatus.Error,
                    detail = "MediaProjectionが停止したためMono PoCを停止しました",
                )
                onProjectionStopped()
            }
        }
        currentProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback
    }

    private fun releasePlayback(current: Playback?) {
        if (current == null) return
        current.stopSignal.set(true)
        runCatching {
            if (current.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                current.record.stop()
            }
        }
        runCatching {
            if (current.track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                current.track.stop()
            }
        }
        current.writer.interrupt()
        if (current.writer !== Thread.currentThread()) {
            runCatching { current.writer.join(STOP_JOIN_TIMEOUT_MS) }
        }
        runCatching { current.record.release() }
        runCatching { current.track.flush() }
        runCatching { current.track.release() }
    }

    private fun tokenForGeneration(): Long = generation

    private data class InputSpec(
        val record: AudioRecord,
        val channels: Int,
        val bufferBytes: Int,
    )

    private data class Playback(
        val record: AudioRecord,
        val track: AudioTrack,
        val inputChannels: Int,
        val sampleRateHz: Int,
        val workBufferFrames: Int,
        val estimatedLatencyMs: Int,
        val stopSignal: AtomicBoolean,
        val writer: Thread,
        val token: Long,
    )

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MIN_SAMPLE_RATE = 8_000
        const val WORK_BUFFER_FRAMES = 1_024
        const val OUTPUT_CHANNELS = 2
        const val BYTES_PER_SAMPLE = 2
        const val PROGRESS_INTERVAL_NS = 250_000_000L
        const val STOP_JOIN_TIMEOUT_MS = 500L
        const val THREAD_NAME = "RazioMonoPlaybackPoc"
    }
}

enum class MonoPlaybackPocStatus {
    Idle,
    Starting,
    Active,
    Partial,
    Stopped,
    Error,
}

data class MonoPlaybackPocUiState(
    val running: Boolean = false,
    val status: MonoPlaybackPocStatus = MonoPlaybackPocStatus.Idle,
    val detail: String = "",
    val inputChannels: Int = 0,
    val outputChannels: Int = 0,
    val sampleRateHz: Int = 0,
    val bufferFrames: Int = 0,
    val estimatedLatencyMs: Int = 0,
    val capturedFrames: Long = 0L,
    val playedFrames: Long = 0L,
)
