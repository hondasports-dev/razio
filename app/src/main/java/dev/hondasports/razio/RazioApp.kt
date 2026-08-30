package dev.hondasports.razio

import android.app.Application
import dev.hondasports.razio.audio.AudioRouteMonitor
import dev.hondasports.razio.audio.GlobalAudioEffectController
import dev.hondasports.razio.audio.NoiseOverlayController
import dev.hondasports.razio.audio.RazioAudioService
import dev.hondasports.razio.audio.RazioPreferences
import dev.hondasports.razio.audio.preset.AudioPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RazioApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var audioEffects: GlobalAudioEffectController
        private set

    lateinit var noiseOverlay: NoiseOverlayController
        private set

    private lateinit var preferences: RazioPreferences
    private var powerPersistenceJob: Job? = null
    private var presetPersistenceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        preferences = RazioPreferences(this)
        audioEffects = GlobalAudioEffectController()
        noiseOverlay = NoiseOverlayController()
        audioEffects.initialize()
        AudioRouteMonitor(this, ::handleRouteChange).start()
        applicationScope.launch {
            audioEffects.setPreset(preferences.savedPreset())
            if (preferences.savedPowerOn()) {
                applyPower(enabled = true, persist = false)
            }
        }
    }

    fun setPowerOn(enabled: Boolean) {
        applyPower(enabled = enabled, persist = true)
    }

    fun setPreset(preset: AudioPreset) {
        audioEffects.setPreset(preset)
        presetPersistenceJob?.cancel()
        presetPersistenceJob = applicationScope.launch {
            preferences.setPreset(preset)
        }
    }

    fun setHissEnabled(enabled: Boolean) {
        noiseOverlay.setHissEnabled(enabled)
    }

    fun setCrackleEnabled(enabled: Boolean) {
        noiseOverlay.setCrackleEnabled(enabled)
    }

    private fun applyPower(
        enabled: Boolean,
        persist: Boolean,
    ) {
        if (!enabled) {
            noiseOverlay.setPowerOn(false)
        }
        audioEffects.setEnabled(enabled)
        if (enabled) {
            RazioAudioService.start(this)
            noiseOverlay.setPowerOn(true)
        } else {
            RazioAudioService.stop(this)
        }
        if (persist) {
            powerPersistenceJob?.cancel()
            powerPersistenceJob = applicationScope.launch {
                preferences.setPowerOn(enabled)
            }
        }
    }

    private fun handleRouteChange() {
        audioEffects.handleRouteChange()
        noiseOverlay.handleRouteChange()
    }
}
