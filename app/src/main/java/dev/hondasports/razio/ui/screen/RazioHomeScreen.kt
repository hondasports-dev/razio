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
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.hondasports.razio.R
import dev.hondasports.razio.audio.AudioEngineReport
import dev.hondasports.razio.audio.AudioEffectUiState
import dev.hondasports.razio.audio.GlobalAudioEffectController
import dev.hondasports.razio.audio.NoiseOverlayController
import dev.hondasports.razio.audio.NoiseLevelRange
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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(24.dp)
private val ControlShape = RoundedCornerShape(16.dp)

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
    onHissLevelChange: (Float) -> Unit = {},
    onCrackleLevelChange: (Float) -> Unit = {},
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
    // Keep the capture button disabled while a permission/projection dialog is open.
    // The analyzer cannot report Starting until the projection FGS callback returns,
    // so the UI needs this short-lived guard to prevent duplicate consent launches.
    var captureRequestPending by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(captureRequestPending, spectrumState.status) {
        if (!captureRequestPending) return@LaunchedEffect
        val spectrumStarted = spectrumState.status != SpectrumAnalyzerStatus.Idle &&
            spectrumState.status != SpectrumAnalyzerStatus.Stopped
        if (spectrumStarted) captureRequestPending = false
    }
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
            captureRequestPending = false
            onSpectrumConsentDenied("MediaProjectionの同意がキャンセルされました")
        }
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            captureRequestPending = false
            onSpectrumConsentDenied("マイク権限がないため入力解析を開始できません")
        } else if (projectionManager != null) {
            projectionLauncher.launch(createProjectionIntent(projectionManager))
        } else {
            captureRequestPending = false
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
        onHissLevelChange = onHissLevelChange,
        onCrackleLevelChange = onCrackleLevelChange,
        spectrumState = spectrumState,
        captureRequestPending = captureRequestPending,
        onSpectrumStart = {
            if (captureRequestPending) return@RazioHomeScreen
            when {
                needsRecordPermission(context) -> {
                    captureRequestPending = true
                    recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                projectionManager != null -> {
                    captureRequestPending = true
                    projectionLauncher.launch(createProjectionIntent(projectionManager))
                }
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
    onHissLevelChange: (Float) -> Unit = {},
    onCrackleLevelChange: (Float) -> Unit = {},
    spectrumState: SpectrumAnalyzerUiState = SpectrumAnalyzerUiState(),
    captureRequestPending: Boolean = false,
    onSpectrumStart: () -> Unit = {},
    onSpectrumStop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colorScheme.primary.copy(alpha = if (state.powerOn) 0.32f else 0.12f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var tuningExpanded by rememberSaveable(state.preset.id) { mutableStateOf(false) }

            HomeHeader(
                powerOn = state.powerOn,
                status = state.status,
                enabled = !state.initializing,
                onPowerChange = onPowerChange,
            )
            PresetStage(
                selectedPreset = state.preset,
                tuning = state.tuning,
                powerOn = state.powerOn,
                enabled = !state.initializing,
                onPresetChange = onPresetChange,
                onResetTuning = { onPresetTuningChange(state.preset.defaultTuning()) },
                modifier = Modifier.padding(top = 28.dp),
            )
            CompactSignalMeter(
                snapshot = spectrumState.output,
                running = spectrumState.running,
                modifier = Modifier.padding(top = 8.dp),
            )
            NoiseFaceControls(
                noiseState = noiseState,
                enabled = state.powerOn && !state.initializing,
                onHissChange = onHissChange,
                onCrackleChange = onCrackleChange,
                onHissLevelChange = onHissLevelChange,
                onCrackleLevelChange = onCrackleLevelChange,
                modifier = Modifier.padding(top = 10.dp),
            )
            DetailsToggle(
                expanded = tuningExpanded,
                enabled = !state.initializing,
                onToggle = { tuningExpanded = !tuningExpanded },
                modifier = Modifier.padding(top = 8.dp),
            )
            if (tuningExpanded) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    AppPanel {
                        Text(
                            text = presetDescription(state.preset),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.preset_tuning_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        PresetTuningEditor(
                            tuning = state.tuning,
                            enabled = !state.initializing,
                            onTuningChange = onPresetTuningChange,
                            showVisuals = false,
                            showDial = true,
                            showFrequencyControls = true,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    DevelopmentPanels(
                        state = state,
                        noiseState = noiseState,
                        spectrumState = spectrumState,
                        captureRequestPending = captureRequestPending,
                        onSpectrumStart = onSpectrumStart,
                        onSpectrumStop = onSpectrumStop,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                }
            } else {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
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
private fun DevelopmentPanels(
    state: AudioEffectUiState,
    noiseState: NoiseOverlayUiState,
    spectrumState: SpectrumAnalyzerUiState,
    captureRequestPending: Boolean,
    onSpectrumStart: () -> Unit,
    onSpectrumStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppPanel {
            SectionHeading(text = stringResource(R.string.noise_overlay_label))
            Text(
                text = stringResource(R.string.noise_level_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        AppPanel {
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
                        shape = ControlShape,
                    ) {
                        Text(text = stringResource(R.string.spectrum_stop))
                    }
                } else {
                    Button(
                        onClick = onSpectrumStart,
                        enabled = !captureRequestPending,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = ControlShape,
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
            if (captureRequestPending && !spectrumState.running) {
                Text(
                    text = stringResource(R.string.capture_request_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
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

        AppPanel {
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
    }
}

@Composable
private fun PresetStage(
    selectedPreset: AudioPreset,
    tuning: AudioPresetTuning,
    powerOn: Boolean,
    enabled: Boolean,
    onPresetChange: (AudioPreset) -> Unit,
    onResetTuning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = AudioPreset.entries
    val pagerState = rememberPagerState(initialPage = selectedPreset.ordinal) { presets.size }
    val latestPreset by rememberUpdatedState(selectedPreset)
    val latestOnPresetChange by rememberUpdatedState(onPresetChange)

    LaunchedEffect(selectedPreset) {
        if (pagerState.settledPage != selectedPreset.ordinal) {
            pagerState.animateScrollToPage(selectedPreset.ordinal)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val preset = presets[page]
            if (preset != latestPreset) {
                latestOnPresetChange(preset)
            }
        }
    }

    val previousLabel = stringResource(R.string.preset_previous)
    val nextLabel = stringResource(R.string.preset_next)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = enabled,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) { page ->
                val preset = presets[page]
                val pageTuning = if (preset == selectedPreset) {
                    tuning
                } else {
                    preset.defaultTuning()
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = presetLabel(preset),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 52.dp),
                    )
                    Text(
                        text = presetBlurb(preset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    val pageCustomized = preset == selectedPreset &&
                        tuning.sanitized() != selectedPreset.defaultTuning().sanitized()
                    ResetTuningButton(
                        presetName = presetLabel(preset),
                        active = pageCustomized,
                        enabled = enabled,
                        onClick = onResetTuning,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    PresetFrequencyCurve(
                        tuning = pageTuning,
                        powerOn = powerOn,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            enabled = enabled,
                            onClickLabel = previousLabel,
                        ) {
                            onPresetChange(presets[(selectedPreset.ordinal - 1).mod(presets.size)])
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            enabled = enabled,
                            onClickLabel = nextLabel,
                        ) {
                            onPresetChange(presets[(selectedPreset.ordinal + 1).mod(presets.size)])
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEachIndexed { index, preset ->
                val selected = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                        .clickable(enabled = enabled) { onPresetChange(preset) },
                )
            }
        }
    }
}

@Composable
private fun ResetTuningButton(
    presetName: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val canReset = active && enabled
    val border = if (canReset) {
        colorScheme.primary
    } else {
        colorScheme.outline.copy(alpha = 0.38f)
    }
    val fill = if (canReset) {
        colorScheme.primary.copy(alpha = 0.18f)
    } else {
        Color.Transparent
    }
    val content = if (canReset) {
        colorScheme.primary
    } else {
        colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    }
    val pill = RoundedCornerShape(100.dp)
    val label = stringResource(R.string.preset_tuning_reset_named, presetName)
    Row(
        modifier = modifier
            .clip(pill)
            .border(width = 1.dp, color = border, shape = pill)
            .background(color = fill, shape = pill)
            .clickable(
                enabled = canReset,
                onClickLabel = label,
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(content),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (canReset) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailsToggle(
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ControlShape,
    ) {
        Text(
            text = stringResource(
                if (expanded) {
                    R.string.preset_tuning_details_close
                } else {
                    R.string.preset_tuning_details_open
                },
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun PresetTuningEditor(
    tuning: AudioPresetTuning,
    enabled: Boolean,
    onTuningChange: (AudioPresetTuning) -> Unit,
    showVisuals: Boolean = true,
    showDial: Boolean = true,
    showFrequencyControls: Boolean = true,
    showToneControls: Boolean = true,
    showDynamicsControls: Boolean = true,
    showCharacterControls: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val safeTuning = tuning.sanitized()
    val lowCutRange = orderedRange(
        AudioPresetTuning.MIN_LOW_CUT_HZ,
        (safeTuning.lowTransitionHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_LOW_CUT_HZ),
    )
    val lowTransitionRange = orderedRange(
        (safeTuning.lowCutHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_LOW_TRANSITION_HZ),
        (safeTuning.midLowHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_LOW_TRANSITION_HZ),
    )
    val midLowRange = orderedRange(
        (safeTuning.lowTransitionHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_LOW_HZ),
        (safeTuning.midHighHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_LOW_HZ),
    )
    val midHighRange = orderedRange(
        (safeTuning.midLowHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_HIGH_HZ),
        (safeTuning.highTransitionHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_MID_HIGH_HZ),
    )
    val highTransitionRange = orderedRange(
        (safeTuning.midHighHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_HIGH_TRANSITION_HZ),
        (safeTuning.highCutHz - AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_HIGH_TRANSITION_HZ),
    )
    val highCutRange = orderedRange(
        (safeTuning.highTransitionHz + AudioPresetTuning.FREQUENCY_GUARD_HZ)
            .coerceIn(AudioPresetTuning.MIN_LOW_CUT_HZ, AudioPresetTuning.MAX_HIGH_CUT_HZ),
        AudioPresetTuning.MAX_HIGH_CUT_HZ,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showDial) {
            PresetTuningDial(
                tuning = safeTuning,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (showVisuals) {
            PresetFrequencyCurve(
                tuning = safeTuning,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (showFrequencyControls) {
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
            label = stringResource(R.string.preset_tuning_low_transition),
            value = safeTuning.lowTransitionHz,
            valueRange = lowTransitionRange,
            step = 10f,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(lowTransitionHz = value)) },
            onStep = { delta ->
                onTuningChange(
                    safeTuning.copy(
                        lowTransitionHz = adjustFrequency(safeTuning.lowTransitionHz, delta, lowTransitionRange),
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
            label = stringResource(R.string.preset_tuning_high_transition),
            value = safeTuning.highTransitionHz,
            valueRange = highTransitionRange,
            step = 50f,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(highTransitionHz = value)) },
            onStep = { delta ->
                onTuningChange(
                    safeTuning.copy(
                        highTransitionHz = adjustFrequency(safeTuning.highTransitionHz, delta, highTransitionRange),
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
        }

        if (showToneControls) {
        Text(
            text = stringResource(R.string.gain_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
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
            label = stringResource(R.string.preset_tuning_low_transition_gain),
            value = safeTuning.lowTransitionGainDb,
            valueRange = AudioPresetTuning.MIN_GAIN_DB..AudioPresetTuning.MAX_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(lowTransitionGainDb = value)) },
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
            label = stringResource(R.string.preset_tuning_high_transition_gain),
            value = safeTuning.highTransitionGainDb,
            valueRange = AudioPresetTuning.MIN_GAIN_DB..AudioPresetTuning.MAX_GAIN_DB,
            step = 1f,
            valueFormatter = ::formatTuningDb,
            enabled = enabled,
            onValueChange = { value -> onTuningChange(safeTuning.copy(highTransitionGainDb = value)) },
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
        }

        if (showDynamicsControls) {
        Text(
            text = stringResource(R.string.dynamics_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
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
        }

        if (showCharacterControls) {
        Text(
            text = stringResource(R.string.character_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
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
        }
    }
}

/** Shows the selected preset's band shape as a compact, non-interactive radio dial. */
@Composable
private fun PresetTuningDial(
    tuning: AudioPresetTuning,
    modifier: Modifier = Modifier,
) {
    val safeTuning = tuning.sanitized()
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val cutColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    val slopeColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
    val midColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    val boundaryColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurface
    val minHz = 20f
    val maxHz = 20_000f
    val tickFrequenciesHz = listOf(
        20f,
        30f,
        50f,
        70f,
        100f,
        150f,
        200f,
        300f,
        500f,
        700f,
        1_000f,
        1_500f,
        2_000f,
        3_000f,
        5_000f,
        7_000f,
        10_000f,
        15_000f,
        20_000f,
    )
    val labeledFrequenciesHz = listOf(20f, 100f, 300f, 1_000f, 3_000f, 10_000f, 20_000f)
    val majorFrequenciesHz = setOf(20f, 100f, 300f, 1_000f, 3_000f, 10_000f, 20_000f)
    val boundaryFrequenciesHz = listOf(
        safeTuning.lowCutHz,
        safeTuning.lowTransitionHz,
        safeTuning.midLowHz,
        safeTuning.midHighHz,
        safeTuning.highTransitionHz,
        safeTuning.highCutHz,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.preset_tuning_dial_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.preset_tuning_dial_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(112.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 30.dp),
            ) {
                val left = 2.dp.toPx()
                val right = size.width - 2.dp.toPx()
                val trackY = size.height * 0.52f
                val trackHeight = 10.dp.toPx()
                val minLogHz = ln(minHz.toDouble())
                val maxLogHz = ln(maxHz.toDouble())

                fun xForHz(hz: Float): Float {
                    val fraction = ((ln(hz.coerceIn(minHz, maxHz).toDouble()) - minLogHz) /
                        (maxLogHz - minLogHz)).toFloat()
                    return left + (right - left) * fraction
                }

                fun drawBand(startHz: Float, endHz: Float, color: Color) {
                    val startX = xForHz(startHz)
                    val endX = xForHz(endHz)
                    drawRect(
                        color = color,
                        topLeft = Offset(startX, trackY - trackHeight / 2f),
                        size = Size((endX - startX).coerceAtLeast(0f), trackHeight),
                    )
                }

                drawRect(
                    color = trackColor,
                    topLeft = Offset(left, trackY - trackHeight / 2f),
                    size = Size(right - left, trackHeight),
                )
                drawBand(minHz, safeTuning.lowCutHz, cutColor)
                drawBand(safeTuning.lowCutHz, safeTuning.midLowHz, slopeColor)
                drawBand(safeTuning.midLowHz, safeTuning.midHighHz, midColor)
                drawBand(safeTuning.midHighHz, safeTuning.highCutHz, slopeColor)
                drawBand(safeTuning.highCutHz, maxHz, cutColor)

                tickFrequenciesHz.forEach { frequencyHz ->
                    val x = xForHz(frequencyHz)
                    val isMajor = frequencyHz in majorFrequenciesHz
                    val tickHeight = if (isMajor) 18.dp.toPx() else 10.dp.toPx()
                    drawLine(
                        color = tickColor.copy(
                            alpha = if (isMajor) 0.75f else 0.35f,
                        ),
                        start = Offset(x, trackY - trackHeight / 2f - tickHeight),
                        end = Offset(x, trackY - trackHeight / 2f - 2.dp.toPx()),
                        strokeWidth = if (isMajor) 1.3.dp.toPx() else 0.8.dp.toPx(),
                    )
                }

                boundaryFrequenciesHz.forEach { frequencyHz ->
                    val x = xForHz(frequencyHz)
                    drawLine(
                        color = boundaryColor.copy(alpha = 0.85f),
                        start = Offset(x, 4.dp.toPx()),
                        end = Offset(x, trackY + trackHeight / 2f + 8.dp.toPx()),
                        strokeWidth = 1.6.dp.toPx(),
                    )
                    drawCircle(
                        color = boundaryColor,
                        radius = 3.dp.toPx(),
                        center = Offset(x, trackY),
                    )
                }
            }
            FrequencyAxisLabels(
                frequenciesHz = labeledFrequenciesHz,
                minHz = minHz,
                maxHz = maxHz,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            )
        }
    }
}

/** Visualizes the editable response curve as the product hero, not a lab plot. */
@Composable
private fun PresetFrequencyCurve(
    tuning: AudioPresetTuning,
    powerOn: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val safeTuning = tuning.sanitized()
    val colorScheme = MaterialTheme.colorScheme
    val accent = if (powerOn) colorScheme.primary else colorScheme.outline
    val plotHeight = 240.dp
    val minHz = 20f
    val maxHz = 20_000f
    val minDb = -48f
    val maxDb = 6f
    val labeledFrequenciesHz = listOf(20f, 100f, 1_000f, 10_000f, 20_000f)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotHeight),
        ) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val chartWidth = right - left
            val chartRange = bottom - top
            val minLogHz = ln(minHz.toDouble())
            val maxLogHz = ln(maxHz.toDouble())

            fun xForHz(hz: Float): Float {
                val fraction = ((ln(hz.coerceIn(minHz, maxHz).toDouble()) - minLogHz) /
                    (maxLogHz - minLogHz)).toFloat()
                return left + chartWidth * fraction
            }

            fun yForDb(db: Float): Float {
                val fraction = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
                return bottom - chartRange * fraction
            }

            val zeroY = yForDb(0f)
            drawLine(
                color = accent.copy(alpha = 0.18f),
                start = Offset(left, zeroY),
                end = Offset(right, zeroY),
                strokeWidth = 1.dp.toPx(),
            )

            val curve = Path()
            val fill = Path()
            val sampleCount = 192
            repeat(sampleCount + 1) { index ->
                val fraction = index.toFloat() / sampleCount
                val frequencyHz = exp(
                    minLogHz + (maxLogHz - minLogHz) * fraction,
                ).toFloat()
                val gainDb = safeTuning.gainDbForCenterHz(frequencyHz)
                    .coerceIn(minDb, maxDb)
                val point = Offset(xForHz(frequencyHz), yForDb(gainDb))
                if (index == 0) {
                    curve.moveTo(point.x, point.y)
                    fill.moveTo(point.x, bottom)
                    fill.lineTo(point.x, point.y)
                } else {
                    curve.lineTo(point.x, point.y)
                    fill.lineTo(point.x, point.y)
                }
            }
            fill.lineTo(right, bottom)
            fill.close()
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = 0.38f), Color.Transparent),
                    startY = top,
                    endY = bottom,
                ),
            )
            drawPath(
                path = curve,
                color = accent.copy(alpha = 0.28f),
                style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                path = curve,
                color = accent,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        FrequencyAxisLabels(
            frequenciesHz = labeledFrequenciesHz,
            minHz = minHz,
            maxHz = maxHz,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun FrequencyAxisLabels(
    frequenciesHz: List<Float>,
    minHz: Float,
    maxHz: Float,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            frequenciesHz.forEach { frequencyHz ->
                Text(
                    text = formatTuningChartHz(frequencyHz),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = modifier.fillMaxWidth().height(18.dp),
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val minLogHz = ln(minHz.toDouble())
        val maxLogHz = ln(maxHz.toDouble())
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val frequencyHz = frequenciesHz[index].coerceIn(minHz, maxHz)
                val fraction = ((ln(frequencyHz.toDouble()) - minLogHz) /
                    (maxLogHz - minLogHz)).toFloat()
                val centerX = width * fraction
                val x = when (index) {
                    0 -> centerX
                    placeables.lastIndex -> centerX - placeable.width
                    else -> centerX - placeable.width / 2f
                }.toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                val y = ((height - placeable.height) / 2).coerceAtLeast(0)
                placeable.place(x, y)
            }
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
    val sliderColors = modernSliderColors()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StepButton(
                text = "−",
                enabled = enabled && safeSliderValue > valueRange.start,
                onClick = { onStep(-step) },
            )
            Text(
                text = formatTuningHz(safeSliderValue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .width(72.dp)
                    .padding(horizontal = 4.dp),
            )
            StepButton(
                text = "＋",
                enabled = enabled && safeSliderValue < valueRange.endInclusive,
                onClick = { onStep(step) },
            )
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
            colors = sliderColors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(36.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
            )
        }
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
    val sliderColors = modernSliderColors()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = valueFormatter(safeSliderValue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
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
            colors = sliderColors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun modernSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
    disabledThumbColor = MaterialTheme.colorScheme.outline,
    disabledActiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
)

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

private fun formatTuningChartHz(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded >= 1_000 && rounded % 1_000 == 0) {
        "${rounded / 1_000}k"
    } else {
        rounded.toString()
    }
}

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

/** A compact product-facing level strip backed by the existing output observation tap. */
@Composable
private fun CompactSignalMeter(
    snapshot: SpectrumSnapshot,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val warm = colorScheme.primary
    val idle = colorScheme.outline.copy(alpha = 0.28f)
    val segmentCount = 24
    val peakFraction = ((snapshot.peakDb - SpectrumMath.FLOOR_DB) / -SpectrumMath.FLOOR_DB)
        .coerceIn(0f, 1f)
    val activeSegments = (peakFraction * segmentCount).roundToInt().coerceIn(0, segmentCount)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(segmentCount) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            color = if (index < activeSegments) {
                                warm.copy(alpha = 0.45f + 0.55f * (index / segmentCount.toFloat()))
                            } else {
                                idle
                            },
                        ),
                )
            }
        }
        Text(
            text = if (running && snapshot.available) {
                stringResource(R.string.signal_meter_peak, formatDb(snapshot.peakDb))
            } else {
                stringResource(R.string.signal_meter_waiting)
            },
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
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

private fun formatNoiseLevel(value: Float): String {
    return String.format(Locale.US, "%.0f%%", value * 100f)
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
private fun HomeHeader(
    powerOn: Boolean,
    status: RazioStatus,
    enabled: Boolean,
    onPowerChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (status) {
        RazioStatus.Active -> MaterialTheme.colorScheme.primary
        RazioStatus.Error, RazioStatus.Unsupported -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (status) {
        RazioStatus.Active -> stringResource(R.string.status_live)
        RazioStatus.Error, RazioStatus.Unsupported -> statusText(status)
        else -> stringResource(R.string.status_standby)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_overline),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.terminal_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        PowerButton(
            powerOn = powerOn,
            enabled = enabled,
            onToggle = onPowerChange,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun PowerButton(
    powerOn: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (powerOn) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (powerOn) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(88.dp)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(
                    color = if (powerOn) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(container)
                .clickable(enabled = enabled) { onToggle(!powerOn) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(if (powerOn) R.string.power_on else R.string.power_off),
                style = MaterialTheme.typography.titleSmall,
                color = content,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AppPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
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
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun NoiseFaceControls(
    noiseState: NoiseOverlayUiState,
    enabled: Boolean,
    onHissChange: (Boolean) -> Unit,
    onCrackleChange: (Boolean) -> Unit,
    onHissLevelChange: (Float) -> Unit,
    onCrackleLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.noise_face_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoiseFaceSwitch(
                label = stringResource(R.string.noise_hiss_label),
                active = noiseState.hissEnabled,
                enabled = enabled,
                onClick = { onHissChange(!noiseState.hissEnabled) },
                modifier = Modifier.weight(1f),
            )
            NoiseFaceSwitch(
                label = stringResource(R.string.noise_crackle_label),
                active = noiseState.crackleEnabled,
                enabled = enabled,
                onClick = { onCrackleChange(!noiseState.crackleEnabled) },
                modifier = Modifier.weight(1f),
            )
        }
        TuningSlider(
            label = stringResource(R.string.noise_hiss_level_label),
            value = noiseState.hissLevel,
            valueRange = NoiseLevelRange.MIN..NoiseLevelRange.MAX,
            valueFormatter = ::formatNoiseLevel,
            enabled = enabled,
            step = 0.05f,
            onValueChange = onHissLevelChange,
            modifier = Modifier.padding(top = 4.dp),
        )
        TuningSlider(
            label = stringResource(R.string.noise_crackle_level_label),
            value = noiseState.crackleLevel,
            valueRange = NoiseLevelRange.MIN..NoiseLevelRange.MAX,
            valueFormatter = ::formatNoiseLevel,
            enabled = enabled,
            step = 0.05f,
            onValueChange = onCrackleLevelChange,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun NoiseFaceSwitch(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val border = if (active) {
        colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f)
    } else {
        colorScheme.outline.copy(alpha = if (enabled) 0.55f else 0.32f)
    }
    val fill = if (active) {
        colorScheme.primary.copy(alpha = if (enabled) 0.18f else 0.08f)
    } else {
        Color.Transparent
    }
    val content = when {
        active && enabled -> colorScheme.primary
        active -> colorScheme.primary.copy(alpha = 0.55f)
        enabled -> colorScheme.onSurfaceVariant
        else -> colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    }
    val pill = RoundedCornerShape(100.dp)
    Row(
        modifier = modifier
            .clip(pill)
            .border(width = 1.dp, color = border, shape = pill)
            .background(color = fill, shape = pill)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(content),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
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
        AudioPreset.SHORTWAVE -> R.string.preset_shortwave
    }
    return stringResource(resId)
}

@Composable
private fun presetBlurb(preset: AudioPreset): String {
    val resId = when (preset) {
        AudioPreset.NARROW_AM -> R.string.preset_narrow_am_blurb
        AudioPreset.VINTAGE_SPEAKER -> R.string.preset_vintage_speaker_blurb
        AudioPreset.WEAK_SIGNAL -> R.string.preset_weak_signal_blurb
        AudioPreset.SATURATION -> R.string.preset_saturation_blurb
        AudioPreset.FADING -> R.string.preset_fading_blurb
        AudioPreset.SHORTWAVE -> R.string.preset_shortwave_blurb
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
        AudioPreset.SHORTWAVE -> R.string.preset_shortwave_description
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
