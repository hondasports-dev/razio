package dev.hondasports.razio.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
 * Input is an AudioPlaybackCapture + AudioRecord copy of the playback mix and is treated as
 * the pre-effect reference. It is never sent to an AudioTrack, so starting the analyzer cannot
 * create a second audible copy of another app's audio. On Android 10+ the output graph is a
 * deterministic post-effect estimate derived from the same input frame and the current
 * DynamicsProcessing profile. A session-0 Visualizer is retained only as a fallback when an
 * input capture is unavailable; it is not presented as guaranteed post-DSP PCM.
 */
class SpectrumAnalyzerController(
    context: Context,
    private val effectProfileProvider: () -> SpectrumEffectProfile = { SpectrumEffectProfile() },
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
    private var estimatedOutput = false
    private var inputSignalObserved = false
    private var inputSignalWarningShown = false
    private var inputHealthCheck: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val CAPTURE_RATE_MILLI_HZ = 20_000
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MIN_SAMPLE_RATE = 8_000
        const val BYTES_PER_SAMPLE = 2
        const val STOP_JOIN_TIMEOUT_MS = 500L
        const val INPUT_THREAD_NAME = "RazioSpectrumInput"
        const val INPUT_SIGNAL_TIMEOUT_MS = 2_000L
        const val INPUT_SIGNAL_WARNING =
            "入力信号なし（無音または対象アプリのcapture policy制限の可能性）"
    }

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
            inputSignalObserved = false
            inputSignalWarningShown = false
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
                estimatedOutput = candidateInput != null
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

        val estimatedOutputAvailable = candidateInput != null
        val nativeOutputAvailable = candidateVisualizer != null
        val outputAvailable = estimatedOutputAvailable || nativeOutputAvailable
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
            append(
                if (inputAvailable) {
                    "入力tap=AudioPlaybackCapture（エフェクト前の解析用コピー）"
                } else {
                    "入力tap=unavailable"
                },
            )
            append(" / ")
            append(
                when {
                    estimatedOutputAvailable ->
                        "出力=同一フレームへDynamicsProcessingを反映したpost-effect推定"
                    nativeOutputAvailable ->
                        "出力mix tap=Visualizer(session 0; post-DSP非保証)"
                    else -> "出力=unavailable"
                },
            )
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

    /** Keep the fallback Visualizer observable across a route change. */
    fun handleRouteChange() {
        val isRunning = synchronized(lock) { running && !released }
        if (isRunning) {
            AudioEffectLog.i("spectrum route change: keep analyzer taps")
        }
    }

    fun stop() {
        val resources = synchronized(lock) {
            inputHealthCheck?.let { mainHandler.removeCallbacks(it) }
            inputHealthCheck = null
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
            estimatedOutput = false
            inputSignalObserved = false
            inputSignalWarningShown = false
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
                            running &&
                                generation == token &&
                                !estimatedOutput &&
                                this@SpectrumAnalyzerController.visualizer === visualizer
                        }
                        if (!isCurrent) return
                        publishFrame(
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
    @SuppressLint("MissingPermission")
    private fun createInputCapture(
        projection: MediaProjection,
        token: Long,
    ): InputCapture {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
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
            scheduleInputHealthCheck(capture)
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
                    publishCapturedFrame(
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

    private fun publishCapturedFrame(
        frame: SpectrumFrame,
        token: Long,
    ) {
        synchronized(lock) {
            if (!running || generation != token) return
            val signalPresent = SpectrumMath.hasUsableSignal(frame)
            val signalRecovered = signalPresent && !inputSignalObserved
            val recoveringFromWarning = signalRecovered && inputSignalWarningShown
            if (signalPresent) {
                inputSignalObserved = true
            }
            if (recoveringFromWarning) {
                inputSignalWarningShown = false
            }
            inputFrames += 1L
            outputFrames += 1L
            val inputUsable = !inputSignalWarningShown
            val inputSnapshot = SpectrumSnapshot(
                available = inputUsable,
                levelsDb = frame.levelsDb,
                rmsDb = frame.rmsDb,
                peakDb = frame.peakDb,
                frameCount = inputFrames,
                detail = if (inputUsable) {
                    "AudioPlaybackCapture（エフェクト前）"
                } else {
                    INPUT_SIGNAL_WARNING
                },
            )
            val outputFrame = runCatching {
                SpectrumEffectEstimator.apply(frame, effectProfileProvider())
            }.onFailure { throwable ->
                AudioEffectLog.e("spectrum post-effect estimate failed", throwable)
            }.getOrDefault(frame)
            val outputSnapshot = SpectrumSnapshot(
                available = true,
                levelsDb = outputFrame.levelsDb,
                rmsDb = outputFrame.rmsDb,
                peakDb = outputFrame.peakDb,
                frameCount = outputFrames,
                detail = "DynamicsProcessing（エフェクト後・推定）",
            )
            val current = _state.value
            val recovered = if (recoveringFromWarning) {
                current.copy(
                    status = SpectrumAnalyzerStatus.Active,
                    detail = current.detail.replace(" / $INPUT_SIGNAL_WARNING", ""),
                    input = inputSnapshot,
                )
            } else {
                current
            }
            _state.value = recovered.copy(
                input = inputSnapshot,
                output = outputSnapshot,
            )
        }
    }

    private fun scheduleInputHealthCheck(capture: InputCapture) {
        val check = object : Runnable {
            override fun run() {
                val shouldWarn = synchronized(lock) {
                    running &&
                        generation == capture.token &&
                        inputCapture === capture &&
                        !inputSignalObserved &&
                        !inputSignalWarningShown
                }
                if (shouldWarn) reportInputSignalUnavailable(capture.token)
                synchronized(lock) {
                    if (inputHealthCheck === this) inputHealthCheck = null
                }
            }
        }
        synchronized(lock) {
            if (!running || generation != capture.token || inputCapture !== capture || inputSignalObserved) {
                return
            }
            inputHealthCheck?.let { mainHandler.removeCallbacks(it) }
            inputHealthCheck = check
        }
        mainHandler.postDelayed(check, INPUT_SIGNAL_TIMEOUT_MS)
    }

    private fun reportInputSignalUnavailable(token: Long) {
        synchronized(lock) {
            if (!running || generation != token || inputCapture == null ||
                inputSignalObserved || inputSignalWarningShown
            ) {
                return
            }
            inputSignalWarningShown = true
            val current = _state.value
            _state.value = current.copy(
                status = if (current.status == SpectrumAnalyzerStatus.Error) {
                    current.status
                } else {
                    SpectrumAnalyzerStatus.Partial
                },
                detail = if (current.detail.contains(INPUT_SIGNAL_WARNING)) {
                    current.detail
                } else {
                    "${current.detail} / $INPUT_SIGNAL_WARNING"
                },
                input = current.input.copy(
                    available = false,
                    detail = INPUT_SIGNAL_WARNING,
                ),
            )
            AudioEffectLog.i("spectrum input signal unavailable token=$token")
        }
    }

    private fun publishFrame(
        frame: SpectrumFrame,
        token: Long,
    ) {
        synchronized(lock) {
            if (!running || generation != token) return
            outputFrames += 1L
            val snapshot = SpectrumSnapshot(
                available = true,
                levelsDb = frame.levelsDb,
                rmsDb = frame.rmsDb,
                peakDb = frame.peakDb,
                frameCount = outputFrames,
                detail = "Visualizer(session 0; post-DSP非保証)",
            )
            val current = _state.value
            _state.value = current.copy(output = snapshot)
        }
    }

    private fun onInputEnded(token: Long, throwable: Throwable) {
        synchronized(lock) {
            if (!running || generation != token) return
            AudioEffectLog.e("spectrum input capture ended", throwable)
            estimatedOutput = false
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

}
