package dev.hondasports.razio.audio

import dev.hondasports.razio.audio.preset.AudioPreset

sealed class AudioEngineReport {
    data object Idle : AudioEngineReport()

    data class NotUsed(
        val reason: String,
    ) : AudioEngineReport()

    data class Ready(
        val enabled: Boolean,
        val detail: String,
    ) : AudioEngineReport()

    data class Unsupported(
        val reason: String,
    ) : AudioEngineReport()

    data class Failed(
        val message: String,
    ) : AudioEngineReport()
}

/** The production topology is intentionally fixed to one DynamicsProcessing effect. */
enum class AudioEffectBackend(val id: String) {
    DYNAMICS_ONLY("dynamics_only"),
    ;

    companion object {
        fun fromId(id: String?): AudioEffectBackend =
            entries.firstOrNull { it.id == id } ?: DYNAMICS_ONLY
    }
}

enum class RazioStatus {
    Idle,
    Initializing,
    Active,
    Disabled,
    Unsupported,
    Error,
}

data class AudioEffectUiState(
    val powerOn: Boolean = false,
    val initializing: Boolean = false,
    val status: RazioStatus = RazioStatus.Idle,
    val preset: AudioPreset = AudioPreset.NARROW_AM,
    val backend: AudioEffectBackend = AudioEffectBackend.DYNAMICS_ONLY,
    val equalizer: AudioEngineReport = AudioEngineReport.NotUsed("backend=dynamics_only"),
    val dynamics: AudioEngineReport = AudioEngineReport.Idle,
)

fun razioStatus(
    initializing: Boolean,
    attempted: Boolean,
    powerOn: Boolean,
    equalizer: AudioEngineReport,
    dynamics: AudioEngineReport,
): RazioStatus {
    if (initializing) return RazioStatus.Initializing
    if (!attempted) return RazioStatus.Idle
    val reports = listOf(equalizer, dynamics)
    val anyReady = reports.any { it is AudioEngineReport.Ready }
    val allUnavailable = reports.all {
        it is AudioEngineReport.Unsupported || it is AudioEngineReport.NotUsed
    }
    val anyFailed = reports.any { it is AudioEngineReport.Failed }
    return when {
        anyReady && powerOn -> RazioStatus.Active
        anyReady && !powerOn -> RazioStatus.Disabled
        allUnavailable -> RazioStatus.Unsupported
        anyFailed -> RazioStatus.Error
        else -> RazioStatus.Unsupported
    }
}
