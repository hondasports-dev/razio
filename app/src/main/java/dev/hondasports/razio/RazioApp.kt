package dev.hondasports.razio

import android.app.Application
import dev.hondasports.razio.audio.GlobalAudioEffectController

class RazioApp : Application() {
    lateinit var audioEffects: GlobalAudioEffectController
        private set

    override fun onCreate() {
        super.onCreate()
        audioEffects = GlobalAudioEffectController()
        audioEffects.initialize()
    }
}
