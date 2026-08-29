package dev.hondasports.razio.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
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
import dev.hondasports.razio.audio.RazioStatus
import dev.hondasports.razio.theme.RazioTheme

@Composable
fun RazioHomeRoute(
    controller: GlobalAudioEffectController,
    onPowerChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(R.string.poc_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.power_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(
                checked = state.powerOn,
                onCheckedChange = onPowerChange,
                enabled = !state.initializing,
            )
        }
        Text(
            text = stringResource(R.string.status_label, statusText(state.status)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.equalizer_label, reportText(state.equalizer)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.dynamics_label, reportText(state.dynamics)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.poc_keep_alive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
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
private fun reportText(report: AudioEngineReport): String {
    return when (report) {
        AudioEngineReport.Idle -> stringResource(R.string.engine_idle)
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
                equalizer = AudioEngineReport.Ready(enabled = true, detail = "session=0 bands=5"),
                dynamics = AudioEngineReport.Ready(enabled = true, detail = "session=0 channels=2"),
            ),
            onPowerChange = {},
        )
    }
}
