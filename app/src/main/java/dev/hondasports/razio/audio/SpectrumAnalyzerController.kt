package dev.hondasports.razio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.audiofx.Visualizer
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the explicit input/output taps used to check whether the global effect is audible.
 *
 * Input is an AudioPlaybackCapture + AudioRecord copy of the playback mix. It is never
 * sent to an AudioTrack, so starting the analyzer cannot create a second audible copy of
 * another app's audio. Output is the session-0 Visualizer mix tap. Neither API promises a
 * raw pre/post-DSP PCM stream; the labels and docs keep that limitation visible.
 */
class SpectrumAnalyzerController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val _state = MutableStateFlow(SpectrumAnalyzerUiState())
    val state: StateFlow<SpectrumAnalyzerUiState> = _state.asStateFlow()

    private var released = false
    private var running = false
    private var generation = 0L
    private var visualizer: Visualizer? = null
    private var inputCapture: InputCapture? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: Callback? = null
    private var inputFrames = 0L
    private var outputFrames = 0L

    /** Starts output-only on old Android versions, or when projection consent is absent. */
    fun startWithoutProjection() {
        start(projection = null)
    }

    /** Starts a dual analyzer after the Activity has obtained MediaProjection consent. */
    fun start(projection: MediaProjection?) {
        stop()
        val token = synchronized(lock) {
            if (released) return
            generation += 1L
            running = true
            inputFrames = 0L
            outputFrames = 0L
            this.projection = projection
            generation
        }
        _state.value = SpectrumAnalyzerUiState(
            running = true,
            status = SpectrumAnalyzerStatus.Starting,
            detail = if (projection == null) {
                "出力のみを初期化中（入力はMediaProjectionの同意が必要）"
            } else {
                "入力と出力を初期化中"
            },
        )

        var candidateVisualizer: Visualizer? = null
        var candidateInput: InputCapture? = null
        val failures = mutableListOf<String>()
        try {
            candidateVisualizer = createVisualizer(token)
        } catch (throwable: Throwable) {
            failures += "出力=${throwable.message ?: throwable.javaClass.simpleName}"
            AudioEffectLog.e("spectrum Visualizer start failed", throwable)
        }
        if (projection != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                failures += "入力=Android 10未満ではAudioPlaybackCapture非対応"
            } else {
                try {
                    candidateInput = createInputCapture(
                        projection = checkNotNull(projection),
                        token = token,
                    )
                } catch (throwable: Throwable) {
                    failures += "入力=${throwable.message ?: throwable.javaClass.simpleName}"
                    AudioEffectLog.e("spectrum AudioRecord start failed", throwable)
                }
            }
        }

        val started = synchronized(lock) {
            if (!released && running && generation == token) {
                visualizer = candidateVisualizer
                inputCapture = candidateInput
                if (projection != null) registerProjectionCallbackLocked(token, projection)
                true
            } else {
                false
            }
        }
        if (!started) {
            releaseVisualizer(candidateVisualizer)
            releaseInput(candidateInput)
            projection?.stop()
            return
        }

        val outputAvailable = candidateVisualizer != null
        val inputAvailable = candidateInput != null
        if (!outputAvailable && !inputAvailable) {
            val reason = failures.joinToString(separator = "; ").ifEmpty { "tap unavailable" }
            stop()
            _state.value = SpectrumAnalyzerUiState(
                status = SpectrumAnalyzerStatus.Error,
                detail = reason,
            )
            return
        }

        val status = if (outputAvailable && inputAvailable) {
            SpectrumAnalyzerStatus.Active
        } else {
            SpectrumAnalyzerStatus.Partial
        }
        val detail = buildString {
            append(if (inputAvailable) "入力tap=AudioPlaybackCapture" else "入力tap=unavailable")
            append(" / ")
            append(if (outputAvailable) "出力mix tap=Visualizer(session 0)" else "出力mix tap=unavailable")
            append(" / 前後位置は端末依存")
            if (failures.isNotEmpty()) append(" / ").append(failures.joinToString("; "))
            if (inputAvailable) append(" / 元音声は再生しない")
        }
        _state.value = SpectrumAnalyzerUiState(
            running = true,
            status = status,
            detail = detail,
        )
        candidateInput?.let(::startInputCapture)
        AudioEffectLog.i("spectrum analyzer started status=$status detail=$detail")
    }

    /** Records a user-visible denial without pretending that a capture started. */
    fun reportConsentDenied(reason: String) {
        stop()
        _state.value = SpectrumAnalyzerUiState(
            status = SpectrumAnalyzerStatus.Error,
            detail = reason,
        )
    }

    /** The session-0 output mix tap normally survives a route change; keep it observable. */
    fun handleRouteChange() {
        val isRunning = synchronized(lock) { running && !released }
        if (isRunning) {
            AudioEffectLog.i("spectrum route change: keep session 0 Visualizer mix tap")
        }
    }

    fun stop() {
        val resources = synchronized(lock) {
            if (!running && visualizer == null && inputCapture == null && projection == null) {
                return
            }
            running = false
            generation += 1L
            val oldVisualizer = visualizer
            val oldInput = inputCapture
            val oldProjection = projection
            val oldProjectionCallback = projectionCallback
            visualizer = null
            inputCapture = null
            projection = null
            projectionCallback = null
            oldVisualizer to Triple(oldInput, oldProjection, oldProjectionCallback)
        }
        val oldVisualizer = resources.first
        val oldInput = resources.second.first
        val oldProjection = resources.second.second
        val oldProjectionCallback = resources.second.third
        if (oldProjection != null && oldProjectionCallback != null) {
            runCatching { oldProjection.unregisterCallback(oldProjectionCallback) }
        }
        releaseVisualizer(oldVisualizer)
        releaseInput(oldInput)
        oldProjection?.let { runCatching { it.stop() } }
        _state.value = SpectrumAnalyzerUiState(status = SpectrumAnalyzerStatus.Stopped)
        AudioEffectLog.i("spectrum analyzer stopped")
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
        }
        stop()
    }

    private fun createVisualizer(token: Long): Visualizer {
        val candidate = Visualizer(0)
        try {
            val range = Visualizer.getCaptureSizeRange()
            val requestedSize = SpectrumMath.FFT_SIZE.coerceIn(range[0], range[1])
                .let { size ->
                    // Visualizer requires a power-of-two capture size on common HALs.
                    Integer.highestOneBit(size).coerceAtLeast(range[0])
                }
            candidate.captureSize = requestedSize
            runCatching {
                candidate.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            }.onFailure { throwable ->
                AudioEffectLog.i(
                    "spectrum Visualizer AS_PLAYED scaling unavailable: " +
                        (throwable.message ?: throwable.javaClass.simpleName),
                )
            }
            candidate.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int,
                    ) {
                        val isCurrent = synchronized(lock) {
                            running && generation == token && this@SpectrumAnalyzerController.visualizer === visualizer
                        }
                        if (!isCurrent) return
                        publishFrame(
                            input = false,
                            frame = SpectrumMath.fromVisualizerWaveform(waveform, samplingRate),
                            token = token,
                        )
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int,
                    ) = Unit
                },
                CAPTURE_RATE_MILLI_HZ,
                true,
                false,
            )
            candidate.enabled = true
            return candidate
        } catch (throwable: Throwable) {
            releaseVisualizer(candidate)
            throw throwable
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createInputCapture(
        projection: MediaProjection,
        token: Long,
    ): InputCapture {
        val sampleRate = AudioTrack.getNativeOutputSampleRate(android.media.AudioManager.STREAM_MUSIC)
            .takeIf { it >= MIN_SAMPLE_RATE }
            ?: DEFAULT_SAMPLE_RATE
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw IllegalStateException("AudioRecord buffer size unavailable: $minBufferBytes")
        }
        val bufferBytes = max(minBufferBytes, SpectrumMath.FFT_SIZE * BYTES_PER_SAMPLE * 2)
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord is not initialized")
        }
        val stopSignal = AtomicBoolean(false)
        val capture = InputCapture(
            record = record,
            sampleRateHz = sampleRate,
            stopSignal = stopSignal,
            token = token,
        )
        capture.thread = Thread(
            { runInputCapture(capture) },
            INPUT_THREAD_NAME,
        )
        return capture
    }

    private fun startInputCapture(capture: InputCapture) {
        try {
            capture.record.startRecording()
            if (capture.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter recording state")
            }
            capture.thread?.start()
        } catch (throwable: Throwable) {
            AudioEffectLog.e("spectrum AudioRecord recording failed", throwable)
            capture.stopSignal.set(true)
            releaseInput(capture)
            onInputEnded(capture.token, throwable)
        }
    }

    private fun runInputCapture(capture: InputCapture) {
        val buffer = ShortArray(SpectrumMath.FFT_SIZE)
        var failure: Throwable? = null
        try {
            while (!capture.stopSignal.get()) {
                val count = capture.record.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (count > 0) {
                    publishFrame(
                        input = true,
                        frame = SpectrumMath.fromPcm16(buffer, count, capture.sampleRateHz),
                        token = capture.token,
                    )
                } else if (count < 0) {
                    throw IllegalStateException("AudioRecord.read failed: $count")
                }
            }
        } catch (throwable: Throwable) {
            if (!capture.stopSignal.get()) failure = throwable
        } finally {
            releaseInput(capture)
            failure?.let { onInputEnded(capture.token, it) }
        }
    }

    private fun publishFrame(
        input: Boolean,
        frame: SpectrumFrame,
        token: Long,
    ) {
        synchronized(lock) {
            if (!running || generation != token) return
            if (input) inputFrames += 1L else outputFrames += 1L
            val snapshot = SpectrumSnapshot(
                available = true,
                levelsDb = frame.levelsDb,
                rmsDb = frame.rmsDb,
                peakDb = frame.peakDb,
                frameCount = if (input) inputFrames else outputFrames,
                detail = if (input) "AudioPlaybackCapture" else "Visualizer(session 0)",
            )
            val current = _state.value
            _state.value = if (input) {
                current.copy(input = snapshot)
            } else {
                current.copy(output = snapshot)
            }
        }
    }

    private fun onInputEnded(token: Long, throwable: Throwable) {
        synchronized(lock) {
            if (!running || generation != token) return
            AudioEffectLog.e("spectrum input capture ended", throwable)
            val current = _state.value
            _state.value = current.copy(
                status = if (current.output.available) SpectrumAnalyzerStatus.Partial
                else SpectrumAnalyzerStatus.Error,
                detail = "入力停止: ${throwable.message ?: throwable.javaClass.simpleName}",
                input = current.input.copy(
                    available = false,
                    detail = throwable.message ?: throwable.javaClass.simpleName,
                ),
            )
        }
    }

    private fun registerProjectionCallbackLocked(
        token: Long,
        currentProjection: MediaProjection?,
    ) {
        if (currentProjection == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val callback = object : Callback() {
            override fun onStop() {
                val shouldStop = synchronized(lock) { running && generation == token }
                if (shouldStop) {
                    reportConsentDenied("MediaProjectionが停止したため入力解析を停止しました")
                }
            }
        }
        currentProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback
    }

    private fun releaseVisualizer(candidate: Visualizer?) {
        if (candidate == null) return
        runCatching { candidate.enabled = false }
        runCatching { candidate.release() }
    }

    private fun releaseInput(capture: InputCapture?) {
        if (capture == null) return
        capture.stopSignal.set(true)
        runCatching {
            if (capture.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                capture.record.stop()
            }
        }
        runCatching { capture.record.release() }
        capture.thread?.let { thread ->
            thread.interrupt()
            if (thread !== Thread.currentThread()) {
                runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
            }
        }
    }

    private class InputCapture(
        val record: AudioRecord,
        val sampleRateHz: Int,
        val stopSignal: AtomicBoolean,
        val token: Long,
    ) {
        var thread: Thread? = null
    }

    private companion object {
        const val CAPTURE_RATE_MILLI_HZ = 20_000
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MIN_SAMPLE_RATE = 8_000
        const val BYTES_PER_SAMPLE = 2
        const val STOP_JOIN_TIMEOUT_MS = 500L
        const val INPUT_THREAD_NAME = "RazioSpectrumInput"
    }
}
