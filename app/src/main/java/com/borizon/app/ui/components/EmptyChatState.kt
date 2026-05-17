package com.borizon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.borizon.app.R
import com.borizon.app.ai.prompts.StarterTemplate
import com.borizon.app.ui.theme.LocalBorizonSemanticColors

/**
 * Clean empty state — greeting + 4 suggested prompts.
 * Minimal, inviting, not overwhelming.
 */
@Composable
internal fun EmptyChatState(
    onNewChat: (StarterTemplate) -> Unit = {},
    onPopulateInput: (String) -> Unit = {},
) {
    val semanticColors = LocalBorizonSemanticColors.current
    val accentColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            // Avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.chat_suggestions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.chat_suggestions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = semanticColors.ui.emptyStateDescColor,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 4 suggested prompts — clean list, no grid
            SuggestionButton(
                text = "What do you know about me?",
                onClick = { onPopulateInput("What do you know about me?") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SuggestionButton(
                text = "Read my last notifications",
                onClick = { onPopulateInput("Read my last notifications") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SuggestionButton(
                text = "What's on my calendar?",
                onClick = { onPopulateInput("What's on my calendar today?") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SuggestionButton(
                text = "Search the web for weather",
                onClick = { onPopulateInput("Search the web for weather this weekend") }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Subtle hint
            Text(
                text = "Or just start typing",
                style = MaterialTheme.typography.bodySmall,
                color = semanticColors.ui.emptyStateDescColor.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SuggestionButton(
    text: String,
    onClick: () -> Unit,
) {
    val semanticColors = LocalBorizonSemanticColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = semanticColors.ui.chipSurfaceColor,
        border = BorderStroke(1.dp, semanticColors.ui.dividerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}
