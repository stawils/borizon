package com.borizon.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 5-level surface container hierarchy creating depth through cool tonal shifts.
 * Dark theme uses progressively lighter navy/slate undertones instead of shadows.
 * Light theme uses cool mint/slate shifts.
 *
 * Usage: SurfaceContainers.containerDefault, etc.
 * Wired into BorizonTheme's Material colorScheme (surface/containerDefault, surfaceVariant/containerHigh).
 */
object SurfaceContainers {
    // Dark: deep navy → progressively lighter
    val containerLowestDark = Color(0xFF0C1222)
    val containerLowDark = Color(0xFF131B2E)
    val containerDefaultDark = Color(0xFF162032)
    val containerHighDark = Color(0xFF1C2840)
    val containerHighestDark = Color(0xFF243350)

    // Light: cool mint → progressively richer
    val containerLowestLight = Color(0xFFF8FFFE)
    val containerLowLight = Color(0xFFF0FDFA)
    val containerDefaultLight = Color(0xFFE6F7F5)
    val containerHighLight = Color(0xFFDCEEF0)
    val containerHighestLight = Color(0xFFD1E5E8)
}

/**
 * Surface level enum for components that need explicit surface selection.
 */
enum class SurfaceLevel {
    Lowest, Low, Default, High, Highest
}

/**
 * Returns the container color for the given level in dark or light mode.
 */
fun SurfaceLevel.toColor(darkTheme: Boolean): Color = when (this) {
    SurfaceLevel.Lowest -> if (darkTheme) SurfaceContainers.containerLowestDark else SurfaceContainers.containerLowestLight
    SurfaceLevel.Low -> if (darkTheme) SurfaceContainers.containerLowDark else SurfaceContainers.containerLowLight
    SurfaceLevel.Default -> if (darkTheme) SurfaceContainers.containerDefaultDark else SurfaceContainers.containerDefaultLight
    SurfaceLevel.High -> if (darkTheme) SurfaceContainers.containerHighDark else SurfaceContainers.containerHighLight
    SurfaceLevel.Highest -> if (darkTheme) SurfaceContainers.containerHighestDark else SurfaceContainers.containerHighestLight
}
