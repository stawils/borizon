package com.borizon.app.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Chat bubble shape with one sharp top corner pointing toward the sender.
 * using RoundRect.
 *
 * @param radius corner radius for rounded corners (default 24dp)
 * @param sharpCornerLeft true = sharp top-left (agent bubbles, left-aligned)
 *                        false = sharp top-right (user bubbles, right-aligned)
 */
class MessageBubbleShape(
    private val radius: Dp = 24.dp,
    private val sharpCornerLeft: Boolean = false,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    topLeftCornerRadius = if (sharpCornerLeft) CornerRadius(0f, 0f)
                                         else CornerRadius(r, r),
                    topRightCornerRadius = if (sharpCornerLeft) CornerRadius(r, r)
                                          else CornerRadius(0f, 0f),
                    bottomLeftCornerRadius = CornerRadius(r, r),
                    bottomRightCornerRadius = CornerRadius(r, r),
                )
            )
        }
        return Outline.Generic(path)
    }
}
