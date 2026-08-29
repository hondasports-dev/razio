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
                equalizer = AudioEngineReport.Idle,
                dynamics = AudioEngineReport.Idle,
            ),
        )
    }

    @Test
    fun readyAndOn_isActive() {
        assertEquals(
            RazioStatus.Active,
            razioStatus(
                initializing = false,
                attempted = true,
                powerOn = true,
                equalizer = AudioEngineReport.Ready(enabled = true, detail = "eq"),
                dynamics = AudioEngineReport.Failed("no dp"),
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
                equalizer = AudioEngineReport.Ready(enabled = false, detail = "eq"),
                dynamics = AudioEngineReport.Unsupported("no dp"),
            ),
        )
    }

    @Test
    fun bothUnsupported_isUnsupported() {
        assertEquals(
            RazioStatus.Unsupported,
            razioStatus(
                initializing = false,
                attempted = true,
                powerOn = true,
                equalizer = AudioEngineReport.Unsupported("eq"),
                dynamics = AudioEngineReport.Unsupported("dp"),
            ),
        )
    }

    @Test
    fun failedWithoutReady_isError() {
        assertEquals(
            RazioStatus.Error,
            razioStatus(
                initializing = false,
                attempted = true,
                powerOn = true,
                equalizer = AudioEngineReport.Failed("eq"),
                dynamics = AudioEngineReport.Unsupported("dp"),
            ),
        )
    }
}
