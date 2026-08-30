package dev.hondasports.razio.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.hondasports.razio.R
import dev.hondasports.razio.audio.AudioEngineReport
import dev.hondasports.razio.audio.AudioEffectUiState
import dev.hondasports.razio.audio.GlobalAudioEffectController
import dev.hondasports.razio.audio.NoiseOverlayController
import dev.hondasports.razio.audio.NoiseOverlayStatus
import dev.hondasports.razio.audio.NoiseOverlayUiState
import dev.hondasports.razio.audio.RazioStatus
import dev.hondasports.razio.audio.SpectrumAnalyzerController
import dev.hondasports.razio.audio.SpectrumAnalyzerStatus
import dev.hondasports.razio.audio.SpectrumAnalyzerUiState
import dev.hondasports.razio.audio.SpectrumMath
import dev.hondasports.razio.audio.SpectrumSnapshot
import dev.hondasports.razio.audio.preset.AudioPreset
import dev.hondasports.razio.audio.preset.AudioPresetTuning
import dev.hondasports.razio.theme.RazioTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RazioHomeRoute(
    controller: GlobalAudioEffectController,
    noiseOverlay: NoiseOverlayController,
    spectrumAnalyzer: SpectrumAnalyzerController,
    onPowerChange: (Boolean) -> Unit,
    onPresetChange: (AudioPreset) -> Unit,
    onPresetTuningChange: (AudioPresetTuning) -> Unit = {},
    onHissChange: (Boolean) -> Unit = {},
    onCrackleChange: (Boolean) -> Unit = {},
    onSpectrumStartWithoutProjection: () -> Unit = {},
    onSpectrumProjectionResult: (Int, Intent?) -> Unit = { _, _ -> },
    onSpectrumConsentDenied: (String) -> Unit = {},
    onSpectrumStop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val noiseState by noiseOverlay.state.collectAsState()
    val spectrumState by spectrumAnalyzer.state.collectAsState()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        onPowerChange(true)
    }
    val projectionManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(MediaProjectionManager::class.java)
    } else {
        null
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onSpectrumProjectionResult(result.resultCode, result.data)
        } else {
            onSpectrumConsentDenied("MediaProjectionの同意がキャンセルされました")
        }
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            onSpectrumConsentDenied("マイク権限がないため入力解析を開始できません")
        } else if (projectionManager != null) {
            projectionLauncher.launch(createProjectionIntent(projectionManager))
        } else {
            onSpectrumStartWithoutProjection()
        }
    }
    RazioHomeScreen(
        state = state,
        onPowerChange = { enabled ->
            if (enabled && needsNotificationPermission(context)) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onPowerChange(enabled)
            }
        },
        onPresetChange = onPresetChange,
        onPresetTuningChange = onPresetTuningChange,
        noiseState = noiseState,
        onHissChange = onHissChange,
        onCrackleChange = onCrackleChange,
        spectrumState = spectrumState,
        onSpectrumStart = {
            when {
                needsRecordPermission(context) -> recordPermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO,
                )
                projectionManager != null -> projectionLauncher.launch(
                    createProjectionIntent(projectionManager),
                )
                else -> onSpectrumStartWithoutProjection()
            }
        },
        onSpectrumStop = onSpectrumStop,
        modifier = modifier,
    )
}

private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) != PackageManager.PERMISSION_GRANTED
}

@Composable
fun RazioHomeScreen(
    state: AudioEffectUiState,
    onPowerChange: (Boolean) -> Unit,
    onPresetChange: (AudioPreset) -> Unit,
    onPresetTuningChange: (AudioPresetTuning) -> Unit = {},
    noiseState: NoiseOverlayUiState = NoiseOverlayUiState(),
    onHissChange: (Boolean) -> Unit = {},
    onCrackleChange: (Boolean) -> Unit = {},
    spectrumState: SpectrumAnalyzerUiState = SpectrumAnalyzerUiState(),
    onSpectrumStart: () -> Unit = {},
    onSpectrumStop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RetroHeader(status = state.status, powerOn = state.powerOn)
        Spacer(modifier = Modifier.height(12.dp))

        RetroPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.power_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.status_label, statusText(state.status)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = state.powerOn,
                    onCheckedChange = onPowerChange,
                    enabled = !state.initializing,
                )
            }
        }

        RetroPanel(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                text = stringResource(R.string.processing_mode_dynamics_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            SectionHeading(
                text = stringResource(R.string.preset_label),
                modifier = Modifier.padding(top = 14.dp),
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
            ) {
                items(AudioPreset.entries, key = { it.id }) { preset ->
                    if (state.preset == preset) {
                        Button(
                            onClick = { onPresetChange(preset) },
                            enabled = !state.initializing,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(text = presetLabel(preset), maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onPresetChange(preset) },
                            enabled = !state.initializing,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(text = presetLabel(preset), maxLines = 1)
                        }
                    }
                }
            }
            Text(
                text = presetDescription(state.preset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        var tuningExpanded by rememberSaveable(state.preset.id) { mutableStateOf(false) }
        RetroPanel(modifier = Modifier.padding(top = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionHeading(text = stringResource(R.string.preset_tuning_heading))
                OutlinedButton(
                    onClick = { tuningExpanded = !tuningExpanded },
                    enabled = !state.initializing,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (tuningExpanded) {
                                R.string.preset_tuning_toggle_close
                            } else {
                                R.string.preset_tuning_toggle_open
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
            if (tuningExpanded) {
                Text(
                    text = stringResource(R.string.preset_tuning_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                PresetTuningEditor(
                    tuning = state.tuning,
                    enabled = !state.initializing,
                    onTuningChange = onPresetTuningChange,
                    onReset = { onPresetTuningChange(state.preset.defaultTuning()) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        RetroPanel(modifier = Modifier.padding(top = 12.dp)) {
            SectionHeading(text = stringResource(R.string.noise_overlay_label))
            NoiseToggleRow(
                label = stringResource(R.string.noise_hiss_label),
                checked = noiseState.hissEnabled,
                enabled = state.powerOn && !state.initializing,
                onCheckedChange = onHissChange,
            )
            NoiseToggleRow(
                label = stringResource(R.string.noise_crackle_label),
                checked = noiseState.crackleEnabled,
                enabled = state.powerOn && !state.initializing,
                onCheckedChange = onCrackleChange,
            )
            Text(
                text = stringResource(
                    R.string.noise_overlay_status,
                    noiseStatusText(noiseState.status),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (noiseState.detail.isNotEmpty()) {
                Text(
                    text = noiseState.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        RetroPanel(modifier = Modifier.padding(top = 12.dp)) {
            SectionHeading(text = stringResource(R.string.spectrum_heading))
            Text(
                text = stringResource(R.string.spectrum_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (spectrumState.running) {
                    OutlinedButton(
                        onClick = onSpectrumStop,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = stringResource(R.string.spectrum_stop))
                    }
                } else {
                    Button(
                        onClick = onSpectrumStart,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = stringResource(R.string.spectrum_start))
                    }
                }
                Text(
                    text = spectrumStatusText(spectrumState.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spectrumState.detail.isNotEmpty()) {
                Text(
                    text = spectrumState.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            SpectrumMeter(
                label = stringResource(R.string.spectrum_input_label),
                snapshot = spectrumState.input,
                accent = MaterialTheme.colorScheme.primary,
            )
            SpectrumMeter(
                label = stringResource(R.string.spectrum_output_label),
                snapshot = spectrumState.output,
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        RetroPanel(modifier = Modifier.padding(top = 12.dp)) {
            SectionHeading(text = stringResource(R.string.engine_status_heading))
            Text(
                text = stringResource(R.string.equalizer_label, reportText(state.equalizer)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.dynamics_label, reportText(state.dynamics)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = stringResource(R.string.poc_keep_alive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
    }
}

private fun needsRecordPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) != PackageManager.PERMISSION_GRANTED
}

private fun createProjectionIntent(manager: MediaProjectionManager): Intent {
    return if (Build.VERSION.SDK_INT >= 37) {
        manager.createScreenCaptureIntent(
            MediaProjectionConfig.Builder()
                .setAudioRequested(true)
                .build(),
        )
    } else {
        manager.createScreenCaptureIntent()
    }
}

@Composable
private fun PresetTuningEditor(
    tuning: AudioPresetTuning,
    enabled: Boolean,
    onTuningChange: (AudioPresetTuning) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeTuning = tuning.sanitized()
    val lowCutRange = orderedRange(
        AudioPresetTuning.MIN_LOW_CUT_HZ,
        (safeTuning.midLowHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_LOW_CUT_HZ),
    )
    val midLowRange = orderedRange(
        (safeTuning.lowCutHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_LOW_HZ),
        (safeTuning.midHighHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_LOW_HZ),
    )
    val midHighRange = orderedRange(
        (safeTuning.midLowHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_HIGH_HZ),
        (safeTuning.highCutHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_HIGH_HZ),
    )
    val highCutRange = orderedRange(
        (safeTuning.midHighHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_HIGH_CUT_HZ),
        AudioPresetTuning.MAX_HIGH_CUT_HZ,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        FrequencyTuningSlider(
            label = stringResource(R.string.preset_tuning_low_cut),
            value = safeTuning.lowCutHz,
            valueRange = lowCutRange,
            step = 10f,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(lowCutHz = value)) },
            onStep = { delta ->
                onTuningChange(
                    safeTuning.copy(
                        lowCutHz = adjustFrequency(safeTuning.lowCutHz, delta, lowCutRange),
                    ),
                )
            },
        )
        FrequencyTuningSlider(
            label = stringResource(R.string.preset_tuning_mid_low),
            value = safeTuning.midLowHz,
            valueRange = midLowRange,
            step = 10f,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(midLowHz = value)) },
            onStep = { delta ->
                onTuningChange(
                    safeTuning.copy(
                        midLowHz = adjustFrequency(safeTuning.midLowHz, delta, midLowRange),
                    ),
                )
            },
        )
        FrequencyTuningSlider(
            label = stringResource(R.string.preset_tuning_mid_high),
            value = safeTuning.midHighHz,
            valueRange = midHighRange,
            step = 50f,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(midHighHz = value)) },
            onStep = { delta ->
                onTuningChange(
                    safeTuning.copy(
                        midHighHz = adjustFrequency(safeTuning.midHighHz, delta, midHighRange),
                    ),
                )
            },
        )
        FrequencyTuningSlider(
            label = stringResource(R.string.preset_tuning_high_cut),
            value = safeTuning.highCutHz,
            valueRange = highCutRange,
            step = 100f,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(highCutHz = value)) },
            onStep = { delta ->
                onTuningChange(
                    safeTuning.copy(
                        highCutHz = adjustFrequency(safeTuning.highCutHz, delta, highCutRange),
                    ),
                )
            },
        )

        TuningSlider(
            label = stringResource(R.string.preset_tuning_low_gain),
            value = safeTuning.lowGainDb,
            valueRange = AudioPresetTuning.MIN_GAIN_DB..AudioPresetTuning.MAX_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(lowGainDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_mid_gain),
            value = safeTuning.midGainDb,
            valueRange = AudioPresetTuning.MIN_GAIN_DB..AudioPresetTuning.MAX_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(midGainDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_high_gain),
            value = safeTuning.highGainDb,
            valueRange = AudioPresetTuning.MIN_GAIN_DB..AudioPresetTuning.MAX_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(highGainDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_mbc_ratio),
            value = safeTuning.mbcRatio,
            valueRange = AudioPresetTuning.MIN_MBC_RATIO..AudioPresetTuning.MAX_MBC_RATIO,
            step = 0.5f,
            valueFormatter = { value -> String.format(Locale.US, "%.1f:1", value) },
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(mbcRatio = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_mbc_threshold),
            value = safeTuning.mbcThresholdDb,
            valueRange = AudioPresetTuning.MIN_THRESHOLD_DB..AudioPresetTuning.MAX_THRESHOLD_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(mbcThresholdDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_mbc_post),
            value = safeTuning.mbcPostGainDb,
            valueRange = AudioPresetTuning.MIN_POST_GAIN_DB..AudioPresetTuning.MAX_POST_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(mbcPostGainDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_makeup),
            value = safeTuning.makeupGainDb,
            valueRange = AudioPresetTuning.MIN_MAKEUP_GAIN_DB..AudioPresetTuning.MAX_MAKEUP_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(makeupGainDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_input_gain),
            value = safeTuning.inputGainDb,
            valueRange = AudioPresetTuning.MIN_INPUT_GAIN_DB..AudioPresetTuning.MAX_INPUT_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(inputGainDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_distortion_relief),
            value = safeTuning.distortionRelief,
            valueRange = 0f..1f,
            step = 0.05f,
            valueFormatter = { value -> String.format(Locale.US, "%.0f%%", value * 100f) },
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(distortionRelief = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_fade_depth),
            value = safeTuning.fadeDepthDb,
            valueRange = 0f..AudioPresetTuning.MAX_FADE_DEPTH_DB,
            step = 0.5f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(fadeDepthDb = value)) },
        )
        TuningSlider(
            label = stringResource(R.string.preset_tuning_fade_period),
            value = safeTuning.fadePeriodMs.toFloat(),
            valueRange = 0f..AudioPresetTuning.MAX_FADE_PERIOD_MS.toFloat(),
            step = 100f,
            valueFormatter = { value -> "${value.roundToInt()} ms" },
            enabled = enabled,
            onValueChange = { value ->
                onTuningChange(safeTuning.copy(fadePeriodMs = value.roundToInt().toLong()))
            },
        )

        OutlinedButton(
            onClick = onReset,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(top = 8.dp),
        ) {
            Text(text = stringResource(R.string.preset_tuning_reset))
        }
    }
}

@Composable
private fun FrequencyTuningSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onStep: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember(value) {
        mutableStateOf(value.coerceIn(valueRange.start, valueRange.endInclusive))
    }
    val safeSliderValue = sliderValue.coerceIn(valueRange.start, valueRange.endInclusive)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { onStep(-step) },
                enabled = enabled && safeSliderValue > valueRange.start,
                modifier = Modifier.size(40.dp),
            ) {
                Text(text = "−")
            }
            Text(
                text = formatTuningHz(safeSliderValue),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            OutlinedButton(
                onClick = { onStep(step) },
                enabled = enabled && safeSliderValue < valueRange.endInclusive,
                modifier = Modifier.size(40.dp),
            ) {
                Text(text = "＋")
            }
        }
        Slider(
            value = safeSliderValue,
            onValueChange = { next ->
                sliderValue = snapToStep(next, valueRange, step)
            },
            onValueChangeFinished = {
                if (safeSliderValue != value) onValueChange(safeSliderValue)
            },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormatter: (Float) -> String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    step: Float = 0f,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember(value) {
        mutableStateOf(value.coerceIn(valueRange.start, valueRange.endInclusive))
    }
    val safeSliderValue = sliderValue.coerceIn(valueRange.start, valueRange.endInclusive)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueFormatter(safeSliderValue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = safeSliderValue,
            onValueChange = { next ->
                sliderValue = if (step > 0f) {
                    snapToStep(next, valueRange, step)
                } else {
                    next.coerceIn(valueRange.start, valueRange.endInclusive)
                }
            },
            onValueChangeFinished = {
                if (safeSliderValue != value) onValueChange(safeSliderValue)
            },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun orderedRange(
    start: Float,
    end: Float,
): ClosedFloatingPointRange<Float> {
    val safeStart = start.coerceAtLeast(0f)
    return safeStart..end.coerceAtLeast(safeStart)
}

private fun snapToStep(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
): Float {
    val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
    if (step <= 0f) return clamped
    val snapped = valueRange.start +
        (((clamped - valueRange.start) / step).roundToInt() * step)
    return snapped.coerceIn(valueRange.start, valueRange.endInclusive)
}

private fun adjustFrequency(
    value: Float,
    delta: Float,
    valueRange: ClosedFloatingPointRange<Float>,
): Float = (value + delta).coerceIn(valueRange.start, valueRange.endInclusive)

private fun formatTuningHz(value: Float): String = "${value.roundToInt()} Hz"

private fun formatTuningDb(value: Float): String =
    String.format(Locale.US, "%.1f dB", value)

@Composable
private fun SpectrumMeter(
    label: String,
    snapshot: SpectrumSnapshot,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 10.dp),
        )
        SpectrumChart(
            levelsDb = snapshot.levelsDb,
            accent = accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.spectrum_rms, formatDb(snapshot.rmsDb)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.spectrum_peak, formatDb(snapshot.peakDb)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!snapshot.available) {
            Text(
                text = stringResource(R.string.spectrum_waiting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SpectrumChart(
    levelsDb: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
        ) {
            val chartBottom = size.height - 2.dp.toPx()
            val chartTop = 4.dp.toPx()
            val chartHeight = chartBottom - chartTop
            listOf(-60f, -30f, 0f).forEach { db ->
                val fraction = (db - SpectrumMath.FLOOR_DB) / -SpectrumMath.FLOOR_DB
                val y = chartBottom - chartHeight * fraction
                drawLine(
                    color = grid,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            val safeLevels = levelsDb.ifEmpty {
                SpectrumMath.bandCentersHz.map { SpectrumMath.FLOOR_DB }
            }
            val barWidth = size.width / safeLevels.size
            safeLevels.forEachIndexed { index, db ->
                val fraction = ((db - SpectrumMath.FLOOR_DB) / -SpectrumMath.FLOOR_DB)
                    .coerceIn(0f, 1f)
                val height = chartHeight * fraction
                drawRect(
                    color = accent,
                    topLeft = Offset(
                        x = index * barWidth + barWidth * 0.16f,
                        y = chartBottom - height,
                    ),
                    size = Size(width = barWidth * 0.68f, height = height),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SpectrumMath.bandCentersHz.forEach { centerHz ->
                Text(
                    text = frequencyLabel(centerHz),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun frequencyLabel(centerHz: Int): String {
    return if (centerHz >= 1_000) {
        "${centerHz / 1_000}k"
    } else {
        centerHz.toString()
    }
}

private fun formatDb(value: Float): String {
    if (value <= SpectrumMath.FLOOR_DB + 0.5f) return "−∞ dB"
    return String.format(Locale.US, "%.1f dB", value)
}

@Composable
private fun spectrumStatusText(status: SpectrumAnalyzerStatus): String {
    val resId = when (status) {
        SpectrumAnalyzerStatus.Idle -> R.string.spectrum_status_idle
        SpectrumAnalyzerStatus.Starting -> R.string.spectrum_status_starting
        SpectrumAnalyzerStatus.Active -> R.string.spectrum_status_active
        SpectrumAnalyzerStatus.Partial -> R.string.spectrum_status_partial
        SpectrumAnalyzerStatus.Stopped -> R.string.spectrum_status_stopped
        SpectrumAnalyzerStatus.Error -> R.string.spectrum_status_error
    }
    return stringResource(resId)
}

@Composable
private fun RetroHeader(
    status: RazioStatus,
    powerOn: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.poc_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        color = if (powerOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
            Text(
                text = statusText(status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RetroPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        content = {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content,
            )
        },
    )
}

@Composable
private fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

@Composable
private fun NoiseToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun presetLabel(preset: AudioPreset): String {
    val resId = when (preset) {
        AudioPreset.NARROW_AM -> R.string.preset_narrow_am
        AudioPreset.VINTAGE_SPEAKER -> R.string.preset_vintage_speaker
        AudioPreset.WEAK_SIGNAL -> R.string.preset_weak_signal
        AudioPreset.SATURATION -> R.string.preset_saturation
        AudioPreset.FADING -> R.string.preset_fading
    }
    return stringResource(resId)
}

@Composable
private fun presetDescription(preset: AudioPreset): String {
    val resId = when (preset) {
        AudioPreset.NARROW_AM -> R.string.preset_narrow_am_description
        AudioPreset.VINTAGE_SPEAKER -> R.string.preset_vintage_speaker_description
        AudioPreset.WEAK_SIGNAL -> R.string.preset_weak_signal_description
        AudioPreset.SATURATION -> R.string.preset_saturation_description
        AudioPreset.FADING -> R.string.preset_fading_description
    }
    return stringResource(resId)
}

@Composable
private fun statusText(status: RazioStatus): String {
    val resId = when (status) {
        RazioStatus.Idle -> R.string.status_idle
        RazioStatus.Initializing -> R.string.status_initializing
        RazioStatus.Active -> R.string.status_active
        RazioStatus.Disabled -> R.string.status_disabled
        RazioStatus.Unsupported -> R.string.status_unsupported
        RazioStatus.Error -> R.string.status_error
    }
    return stringResource(resId)
}

@Composable
private fun noiseStatusText(status: NoiseOverlayStatus): String {
    val resId = when (status) {
        NoiseOverlayStatus.Idle -> R.string.noise_status_idle
        NoiseOverlayStatus.Starting -> R.string.noise_status_starting
        NoiseOverlayStatus.Active -> R.string.noise_status_active
        NoiseOverlayStatus.Disabled -> R.string.noise_status_disabled
        NoiseOverlayStatus.Error -> R.string.noise_status_error
    }
    return stringResource(resId)
}

@Composable
private fun reportText(report: AudioEngineReport): String {
    return when (report) {
        AudioEngineReport.Idle -> stringResource(R.string.engine_idle)
        is AudioEngineReport.NotUsed -> stringResource(R.string.engine_not_used, report.reason)
        is AudioEngineReport.Ready -> {
            val enabled = if (report.enabled) {
                stringResource(R.string.engine_enabled)
            } else {
                stringResource(R.string.engine_disabled)
            }
            "$enabled / ${report.detail}"
        }
        is AudioEngineReport.Unsupported -> stringResource(R.string.engine_unsupported, report.reason)
        is AudioEngineReport.Failed -> stringResource(R.string.engine_failed, report.message)
    }
}

@Preview(showBackground = true)
@Composable
private fun RazioHomeScreenPreview() {
    RazioTheme {
        RazioHomeScreen(
            state = AudioEffectUiState(
                powerOn = true,
                status = RazioStatus.Active,
                equalizer = AudioEngineReport.NotUsed(reason = "backend=dynamics_only"),
                dynamics = AudioEngineReport.Ready(enabled = true, detail = "session=0 channels=2"),
            ),
            onPowerChange = {},
            onPresetChange = {},
            noiseState = NoiseOverlayUiState(
                powerOn = true,
                hissEnabled = true,
                status = NoiseOverlayStatus.Active,
                detail = "sampleRate=48000Hz buffer=19200B usage=media focus=none",
            ),
            onHissChange = {},
            onCrackleChange = {},
        )
    }
}
