package com.borizon.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.borizon.app.R
import com.borizon.app.data.models.Conversation
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import com.borizon.app.ui.theme.Metadata
import com.borizon.app.ui.theme.SurfaceLevel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    activeConversationId: Long,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
    onSearch: (String, (List<com.borizon.app.ui.screens.BorizonViewModel.ConversationSearchResult>) -> Unit) -> Unit = { _, cb -> cb(emptyList()) },
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<com.borizon.app.ui.screens.BorizonViewModel.ConversationSearchResult>>(emptyList()) }
    val isSearching = searchQuery.isNotBlank()
    val filtered = if (searchQuery.isBlank()) conversations
        else conversations.filter { it.title.contains(searchQuery, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.drawer_history),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(20.dp))
            }
        }

        // New chat button
        OutlinedButton(
            onClick = { onNewChat(); onClose() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.drawer_new_conversation))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { query ->
                searchQuery = query
                if (query.length >= 2) {
                    onSearch(query) { results -> searchResults = results }
                } else {
                    searchResults = emptyList()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text(stringResource(R.string.drawer_search), style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.drawer_clear), modifier = Modifier.size(16.dp))
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Content search results (when searching)
        if (isSearching && searchResults.isNotEmpty()) {
            Text(
                text = "In conversations",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
            )
        }
        // Pre-resolve string resources for grouped labels
        val todayLabel = stringResource(R.string.drawer_today)
        val yesterdayLabel = stringResource(R.string.drawer_yesterday)
        val weekLabel = stringResource(R.string.drawer_previous_7_days)
        val olderLabel = stringResource(R.string.drawer_older)
        val noResultsText = stringResource(R.string.drawer_no_conversations)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Content search results first
            if (isSearching) {
                items(searchResults, key = { "search-${it.conversationId}" }) { result ->
                    SearchResultItem(
                        result = result,
                        isActive = result.conversationId == activeConversationId,
                        onClick = { onSelectConversation(result.conversationId); onClose() }
                    )
                }
                // Then title-matched conversations not already in search results
                val searchIds = searchResults.map { it.conversationId }.toSet()
                val titleOnly = filtered.filter { it.id !in searchIds }
                if (titleOnly.isNotEmpty()) {
                    item {
                        Text(
                            text = "By title",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(titleOnly, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            isActive = conversation.id == activeConversationId,
                            onClick = { onSelectConversation(conversation.id); onClose() },
                            onDelete = { onDeleteConversation(conversation.id) }
                        )
                    }
                }
            } else {
                // Normal mode: grouped conversations
                val grouped = groupConversationsByDate(
                    filtered,
                    todayLabel = todayLabel,
                    yesterdayLabel = yesterdayLabel,
                    weekLabel = weekLabel,
                    olderLabel = olderLabel,
                )
                grouped.forEach { (label, items) ->
                    item {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(items, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            isActive = conversation.id == activeConversationId,
                            onClick = { onSelectConversation(conversation.id); onClose() },
                            onDelete = { onDeleteConversation(conversation.id) }
                        )
                    }
                }
            }

            if (filtered.isEmpty() && searchResults.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Text(
                        noResultsText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: com.borizon.app.ui.screens.BorizonViewModel.ConversationSearchResult,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val semanticColors = LocalBorizonSemanticColors.current
    BorizonCard(
        surfaceLevel = if (isActive) SurfaceLevel.High else SurfaceLevel.Low,
        onClick = onClick,
        cornerSize = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    result.snippet,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.drawer_delete_title)) },
            text = { Text(stringResource(R.string.drawer_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    val semanticColors = LocalBorizonSemanticColors.current
    BorizonCard(
        surfaceLevel = if (isActive) SurfaceLevel.High else SurfaceLevel.Low,
        onClick = onClick,
        cornerSize = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatRelativeDate(conversation.updatedAt, stringResource(R.string.drawer_just_now)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    if (conversation.messageCount > 0) {
                        Text(
                            "  ${conversation.messageCount} msg${if (conversation.messageCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Delete button
            IconButton(
                onClick = { showDeleteDialog = true },
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun groupConversationsByDate(
    conversations: List<Conversation>,
    todayLabel: String,
    yesterdayLabel: String,
    weekLabel: String,
    olderLabel: String,
): Map<String, List<Conversation>> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val yesterdayStart = todayStart - 24 * 60 * 60 * 1000
    val weekStart = todayStart - 7 * 24 * 60 * 60 * 1000L

    val groups = linkedMapOf<String, MutableList<Conversation>>()
    for (conv in conversations) {
        val label = when {
            conv.updatedAt >= todayStart -> todayLabel
            conv.updatedAt >= yesterdayStart -> yesterdayLabel
            conv.updatedAt >= weekStart -> weekLabel
            else -> olderLabel
        }
        groups.getOrPut(label) { mutableListOf() }.add(conv)
    }
    return groups
}

private fun formatRelativeDate(timestamp: Long, justNowLabel: String): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60 * 1000 -> justNowLabel
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m ago"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h ago"
        diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
