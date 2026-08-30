package dev.hondasports.razio.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.hondasports.razio.audio.preset.AudioPreset
import dev.hondasports.razio.theme.RazioTheme

@Composable
fun RazioHomeRoute(
    controller: GlobalAudioEffectController,
    noiseOverlay: NoiseOverlayController,
    onPowerChange: (Boolean) -> Unit,
    onPresetChange: (AudioPreset) -> Unit,
    onHissChange: (Boolean) -> Unit = {},
    onCrackleChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val noiseState by noiseOverlay.state.collectAsState()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        onPowerChange(true)
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
        noiseState = noiseState,
        onHissChange = onHissChange,
        onCrackleChange = onCrackleChange,
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
    noiseState: NoiseOverlayUiState = NoiseOverlayUiState(),
    onHissChange: (Boolean) -> Unit = {},
    onCrackleChange: (Boolean) -> Unit = {},
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
