package com.alara.hermes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

enum class ThemeMode { AMOLED, DARK, LIGHT, SYSTEM }

private val AmoledScheme = darkColorScheme(
    primary = HermesColors.Accent,
    onPrimary = HermesColors.OnAccent,
    primaryContainer = HermesColors.BubbleOutgoing,
    onPrimaryContainer = HermesColors.TextPrimary,
    secondary = HermesColors.TextSecondary,
    onSecondary = HermesColors.Black,
    background = HermesColors.Black,
    onBackground = HermesColors.TextPrimary,
    surface = HermesColors.Black,
    onSurface = HermesColors.TextPrimary,
    surfaceVariant = HermesColors.Surface2,
    onSurfaceVariant = HermesColors.TextSecondary,
    surfaceContainerLowest = HermesColors.Black,
    surfaceContainerLow = HermesColors.Surface1,
    surfaceContainer = HermesColors.Surface2,
    surfaceContainerHigh = HermesColors.Surface3,
    surfaceContainerHighest = HermesColors.Surface3,
    outline = HermesColors.Border,
    outlineVariant = HermesColors.BorderSubtle,
    error = HermesColors.Danger,
    onError = HermesColors.Black,
)

private val DarkScheme = AmoledScheme.copy(
    background = HermesColors.Surface1,
    surface = HermesColors.Surface1,
    surfaceContainerLowest = HermesColors.Surface1,
)

private val LightScheme = lightColorScheme(
    primary = HermesColors.LightAccent,
    onPrimary = HermesColors.LightSurface1,
    background = HermesColors.LightBackground,
    onBackground = HermesColors.LightTextPrimary,
    surface = HermesColors.LightBackground,
    onSurface = HermesColors.LightTextPrimary,
    surfaceVariant = HermesColors.LightSurface2,
    onSurfaceVariant = HermesColors.LightTextSecondary,
    surfaceContainerLowest = HermesColors.LightSurface1,
    surfaceContainerLow = HermesColors.LightSurface1,
    surfaceContainer = HermesColors.LightSurface2,
    surfaceContainerHigh = HermesColors.LightSurface2,
    surfaceContainerHighest = HermesColors.LightSurface2,
    outline = HermesColors.LightBorder,
    outlineVariant = HermesColors.LightBorder,
)

@Composable
fun HermesTheme(
    mode: ThemeMode = ThemeMode.AMOLED,
    content: @Composable () -> Unit,
) {
    val scheme = when (mode) {
        ThemeMode.AMOLED -> AmoledScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) AmoledScheme else LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = HermesTypography,
        content = content,
    )
}
