package com.borizon.app.ai.tools

import android.content.ComponentName
import android.content.Context
import com.borizon.app.ai.notifications.BorizonNotificationListener
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.borizon.app.data.dao.NotificationDao
import com.borizon.app.util.escapeLike
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import com.borizon.app.ai.tools.ToolCallTracker

/**
 * Notification tools — read and search notification history.
 *
 * Requires notification listener permission granted by user via system settings.
 */
class NotificationTools(
    private val notificationDao: NotificationDao,
    private val actionChannel: Channel<BorizonAction>,
    private val context: Context,
) : ToolSet {

    companion object {
        private const val TAG = "NotificationTools"
    }

    private fun isListenerEnabled(): Boolean {
        val cn = ComponentName(context, BorizonNotificationListener::class.java)
        val enabledListeners = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        // Parse colon-separated component flat names
        return enabledListeners.split(':').any { it.trim() == cn.flattenToString() }
    }

    @Tool(description = "Read or search notifications.")
    fun readRecentNotifications(
        @ToolParam(description = "Max 1-30 results") limit: Int,
        @ToolParam(description = "Search query, empty for recent") query: String = "",
    ): Map<String, String> {
        ToolCallTracker.increment()
        if (!isListenerEnabled()) {
            return mapOf("result" to "error", "error" to "Notification access is not enabled. Ask the user to enable it in Settings > Notification Access.")
        }
        val actualLimit = limit.coerceIn(1, 30)
        return try {
            val results = if (query.isNotBlank()) {
                runBlocking(Dispatchers.IO) { notificationDao.search(query.escapeLike()) }.take(actualLimit)
            } else {
                runBlocking(Dispatchers.IO) { notificationDao.getRecent(actualLimit) }
            }
            if (results.isEmpty()) {
                val hint = if (query.isNotBlank()) "matching '$query'" else "stored. Make sure notification access is enabled in Settings"
                mapOf("result" to "empty", "message" to "No notifications $hint")
            } else {
                val formatted = results.joinToString("\n") { n ->
                    "[${n.packageName}] ${n.title}: ${n.text}"
                }
                val label = if (query.isNotBlank()) "Found ${results.size} notifications" else "Read ${results.size} notifications"
                actionChannel.trySend(BorizonAction.Progress(
                    label = label,
                    isInProgress = false,
                    toolType = ToolType.NOTIFICATION_READ,
                ))
                mapOf("result" to "ok", "count" to results.size.toString(), "notifications" to formatted)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read notifications", e)
            mapOf("result" to "error", "error" to (e.message ?: "Failed to read"))
        }
    }
}
