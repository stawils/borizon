package com.borizon.app.ai.tools

import android.util.Log
import com.borizon.app.util.debugLog
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.borizon.app.data.dao.MemoryDao
import com.borizon.app.data.models.MemoryCategory
import com.borizon.app.data.models.MemoryEntry
import com.borizon.app.util.escapeLike
import com.borizon.app.ai.harness.ToolResultCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import com.borizon.app.ai.tools.ToolCallTracker

/**
 * MemoryTools — 3 dedicated methods instead of 2 action-dispatch methods.
 * E2B model sees function name and knows exactly what to do.
 */
class MemoryTools(
    private val memoryDao: MemoryDao,
    private val actionChannel: Channel<BorizonAction>,
    private val getActiveConversationId: () -> Long = { 0L },
) : ToolSet {

    companion object {
        private const val TAG = "MemoryTools"
    }

    @Tool(description = "Save a fact about the user.")
    fun memorySave(
        @ToolParam(description = "Fact to remember") content: String,
        @ToolParam(description = "PREFERENCE, FACT, RELATIONSHIP, EVENT, SKILL") category: String = "FACT",
        @ToolParam(description = "0.0-1.0 importance") importance: Float = 0.5f,
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        ToolCallTracker.increment()
        if (content.isBlank()) return@runBlocking mapOf("result" to "error", "error" to "Content is empty")
        try {
            val cat = parseCategory(category)
            val convId = getActiveConversationId()
            val memory = MemoryEntry(
                content = content,
                category = cat,
                importance = importance.coerceIn(0f, 1f),
                sourceConversationId = if (convId > 0) convId else null,
            )
            val insertId = memoryDao.insert(memory)
            memoryDao.pruneToMax()
            val id = insertId
            actionChannel.trySend(BorizonAction.Progress(
                label = "Remembered: $content",
                isInProgress = false,
                toolType = ToolType.MEMORY_SAVE,
            ))
            debugLog(TAG, "Saved memory #$id: [$cat] $content (importance=$importance)")
            mapOf("result" to "saved", "id" to id.toString(), "content" to content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memory", e)
            mapOf("result" to "error", "error" to (e.message ?: "Failed to save"))
        }
    }

    @Tool(description = "Search stored memories.")
    fun memorySearch(
        @ToolParam(description = "Keywords to search") query: String,
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        ToolCallTracker.increment()
        if (query.isBlank()) return@runBlocking mapOf("result" to "error", "error" to "Query is empty")
        try {
            val results = memoryDao.search(query.escapeLike(), limit = 10)
            if (results.isEmpty()) {
                mapOf("result" to "empty", "message" to "No memories matching '$query'")
            } else {
                try { memoryDao.incrementAccessCounts(results.map { it.id }) } catch (_: Exception) {}
                val formatted = results.joinToString("\n") { m ->
                    "[${m.category}] ${m.content} (importance: ${"%.1f".format(m.importance)})"
                }
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Found ${results.size} memories",
                    isInProgress = false,
                    toolType = ToolType.MEMORY_SEARCH,
                ))
                mapOf("result" to "found", "count" to results.size.toString(), "memories" to formatted).also {
                    ToolResultCache.put("memorySearch", formatted.take(300))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search memories", e)
            mapOf("result" to "error", "error" to (e.message ?: "Search failed"))
        }
    }

    @Tool(description = "Delete a memory by ID.")
    fun memoryForget(
        @ToolParam(description = "Memory ID to delete") memoryId: Int,
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        ToolCallTracker.increment()
        try {
            val longId = memoryId.toLong()
            val memory = memoryDao.getById(longId)
            if (memory == null) return@runBlocking mapOf("result" to "not_found", "message" to "Memory #$memoryId not found")
            memoryDao.delete(longId)
            actionChannel.trySend(BorizonAction.Progress(
                label = "Forgot: ${memory.content}",
                isInProgress = false,
                toolType = ToolType.MEMORY_FORGET,
            ))
            debugLog(TAG, "Forgot memory #$memoryId: ${memory.content}")
            mapOf("result" to "forgotten", "content" to memory.content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forget memory", e)
            mapOf("result" to "error", "error" to (e.message ?: "Delete failed"))
        }
    }

    private fun parseCategory(raw: String): MemoryCategory = try {
        MemoryCategory.valueOf(raw.uppercase().trim())
    } catch (_: Exception) {
        MemoryCategory.FACT
    }
}
