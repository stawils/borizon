package com.borizon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borizon.app.R
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import com.borizon.app.ui.theme.Timestamp
import androidx.compose.animation.core.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun TypingIndicator(elapsedSeconds: Int = 0, activityLabel: String? = null) {
    val semanticColors = LocalBorizonSemanticColors.current
    val primary = MaterialTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")

    val phaseQuick = listOf(
        stringResource(R.string.phase_quick_1),
        stringResource(R.string.phase_quick_2),
        stringResource(R.string.phase_quick_3),
        stringResource(R.string.phase_quick_4),
        stringResource(R.string.phase_quick_5),
    )
    val phaseDeep = listOf(
        stringResource(R.string.phase_deep_1),
        stringResource(R.string.phase_deep_2),
        stringResource(R.string.phase_deep_3),
        stringResource(R.string.phase_deep_4),
        stringResource(R.string.phase_deep_5),
        stringResource(R.string.phase_deep_6),
    )
    val phaseCare = listOf(
        stringResource(R.string.phase_care_1),
        stringResource(R.string.phase_care_2),
        stringResource(R.string.phase_care_3),
        stringResource(R.string.phase_care_4),
        stringResource(R.string.phase_care_5),
        stringResource(R.string.phase_care_6),
    )

    val breathDuration = (2200 - (elapsedSeconds * 40).coerceAtMost(600))
    val breathe by infiniteTransition.animateFloat(
        0.88f, 1f,
        infiniteRepeatable(tween(breathDuration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "breathe"
    )
    val lightSweep by infiniteTransition.animateFloat(
        -0.3f, 1.3f,
        infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        "lightSweep"
    )
    val orbitSpeed = (3500 - (elapsedSeconds * 80).coerceAtMost(1200))
    val orbitAngle by infiniteTransition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(orbitSpeed, easing = LinearEasing), RepeatMode.Restart),
        "orbit"
    )
    val shimmer by infiniteTransition.animateFloat(
        0f, 2f,
        infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        "shimmer"
    )

    // Use real activity label when available (from tool events)
    val realLabel = activityLabel
    val currentPhase = when {
        realLabel != null -> listOf(realLabel)
        elapsedSeconds < 4 -> phaseQuick
        elapsedSeconds < 12 -> phaseDeep
        else -> phaseCare
    }
    var stateIndex by remember { mutableIntStateOf(0) }
    val stateDuration = when {
        realLabel != null -> 99999 // no cycling when showing real activity
        elapsedSeconds < 4 -> 2400
        elapsedSeconds < 12 -> 3200
        else -> 4000
    }
    LaunchedEffect(currentPhase) {
        stateIndex = 0
        while (true) {
            kotlinx.coroutines.delay(stateDuration.toLong())
            stateIndex = (stateIndex + 1) % currentPhase.size
        }
    }
    val stateText = currentPhase[stateIndex % currentPhase.size]
    val showParticles = !realLabel.isNullOrEmpty() || elapsedSeconds >= 3

    val shimmerBrush = Brush.horizontalGradient(
        colorStops = arrayOf(
            (shimmer - 0.4f).coerceIn(0f, 1f) to semanticColors.chat.agentBubbleText,
            shimmer.coerceIn(0f, 1f) to primary,
            (shimmer + 0.4f).coerceIn(0f, 1f) to semanticColors.chat.agentBubbleText,
        )
    )

    Column(modifier = Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.Start) {
        Surface(
            shape = MessageBubbleShape(radius = 18.dp, sharpCornerLeft = true),
            color = semanticColors.chat.agentBubbleBg
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Canvas(modifier = Modifier.size(28.dp)) {
                    val d = size.minDimension
                    val cx = d / 2f
                    val cy = d / 2f
                    val s = d * breathe / 108f

                    val glowAlpha = (0.18f + elapsedSeconds * 0.015f).coerceAtMost(0.4f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to primary.copy(alpha = glowAlpha),
                            1f to Color.Transparent,
                        ),
                        radius = d * 0.48f,
                        center = Offset(cx, cy)
                    )

                    translate(cx, cy) {
                        val outer = Path().apply {
                            moveTo(0f, (30 - 53) * s)
                            cubicTo(0f, (30 - 53) * s, (38 - 54) * s, (44 - 53) * s, (38 - 54) * s, (58 - 53) * s)
                            cubicTo((38 - 54) * s, (68 - 53) * s, (45 - 54) * s, (76 - 53) * s, 0f, (76 - 53) * s)
                            cubicTo((63 - 54) * s, (76 - 53) * s, (70 - 54) * s, (68 - 53) * s, (70 - 54) * s, (58 - 53) * s)
                            cubicTo((70 - 54) * s, (44 - 53) * s, 0f, (30 - 53) * s, 0f, (30 - 53) * s)
                            close()
                        }
                        drawPath(outer, color = primary)

                        val inner = Path().apply {
                            moveTo(0f, (36 - 53) * s)
                            cubicTo(0f, (36 - 53) * s, (42 - 54) * s, (47 - 53) * s, (42 - 54) * s, (58 - 53) * s)
                            cubicTo((42 - 54) * s, (66 - 53) * s, (47 - 54) * s, (72 - 53) * s, 0f, (72 - 53) * s)
                            cubicTo((61 - 54) * s, (72 - 53) * s, (66 - 54) * s, (66 - 53) * s, (66 - 54) * s, (58 - 53) * s)
                            cubicTo((66 - 54) * s, (47 - 53) * s, 0f, (36 - 53) * s, 0f, (36 - 53) * s)
                            close()
                        }
                        drawPath(inner, color = primary.copy(alpha = 0.45f))

                        val sweepX = (lightSweep - 0.5f) * d * 0.9f
                        drawPath(
                            outer,
                            brush = Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0f),
                                0.5f to Color.White.copy(alpha = 0.3f),
                                1f to Color.White.copy(alpha = 0f),
                                start = Offset(sweepX - d * 0.12f, -d * 0.5f),
                                end = Offset(sweepX + d * 0.12f, d * 0.5f)
                            )
                        )
                    }

                    if (showParticles) {
                        val orbitR = d * 0.44f
                        val count = when {
                            elapsedSeconds >= 15 -> 5
                            elapsedSeconds >= 8 -> 4
                            else -> 3
                        }
                        for (i in 0 until count) {
                            val a = Math.toRadians((orbitAngle + i * (360.0 / count)).toDouble())
                            val px = cx + cos(a).toFloat() * orbitR
                            val py = cy + sin(a).toFloat() * orbitR
                            val pAlpha = (0.35f + 0.35f * sin(((orbitAngle + i * 90f) / 360f) * Math.PI.toFloat() * 2)).coerceIn(0.2f, 0.7f)
                            drawCircle(
                                primary.copy(alpha = pAlpha),
                                radius = d * 0.035f,
                                center = Offset(px, py)
                            )
                        }
                    }
                }

                Text(
                    text = stateText + "…",
                    style = TextStyle(
                        brush = shimmerBrush,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                )
            }
        }
        Text(text = "${elapsedSeconds}s", style = Timestamp,
            color = semanticColors.chat.dateSeparatorText,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}
