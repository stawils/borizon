package com.borizon.app.ai.harness

import android.util.Log
import com.borizon.app.util.debugLog
import com.borizon.app.ai.inference.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        /** Minimum acceptable summary length. Shorter likely means the model
         *  produced a vapid or failed summary (e.g. "The user asked questions."). */
        private const val MIN_SUMMARY_LENGTH = 100
        /** If a summary is below this length relative to the source transcript,
         *  it's likely too lossy. 10% minimum compression floor. */
        private const val MIN_SUMMARY_RATIO = 0.10f

        /**
         * Structured compaction prompt. Tells the model exactly what categories
         * of information to preserve, with concrete examples.
         *
         * The prompt is ordered by priority: facts > decisions > tool results > context.
         * This ordering matters because Gemma tends to front-load its output
         * and may truncate if the summary budget is tight.
         */
        private const val COMPACTION_PROMPT = """You are a context compaction engine. Summarize this conversation for an AI assistant that will continue it.

Preserve in this order of priority:
1. FACTS: specific names, numbers, dates, locations, amounts. "User's sister is Sara, lives in Amman".
2. DECISIONS: what the user chose or agreed to. "User wants dark mode off for photos".
3. TOOL RESULTS: key data from tool calls — weather data, search results, contact info, calendar events.
4. STATE: what the assistant was doing or about to do. "Was checking SMS from Ahmed about Friday meeting".
5. TOPICS: what was discussed, in order.

Rules:
- Use bullet points, one fact per line.
- Preserve exact values: don't round numbers or paraphrase names.
- If tool results contain data the user referenced, include it.
- Omit greetings, acknowledgments, and pleasantries.
- Output ONLY the summary, no preamble."""
    }

    data class CompactionResult(
        val initialMessages: List<com.borizon.app.data.models.ChatMessage>,
        val messagesCompacted: Int
    )

    /**
     * Determine if the context needs compaction.
     * Uses [com.borizon.app.util.TokenEstimator] for unicode-aware per-script token estimation.
     * See TokenEstimator docs for calibration status and accuracy targets.
     */
    fun shouldCompact(messages: List<com.borizon.app.data.models.ChatMessage>, maxSafeTokens: Int): Boolean {
        val estimatedTokens = messages.sumOf { msg ->
            com.borizon.app.util.TokenEstimator.estimateTokens(
                content = msg.content,
                thinkingContent = msg.thinkingContent,
                role = msg.role,
                toolEventCount = msg.toolEvents?.size ?: 0,
            )
        }
        return estimatedTokens >= maxSafeTokens
    }

    /** No-op retained for call-site compatibility. */
    fun recordTurn() {}

    /** No-op retained for call-site compatibility. */
    fun reset() {}

    /**
     * Compact the given messages into a single summary message.
     *
     * Compaction pipeline:
     *   1. Build transcript from messages (most-recent-biased, capped at MAX_TRANSCRIPT_CHARS)
     *   2. Enrich transcript with tool event summaries (data the model needs to recall)
     *   3. Generate summary via model (generateAnalysis, 30s timeout)
     *   4. Fidelity check: reject summaries that are too short or too lossy
     *   5. Return summary + [keepCount] messages for immediate context
     *
     * @param keepCount Number of recent messages to keep after the summary.
     *   Caller determines this based on token budget to prevent kept messages
     *   from exceeding available context.
     * @return CompactionResult, or null if compaction failed or failed fidelity checks.
     */
    suspend fun compact(
        messages: List<com.borizon.app.data.models.ChatMessage>,
        keepCount: Int = 2,
    ): CompactionResult? = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext null

        // --- Step 1: Build transcript (most-recent-biased) ---
        val transcript = buildTranscript(messages)

        if (transcript.isBlank()) {
            debugLog(TAG, "Transcript is empty, skipping compaction")
            return@withContext null
        }

        // --- Step 2: Enrich with tool event summaries ---
        // Tool events contain data the model produced/used but aren't in the text content.
        // Without this, "What was the weather you showed me?" is unanswerable after compaction.
        val toolDigest = buildToolDigest(messages)
        val enrichedTranscript = if (toolDigest.isNotBlank()) {
            "$transcript\n\n[TOOL RESULTS TO PRESERVE:]\n$toolDigest"
        } else {
            transcript
        }

        // --- Step 3: Generate summary ---
        val summary = try {
            modelManager.generateAnalysis(
                systemPrompt = COMPACTION_PROMPT,
                userMessage = "Summarize this conversation for continuation:\n\n$enrichedTranscript"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Compaction model call failed", e)
            return@withContext null
        }

        // --- Step 4: Fidelity checks ---
        if (!passesFidelityCheck(summary, enrichedTranscript)) {
            Log.w(TAG, "Compaction summary failed fidelity check (len=${summary.length}, transcript=${enrichedTranscript.length})")
            return@withContext null
        }

        debugLog(TAG, "Context compacted: ${summary.take(80)}...")

        val summaryMessage = com.borizon.app.data.models.ChatMessage(
            role = "system",
            content = "Previous conversation summary: $summary",
            type = com.borizon.app.data.models.MessageType.SYSTEM
        )

        // Keep the last 2 messages for immediate context
        val lastMessages = messages.takeLast(keepCount)

        CompactionResult(
            initialMessages = listOf(summaryMessage) + lastMessages,
            messagesCompacted = messages.size - lastMessages.size
        )
    }

    /**
     * Build a transcript from messages, most-recent-biased, capped at MAX_TRANSCRIPT_CHARS.
     * Older messages are dropped first to keep the most relevant context.
     */
    private fun buildTranscript(messages: List<com.borizon.app.data.models.ChatMessage>): String {
        return buildString {
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
    }

    /**
     * Extract key data from tool events across all messages.
     * This is the data that would be silently lost during compaction
     * because it lives in toolEvents, not in message content.
     *
     * Produces a compact digest of tool types and labels so the model
     * knows what data it previously accessed.
     */
    private fun buildToolDigest(messages: List<com.borizon.app.data.models.ChatMessage>): String {
        val events = messages.flatMap { msg ->
            (msg.toolEvents ?: emptyList()).map { event ->
                "${event.toolType.name}: ${event.detailDescription.ifBlank { event.label }}"
            }
        }
        if (events.isEmpty()) return ""
        return events.joinToString("\n") { "- $it" }
    }

    /**
     * Fidelity check: verify the summary is substantive enough to be useful.
     *
     * Rejects summaries that are:
     * - Too short (< 100 chars) — likely vapid or failed generation
     * - Too compressed (< 10% of transcript length) — likely dropped important details
     * - Containing generic fallback phrases ("The user asked questions")
     *
     * Returns true if the summary passes all checks.
     */
    private fun passesFidelityCheck(summary: String, transcript: String): Boolean {
        if (summary.isBlank()) return false
        if (summary.length < MIN_SUMMARY_LENGTH) {
            debugLog(TAG, "Fidelity fail: summary too short (${summary.length} < $MIN_SUMMARY_LENGTH)")
            return false
        }
        val ratio = summary.length.toFloat() / transcript.length.toFloat()
        if (ratio < MIN_SUMMARY_RATIO) {
            debugLog(TAG, "Fidelity fail: compression ratio too high ($ratio < $MIN_SUMMARY_RATIO)")
            return false
        }
        // Detect generic/fallback summaries that add no information
        val genericPhrases = listOf(
            "The user asked questions",
            "The assistant responded",
            "The conversation was about",
            "Summary unavailable",
        )
        val lower = summary.lowercase()
        for (phrase in genericPhrases) {
            if (lower.startsWith(phrase.lowercase())) {
                debugLog(TAG, "Fidelity fail: generic summary detected: '$phrase'")
                return false
            }
        }
        return true
    }
}
