package com.borizon.app.ai.harness

/**
 * Pure function — no side effects, no dependencies, fully unit-testable.
 *
 * Detects acknowledgment-only responses from the model — when it says it will
 * do something but doesn't actually call any tools.
 *
 * ONLY call when ToolCallTracker reports zero tool executions — if tools were
 * called, the agent loop should stop via structural detection regardless of text.
 *
 * Decision flow:
 *   1. Length gate (>200 chars → substantive, not ack)
 *   2. Substance signals (digits, units, URLs, reasoning words, multiple sentences)
 *   3. Ack pattern match (anchored regexes for common ack-only phrasings)
 */
object AckDetector {

    /**
     * Returns true if the text appears to be an acknowledgment without action.
     *
     * Biased toward false-negatives (missed acks) over false-positives:
     * - A missed ack → user sees vapid response, retries → recoverable
     * - A false positive → wastes a force-turn iteration → costs tokens and time
     */
    fun isAcknowledgment(text: String): Boolean {
        // --- Layer 1: Length gate ---
        if (text.length > 200) return false
        val lower = text.lowercase().trim()

        // --- Layer 2: Substance signals ---
        val hasSubstance = listOf(
            Regex("\\d"),
            Regex("\\b(kb|mb|gb|tb|%|am|pm|hours?|minutes?|seconds?)\\b"),
            Regex("http"),
            Regex("[.!?].+[.!?]"),
            Regex("\\b(because|however|but |although|therefore|so |means|found)\\b"),
        ).any { it.containsMatchIn(lower) }
        if (hasSubstance) return false

        // --- Layer 3: Acknowledgment patterns ---
        val ackPatterns = listOf(
            Regex("^i'?ll (check|look|find|get|do|prepare|run|start|grab|pull|fetch|search|scan)"),
            Regex("^let me (check|look|find|get|run|start|prepare|grab|pull|fetch|search|scan)"),
            Regex("^(sure|okay|of course|absolutely|great|got it|done|will do|on it|roger)[,.!]?$"),
            Regex("^(on it|working on it|coming right up|coming up)"),
            Regex("^give me (a )?(sec|second|moment|minute)"),
            Regex("^(one moment|just a (sec|second|moment|minute))"),
        )
        return ackPatterns.any { it.containsMatchIn(lower) }
    }
}
