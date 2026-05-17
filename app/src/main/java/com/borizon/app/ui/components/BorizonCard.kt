package com.borizon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import com.borizon.app.ui.theme.LocalDarkTheme
import com.borizon.app.ui.theme.SurfaceLevel
import com.borizon.app.ui.theme.toColor

/**
 * Accent stripe configuration for BorizonCard.
 */
data class AccentStripe(
    val color: Color,
    val position: StripePosition = StripePosition.LEFT,
    val width: Dp = 3.dp
)

enum class StripePosition { LEFT, TOP }

/**
 * Unified premium card component.
 *
 * Design: 16dp rounded corners (organic feel), no shadows (depth via surface color),
 * optional accent stripe, gentle press feedback when clickable.
 */
@Composable
fun BorizonCard(
    modifier: Modifier = Modifier,
    surfaceLevel: SurfaceLevel = SurfaceLevel.Default,
    accentStripe: AccentStripe? = null,
    onClick: (() -> Unit)? = null,
    borderColor: Color? = null,
    cornerSize: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDarkTheme = LocalDarkTheme.current
    val surfaceColor = surfaceLevel.toColor(isDarkTheme)
    val uiColors = LocalBorizonSemanticColors.current.ui
    val border = borderColor?.let { BorderStroke(1.dp, it) }
        ?: BorderStroke(1.dp, uiColors.dividerColor)
    val shape = RoundedCornerShape(cornerSize)

    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier,
        shape = shape,
        color = surfaceColor,
        border = border,
    ) {
        if (accentStripe != null && accentStripe.position == StripePosition.LEFT) {
            // Row with left stripe + content
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(accentStripe.width)
                        .background(accentStripe.color)
                        .then(
                            Modifier.clip(
                                RoundedCornerShape(topStart = cornerSize, bottomStart = cornerSize)
                            )
                        )
                )
                Column(
                    modifier = Modifier.weight(1f),
                    content = content
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top accent stripe
                if (accentStripe != null && accentStripe.position == StripePosition.TOP) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(accentStripe.width)
                            .background(accentStripe.color)
                    )
                }
                content()
            }
        }
    }
}
