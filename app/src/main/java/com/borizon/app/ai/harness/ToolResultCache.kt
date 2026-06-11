package com.borizon.app.ai.harness

import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the last tool return values for enrichment during context compaction.
 *
 * Each @Tool method should call [put] with its tool name and result text.
 * During compaction, [getDigest] provides the actual data the model found —
 * not just tool type labels — so post-compaction queries like "What was the
 * weather you found?" remain answerable.
 */
object ToolResultCache {
    private val cache = ConcurrentHashMap<String, String>()

    /** Register a tool's result. Called from each @Tool method after execution. */
    fun put(toolName: String, result: String) {
        cache[toolName] = result.take(300) // Cap per result to avoid memory bloat
    }

    /** Get a compact digest of all recent tool results for compaction enrichment. */
    fun getDigest(): String {
        if (cache.isEmpty()) return ""
        return cache.entries.joinToString("\n") { (name, result) ->
            "- $name → ${result.take(200)}"
        }
    }

    /** Clear all cached results (call on new conversation). */
    fun clear() {
        cache.clear()
    }
}
