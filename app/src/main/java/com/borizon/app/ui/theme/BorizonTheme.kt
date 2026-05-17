package com.borizon.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * CompositionLocal for accessing Borizon's custom semantic colors
 * anywhere in the composable tree.
 */
object LocalBorizonColors {
    val current: BorizonColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current

    internal val LocalAppColors = staticCompositionLocalOf { BorizonColors }
}

/**
 * CompositionLocal for accessing domain-specific semantic color tokens.
 * Usage: LocalBorizonSemanticColors.current.chat.agentBubbleBg
 */
object LocalBorizonSemanticColors {
    val current: BorizonSemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current

    internal val LocalSemanticColors = staticCompositionLocalOf { darkSemanticColors() }
}

/**
 * Whether the Borizon theme is currently in dark mode.
 * Use this instead of `isSystemInDarkTheme()` to respect theme overrides.
 */
object LocalDarkTheme {
    val current: Boolean
        @Composable @ReadOnlyComposable get() = LocalDarkThemeFlag.current

    internal val LocalDarkThemeFlag = staticCompositionLocalOf { true }
}

private val LightColorScheme = lightColorScheme(
    primary = BorizonColors.AccentPrimary,
    onPrimary = BorizonColors.TextPrimary,
    primaryContainer = BorizonColors.AccentLight,
    onPrimaryContainer = BorizonColors.TextPrimary,
    secondary = BorizonColors.TextSecondary,
    onSecondary = BorizonColors.BackgroundPrimary,
    secondaryContainer = BorizonColors.BackgroundSecondary,
    onSecondaryContainer = BorizonColors.TextPrimary,
    tertiary = BorizonColors.Success,
    onTertiary = BorizonColors.BackgroundPrimary,
    tertiaryContainer = BorizonColors.Success.copy(alpha = 0.2f),
    error = BorizonColors.Error,
    onError = BorizonColors.BackgroundPrimary,
    errorContainer = BorizonColors.Error.copy(alpha = 0.15f),
    onErrorContainer = BorizonColors.Error,
    background = BorizonColors.BackgroundPrimary,
    onBackground = BorizonColors.TextPrimary,
    surface = SurfaceContainers.containerDefaultLight,
    onSurface = BorizonColors.TextPrimary,
    surfaceVariant = SurfaceContainers.containerHighLight,
    onSurfaceVariant = BorizonColors.TextSecondary,
    outline = BorizonColors.TextSecondary.copy(alpha = 0.3f),
    inverseSurface = BorizonColors.BackgroundDark,
    inverseOnSurface = BorizonColors.TextOnDark
)

private val DarkColorScheme = darkColorScheme(
    primary = BorizonColors.AccentPrimary,
    onPrimary = BorizonColors.BackgroundDark,
    primaryContainer = BorizonColors.AccentPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = BorizonColors.TextSecondary,
    onSecondary = BorizonColors.BackgroundDark,
    secondaryContainer = BorizonColors.BackgroundDark,
    onSecondaryContainer = BorizonColors.TextOnDark,
    tertiary = BorizonColors.Success,
    onTertiary = BorizonColors.BackgroundDark,
    error = BorizonColors.Error,
    onError = BorizonColors.BackgroundPrimary,
    background = BorizonColors.BackgroundDark,
    onBackground = BorizonColors.TextOnDark,
    surface = SurfaceContainers.containerDefaultDark,
    onSurface = BorizonColors.TextOnDark,
    surfaceVariant = SurfaceContainers.containerHighDark,
    onSurfaceVariant = BorizonColors.TextSecondary,
    outline = BorizonColors.TextSecondary.copy(alpha = 0.3f),
    inverseSurface = BorizonColors.BackgroundPrimary,
    inverseOnSurface = BorizonColors.TextPrimary
)

/**
 * BorizonTheme -- Deep Teal.
 * Supports both light and dark modes, but designed primarily for dark.
 * Dynamic color is disabled to preserve the Deep Teal palette.
 */
@Composable
fun BorizonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) darkSemanticColors() else lightSemanticColors()

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BorizonTypography,
        shapes = BorizonShapes,
    ) {
        CompositionLocalProvider(
            LocalBorizonColors.LocalAppColors provides BorizonColors,
            LocalBorizonSemanticColors.LocalSemanticColors provides semanticColors,
            LocalDarkTheme.LocalDarkThemeFlag provides darkTheme,
            content = content
        )
    }
}
