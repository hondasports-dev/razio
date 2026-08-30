package dev.hondasports.razio.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
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
 * session 0 の global mix に Equalizer / DynamicsProcessing を載せる PoC。
 * session 0 は deprecated で端末依存。生成失敗は隠さず [AudioEngineReport] にする。
 * Application に保持し、プロセスが生きている間だけ効果を維持する。
 */
class GlobalAudioEffectController(
    private val sessionId: Int = AudioSession.GLOBAL_OUTPUT_MIX,
) {
    private val _state = MutableStateFlow(AudioEffectUiState())
    val state: StateFlow<AudioEffectUiState> = _state.asStateFlow()

    private var equalizer: Equalizer? = null
    private var dynamics: DynamicsProcessing? = null
    private var attempted: Boolean = false
    private var backend: AudioEffectBackend = AudioEffectBackend.SPLIT
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
        val equalizerReport = when (backend) {
            AudioEffectBackend.SPLIT -> createEqualizer()
            AudioEffectBackend.DYNAMICS_ONLY -> AudioEngineReport.NotUsed(
                reason = "backend=${AudioEffectBackend.DYNAMICS_ONLY.id}",
            )
        }
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

    /**
     * Switches the experimental processing topology. Effects are recreated so
     * the old chain cannot remain attached while the new one is being tested.
     * Split remains the default and is intentionally not persisted.
     */
    fun setBackend(newBackend: AudioEffectBackend) {
        if (backend == newBackend) return
        stopFading()
        cancelPresetTransition(applyFinalPreset = false)
        val wasOn = _state.value.powerOn
        AudioEffectLog.i("backend switch ${backend.id} -> ${newBackend.id} wasOn=$wasOn")
        backend = newBackend
        initialize()
        if (wasOn) setEnabled(true)
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            stopFading()
            cancelPresetTransition(applyFinalPreset = true)
        }
        if (enabled && equalizer == null && dynamics == null) {
            initialize()
        }
        val preparedEqualizerReport = retryEqualizerPreset(_state.value.equalizer)
        val equalizerReport = applyEnabled(equalizer, preparedEqualizerReport, enabled, "equalizer")
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
            equalizer = equalizerReport,
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
            equalizer?.let { applyEqualizerPreset(it, selectedPreset) }
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

    private fun createEqualizer(): AudioEngineReport {
        var candidate: Equalizer? = null
        return try {
            candidate = Equalizer(PRIORITY, sessionId)
            candidate.enabled = false
            applyEqualizerPreset(candidate, selectedPreset)
            val detail = equalizerDetail(candidate)
            equalizer = candidate
            candidate = null
            AudioEffectLog.i("equalizer create ok $detail")
            AudioEngineReport.Ready(enabled = false, detail = detail)
        } catch (throwable: Throwable) {
            candidate.releaseQuietly("equalizer candidate")
            AudioEffectLog.e("equalizer create failed session=$sessionId", throwable)
            classify(throwable)
        }
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
            equalizer?.enabled
            dynamics?.enabled
            equalizer != null || dynamics != null
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
        val currentEqualizer = equalizer
        val currentDynamics = dynamics
        if (currentEqualizer == null && currentDynamics == null) return false

        val token = Any()
        val transition = PresetTransition(
            token = token,
            from = from,
            to = to,
            startedAtMs = SystemClock.uptimeMillis(),
        )
        transitionState = transition
        try {
            applyPresetAtProgress(currentEqualizer, currentDynamics, from, to, 0f)
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
                        currentEqualizer,
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
        equalizer: Equalizer?,
        dynamics: DynamicsProcessing?,
        from: AudioPresetParameters,
        to: AudioPresetParameters,
        progress: Float,
    ) {
        equalizer?.let { applyEqualizerAtProgress(it, from, to, progress) }
        dynamics?.let {
            AmDynamicsConfig.applyPresetAtProgress(
                dynamics = it,
                from = from,
                to = to,
                progress = progress,
                usePreEqCurve = shouldUseDynamicsPreEqCurve(),
                usePostEqCurve = shouldUseDynamicsPostEqCurve(),
            )
        }
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

    private fun retryEqualizerPreset(report: AudioEngineReport): AudioEngineReport {
        val effect = equalizer ?: return report
        if (report !is AudioEngineReport.Failed) return report
        return try {
            applyEqualizerPreset(effect, selectedPreset)
            AudioEngineReport.Ready(enabled = effect.enabled, detail = equalizerDetail(effect))
        } catch (throwable: Throwable) {
            AudioEffectLog.e("equalizer preset retry failed", throwable)
            classify(throwable)
        }
    }

    private fun shouldUseDynamicsPreEqCurve(): Boolean {
        if (backend == AudioEffectBackend.DYNAMICS_ONLY) return false
        val effect = equalizer ?: return true
        return try {
            // The report can be Failed even when the native effect stayed enabled.
            // Use the native state so Dynamics never duplicates an active EQ curve.
            !effect.enabled
        } catch (throwable: Throwable) {
            AudioEffectLog.e(
                "equalizer enabled state unavailable; keep dynamics preEq flat",
                throwable,
            )
            false
        }
    }

    private fun shouldUseDynamicsPostEqCurve(): Boolean {
        // Dynamics only uses the Post-EQ so the tone curve is applied after
        // MBC makeup gain. Split keeps the existing dedicated Equalizer path.
        return backend == AudioEffectBackend.DYNAMICS_ONLY
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
            applyPresetAtProgress(
                equalizer = equalizer,
                dynamics = dynamics,
                from = targetParameters,
                to = targetParameters,
                progress = 1f,
            )
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
        val currentEqualizerReport = _state.value.equalizer
        val equalizerReport = equalizer?.let {
            if (currentEqualizerReport is AudioEngineReport.Unsupported ||
                currentEqualizerReport is AudioEngineReport.Failed
            ) {
                return@let currentEqualizerReport
            }
            try {
                AudioEngineReport.Ready(enabled = it.enabled, detail = equalizerDetail(it))
            } catch (throwable: Throwable) {
                classify(throwable)
            }
        } ?: _state.value.equalizer
        val currentDynamicsReport = _state.value.dynamics
        val dynamicsReport = dynamics?.let {
            if (currentDynamicsReport is AudioEngineReport.Unsupported ||
                currentDynamicsReport is AudioEngineReport.Failed
            ) {
                return@let currentDynamicsReport
            }
            try {
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
            equalizer = equalizerReport,
            dynamics = dynamicsReport,
        )
    }

    private fun applyEqualizerPreset(
        equalizer: Equalizer,
        preset: AudioPreset,
    ) {
        applyEqualizerAtProgress(
            equalizer = equalizer,
            from = preset.parameters(),
            to = preset.parameters(),
            progress = 1f,
        )
    }

    private fun applyEqualizerAtProgress(
        equalizer: Equalizer,
        from: AudioPresetParameters,
        to: AudioPresetParameters,
        progress: Float,
    ) {
        val range = equalizer.bandLevelRange
        val min = range.getOrNull(0) ?: return
        val max = range.getOrNull(1) ?: return
        val bandCount = equalizer.numberOfBands.toInt()
        val t = progress.coerceIn(0f, 1f)
        for (band in 0 until bandCount) {
            val centerHz = equalizer.getCenterFreq(band.toShort()) / 1000f
            val gainDb = lerp(
                from.gainDbForCenterHz(centerHz),
                to.gainDbForCenterHz(centerHz),
                t,
            )
            equalizer.setBandLevel(
                band.toShort(),
                AudioPreset.millibelsForGainDb(gainDb, min, max),
            )
        }
    }

    private fun equalizerDetail(equalizer: Equalizer): String {
        val bandCount = equalizer.numberOfBands.toInt()
        val bands = (0 until bandCount).joinToString(separator = " ") { band ->
            val hz = equalizer.getCenterFreq(band.toShort()) / 1000f
            val level = equalizer.getBandLevel(band.toShort())
            "${hz.toInt()}Hz:${level}mB"
        }
        return "session=$sessionId preset=${selectedPreset.id} bands=$bandCount $bands"
    }

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
        equalizer = equalizer.releaseQuietly("equalizer")
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
