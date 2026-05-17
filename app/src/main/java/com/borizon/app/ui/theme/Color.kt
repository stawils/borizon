package com.borizon.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Deep Teal color palette.
 * Cool, modern tones inspired by deep water and twilight --
 * teal accents, slate neutrals, and cyan highlights create a private, trustworthy atmosphere.
 */

// Primary -- vibrant teal
val TealPrimary = Color(0xFF2DD4BF)
val TealOnPrimary = Color(0xFF042F2E)
val TealPrimaryContainer = Color(0xFF134E4A)
val TealOnPrimaryContainer = Color(0xFFA7F3D0)

// Secondary -- cool slate
val SlateSecondary = Color(0xFF64748B)
val SlateOnSecondary = Color(0xFF1E293B)
val SlateSecondaryContainer = Color(0xFF334155)
val SlateOnSecondaryContainer = Color(0xFFCBD5E1)

// Tertiary -- electric cyan
val CyanTertiary = Color(0xFF22D3EE)
val CyanOnTertiary = Color(0xFF083344)
val CyanTertiaryContainer = Color(0xFF164E63)
val CyanOnTertiaryContainer = Color(0xFFA5F3FC)

// Background / Surface -- deep navy
val NavyBackground = Color(0xFF0C1222)
val NavyOnBackground = Color(0xFFE2E8F0)
val NavySurface = Color(0xFF162032)
val NavyOnSurface = Color(0xFFE2E8F0)
val NavySurfaceVariant = Color(0xFF1E293B)
val NavyOnSurfaceVariant = Color(0xFFB0BEC5)

// Error -- soft rose
val RoseError = Color(0xFFFB7185)
val RoseOnError = Color(0xFF4C0519)
val RoseErrorContainer = Color(0xFF881337)
val RoseOnErrorContainer = Color(0xFFFFE4E6)

// Outline
val SlateOutline = Color(0xFF475569)
val SlateOutlineVariant = Color(0xFF334155)

/**
 * BorizonColors -- semantic color aliases used by BorizonTheme.
 * Maps Deep Teal palette to semantic names for clarity.
 */
object BorizonColors {
    // Accent
    val AccentPrimary = TealPrimary
    val AccentLight = TealPrimary.copy(alpha = 0.2f)

    // Text
    val TextPrimary = NavyOnBackground
    val TextSecondary = NavyOnSurfaceVariant
    val TextOnDark = NavyOnSurface

    // Background
    val BackgroundPrimary = Color(0xFFF0FDFA) // Light mode mint white
    val BackgroundSecondary = Color(0xFFF1F5F9) // Light mode slate tint
    val BackgroundDark = NavyBackground
    val SurfaceDark = NavySurface

    // Semantic
    val Success = Color(0xFF4ADE80)   // Fresh green
    val Error = RoseError
    val Warning = Color(0xFFFBBF24)   // Warm amber
    val Info = Color(0xFF60A5FA)      // Sky blue
}
