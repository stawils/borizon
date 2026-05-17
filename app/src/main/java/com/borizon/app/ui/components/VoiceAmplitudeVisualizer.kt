package com.borizon.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Circular amplitude visualizer that shows pulsing bars around a center point.
 * Driven by RMS amplitude (0-100) from SpeechRecognizer's onRmsChanged.
 */
@Composable
fun VoiceAmplitudeVisualizer(
    amplitude: Int,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
) {
    // Smooth the amplitude transitions
    val animatedAmplitude by animateIntAsState(
        targetValue = amplitude,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "amplitude"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(
        modifier = modifier
            .size(80.dp)
            .clip(CircleShape)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.width / 2f
        val innerRadius = outerRadius * 0.35f
        val maxBarLength = (outerRadius - innerRadius) * 0.9f

        val ampFraction = (animatedAmplitude / 100f) * pulse

        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f
            val radians = Math.toRadians(angle.toDouble()).toFloat()

            val barLength = maxBarLength * (0.15f + ampFraction * 0.85f)
            val startRadius = innerRadius
            val endRadius = innerRadius + barLength

            val startX = center.x + startRadius * kotlin.math.cos(radians)
            val startY = center.y + startRadius * kotlin.math.sin(radians)
            val endX = center.x + endRadius * kotlin.math.cos(radians)
            val endY = center.y + endRadius * kotlin.math.sin(radians)

            val barAlpha = 0.3f + ampFraction * 0.7f
            val color = if (animatedAmplitude > 5) activeColor.copy(alpha = barAlpha) else inactiveColor

            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3f,
            )
        }

        // Center dot
        drawCircle(
            color = if (animatedAmplitude > 5) activeColor else inactiveColor,
            radius = innerRadius * 0.6f,
            center = center
        )
    }
}
