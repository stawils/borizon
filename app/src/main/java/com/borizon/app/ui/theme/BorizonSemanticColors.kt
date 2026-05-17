package com.borizon.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens grouped by domain.
 * Pre-resolved for dark or light theme — no ad-hoc alpha derivations needed in screens.
 *
 * Access via: LocalBorizonSemanticColors.current.chat.agentBubbleBg
 */
@Immutable
data class BorizonSemanticColors(
    // ── Chat domain ──────────────────────────────────────────────
    val chat: ChatColors,
    // ── Journal domain ───────────────────────────────────────────
    val journal: JournalColors,
    // ── General UI ───────────────────────────────────────────────
    val ui: UiColors,
    // ── Status ───────────────────────────────────────────────────
    val status: StatusColors,
)

@Immutable
data class ChatColors(
    val userBubbleBg: Color,
    val userBubbleText: Color,
    val agentBubbleBg: Color,
    val agentBubbleText: Color,
    val thinkingPanelBg: Color,
    val thinkingPanelAccent: Color,
    val streamingCursorColor: Color,
    val typingDotColor: Color,
    val dateSeparatorText: Color,
    val inputBarBg: Color,
    val inputBarBorder: Color,
    val senderLabelText: Color,
    val recordingIndicatorColor: Color = Color(0xFFFF4444),
    val webViewBackground: Color = Color(0xFF1a1a2e),
)

@Immutable
data class JournalColors(
    val insightPatternColor: Color,
    val insightStrengthColor: Color,
    val insightCardSurface: Color,
)

@Immutable
data class UiColors(
    val emptyStateIconColor: Color,
    val emptyStateTitleColor: Color,
    val emptyStateDescColor: Color,
    val cardSurfaceDefault: Color,
    val cardSurfaceElevated: Color,
    val chipSurfaceColor: Color,
    val chipActiveSurfaceColor: Color,
    val chipActiveTextColor: Color,
    val dividerColor: Color,
    val drawerSurface: Color,
    val navigationBarSurface: Color,
)

@Immutable
data class StatusColors(
    val success: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
)

// ── Factory functions ──────────────────────────────────────────────

@Stable
fun darkSemanticColors(): BorizonSemanticColors = BorizonSemanticColors(
    chat = ChatColors(
        userBubbleBg = TealPrimaryContainer,
        userBubbleText = TealOnPrimaryContainer,
        agentBubbleBg = SurfaceContainers.containerDefaultDark,
        agentBubbleText = NavyOnSurface,
        thinkingPanelBg = NavySurface.copy(alpha = 0.3f),
        thinkingPanelAccent = CyanTertiary,
        streamingCursorColor = TealPrimary,
        typingDotColor = NavyOnSurfaceVariant.copy(alpha = 0.5f),
        dateSeparatorText = NavyOnSurfaceVariant.copy(alpha = 0.4f),
        inputBarBg = SurfaceContainers.containerHighDark.copy(alpha = 0.7f),
        inputBarBorder = SlateOutline,
        senderLabelText = NavyOnSurfaceVariant.copy(alpha = 0.5f),
        recordingIndicatorColor = Color(0xFFFF4444),
        webViewBackground = Color(0xFF0C1222),
    ),
    journal = JournalColors(
        insightPatternColor = TealPrimary,
        insightStrengthColor = CyanTertiary,
        insightCardSurface = SurfaceContainers.containerDefaultDark,
    ),
    ui = UiColors(
        emptyStateIconColor = TealPrimary.copy(alpha = 0.6f),
        emptyStateTitleColor = NavyOnSurfaceVariant.copy(alpha = 0.7f),
        emptyStateDescColor = NavyOnSurfaceVariant.copy(alpha = 0.5f),
        cardSurfaceDefault = SurfaceContainers.containerDefaultDark,
        cardSurfaceElevated = SurfaceContainers.containerHighDark,
        chipSurfaceColor = NavySurface.copy(alpha = 0.4f),
        chipActiveSurfaceColor = TealPrimary.copy(alpha = 0.15f),
        chipActiveTextColor = TealPrimary,
        dividerColor = SlateOutlineVariant.copy(alpha = 0.3f),
        drawerSurface = SurfaceContainers.containerHighDark,
        navigationBarSurface = SurfaceContainers.containerLowestDark,
    ),
    status = StatusColors(
        success = Color(0xFF4ADE80),
        error = RoseError,
        warning = Color(0xFFFBBF24),
        info = Color(0xFF60A5FA),
    ),
)

@Stable
fun lightSemanticColors(): BorizonSemanticColors = BorizonSemanticColors(
    chat = ChatColors(
        userBubbleBg = TealPrimary.copy(alpha = 0.15f),
        userBubbleText = TealOnPrimary,
        agentBubbleBg = SurfaceContainers.containerDefaultLight,
        agentBubbleText = NavyBackground,
        thinkingPanelBg = SurfaceContainers.containerDefaultLight.copy(alpha = 0.5f),
        thinkingPanelAccent = CyanTertiary,
        streamingCursorColor = TealPrimary,
        typingDotColor = SlateSecondary.copy(alpha = 0.5f),
        dateSeparatorText = SlateSecondary.copy(alpha = 0.4f),
        inputBarBg = SurfaceContainers.containerHighLight.copy(alpha = 0.7f),
        inputBarBorder = SlateOutline.copy(alpha = 0.3f),
        senderLabelText = SlateSecondary.copy(alpha = 0.5f),
        recordingIndicatorColor = Color(0xFFCC0000),
        webViewBackground = Color(0xFFF5F5F5),
    ),
    journal = JournalColors(
        insightPatternColor = TealPrimary,
        insightStrengthColor = CyanTertiary,
        insightCardSurface = Color.White,
    ),
    ui = UiColors(
        emptyStateIconColor = TealPrimary.copy(alpha = 0.4f),
        emptyStateTitleColor = SlateSecondary.copy(alpha = 0.7f),
        emptyStateDescColor = SlateSecondary.copy(alpha = 0.5f),
        cardSurfaceDefault = Color.White,
        cardSurfaceElevated = SurfaceContainers.containerDefaultLight,
        chipSurfaceColor = SurfaceContainers.containerDefaultLight.copy(alpha = 0.5f),
        chipActiveSurfaceColor = TealPrimary.copy(alpha = 0.1f),
        chipActiveTextColor = TealPrimary,
        dividerColor = SlateOutline.copy(alpha = 0.15f),
        drawerSurface = SurfaceContainers.containerHighLight,
        navigationBarSurface = Color.White,
    ),
    status = StatusColors(
        success = Color(0xFF4ADE80),
        error = RoseError,
        warning = Color(0xFFFBBF24),
        info = Color(0xFF60A5FA),
    ),
)
