package dev.hondasports.razio

import android.app.Application
import dev.hondasports.razio.audio.AudioRouteMonitor
import dev.hondasports.razio.audio.GlobalAudioEffectController
import dev.hondasports.razio.audio.RazioPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RazioApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var audioEffects: GlobalAudioEffectController
        private set

    private lateinit var preferences: RazioPreferences

    override fun onCreate() {
        super.onCreate()
        preferences = RazioPreferences(this)
        audioEffects = GlobalAudioEffectController()
        audioEffects.initialize()
        AudioRouteMonitor(this, audioEffects::handleRouteChange).start()
        applicationScope.launch {
            if (preferences.savedPowerOn()) {
                audioEffects.setEnabled(true)
            }
        }
    }

    fun setPowerOn(enabled: Boolean) {
        audioEffects.setEnabled(enabled)
        applicationScope.launch {
            preferences.setPowerOn(enabled)
        }
    }
}
