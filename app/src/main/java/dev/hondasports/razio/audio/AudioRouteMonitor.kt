package dev.hondasports.razio.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

class AudioRouteMonitor(
    context: Context,
    private val onRouteChanged: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            AudioEffectLog.i("audio devices added count=${addedDevices.size}")
            onRouteChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            AudioEffectLog.i("audio devices removed count=${removedDevices.size}")
            onRouteChanged()
        }
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        AudioEffectLog.i("audio route monitor started")
    }
}
