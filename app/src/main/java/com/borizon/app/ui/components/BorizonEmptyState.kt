package com.borizon.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import com.borizon.app.ui.theme.BorizonMotion

/**
 * Shared empty state component.
 *
 * Features:
 * - Staggered fade-in (icon → title → description → CTA)
 * - Gentle pulse on icon via Modifier.breathe()
 * - All colors from BorizonSemanticColors
 */
@Composable
fun BorizonEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val semanticColors = LocalBorizonSemanticColors.current

    // Staggered fade-in: 0ms → 100ms → 200ms → 300ms
    val iconAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val descAlpha = remember { Animatable(0f) }
    val ctaAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        iconAlpha.animateTo(1f, tween(BorizonMotion.DurationNormal, 0, EaseOut))
        titleAlpha.animateTo(1f, tween(BorizonMotion.DurationNormal, 100, EaseOut))
        descAlpha.animateTo(1f, tween(BorizonMotion.DurationNormal, 200, EaseOut))
        if (actionLabel != null) {
            ctaAlpha.animateTo(1f, tween(BorizonMotion.DurationNormal, 300, EaseOut))
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulsing indicator dot
        Surface(
            modifier = Modifier
                .size(12.dp)
                .breathe(minAlpha = 0.3f, maxAlpha = 0.7f),
            shape = CircleShape,
            color = semanticColors.ui.emptyStateIconColor.copy(alpha = iconAlpha.value)
        ) {}

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = semanticColors.ui.emptyStateTitleColor.copy(alpha = titleAlpha.value)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = semanticColors.ui.emptyStateDescColor.copy(alpha = descAlpha.value)
        )

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = onAction,
                modifier = Modifier.graphicsLayer { alpha = ctaAlpha.value }
            ) {
                Text(actionLabel)
            }
        }
    }
}
