package dev.hondasports.razio

import android.app.Application
import android.content.Intent
import android.os.Build
import android.media.projection.MediaProjectionManager
import dev.hondasports.razio.audio.AudioRouteMonitor
import dev.hondasports.razio.audio.GlobalAudioEffectController
import dev.hondasports.razio.audio.NoiseOverlayController
import dev.hondasports.razio.audio.RazioAudioService
import dev.hondasports.razio.audio.RazioPreferences
import dev.hondasports.razio.audio.SpectrumAnalyzerController
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

    lateinit var spectrumAnalyzer: SpectrumAnalyzerController
        private set

    private lateinit var preferences: RazioPreferences
    private var powerPersistenceJob: Job? = null
    private var presetPersistenceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        preferences = RazioPreferences(this)
        audioEffects = GlobalAudioEffectController()
        noiseOverlay = NoiseOverlayController()
        spectrumAnalyzer = SpectrumAnalyzerController(this)
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

    /** Starts the output-only analyzer on API < 29, where playback capture is unavailable. */
    fun startSpectrumWithoutProjection() {
        spectrumAnalyzer.startWithoutProjection()
    }

    /**
     * Converts the ActivityResult token into a MediaProjection and starts the dual analyzer.
     * The projection FGS owner is separate from the RAZIO effect owner so either can stop
     * without unexpectedly removing the other's foreground service.
     */
    fun startSpectrumProjection(
        resultCode: Int,
        data: Intent?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || data == null) {
            spectrumAnalyzer.reportConsentDenied("MediaProjectionはこの端末で利用できません")
            return
        }
        val projectionManager = runCatching {
            getSystemService(MediaProjectionManager::class.java)
        }.getOrNull()
        if (projectionManager == null) {
            spectrumAnalyzer.reportConsentDenied("MediaProjection管理サービスを取得できませんでした")
            return
        }
        spectrumServiceStarted = true
        RazioAudioService.startForMediaProjection(this) { fgsReady ->
            if (!fgsReady) {
                spectrumServiceStarted = false
                spectrumAnalyzer.reportConsentDenied(
                    "MediaProjection用のforeground serviceを開始できませんでした",
                )
                return@startForMediaProjection
            }
            val mediaProjection = runCatching {
                projectionManager.getMediaProjection(resultCode, data)
            }.getOrNull()
            if (mediaProjection == null) {
                spectrumServiceStarted = false
                RazioAudioService.stopSpectrum(this)
                spectrumAnalyzer.reportConsentDenied(
                    "MediaProjectionの同意トークンを取得できませんでした",
                )
                return@startForMediaProjection
            }
            spectrumAnalyzer.start(mediaProjection)
        }
    }

    fun spectrumConsentDenied(reason: String) {
        stopSpectrum()
        spectrumAnalyzer.reportConsentDenied(reason)
    }

    fun stopSpectrum() {
        spectrumAnalyzer.stop()
        if (spectrumServiceStarted) {
            RazioAudioService.stopSpectrum(this)
            spectrumServiceStarted = false
        }
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
        spectrumAnalyzer.handleRouteChange()
    }

    private var spectrumServiceStarted = false
}
