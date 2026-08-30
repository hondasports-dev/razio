package dev.hondasports.razio.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.hondasports.razio.audio.preset.AmDynamicsConfig
import dev.hondasports.razio.audio.preset.AudioPreset
import dev.hondasports.razio.audio.preset.AudioPresetParameters
import dev.hondasports.razio.domain.model.AudioSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * session 0 の global mix に DynamicsProcessing 単体を載せる PoC。
 * session 0 は deprecated で端末依存。生成失敗は隠さず [AudioEngineReport] にする。
 * Application に保持し、プロセスが生きている間だけ効果を維持する。
 */
class GlobalAudioEffectController(
    private val sessionId: Int = AudioSession.GLOBAL_OUTPUT_MIX,
) {
    private val _state = MutableStateFlow(AudioEffectUiState())
    val state: StateFlow<AudioEffectUiState> = _state.asStateFlow()

    private var dynamics: DynamicsProcessing? = null
    private var attempted: Boolean = false
    private val backend: AudioEffectBackend = AudioEffectBackend.DYNAMICS_ONLY
    private var selectedPreset: AudioPreset = AudioPreset.NARROW_AM
    private val transitionHandler = Handler(Looper.getMainLooper())
    private var transitionState: PresetTransition? = null
    private var fadingRunnable: Runnable? = null

    fun initialize() {
        stopFading()
        cancelPresetTransition(applyFinalPreset = false)
        _state.update {
            it.copy(
                initializing = true,
                status = RazioStatus.Initializing,
                backend = backend,
            )
        }
        logAvailableEffects()
        releaseEngines()
        val equalizerReport = AudioEngineReport.NotUsed(
            reason = "backend=${AudioEffectBackend.DYNAMICS_ONLY.id}",
        )
        val dynamicsReport = createDynamics()
        attempted = true
        publish(
            powerOn = false,
            initializing = false,
            backend = backend,
            equalizer = equalizerReport,
            dynamics = dynamicsReport,
        )
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            stopFading()
            cancelPresetTransition(applyFinalPreset = true)
        }
        if (enabled && dynamics == null) {
            initialize()
        }
        val configuredDynamicsReport = applyDynamicsPreset(
            report = _state.value.dynamics,
            usePreEqCurve = shouldUseDynamicsPreEqCurve(),
            usePostEqCurve = shouldUseDynamicsPostEqCurve(),
        )
        val dynamicsReport = applyEnabled(dynamics, configuredDynamicsReport, enabled, "dynamics")
        publish(
            powerOn = enabled,
            initializing = false,
            backend = backend,
            equalizer = dynamicsOnlyEqualizerReport(),
            dynamics = dynamicsReport,
        )
        updateFadingSchedule()
    }

    fun setPreset(preset: AudioPreset) {
        if (selectedPreset == preset) return
        stopFading()
        val previousPreset = selectedPreset
        val wasOn = _state.value.powerOn
        val currentParameters = cancelPresetTransition(applyFinalPreset = false)
        selectedPreset = preset
        if (startPresetTransition(
                from = currentParameters ?: previousPreset.parameters(),
                to = preset.parameters(),
                powerOn = wasOn,
            )
        ) {
            return
        }
        initialize()
        if (wasOn) {
            setEnabled(true)
        }
    }

    fun handleRouteChange() {
        stopFading()
        cancelPresetTransition(applyFinalPreset = true)
        val wantOn = _state.value.powerOn
        AudioEffectLog.i("route change wantOn=$wantOn")
        if (!wantOn) return
        if (!enginesHealthy()) {
            AudioEffectLog.i("route change recreate")
            initialize()
            setEnabled(true)
            return
        }
        try {
            dynamics?.let {
                AmDynamicsConfig.applyPreset(
                    dynamics = it,
                    preset = selectedPreset,
                    usePreEqCurve = shouldUseDynamicsPreEqCurve(),
                    usePostEqCurve = shouldUseDynamicsPostEqCurve(),
                )
            }
        } catch (throwable: Throwable) {
            AudioEffectLog.e("route change update failed", throwable)
            initialize()
        }
        setEnabled(true)
    }

    fun release() {
        stopFading()
        cancelPresetTransition(applyFinalPreset = false)
        releaseEngines()
        attempted = false
        _state.value = AudioEffectUiState(backend = backend)
    }

    private fun createDynamics(): AudioEngineReport {
        var lastError: Throwable? = null
        val channelCounts = linkedSetOf<Int>().apply {
            CHANNEL_COUNTS.forEach(::add)
        }
        try {
            val probe = DynamicsProcessing(PRIORITY, sessionId, null)
            try {
                probe.enabled = false
                probe.channelCount.takeIf { it > 0 }?.let(channelCounts::add)
            } finally {
                probe.releaseQuietly("dynamics probe")
            }
        } catch (throwable: Throwable) {
            lastError = throwable
            AudioEffectLog.e("dynamics channel probe failed session=$sessionId", throwable)
        }
        for (channelCount in channelCounts) {
            var candidate: DynamicsProcessing? = null
            try {
                candidate = DynamicsProcessing(
                    PRIORITY,
                    sessionId,
                    AmDynamicsConfig.build(
                        channelCount = channelCount,
                        preset = selectedPreset,
                        usePreEqCurve = shouldUseDynamicsPreEqCurve(),
                        usePostEqCurve = shouldUseDynamicsPostEqCurve(),
                    ),
                )
                candidate.enabled = false
                verifyPostEqConfiguration(candidate)
                val detail = dynamicsDetail(
                    candidate,
                    usePreEqCurve = shouldUseDynamicsPreEqCurve(),
                    usePostEqCurve = shouldUseDynamicsPostEqCurve(),
                )
                dynamics = candidate
                AudioEffectLog.i("dynamics create ok $detail")
                return AudioEngineReport.Ready(enabled = false, detail = detail)
            } catch (throwable: Throwable) {
                lastError = throwable
                candidate.releaseQuietly("dynamics candidate")
                AudioEffectLog.e("dynamics create failed channels=$channelCount", throwable)
            }
        }
        AudioEffectLog.e(
            "dynamics configured create failed session=$sessionId; " +
                "default config is not used as a preset fallback",
            lastError,
        )
        return classify(
            lastError ?: IllegalStateException("No compatible DynamicsProcessing channel count"),
        )
    }

    private fun enginesHealthy(): Boolean {
        return try {
            val effect = dynamics ?: return false
            effect.enabled
            verifyPostEqConfiguration(effect)
            true
        } catch (throwable: Throwable) {
            AudioEffectLog.e("engine health check failed", throwable)
            false
        }
    }

    private fun startPresetTransition(
        from: AudioPresetParameters,
        to: AudioPresetParameters,
        powerOn: Boolean,
    ): Boolean {
        val currentDynamics = dynamics
        if (currentDynamics == null) return false

        val token = Any()
        val transition = PresetTransition(
            token = token,
            from = from,
            to = to,
            startedAtMs = SystemClock.uptimeMillis(),
        )
        transitionState = transition
        try {
            applyPresetAtProgress(currentDynamics, from, to, 0f)
            publishExisting(powerOn)
        } catch (throwable: Throwable) {
            transitionState = null
            AudioEffectLog.e("preset transition start failed", throwable)
            return false
        }

        for (step in 1..TRANSITION_STEPS) {
            transitionHandler.postDelayed({
                val currentTransition = transitionState ?: return@postDelayed
                if (currentTransition.token !== token) return@postDelayed
                try {
                    val progress = transitionProgress(currentTransition)
                    applyPresetAtProgress(
                        currentDynamics,
                        currentTransition.from,
                        currentTransition.to,
                        progress,
                    )
                    if (step == TRANSITION_STEPS || progress >= 1f) {
                        transitionState = null
                        publishExisting(_state.value.powerOn)
                        updateFadingSchedule()
                    }
                } catch (throwable: Throwable) {
                    transitionState = null
                    AudioEffectLog.e("preset transition step failed", throwable)
                    val shouldBeOn = _state.value.powerOn
                    initialize()
                    if (shouldBeOn) setEnabled(true)
                }
            }, token, TRANSITION_STEP_MS * step)
        }
        return true
    }

    private fun applyPresetAtProgress(
        dynamics: DynamicsProcessing,
        from: AudioPresetParameters,
        to: AudioPresetParameters,
        progress: Float,
    ) {
        AmDynamicsConfig.applyPresetAtProgress(
            dynamics = dynamics,
            from = from,
            to = to,
            progress = progress,
            usePreEqCurve = false,
            usePostEqCurve = true,
        )
    }

    private fun applyDynamicsPreset(
        report: AudioEngineReport,
        usePreEqCurve: Boolean,
        usePostEqCurve: Boolean,
    ): AudioEngineReport {
        val effect = dynamics ?: return report
        if (report is AudioEngineReport.Unsupported) return report
        return try {
            AmDynamicsConfig.applyPreset(
                dynamics = effect,
                preset = selectedPreset,
                usePreEqCurve = usePreEqCurve,
                usePostEqCurve = usePostEqCurve,
            )
            verifyPostEqConfiguration(effect)
            val enabled = (report as? AudioEngineReport.Ready)?.enabled ?: effect.enabled
            AudioEngineReport.Ready(
                enabled = enabled,
                detail = dynamicsDetail(
                    effect,
                    usePreEqCurve = usePreEqCurve,
                    usePostEqCurve = usePostEqCurve,
                ),
            )
        } catch (throwable: Throwable) {
            AudioEffectLog.e("dynamics preset apply failed", throwable)
            classify(throwable)
        }
    }

    // The production topology deliberately keeps Pre-EQ flat and applies the
    // full tone curve once in Post-EQ after MBC.
    private fun shouldUseDynamicsPreEqCurve(): Boolean = false

    private fun shouldUseDynamicsPostEqCurve(): Boolean = true

    /**
     * The production path depends on a real Post-EQ stage rather than merely
     * accepting a config object. Some HALs can construct DynamicsProcessing
     * while silently disabling or truncating a stage, which would let high
     * frequencies bypass the requested -48 dB curve.
     */
    private fun verifyPostEqConfiguration(effect: DynamicsProcessing) {
        val config = effect.config
        if (!config.isPostEqInUse || config.postEqBandCount != AmDynamicsConfig.POST_EQ_BAND_COUNT) {
            throw UnsupportedOperationException(
                "DynamicsProcessing Post-EQ not supported: " +
                    "inUse=${config.isPostEqInUse} bands=${config.postEqBandCount}",
            )
        }
        val channelCount = effect.channelCount.coerceAtLeast(1)
        for (channelIndex in 0 until channelCount) {
            val stage = config.getPostEqByChannelIndex(channelIndex)
            if (!stage.isInUse || !stage.isEnabled ||
                stage.bandCount != AmDynamicsConfig.POST_EQ_BAND_COUNT
            ) {
                throw UnsupportedOperationException(
                    "DynamicsProcessing Post-EQ not supported: " +
                        "channel=$channelIndex inUse=${stage.isInUse} " +
                        "enabled=${stage.isEnabled} bands=${stage.bandCount}",
                )
            }
            for (bandIndex in 0 until AmDynamicsConfig.POST_EQ_BAND_COUNT) {
                val band = stage.getBand(bandIndex)
                if (!band.isEnabled) {
                    throw UnsupportedOperationException(
                        "DynamicsProcessing Post-EQ band not supported: " +
                        "channel=$channelIndex index=$bandIndex disabled",
                    )
                }
                if (bandIndex >= AmDynamicsConfig.POST_EQ_HIGH_CUT_BAND_START_INDEX &&
                    band.gain > AmDynamicsConfig.POST_EQ_HIGH_CUT_GAIN_DB + 0.5f
                ) {
                    throw UnsupportedOperationException(
                        "DynamicsProcessing Post-EQ high cut not supported: " +
                            "channel=$channelIndex index=$bandIndex gain=${band.gain}dB",
                    )
                }
            }
            val finalBand = stage.getBand(AmDynamicsConfig.POST_EQ_BAND_COUNT - 1)
            if (finalBand.cutoffFrequency < AmDynamicsConfig.POST_EQ_FINAL_CUTOFF_HZ - 1f) {
                throw UnsupportedOperationException(
                    "DynamicsProcessing Post-EQ final cutoff not supported: " +
                        "${finalBand.cutoffFrequency}Hz",
                )
            }
        }
    }

    private fun cancelPresetTransition(applyFinalPreset: Boolean): AudioPresetParameters? {
        val transition = transitionState ?: return null
        val currentParameters = AudioPresetParameters.interpolate(
            from = transition.from,
            to = transition.to,
            progress = transitionProgress(transition),
        )
        transitionHandler.removeCallbacksAndMessages(transition.token)
        transitionState = null
        val targetParameters = if (applyFinalPreset) {
            selectedPreset.parameters()
        } else {
            currentParameters
        }
        try {
            dynamics?.let {
                applyPresetAtProgress(
                    dynamics = it,
                    from = targetParameters,
                    to = targetParameters,
                    progress = 1f,
                )
            }
            if (applyFinalPreset) {
                publishExisting(_state.value.powerOn)
            }
        } catch (throwable: Throwable) {
            AudioEffectLog.e("preset transition finish failed", throwable)
        }
        return currentParameters
    }

    /**
     * Starts the Fading modulation only after a preset transition has settled.
     * The Handler and its Runnable are owned by this controller and are stopped
     * on every lifecycle/effect transition path so a released effect is never
     * touched by a stale callback.
     */
    private fun updateFadingSchedule() {
        stopFading()
        val preset = selectedPreset
        val effect = dynamics ?: return
        if (!_state.value.powerOn || transitionState != null) return
        val report = _state.value.dynamics
        if (report !is AudioEngineReport.Ready || !report.enabled) return
        if (preset.fadeDepthDb <= 0f || preset.fadePeriodMs <= 0L) return

        val startedAtMs = SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (fadingRunnable !== this) return
                val currentDynamics = dynamics
                if (
                    currentDynamics == null ||
                    !_state.value.powerOn ||
                    selectedPreset != preset ||
                    transitionState != null
                ) {
                    stopFading()
                    return
                }
                val elapsedMs = SystemClock.uptimeMillis() - startedAtMs
                val phase = (elapsedMs.toDouble() / preset.fadePeriodMs.toDouble()) *
                    (2.0 * kotlin.math.PI)
                val gainDb = preset.inputGainDb +
                    kotlin.math.sin(phase).toFloat() * preset.fadeDepthDb
                try {
                    currentDynamics.setInputGainAllChannelsTo(gainDb)
                } catch (throwable: Throwable) {
                    AudioEffectLog.e("fading input gain update failed", throwable)
                    stopFading()
                    return
                }
                transitionHandler.postDelayed(this, FADING_TICK_MS)
            }
        }
        fadingRunnable = runnable
        AudioEffectLog.i(
            "fading modulation started depth=${preset.fadeDepthDb}dB " +
                "period=${preset.fadePeriodMs}ms tick=${FADING_TICK_MS}ms",
        )
        transitionHandler.post(runnable)
    }

    private fun stopFading() {
        fadingRunnable?.let {
            transitionHandler.removeCallbacks(it)
            AudioEffectLog.i("fading modulation stopped")
        }
        fadingRunnable = null
    }

    private fun transitionProgress(transition: PresetTransition): Float {
        val elapsedMs = SystemClock.uptimeMillis() - transition.startedAtMs
        return (elapsedMs.toFloat() / TRANSITION_DURATION_MS).coerceIn(0f, 1f)
    }

    private fun publishExisting(powerOn: Boolean) {
        attempted = true
        val currentDynamicsReport = _state.value.dynamics
        val dynamicsReport = dynamics?.let {
            if (currentDynamicsReport is AudioEngineReport.Unsupported ||
                currentDynamicsReport is AudioEngineReport.Failed
            ) {
                return@let currentDynamicsReport
            }
            try {
                verifyPostEqConfiguration(it)
                AudioEngineReport.Ready(
                    enabled = it.enabled,
                    detail = dynamicsDetail(
                        it,
                        usePreEqCurve = shouldUseDynamicsPreEqCurve(),
                        usePostEqCurve = shouldUseDynamicsPostEqCurve(),
                    ),
                )
            } catch (throwable: Throwable) {
                classify(throwable)
            }
        } ?: _state.value.dynamics
        publish(
            powerOn = powerOn,
            initializing = false,
            backend = backend,
            equalizer = dynamicsOnlyEqualizerReport(),
            dynamics = dynamicsReport,
        )
    }

    private fun dynamicsOnlyEqualizerReport(): AudioEngineReport = AudioEngineReport.NotUsed(
        reason = "backend=${AudioEffectBackend.DYNAMICS_ONLY.id}",
    )

    private fun dynamicsDetail(
        dynamics: DynamicsProcessing,
        usePreEqCurve: Boolean,
        usePostEqCurve: Boolean,
    ): String {
        val config = dynamics.config
        val inputGain = runCatching {
            dynamics.getInputGainByChannelIndex(0)
        }.getOrNull()
        val mbc = runCatching {
            dynamics.getMbcBandByChannelIndex(0, 0)
        }.getOrNull()
        val postEqEnabled = usePostEqCurve && config.isPostEqInUse && config.postEqBandCount > 0
        val postEqMode = when {
            postEqEnabled -> "curve"
            usePostEqCurve -> "unavailable"
            else -> "flat"
        }
        val postEqBands = if (postEqEnabled) {
            eqBandsDetail(dynamics, post = true, bandCount = config.postEqBandCount)
        } else {
            "-"
        }
        return "session=$sessionId backend=${backend.id} channels=${dynamics.channelCount} " +
            "preset=${selectedPreset.id} preEq=${if (usePreEqCurve) "curve" else "flat"} " +
            "postEq=$postEqMode " +
            "postEqBands=$postEqBands " +
            "inputGain=${inputGain ?: selectedPreset.inputGainDb}dB " +
            "mbc=${mbc?.let { "ratio=${it.ratio} threshold=${it.threshold}dB " +
                "attack=${it.attackTime}ms release=${it.releaseTime}ms post=${it.postGain}dB" }
                ?: "ratio=${selectedPreset.mbcRatio} threshold=${selectedPreset.mbcThresholdDb}dB " +
                "post=${selectedPreset.effectiveMbcPostGainDb}dB"} " +
            "fade=${selectedPreset.fadeDepthDb}dB/${selectedPreset.fadePeriodMs}ms"
    }

    private fun eqBandsDetail(
        dynamics: DynamicsProcessing,
        post: Boolean,
        bandCount: Int,
    ): String {
        return runCatching {
            (0 until bandCount).joinToString(separator = ",") { index ->
                val band = if (post) {
                    dynamics.getPostEqBandByChannelIndex(0, index)
                } else {
                    dynamics.getPreEqBandByChannelIndex(0, index)
                }
                "${band.cutoffFrequency.toInt()}Hz:${band.gain}dB"
            }
        }.getOrElse { throwable ->
            "readback_failed:${throwable.javaClass.simpleName}"
        }
    }

    private fun lerp(
        start: Float,
        end: Float,
        progress: Float,
    ): Float = start + (end - start) * progress.coerceIn(0f, 1f)

    private fun applyEnabled(
        effect: AudioEffect?,
        report: AudioEngineReport,
        enabled: Boolean,
        name: String,
    ): AudioEngineReport {
        if (effect == null) {
            return report
        }
        return try {
            effect.enabled = enabled
            val actuallyEnabled = effect.enabled
            AudioEffectLog.i("$name setEnabled requested=$enabled actual=$actuallyEnabled")
            when {
                enabled && !actuallyEnabled -> {
                    AudioEngineReport.Failed("$name enable request was not applied")
                }
                !enabled && actuallyEnabled -> {
                    AudioEngineReport.Failed("$name disable request was not applied")
                }
                report is AudioEngineReport.Ready -> report.copy(enabled = actuallyEnabled)
                else -> report
            }
        } catch (throwable: Throwable) {
            AudioEffectLog.e("$name setEnabled failed", throwable)
            classify(throwable)
        }
    }

    private fun publish(
        powerOn: Boolean,
        initializing: Boolean,
        backend: AudioEffectBackend,
        equalizer: AudioEngineReport,
        dynamics: AudioEngineReport,
    ) {
        _state.update {
            AudioEffectUiState(
                powerOn = powerOn,
                initializing = initializing,
                preset = selectedPreset,
                backend = backend,
                status = razioStatus(
                    initializing = initializing,
                    attempted = attempted,
                    powerOn = powerOn,
                    equalizer = equalizer,
                    dynamics = dynamics,
                ),
                equalizer = equalizer,
                dynamics = dynamics,
            )
        }
    }

    private fun logAvailableEffects() {
        try {
            (AudioEffect.queryEffects() ?: emptyArray()).forEach { descriptor ->
                AudioEffectLog.i(
                    "available name=${descriptor.name} type=${descriptor.type} " +
                        "uuid=${descriptor.uuid} mode=${descriptor.connectMode}",
                )
            }
        } catch (throwable: Throwable) {
            AudioEffectLog.e("queryEffects failed", throwable)
        }
    }

    private fun releaseEngines() {
        dynamics = dynamics.releaseQuietly("dynamics")
    }

    private fun <T : AudioEffect> T?.releaseQuietly(name: String): T? {
        if (this == null) return null
        try {
            release()
            AudioEffectLog.i("$name released")
        } catch (throwable: Throwable) {
            AudioEffectLog.e("$name release failed", throwable)
        }
        return null
    }

    private fun classify(throwable: Throwable): AudioEngineReport {
        val message = throwable.message ?: throwable.javaClass.simpleName
        val unsupported =
            throwable is UnsupportedOperationException ||
                message.contains("not supported", ignoreCase = true) ||
                message.contains("not available", ignoreCase = true)
        return if (unsupported) {
            AudioEngineReport.Unsupported(message)
        } else {
            AudioEngineReport.Failed(message)
        }
    }

    private data class PresetTransition(
        val token: Any,
        val from: AudioPresetParameters,
        val to: AudioPresetParameters,
        val startedAtMs: Long,
    )

    private companion object {
        const val PRIORITY = 0
        const val TRANSITION_STEPS = 8
        const val TRANSITION_STEP_MS = 10L
        const val TRANSITION_DURATION_MS = TRANSITION_STEPS * TRANSITION_STEP_MS
        const val FADING_TICK_MS = 100L
        val CHANNEL_COUNTS = intArrayOf(2, 1)
    }
}
