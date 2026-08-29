package dev.hondasports.razio.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import dev.hondasports.razio.audio.preset.AmDynamicsConfig
import dev.hondasports.razio.audio.preset.AmPreset
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

    fun initialize() {
        _state.update { it.copy(initializing = true, status = RazioStatus.Initializing) }
        logAvailableEffects()
        releaseEngines()
        val equalizerReport = createEqualizer()
        val dynamicsReport = createDynamics()
        attempted = true
        publish(
            powerOn = false,
            initializing = false,
            equalizer = equalizerReport,
            dynamics = dynamicsReport,
        )
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled && equalizer == null && dynamics == null) {
            initialize()
        }
        val equalizerReport = applyEnabled(equalizer, _state.value.equalizer, enabled, "equalizer")
        val dynamicsReport = applyEnabled(dynamics, _state.value.dynamics, enabled, "dynamics")
        publish(
            powerOn = enabled,
            initializing = false,
            equalizer = equalizerReport,
            dynamics = dynamicsReport,
        )
    }

    fun release() {
        releaseEngines()
        attempted = false
        _state.value = AudioEffectUiState()
    }

    private fun createEqualizer(): AudioEngineReport {
        return try {
            val created = Equalizer(PRIORITY, sessionId)
            created.enabled = false
            applyEqualizerPreset(created)
            equalizer = created
            val detail = equalizerDetail(created)
            AudioEffectLog.i("equalizer create ok $detail")
            AudioEngineReport.Ready(enabled = false, detail = detail)
        } catch (throwable: Throwable) {
            AudioEffectLog.e("equalizer create failed session=$sessionId", throwable)
            classify(throwable)
        }
    }

    private fun createDynamics(): AudioEngineReport {
        var lastError: Throwable? = null
        for (channelCount in CHANNEL_COUNTS) {
            try {
                val created = DynamicsProcessing(PRIORITY, sessionId, AmDynamicsConfig.build(channelCount))
                created.enabled = false
                dynamics = created
                val detail = "session=$sessionId channels=$channelCount am-config"
                AudioEffectLog.i("dynamics create ok $detail")
                return AudioEngineReport.Ready(enabled = false, detail = detail)
            } catch (throwable: Throwable) {
                lastError = throwable
                AudioEffectLog.e("dynamics create failed channels=$channelCount", throwable)
            }
        }
        return try {
            val created = DynamicsProcessing(PRIORITY, sessionId, null)
            created.enabled = false
            dynamics = created
            val detail = "session=$sessionId default-config"
            AudioEffectLog.i("dynamics create ok $detail")
            AudioEngineReport.Ready(enabled = false, detail = detail)
        } catch (throwable: Throwable) {
            lastError = throwable
            AudioEffectLog.e("dynamics default create failed session=$sessionId", throwable)
            classify(lastError)
        }
    }

    private fun applyEqualizerPreset(equalizer: Equalizer) {
        val range = equalizer.bandLevelRange
        val min = range.getOrNull(0) ?: return
        val max = range.getOrNull(1) ?: return
        val bandCount = equalizer.numberOfBands.toInt()
        for (band in 0 until bandCount) {
            val centerHz = equalizer.getCenterFreq(band.toShort()) / 1000f
            equalizer.setBandLevel(band.toShort(), AmPreset.millibels(centerHz, min, max))
        }
    }

    private fun equalizerDetail(equalizer: Equalizer): String {
        val bandCount = equalizer.numberOfBands.toInt()
        val bands = (0 until bandCount).joinToString(separator = " ") { band ->
            val hz = equalizer.getCenterFreq(band.toShort()) / 1000f
            val level = equalizer.getBandLevel(band.toShort())
            "${hz.toInt()}Hz:${level}mB"
        }
        return "session=$sessionId bands=$bandCount $bands"
    }

    private fun applyEnabled(
        effect: AudioEffect?,
        report: AudioEngineReport,
        enabled: Boolean,
        name: String,
    ): AudioEngineReport {
        if (effect == null || report !is AudioEngineReport.Ready) {
            return report
        }
        return try {
            effect.enabled = enabled
            val actuallyEnabled = effect.enabled
            AudioEffectLog.i("$name setEnabled requested=$enabled actual=$actuallyEnabled")
            report.copy(enabled = actuallyEnabled)
        } catch (throwable: Throwable) {
            AudioEffectLog.e("$name setEnabled failed", throwable)
            classify(throwable)
        }
    }

    private fun publish(
        powerOn: Boolean,
        initializing: Boolean,
        equalizer: AudioEngineReport,
        dynamics: AudioEngineReport,
    ) {
        _state.update {
            AudioEffectUiState(
                powerOn = powerOn,
                initializing = initializing,
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

    private companion object {
        const val PRIORITY = 0
        val CHANNEL_COUNTS = intArrayOf(2, 1)
    }
}
