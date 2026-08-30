package dev.hondasports.razio.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class RazioStatusTest {
    @Test
    fun initializing_wins() {
        assertEquals(
            RazioStatus.Initializing,
            razioStatus(
                initializing = true,
                attempted = false,
                powerOn = false,
                equalizer = AudioEngineReport.NotUsed("backend=dynamics_only"),
                dynamics = AudioEngineReport.Idle,
            ),
        )
    }

    @Test
    fun dynamicsReadyAndOn_isActive() {
        assertEquals(
            RazioStatus.Active,
            razioStatus(
                initializing = false,
                attempted = true,
                powerOn = true,
                equalizer = AudioEngineReport.NotUsed("backend=dynamics_only"),
                dynamics = AudioEngineReport.Ready(enabled = true, detail = "dp"),
            ),
        )
    }

    @Test
    fun readyAndOff_isDisabled() {
        assertEquals(
            RazioStatus.Disabled,
            razioStatus(
                initializing = false,
                attempted = true,
                powerOn = false,
                equalizer = AudioEngineReport.NotUsed("backend=dynamics_only"),
                dynamics = AudioEngineReport.Ready(enabled = false, detail = "dp"),
            ),
        )
    }

    @Test
    fun dynamicsUnsupported_isUnsupported() {
        assertEquals(
            RazioStatus.Unsupported,
            razioStatus(
                initializing = false,
                attempted = true,
                powerOn = true,
                equalizer = AudioEngineReport.NotUsed("backend=dynamics_only"),
                dynamics = AudioEngineReport.Unsupported("dp"),
            ),
        )
    }

    @Test
    fun dynamicsFailedWithoutReady_isError() {
        assertEquals(
            RazioStatus.Error,
            razioStatus(
                initializing = false,
                attempted = true,
                powerOn = true,
                equalizer = AudioEngineReport.NotUsed("backend=dynamics_only"),
                dynamics = AudioEngineReport.Failed("dp"),
            ),
        )
    }

    @Test
    fun unknownBackendId_fallsBackToDynamicsOnly() {
        assertEquals(AudioEffectBackend.DYNAMICS_ONLY, AudioEffectBackend.fromId("unknown"))
        assertEquals(AudioEffectBackend.DYNAMICS_ONLY, AudioEffectBackend.fromId("dynamics_only"))
    }
}
