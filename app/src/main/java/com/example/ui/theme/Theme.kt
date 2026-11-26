package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NimeObsidianColorScheme = darkColorScheme(
    primary = NimeCopper,
    onPrimary = NimeObsidianBg,
    primaryContainer = NimeCopper.copy(alpha = 0.18f),
    onPrimaryContainer = NimeCopperSoft,
    secondary = NimeSage,
    onSecondary = NimeObsidianBg,
    secondaryContainer = NimeSage.copy(alpha = 0.18f),
    onSecondaryContainer = NimeSage,
    tertiary = NimeCobalt,
    onTertiary = NimeTextPrimary,
    tertiaryContainer = NimeCobalt.copy(alpha = 0.18f),
    onTertiaryContainer = NimeCobalt,
    error = NimeCrimson,
    onError = NimeTextPrimary,
    errorContainer = NimeCrimson.copy(alpha = 0.16f),
    onErrorContainer = NimeCrimson,
    background = NimeObsidianBg,
    onBackground = NimeTextPrimary,
    surface = NimeCharcoalSurface,
    onSurface = NimeTextPrimary,
    surfaceVariant = NimePanelSurface,
    onSurfaceVariant = NimeTextSecondary,
    outline = NimeBorderHairline,
    outlineVariant = NimeRecessedSurface
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NimeObsidianColorScheme,
        typography = Typography,
        content = content
    )
}
