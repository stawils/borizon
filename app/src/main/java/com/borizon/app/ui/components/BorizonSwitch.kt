package com.borizon.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.borizon.app.ui.theme.BorizonMotion

/**
 * Custom switch with Deep Teal styling.
 * - 28dp track height
 * - Theme-aware colors (teal primary on, surface off)
 * - Smooth 200ms transition
 */
@Composable
fun BorizonSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackColor = if (checked) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = tween(BorizonMotion.DurationFast),
        label = "thumbOffset"
    )

    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                toggleableState = ToggleableState(checked)
            }
            .size(width = 48.dp, height = 28.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(if (!enabled) trackColor.copy(alpha = 0.4f) else trackColor)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (!enabled) Color.White.copy(alpha = 0.6f) else Color.White)
        )
    }
}
