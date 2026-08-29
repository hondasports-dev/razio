package dev.hondasports.razio.audio

sealed class AudioEngineReport {
    data object Idle : AudioEngineReport()

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
    val equalizer: AudioEngineReport = AudioEngineReport.Idle,
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
    val allUnsupported = reports.all { it is AudioEngineReport.Unsupported }
    val anyFailed = reports.any { it is AudioEngineReport.Failed }
    return when {
        anyReady && powerOn -> RazioStatus.Active
        anyReady && !powerOn -> RazioStatus.Disabled
        allUnsupported -> RazioStatus.Unsupported
        anyFailed -> RazioStatus.Error
        else -> RazioStatus.Unsupported
    }
}
