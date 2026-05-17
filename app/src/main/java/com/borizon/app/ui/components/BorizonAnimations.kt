package com.borizon.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.borizon.app.ui.theme.BorizonMotion

/**
 * Staggered entry for list items — fade + slide based on index.
 * Use in LazyColumn items: `modifier = Modifier.staggeredEntry(index)`
 */
fun Modifier.staggeredEntry(
    index: Int,
    baseDelay: Int = BorizonMotion.StaggerStep
): Modifier = composed {
    val delay = index * baseDelay
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(20f) }

    LaunchedEffect(delay) {
        alpha.animateTo(
            1f,
            animationSpec = tween(
                BorizonMotion.DurationNormal,
                delayMillis = delay,
                easing = BorizonMotion.EaseOutWarm
            )
        )
    }
    LaunchedEffect(delay) {
        offsetY.animateTo(
            0f,
            animationSpec = tween(
                BorizonMotion.DurationNormal,
                delayMillis = delay,
                easing = BorizonMotion.EaseOutWarm
            )
        )
    }

    this.graphicsLayer {
        this.alpha = alpha.value
        this.translationY = offsetY.value
    }
}

/**
 * Breathing pulse — oscillates alpha between min and max.
 * Use for decorative elements, empty state icons, status indicators.
 */
@Composable
fun Modifier.breathe(
    minAlpha: Float = 0.3f,
    maxAlpha: Float = 0.7f,
    duration: Int = BorizonMotion.DurationContemplative * 3
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val alpha by infiniteTransition.animateFloat(
        initialValue = minAlpha,
        targetValue = maxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAlpha"
    )
    return this.graphicsLayer { this.alpha = alpha }
}
