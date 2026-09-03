package dev.hondasports.razio.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
    darkColorScheme(
        primary = AmberGlow,
        onPrimary = NightInk,
        primaryContainer = AmberContainer,
        onPrimaryContainer = Cream,
        secondary = CreamMuted,
        onSecondary = NightInk,
        secondaryContainer = NightHighest,
        onSecondaryContainer = Cream,
        tertiary = AmberDeep,
        onTertiary = NightInk,
        tertiaryContainer = Color(0xFF3A2410),
        onTertiaryContainer = Cream,
        background = NightInk,
        onBackground = Cream,
        surface = NightSurface,
        onSurface = Cream,
        surfaceVariant = NightRaised,
        onSurfaceVariant = CreamMuted,
        surfaceContainerLowest = NightInk,
        surfaceContainerLow = NightSurface,
        surfaceContainer = NightRaised,
        surfaceContainerHigh = NightHighest,
        surfaceContainerHighest = Color(0xFF352E27),
        outline = NightOutline,
        outlineVariant = Color(0xFF2A241F),
        error = ErrorCoral,
        onError = NightInk,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Rust,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD7B0),
        onPrimaryContainer = Ink,
        secondary = Olive,
        onSecondary = Color.White,
        secondaryContainer = PaperRaised,
        onSecondaryContainer = Ink,
        tertiary = AmberDeep,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE2C4),
        onTertiaryContainer = Ink,
        background = PaperBg,
        onBackground = Ink,
        surface = PaperSurface,
        onSurface = Ink,
        surfaceVariant = PaperRaised,
        onSurfaceVariant = InkMuted,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = PaperSurface,
        surfaceContainer = PaperRaised,
        surfaceContainerHigh = Color(0xFFE6D7C6),
        surfaceContainerHighest = Color(0xFFDDCCB8),
        outline = PaperOutline,
        outlineVariant = Color(0xFFE8DCCB),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
    )

private val RazioShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(32.dp),
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
        shapes = RazioShapes,
        content = content,
    )
}
