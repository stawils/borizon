package com.borizon.app.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import com.borizon.app.ui.theme.BorizonMotion

/**
 * Chat skeleton loader — 3-4 placeholder message bubbles with shimmer.
 * Shown while chat history loads.
 */
@Composable
fun ChatSkeletonLoader(modifier: Modifier = Modifier) {
    val semanticColors = LocalBorizonSemanticColors.current
    val surfaceColor = semanticColors.ui.cardSurfaceDefault
    val shimmerShape = RoundedCornerShape(12.dp)
    val shimmerProgress = rememberShimmerProgress()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // User bubble skeleton (right-aligned)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            ShimmerBox(
                width = 200.dp,
                height = 44.dp,
                shape = shimmerShape,
                baseColor = surfaceColor,
                progress = shimmerProgress
            )
        }

        // Agent bubble skeleton (left-aligned)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            ShimmerBox(
                width = 260.dp,
                height = 80.dp,
                shape = shimmerShape,
                baseColor = surfaceColor,
                progress = shimmerProgress
            )
        }

        // Another user bubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            ShimmerBox(
                width = 180.dp,
                height = 44.dp,
                shape = shimmerShape,
                baseColor = surfaceColor,
                progress = shimmerProgress
            )
        }

        // Another agent bubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            ShimmerBox(
                width = 240.dp,
                height = 60.dp,
                shape = shimmerShape,
                baseColor = surfaceColor,
                progress = shimmerProgress
            )
        }
    }
}

/**
 * Generic card skeleton — title bar + text lines with shimmer.
 */
@Composable
fun CardSkeletonLoader(
    modifier: Modifier = Modifier,
    lineCount: Int = 3
) {
    val semanticColors = LocalBorizonSemanticColors.current
    val surfaceColor = semanticColors.ui.cardSurfaceDefault
    val lineShape = RoundedCornerShape(4.dp)
    val shimmerProgress = rememberShimmerProgress()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShimmerBox(
            width = null, // fillMaxWidth with fraction
            height = 16.dp,
            shape = lineShape,
            baseColor = surfaceColor,
            progress = shimmerProgress,
            widthFraction = 0.5f
        )

        Spacer(modifier = Modifier.height(4.dp))

        repeat(lineCount) { index ->
            val widthFraction = if (index == lineCount - 1) 0.6f else 0.9f
            ShimmerBox(
                width = null,
                height = 12.dp,
                shape = lineShape,
                baseColor = surfaceColor,
                progress = shimmerProgress,
                widthFraction = widthFraction
            )
        }
    }
}

/**
 * Branded "borizon teardrop" loader — pulsing circle.
 * Used for model loading and other branded loading states.
 */
@Composable
fun BorizonBrandedLoader(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "brandedLoader")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BorizonMotion.DurationContemplative, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loaderScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BorizonMotion.DurationContemplative, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loaderAlpha"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(
                    MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(percent = 50)
                )
        )
    }
}

// ── Internal helpers ──────────────────────────────────────────────

@Composable
private fun rememberShimmerProgress(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BorizonMotion.DurationContemplative, easing = EaseInOutSine),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    ).value
}

@Composable
private fun ShimmerBox(
    width: androidx.compose.ui.unit.Dp?,
    height: androidx.compose.ui.unit.Dp,
    shape: RoundedCornerShape,
    baseColor: Color,
    progress: Float,
    widthFraction: Float = 1f,
) {
    val highlightColor = Color.White.copy(alpha = 0.08f)
    val modifier = if (width != null) {
        Modifier.width(width).height(height)
    } else {
        Modifier.fillMaxWidth(widthFraction).height(height)
    }
    val brush = remember(progress) {
        Brush.linearGradient(
            colors = listOf(baseColor, highlightColor, baseColor),
            start = Offset(x = -1000f + (2000f * progress), y = 0f),
            end = Offset(x = 1000f + (2000f * progress), y = 0f)
        )
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush = brush)
    )
}
