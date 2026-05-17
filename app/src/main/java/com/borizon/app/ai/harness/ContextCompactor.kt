package com.borizon.app.ai.harness

import android.util.Log
import com.borizon.app.util.debugLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Role
import com.borizon.app.ai.inference.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ContextCompactor — turn-by-turn context management.
 *
 * Monitors conversation length and compacts history when it exceeds the model's budget.
 * Runs DURING the conversation to keep context within budget.
 */
class ContextCompactor(
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "ContextCompactor"
        private const val MAX_TRANSCRIPT_CHARS = 8000

        private const val COMPACTION_PROMPT = "You are a context compaction engine. Your goal is to summarize a conversation while preserving all critical facts, decisions, and emotional context. Rules: 1. Be extremely concise but factual. 2. Preserve specific proper nouns, dates, and numbers. 3. Capture the 'current state' of the discussion. 4. Output ONLY the summary text, no preamble."
    }

    data class CompactionResult(
        val initialMessages: List<com.borizon.app.data.models.ChatMessage>,
        val messagesCompacted: Int
    )

    /**
     * Determine if the context needs compaction.
     * Estimates tokens from content length (chars / 4) instead of assuming fixed 80 per message.
     */
    fun shouldCompact(messages: List<com.borizon.app.data.models.ChatMessage>, maxSafeTokens: Int): Boolean {
        val estimatedTokens = messages.sumOf { msg ->
            var tokens = (msg.content.length + msg.thinkingContent.orEmpty().length) / 4 + 50
            // Tool call/response tokens are invisible in content but occupy KV cache
            val eventCount = msg.toolEvents?.size ?: 0
            if (eventCount > 0) tokens += 200 + eventCount * 100
            tokens
        }
        return estimatedTokens >= (maxSafeTokens * 0.6).toInt()
    }

    /** No-op retained for call-site compatibility. */
    fun recordTurn() {}

    /** No-op retained for call-site compatibility. */
    fun reset() {}

    /**
     * Compact the given messages into a single summary message.
     */
    suspend fun compact(messages: List<com.borizon.app.data.models.ChatMessage>): CompactionResult? = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext null

        val transcript = buildString {
            // Build from most recent first so truncation drops oldest messages
            val reversed = messages.reversed()
            val lines = mutableListOf<String>()
            for (msg in reversed) {
                val line = "${msg.role}: ${msg.content}"
                val projectedLength = lines.sumOf { it.length + 1 } + line.length + 1
                if (projectedLength > MAX_TRANSCRIPT_CHARS) break
                lines.add(0, line)
            }
            lines.forEachIndexed { i, line ->
                if (i > 0) append('\n')
                append(line)
            }
        }

        try {
            val summary = modelManager.generateAnalysis(
                systemPrompt = COMPACTION_PROMPT,
                userMessage = "Summarize this conversation concisely:\n\n$transcript"
            )

            debugLog(TAG, "Context compacted: ${summary.take(50)}...")

            val summaryMessage = com.borizon.app.data.models.ChatMessage(
                role = "system",
                content = "Previous conversation summary: $summary",
                type = com.borizon.app.data.models.MessageType.SYSTEM
            )

            // Keep the last 2 messages for immediate context
            val lastMessages = messages.takeLast(2)

            CompactionResult(
                initialMessages = listOf(summaryMessage) + lastMessages,
                messagesCompacted = messages.size - lastMessages.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Compaction failed", e)
            null
        }
    }
}
