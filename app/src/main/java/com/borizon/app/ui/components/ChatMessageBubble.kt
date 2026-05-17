package com.borizon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.borizon.app.R
import com.borizon.app.data.models.ChatMessage
import com.borizon.app.ui.theme.BubbleBody
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import com.borizon.app.ui.theme.Timestamp

@Composable
internal fun ChatMessageBubble(
    message: ChatMessage,
    showTimestamp: Boolean,
    screenWidth: Dp,
    isLastAssistant: Boolean,
    isSpeakingThis: Boolean,
    responseDurationMs: Long = 0L,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onShare: (String) -> Unit = {},
    onImageClick: (Bitmap) -> Unit = {},
) {
    val isUser = message.role == "user"
    val maxBubbleWidth = screenWidth * 0.82f
    val semanticColors = LocalBorizonSemanticColors.current
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1500); copied = false }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (isUser) stringResource(R.string.chat_you) else stringResource(R.string.chat_title),
            style = Timestamp,
            color = semanticColors.chat.senderLabelText,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Surface(
            shape = MessageBubbleShape(
                radius = 18.dp,
                sharpCornerLeft = !isUser
            ),
            color = if (isUser) semanticColors.chat.userBubbleBg
                    else semanticColors.chat.agentBubbleBg,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        ) {
            if (isUser) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    message.imageBitmaps?.let { bitmaps ->
                        if (bitmaps.isNotEmpty()) {
                            MessageImageGrid(
                                bitmaps = bitmaps,
                                onImageClick = onImageClick,
                            )
                            if (message.content.isNotBlank() && message.content != "[Image]") {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                    message.audioFilePath?.let { path ->
                        val audioExists by remember(path) {
                            derivedStateOf { java.io.File(path).exists() }
                        }
                        if (audioExists) {
                            AudioPlaybackPanel(
                                wavFile = java.io.File(path),
                                accentColor = semanticColors.chat.userBubbleText,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (message.content.isNotBlank() && message.content != "[Image]") {
                        Text(
                            text = message.content,
                            style = BubbleBody,
                            color = semanticColors.chat.userBubbleText
                        )
                    }
                }
            } else {
                MarkdownRenderer(
                    markdown = message.content,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        ,
                )
            }
        }

        Row(
            modifier = Modifier.padding(start = 4.dp, top = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BorizonActionChip(stringResource(R.string.copy), Icons.Default.ContentCopy, copied) {
                clipboardManager.setText(AnnotatedString(message.content)); copied = true
            }
            BorizonActionChip(stringResource(R.string.share), Icons.Default.Share) { onShare(message.content) }
            if (!isUser) {
                BorizonActionChip(
                    if (isSpeakingThis) stringResource(R.string.stop) else stringResource(R.string.listen),
                    if (isSpeakingThis) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    onClick = if (isSpeakingThis) onStopSpeaking else onSpeak
                )
                if (isLastAssistant) {
                    BorizonActionChip(stringResource(R.string.retry), Icons.Default.Refresh, onClick = onRegenerate)
                }
            }
        }

        if (showTimestamp || responseDurationMs > 0) {
            val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showTimestamp) {
                    Text(text = timeFormat.format(java.util.Date(message.timestamp)),
                        style = Timestamp, color = semanticColors.chat.dateSeparatorText)
                }
                if (responseDurationMs > 0) {
                    Text(text = formatDuration(responseDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = semanticColors.chat.dateSeparatorText.copy(alpha = 0.5f))
                }
                if (message.wallClockTps > 0) {
                    Text(text = String.format("%.1f tok/s", message.wallClockTps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
internal fun BorizonActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val semanticColors = LocalBorizonSemanticColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) semanticColors.ui.chipActiveSurfaceColor else semanticColors.ui.chipSurfaceColor,
        border = BorderStroke(1.dp, semanticColors.ui.dividerColor),
        modifier = Modifier.defaultMinSize(minHeight = 32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp),
                tint = if (active) semanticColors.ui.chipActiveTextColor
                       else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (active) semanticColors.ui.chipActiveTextColor
                       else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MessageImageGrid(
    bitmaps: List<Bitmap>,
    onImageClick: (Bitmap) -> Unit = {},
) {
    if (bitmaps.size == 1) {
        val bitmap = bitmaps.first()
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.chat_shared_image),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onImageClick(bitmap) },
            contentScale = ContentScale.Fit,
        )
    } else {
        val cols = 3
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            bitmaps.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.image),
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(bitmap) },
                            contentScale = ContentScale.Crop,
                        )
                    }
                    repeat(cols - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1.0 -> "${ms} ms"
        seconds < 60.0 -> "${"%.1f".format(seconds)} s"
        else -> "${"%.1f".format(seconds / 60.0)} min"
    }
}
