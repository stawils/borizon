package com.borizon.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PlayArrow

import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.LocalPhone
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.borizon.app.ai.tools.ToolEvent
import com.borizon.app.ai.tools.ToolNavigationTarget
import com.borizon.app.ai.tools.ToolType
import com.borizon.app.ui.theme.SurfaceContainers
import com.borizon.app.R
import com.borizon.app.ui.theme.toColor

/**
 * Collapsible timeline showing tool calls made during a reflection response.
 *
 * Two modes:
 * - **Live** (during generation): auto-expanded, active tools show spinner
 * - **Frozen** (after generation): collapsed by default, completed tools show checkmark, tappable rows navigate
 */
@Composable
fun ToolTimelinePanel(
    events: List<ToolEvent>,
    isLive: Boolean,
    modifier: Modifier = Modifier,
    onToolClick: ((ToolNavigationTarget) -> Unit)? = null,
) {
    if (events.isEmpty()) return

    val isDarkTheme = isSystemInDarkTheme()
    var isExpanded by remember { mutableStateOf(isLive) }

    // Auto-expand when new events arrive during live mode
    LaunchedEffect(events.size) {
        if (isLive) isExpanded = true
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = if (isDarkTheme) SurfaceContainers.containerLowestDark else SurfaceContainers.containerLowestLight,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column {
            // Header row — always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !isLive) { isExpanded = !isExpanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Summary icon
                Icon(
                    imageVector = if (isLive) Icons.Rounded.Psychology else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isLive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = let {
                        val runningCount = events.count { it.isInProgress }
                        when {
                            isLive && runningCount > 0 -> "$runningCount tool${if (runningCount > 1) "s" else ""} running..."
                            else -> "${events.size} tool${if (events.size > 1) "s" else ""} used"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )

                // Expand/collapse chevron (hidden in live mode)
                if (!isLive) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }

            // Expanded tool rows
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                    // Cap visible events to prevent oversized panel
                    val visibleEvents = events.take(10)
                    visibleEvents.forEach { event ->
                        val hasNav = !isLive && event.navigationTarget != ToolNavigationTarget.None && onToolClick != null
                        ToolEventRow(
                            event = event,
                            onClick = if (hasNav) {{ onToolClick!!(event.navigationTarget) }} else null,
                        )
                    }
                    if (events.size > 10) {
                        Text(
                            text = "+${events.size - 10} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolEventRow(
    event: ToolEvent,
    onClick: (() -> Unit)?,
) {
    val icon = toolIcon(event.toolType)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tool-specific icon or spinner
        if (event.isInProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Normal,
                color = if (event.isInProgress)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
            )
            if (event.detailDescription.isNotBlank()) {
                Text(
                    text = event.detailDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
        }

        // Duration badge for completed tools
        if (!event.isInProgress && event.durationMs > 0) {
            Text(
                text = formatToolDuration(event.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        // Tappable arrow for navigable results
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.tool_view_details),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            )
        }
    }
}

private fun toolIcon(type: ToolType): ImageVector = when (type) {
    ToolType.GET_TIME_CONTEXT -> Icons.Outlined.Schedule
    // Phone tools
    ToolType.SET_ALARM -> Icons.Outlined.Alarm
    ToolType.CREATE_REMINDER -> Icons.Outlined.Notifications
    ToolType.SHARE_TEXT -> Icons.Outlined.Share
    ToolType.OPEN_CALENDAR -> Icons.Outlined.CalendarMonth
    ToolType.OPEN_URL -> Icons.Outlined.Language
    ToolType.OPEN_SETTINGS -> Icons.Outlined.Settings
    ToolType.SEND_EMAIL -> Icons.Outlined.Email
    ToolType.CREATE_CONTACT -> Icons.Outlined.PersonAdd
    ToolType.PHONE_CALL -> Icons.Outlined.LocalPhone
    ToolType.SEND_SMS -> Icons.Outlined.Chat
    ToolType.READ_CONTACTS -> Icons.Outlined.Contacts
    ToolType.OPEN_APP -> Icons.Outlined.Apps
    // Skill tools
    ToolType.LOAD_SKILL -> Icons.Outlined.AutoAwesome
    ToolType.LIST_SKILLS -> Icons.Outlined.GridView
    ToolType.RUN_JS -> Icons.Outlined.PlayArrow
    // Web tools
    ToolType.WEB_SEARCH -> Icons.Outlined.Language
    ToolType.WEB_READ -> Icons.Outlined.MenuBook
    // Memory tools
    ToolType.MEMORY_SAVE -> Icons.Outlined.EditNote
    ToolType.MEMORY_SEARCH -> Icons.Outlined.Search
    ToolType.MEMORY_FORGET -> Icons.Outlined.Delete
    // Notification tools
    ToolType.NOTIFICATION_READ -> Icons.Outlined.Notifications
    // SMS + Call tools
    ToolType.SMS_READ, ToolType.SMS_CONVERSATION -> Icons.Outlined.Chat
    ToolType.CALL_LOG_READ, ToolType.CALL_LOG_CONTACT -> Icons.Outlined.LocalPhone
    // Installed apps tools
    ToolType.APP_LIST, ToolType.APP_DETAILS -> Icons.Outlined.Apps
    // Shell tools
    ToolType.SHELL_EXECUTE -> Icons.Outlined.Terminal
}

private fun formatToolDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${((ms % 60_000) / 1000)}s"
}
