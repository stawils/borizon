package com.borizon.app.util

/**
 * Unicode-aware token estimator for Gemma (SentencePiece) tokenizers.
 *
 * LiteRT does not expose a tokenizer API, so we estimate from text content.
 * The naive `length / 4` approximation undercounts by 30–50% for Arabic,
 * CJK, and other non-Latin scripts because SentencePiece tokenizes these
 * into shorter subword pieces (often 1–2 chars per token vs 4+ for English).
 *
 * ## Method
 *
 * 1. Classify each character by Unicode block into a script category.
 * 2. Apply a per-script chars-per-token ratio (derived from Gemma/SentencePiece
 *    benchmarks and Gemma tokenizer documentation).
 * 3. Add per-message overhead for role tags, special tokens, and formatting.
 * 4. Tool calls add extra overhead for structured JSON in the KV cache.
 *
 * ## Ratios (chars per token)
 *
 * | Script      | Ratio | Source                              |
 * |-------------|-------|-------------------------------------|
 * | Latin       | 4.0   | Gemma tokenizer docs (English avg)  |
 * | Arabic      | 2.0   | SentencePiece Arabic benchmarks     |
 * | CJK         | 1.5   | Gemma CJK: ~1.5 chars/tok          |
 * | Devanagari  | 2.5   | SentencePiece Indic benchmarks      |
 * | Mixed/Other | 2.5   | Conservative default                |
 *
 * ## Calibration status
 *
 * TODO: CALIBRATION STUDY — These ratios need validation against actual
 * LiteRT token counts on device. Method:
 *   1. Generate responses in each target script via `generateStream`.
 *   2. Record `tokenCount` from `StreamToken.done`.
 *   3. Divide `content.length` by `tokenCount` for each response.
 *   4. Update the ratios below to match measured values.
 *   5. Also validate the per-message overhead constants (ROLE_OVERHEAD, etc.)
 *      against actual KV cache consumption.
 *
 * Target scripts: English, Arabic, Chinese, Japanese, Korean, Hindi.
 * Target: ±10% accuracy for budget estimation. Current worst case: Arabic
 * may still be off by 15–20% until calibrated.
 *
 * ## Safety margin
 *
 * All estimates include a 15% safety margin (divide by 0.85) to bias
 * toward early compaction rather than late. Compacting early wastes a
 * few tokens of context; compacting late causes context overflow and
 * empty responses.
 */
object TokenEstimator {
    // --- Per-message overhead ---
    // Role tags (<start_of_turn>user\n, <end_of_turn>, etc.) + formatting
    private const val ROLE_OVERHEAD = 30
    // System message overhead (typically longer role + special formatting)
    private const val SYSTEM_OVERHEAD = 50
    // Base overhead for any message (whitespace, newlines, etc.)
    private const val BASE_OVERHEAD = 20
    // Thinking content adds extra tokens (often heavily subword-tokenized)
    private const val THINKING_OVERHEAD = 15

    // --- Tool call overhead ---
    // <|tool_call|>json_structure<|/tool_call|> minimum overhead
    private const val TOOL_CALL_BASE = 150
    // Per-event overhead: tool name + args JSON + <|tool_outputs|>result<|/tool_outputs|>
    private const val TOOL_CALL_PER_EVENT = 80

    // --- Safety margin ---
    // Multiply estimates by this to bias toward early compaction.
    // 1.0 = no margin. 1.15 = 15% safety margin.
    private const val SAFETY_MARGIN = 1.15

    /**
     * Estimate the token count for a single message.
     *
     * @param content Message text content
     * @param thinkingContent Optional thinking/reasoning content
     * @param role Message role (affects overhead: "system" has higher overhead)
     * @param toolEventCount Number of tool events embedded in this message
     * @return Estimated token count including overhead and safety margin
     */
    fun estimateTokens(
        content: String,
        thinkingContent: String? = null,
        role: String = "user",
        toolEventCount: Int = 0,
    ): Int {
        val contentTokens = estimateTextTokens(content)
        val thinkingTokens = if (!thinkingContent.isNullOrEmpty()) {
            estimateTextTokens(thinkingContent) + THINKING_OVERHEAD
        } else 0

        val roleOverhead = if (role == "system") SYSTEM_OVERHEAD else ROLE_OVERHEAD
        val toolOverhead = if (toolEventCount > 0) {
            TOOL_CALL_BASE + toolEventCount * TOOL_CALL_PER_EVENT
        } else 0

        val raw = contentTokens + thinkingTokens + roleOverhead + BASE_OVERHEAD + toolOverhead
        return (raw * SAFETY_MARGIN).toInt()
    }

    /**
     * Estimate token count for raw text by classifying character scripts.
     *
     * Processes the string character by character, accumulating char counts
     * per script category, then divides each by the appropriate ratio.
     * This is O(n) where n is the string length.
     */
    fun estimateTextTokens(text: String): Int {
        if (text.isEmpty()) return 0

        var latinChars = 0
        var arabicChars = 0
        var cjkChars = 0
        var devanagariChars = 0
        var otherChars = 0

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                isLatin(cp)      -> latinChars++
                isArabic(cp)     -> arabicChars++
                isCJK(cp)        -> cjkChars++
                isDevanagari(cp) -> devanagariChars++
                else             -> otherChars++
            }
            i += Character.charCount(cp)
        }

        // Chars-per-token ratios per script (higher = more chars per token = fewer tokens per char)
        val tokens = (latinChars / 4.0) +
                     (arabicChars / 2.0) +
                     (cjkChars / 1.5) +
                     (devanagariChars / 2.5) +
                     (otherChars / 2.5)

        // Minimum 1 token for any non-empty text
        return tokens.toInt().coerceAtLeast(1)
    }

    // --- Script classification ---
    // These classify by Unicode code point ranges.
    // Each function handles supplementary characters correctly via codePointAt.

    private fun isLatin(cp: Int): Boolean {
        // Basic Latin, Latin-1 Supplement, Latin Extended-A/B, IPA Extensions
        // Covers English, French, German, Spanish, Portuguese, etc.
        return cp in 0x0000..0x024F ||
               cp in 0x1E00..0x1EFF || // Latin Extended Additional
               cp in 0x2C60..0x2C7F || // Latin Extended-C
               cp in 0xA720..0xA7FF || // Latin Extended-D
               cp in 0xAB30..0xAB6F    // Latin Extended-E
    }

    private fun isArabic(cp: Int): Boolean {
        // Arabic, Arabic Supplement, Arabic Extended-A/B, Arabic Presentation Forms
        return cp in 0x0600..0x06FF ||
               cp in 0x0750..0x077F || // Arabic Supplement
               cp in 0x08A0..0x08FF || // Arabic Extended-A
               cp in 0xFB50..0xFDFF || // Arabic Presentation Forms-A
               cp in 0xFE70..0xFEFF    // Arabic Presentation Forms-B
    }

    private fun isCJK(cp: Int): Boolean {
        // CJK Unified Ideographs (Chinese, Japanese Kanji, Korean Hanja)
        // + Hiragana + Katakana + Hangul Syllables
        return cp in 0x4E00..0x9FFF ||  // CJK Unified Ideographs
               cp in 0x3400..0x4DBF ||  // CJK Unified Ideographs Extension A
               cp in 0x3040..0x309F ||  // Hiragana
               cp in 0x30A0..0x30FF ||  // Katakana
               cp in 0xAC00..0xD7AF ||  // Hangul Syllables
               cp in 0xFF00..0xFFEF     // Halfwidth and Fullwidth Forms
    }

    private fun isDevanagari(cp: Int): Boolean {
        // Devanagari + Devanagari Extended
        return cp in 0x0900..0x097F ||
               cp in 0xA8E0..0xA8FF ||
               cp in 0x1CD0..0x1CFF    // Vedic Extensions (used with Devanagari)
    }
}
