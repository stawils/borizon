package com.borizon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Shared Borizon brand colors. */
object BorizonColors {
    val BG = Color(0xFF0C1222)
    val GLOW = Color(0xFF0F2030)
    val OUTER = Color(0xFF2DD4BF)
    val INNER = Color(0xFF14B8A6)
    val HIGHLIGHT = Color(0xFF5EEAD4)
    val TEXT = Color(0xFF5EEAD4)
}

/** Build the 3 logo paths at a given scale, centered at (0,0). */
internal fun buildLogoPaths(s: Float): Triple<Path, Path, Path> {
    val outer = Path().apply {
        moveTo(0f, (30 - 53) * s)
        cubicTo(0f, (30 - 53) * s, (38 - 54) * s, (44 - 53) * s, (38 - 54) * s, (58 - 53) * s)
        cubicTo((38 - 54) * s, (68 - 53) * s, (45 - 54) * s, (76 - 53) * s, 0f, (76 - 53) * s)
        cubicTo((63 - 54) * s, (76 - 53) * s, (70 - 54) * s, (68 - 53) * s, (70 - 54) * s, (58 - 53) * s)
        cubicTo((70 - 54) * s, (44 - 53) * s, 0f, (30 - 53) * s, 0f, (30 - 53) * s)
        close()
    }
    val inner = Path().apply {
        moveTo(0f, (36 - 53) * s)
        cubicTo(0f, (36 - 53) * s, (42 - 54) * s, (47 - 53) * s, (42 - 54) * s, (58 - 53) * s)
        cubicTo((42 - 54) * s, (66 - 53) * s, (47 - 54) * s, (72 - 53) * s, 0f, (72 - 53) * s)
        cubicTo((61 - 54) * s, (72 - 53) * s, (66 - 54) * s, (66 - 53) * s, (66 - 54) * s, (58 - 53) * s)
        cubicTo((66 - 54) * s, (47 - 53) * s, 0f, (36 - 53) * s, 0f, (36 - 53) * s)
        close()
    }
    val highlight = Path().apply {
        moveTo(0f, (42 - 53) * s)
        cubicTo(0f, (42 - 53) * s, (46 - 54) * s, (50 - 53) * s, (46 - 54) * s, (58 - 53) * s)
        cubicTo((46 - 54) * s, (63 - 53) * s, (49 - 54) * s, (68 - 53) * s, 0f, (68 - 53) * s)
        cubicTo((59 - 54) * s, (68 - 53) * s, (62 - 54) * s, (63 - 53) * s, (62 - 54) * s, (58 - 53) * s)
        cubicTo((62 - 54) * s, (50 - 53) * s, 0f, (42 - 53) * s, 0f, (42 - 53) * s)
        close()
    }
    return Triple(outer, inner, highlight)
}

/**
 * Static Borizon logo canvas with glow background.
 * Used by BiometricScreen and other branded screens.
 *
 * @param alpha Overall opacity (0..1). Default 1f.
 * @param logoVerticalBias Vertical position as fraction of height (0=top, 0.5=center). Default 0.36.
 */
@Composable
fun BorizonLogoCanvas(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    logoVerticalBias: Float = 0.36f,
) {
    val density = LocalDensity.current
    val wPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val hPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val logoSizePx = minOf(wPx, hPx) * 0.40f
    val cx = wPx / 2f
    val cy = hPx * logoVerticalBias
    val scale = logoSizePx / 108f

    val (outerPath, innerPath, highlightPath) = remember(scale) { buildLogoPaths(scale) }

    val glowRadius = logoSizePx * 2.5f
    val glowCy = cy + logoSizePx * 0.22f
    val glowBrush = remember(cx, glowCy, glowRadius) {
        Brush.radialGradient(
            colorStops = arrayOf(0f to BorizonColors.GLOW, 1f to BorizonColors.BG.copy(alpha = 0f)),
            center = Offset(cx, glowCy),
            radius = glowRadius
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(BorizonColors.BG)

        // Glow
        if (alpha > 0.01f) {
            drawCircle(
                brush = glowBrush,
                radius = glowRadius,
                alpha = 0.7f * alpha
            )
        }

        // Logo
        val la = alpha
        if (la > 0.01f) {
            translate(left = cx, top = cy) {
                drawPath(outerPath, color = BorizonColors.OUTER, alpha = la)
                drawPath(innerPath, color = BorizonColors.INNER, alpha = la)
                drawPath(highlightPath, color = BorizonColors.HIGHLIGHT, alpha = la * 0.5f)
            }
        }
    }
}
