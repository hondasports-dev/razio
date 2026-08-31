package dev.hondasports.razio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.hondasports.razio.theme.RazioTheme
import dev.hondasports.razio.ui.screen.RazioHomeRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RazioApp
        setContent {
            RazioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RazioHomeRoute(
                        controller = app.audioEffects,
                        noiseOverlay = app.noiseOverlay,
                        spectrumAnalyzer = app.spectrumAnalyzer,
                        onPowerChange = app::setPowerOn,
                        onPresetChange = app::setPreset,
                        onPresetTuningChange = app::setPresetTuning,
                        onHissChange = app::setHissEnabled,
                        onCrackleChange = app::setCrackleEnabled,
                        onHissLevelChange = app::setHissLevel,
                        onCrackleLevelChange = app::setCrackleLevel,
                        onSpectrumStartWithoutProjection = app::startSpectrumWithoutProjection,
                        onSpectrumProjectionResult = app::startSpectrumProjection,
                        onSpectrumConsentDenied = app::spectrumConsentDenied,
                        onSpectrumStop = app::stopSpectrum,
                    )
                }
            }
        }
    }
}
