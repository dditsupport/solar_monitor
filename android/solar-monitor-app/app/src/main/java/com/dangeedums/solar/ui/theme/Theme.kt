package com.dangeedums.solar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Solar Monitor brand theme: Material 3 roles built from the web dashboard's
// green (#1F6E2A) with sun amber as the tertiary accent (power, peaks). The
// app used to take its colours from the wallpaper (dynamic colour), which
// turned every screen grey-blue and lost the brand; it now looks the same on
// every phone, in light and dark.

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E6B2E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F4A9),
    onPrimaryContainer = Color(0xFF00210A),
    inversePrimary = Color(0xFF8CD88F),
    secondary = Color(0xFF506350),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E8D0),
    onSecondaryContainer = Color(0xFF0E1F10),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF6FBF2),
    onBackground = Color(0xFF181D17),
    surface = Color(0xFFF6FBF2),
    onSurface = Color(0xFF181D17),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5EC),
    surfaceContainer = Color(0xFFEAEFE6),
    surfaceContainerHigh = Color(0xFFE4EAE1),
    surfaceContainerHighest = Color(0xFFDFE4DB),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8CD88F),
    onPrimary = Color(0xFF003915),
    primaryContainer = Color(0xFF005321),
    onPrimaryContainer = Color(0xFFA8F4A9),
    inversePrimary = Color(0xFF1E6B2E),
    secondary = Color(0xFFB7CCB4),
    onSecondary = Color(0xFF233424),
    secondaryContainer = Color(0xFF394B39),
    onSecondaryContainer = Color(0xFFD3E8D0),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF101510),
    onBackground = Color(0xFFDFE4DB),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFDFE4DB),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D17),
    surfaceContainer = Color(0xFF1C211B),
    surfaceContainerHigh = Color(0xFF262B25),
    surfaceContainerHighest = Color(0xFF313630),
)

// Softer, rounder corners throughout (cards 24 dp, buttons fully rounded).
private val SolarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Default Material 3 type, with bolder titles and figures.
private val SolarTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SolarMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = SolarShapes,
        typography = SolarTypography,
        content = content,
    )
}
