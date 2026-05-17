package com.borizon.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.borizon.app.R
import kotlin.math.cos
import kotlin.math.sin

private val BG = BorizonColors.BG
private val GLOW_COLOR = BorizonColors.GLOW
private val C_OUTER = BorizonColors.OUTER
private val C_INNER = BorizonColors.INNER
private val C_HIGH = BorizonColors.HIGHLIGHT
private val C_TEXT = BorizonColors.TEXT
private val C_PARTICLE = BorizonColors.TEXT

private data class Particle(
    val angle: Float, val dist: Float, val speed: Float, val size: Float, val phase: Float
)

private val PARTICLES = listOf(
    Particle(0.0f, 1.2f, 0.7f, 3.5f, 0f),
    Particle(0.6f, 1.6f, -0.5f, 2.5f, 1.2f),
    Particle(1.3f, 1.4f, 0.6f, 4f, 2.5f),
    Particle(2.0f, 1.8f, -0.4f, 2.5f, 0.8f),
    Particle(2.6f, 1.0f, 0.8f, 3.5f, 3.1f),
    Particle(3.3f, 1.7f, -0.6f, 2.5f, 1.8f),
    Particle(3.9f, 1.3f, 0.5f, 4f, 4.0f),
    Particle(4.5f, 1.9f, -0.3f, 2f, 2.0f),
    Particle(5.1f, 1.1f, 0.9f, 3.5f, 0.4f),
    Particle(5.7f, 1.5f, -0.7f, 2.5f, 3.5f),
)

/** Create the 3 logo paths at a given scale, centered at (0,0). */
// buildLogoPaths moved to BorizonLogo.kt (shared)

@Composable
fun AnimatedSplashScreen(onFinished: () -> Unit) {
    val logoAlpha = remember { Animatable(0f) }
    val glowAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }
    val fadeOut = remember { Animatable(1f) }

    // Smooth monotonically-increasing orbit time — no resets/jumps
    var orbitTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastFrame = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - lastFrame) / 1_000_000_000f // seconds
            lastFrame = now
            orbitTime += dt
        }
    }

    val density = LocalDensity.current
    val wPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val hPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val logoSizePx = minOf(wPx, hPx) * 0.40f
    val cx = wPx / 2f
    val cy = hPx * 0.36f
    val scale = logoSizePx / 108f

    // Cache paths + brush — only depend on screen size (constant)
    val (outerPath, innerPath, highlightPath) = remember(scale) { buildLogoPaths(scale) }
    val glowRadius = logoSizePx * 2.5f
    val glowCy = cy + logoSizePx * 0.22f
    val glowBrush = remember(cx, glowCy, glowRadius) {
        Brush.radialGradient(
            colorStops = arrayOf(0f to GLOW_COLOR, 1f to BG.copy(alpha = 0f)),
            center = Offset(cx, glowCy),
            radius = glowRadius
        )
    }

    // Sequential reveal chain
    LaunchedEffect(Unit) {
        logoAlpha.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
        glowAlpha.animateTo(0.7f, tween(500, easing = FastOutSlowInEasing))
        titleAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        taglineAlpha.animateTo(0.6f, tween(300, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(300)
        fadeOut.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
        onFinished()
    }


    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gA = fadeOut.value

            drawRect(BG)

            // Glow — cached brush, only alpha changes per frame
            val glow = glowAlpha.value * gA
            if (glow > 0.01f) {
                drawCircle(
                    brush = glowBrush,
                    radius = glowRadius,
                    alpha = glow
                )
            }

            // Particles — smooth continuous orbit, no jumps
            val particleFade = (logoAlpha.value * 0.8f).coerceIn(0f, 1f) * gA
            if (particleFade > 0.01f) {
                val t = orbitTime
                PARTICLES.forEach { p ->
                    val pt = t * p.speed + p.phase
                    val orbitR = p.dist * logoSizePx
                    val wobble = (sin(pt * 1.3 + p.angle) * 5f).toFloat()
                    val px = cx + (cos(pt + p.angle) * (orbitR + wobble)).toFloat()
                    val py = cy + (sin(pt + p.angle) * (orbitR * 0.65 + wobble * 0.5)).toFloat()
                    val pAlpha = particleFade * (sin(pt * 2 + p.phase) * 0.5 + 0.5).toFloat() * 0.6f
                    if (pAlpha > 0.02f) {
                        drawCircle(
                            C_PARTICLE.copy(alpha = pAlpha),
                            radius = p.size,
                            center = Offset(px, py)
                        )
                    }
                }
            }

            // Logo — cached paths, only alpha changes per frame
            val la = logoAlpha.value * gA
            if (la > 0.01f) {
                translate(left = cx, top = cy) {
                    drawPath(outerPath, color = C_OUTER, alpha = la)
                    drawPath(innerPath, color = C_INNER, alpha = la)
                    drawPath(highlightPath, color = C_HIGH, alpha = la * 0.5f)
                }
            }
        }

        // Text
        val titleSizeSp = (wPx / density.density * 0.10).sp
        val taglineSizeSp = (wPx / density.density * 0.030).sp
        val textTopPadding = with(density) { (cy + logoSizePx * 0.55f).toDp() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = textTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    color = C_TEXT.copy(alpha = titleAlpha.value * fadeOut.value),
                    fontSize = titleSizeSp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).sp,
                    textAlign = TextAlign.Center
                )
            )
            Text(
                text = stringResource(R.string.splash_tagline),
                modifier = Modifier.padding(top = 10.dp),
                style = TextStyle(
                    color = C_TEXT.copy(alpha = taglineAlpha.value * fadeOut.value),
                    fontSize = taglineSizeSp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.5.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
