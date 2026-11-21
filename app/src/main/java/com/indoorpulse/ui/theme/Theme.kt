package com.indoorpulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class Appearance(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    companion object {
        fun fromPreference(value: String?): Appearance = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF78E4C3),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF104E43),
    onPrimaryContainer = Color(0xFFAEF5DE),
    secondary = Color(0xFF9DD5E6),
    onSecondary = Color(0xFF113742),
    secondaryContainer = Color(0xFF1B414D),
    onSecondaryContainer = Color(0xFFC7EAF4),
    tertiary = Color(0xFFFFCD82),
    onTertiary = Color(0xFF412C0C),
    tertiaryContainer = Color(0xFF483719),
    onTertiaryContainer = Color(0xFFFFE4B9),
    error = Color(0xFFFFB5AA),
    onError = Color(0xFF560D09),
    errorContainer = Color(0xFF682720),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Color(0xFF0D1B20),
    onBackground = Color(0xFFEBF4F2),
    surface = Color(0xFF0D1B20),
    onSurface = Color(0xFFEBF4F2),
    surfaceVariant = Color(0xFF273D43),
    onSurfaceVariant = Color(0xFFADC3C6),
    surfaceContainerLowest = Color(0xFF09161A),
    surfaceContainerLow = Color(0xFF122228),
    surfaceContainer = Color(0xFF16282E),
    surfaceContainerHigh = Color(0xFF1B3037),
    surfaceContainerHighest = Color(0xFF233B42),
    outline = Color(0xFF819BA0),
    outlineVariant = Color(0xFF355058)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006856),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF5E6),
    onPrimaryContainer = Color(0xFF00382E),
    secondary = Color(0xFF246273),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EFF4),
    onSecondaryContainer = Color(0xFF123C48),
    tertiary = Color(0xFF76511B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7BF),
    onTertiaryContainer = Color(0xFF402C0A),
    error = Color(0xFFA32822),
    onError = Color.White,
    errorContainer = Color(0xFFFFDFD8),
    onErrorContainer = Color(0xFF6E150F),
    background = Color(0xFFF6F9F6),
    onBackground = Color(0xFF172C2E),
    surface = Color(0xFFF6F9F6),
    onSurface = Color(0xFF172C2E),
    surfaceVariant = Color(0xFFDCF0EB),
    onSurfaceVariant = Color(0xFF43605F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE8F0EC),
    surfaceContainerHigh = Color(0xFFE2ECE7),
    surfaceContainerHighest = Color(0xFFD9E7E2),
    outline = Color(0xFF637C79),
    outlineVariant = Color(0xFFC3D5CF)
)

@Composable
fun IndoorPulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
