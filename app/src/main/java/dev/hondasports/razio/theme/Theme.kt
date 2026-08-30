package dev.hondasports.razio.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = RadioAmberLight,
        onPrimary = RadioInk,
        primaryContainer = RadioRust,
        onPrimaryContainer = RadioPaper,
        secondary = RadioOlive,
        onSecondary = RadioPaper,
        secondaryContainer = RadioDarkPanelDeep,
        onSecondaryContainer = RadioPaper,
        tertiary = RadioAmber,
        onTertiary = RadioInk,
        background = RadioDark,
        onBackground = RadioPaper,
        surface = RadioDarkPanel,
        onSurface = RadioPaper,
        surfaceVariant = RadioDarkPanelDeep,
        onSurfaceVariant = RadioPaperMuted,
        outline = RadioPaperMuted,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = RadioRust,
        onPrimary = Color.White,
        primaryContainer = RadioAmberLight,
        onPrimaryContainer = RadioInk,
        secondary = RadioOlive,
        onSecondary = Color.White,
        secondaryContainer = RadioPanelDeep,
        onSecondaryContainer = RadioInk,
        tertiary = RadioAmber,
        onTertiary = Color.White,
        background = RadioPaper,
        onBackground = RadioInk,
        surface = RadioPanel,
        onSurface = RadioInk,
        surfaceVariant = RadioPanelDeep,
        onSurfaceVariant = RadioRust,
        outline = RadioRust,
    )

@Composable
fun RazioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
