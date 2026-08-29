package dev.hondasports.razio.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSessionTest {
    @Test
    fun globalOutputMix_isSessionZero() {
        assertEquals(0, AudioSession.GLOBAL_OUTPUT_MIX)
    }
}
