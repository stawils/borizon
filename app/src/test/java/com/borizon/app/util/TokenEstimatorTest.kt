package com.borizon.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stress tests for TokenEstimator — validates unicode-aware token estimation
 * and demonstrates the Arabic/CJK undercount that the naive /4 formula produces.
 */
class TokenEstimatorTest {

    // --- Basic smoke tests ---

    @Test
    fun `empty string returns 0 tokens`() {
        assertEquals(0, TokenEstimator.estimateTextTokens(""))
    }

    @Test
    fun `non-empty string returns at least 1 token`() {
        assertTrue(TokenEstimator.estimateTextTokens("a") >= 1)
    }

    // --- English / Latin ---

    @Test
    fun `english text estimates roughly length div 4`() {
        val text = "The quick brown fox jumps over the lazy dog."
        val estimated = TokenEstimator.estimateTextTokens(text)
        // 44 chars / 4.0 = 11 tokens, expect ~10-14 range (with rounding)
        assertTrue("Expected ~11, got $estimated", estimated in 9..14)
    }

    // --- Arabic ---

    @Test
    fun `arabic text estimates higher than naive div 4`() {
        // "مرحبا بك في تطبيق بوريزون" (Welcome to Borizon app)
        val arabic = "مرحبا بك في تطبيق بوريزون"
        val estimated = TokenEstimator.estimateTextTokens(arabic)
        val naive = arabic.length / 4

        // Arabic has ~24 chars (including spaces), naive /4 = 6 tokens.
        // Arabic chars use /2.0 ratio, spaces are Latin /4.0.
        // Arabic chars alone: ~21/2.0 = 10 + spaces 3/4.0 = 0 → ~11 tokens.
        assertTrue(
            "Arabic estimate ($estimated) should be higher than naive ($naive)",
            estimated > naive
        )
        // With /2.0 ratio for Arabic chars, we get ~1.8x the naive estimate
        // (not quite 2x because spaces count as Latin)
        assertTrue(
            "Arabic estimate ($estimated) should be at least 1.5x naive ($naive)",
            estimated >= (naive * 15 / 10) // 1.5x
        )
    }

    @Test
    fun `arabic conversation undercount demonstration`() {
        // Simulates a realistic Arabic conversation turn
        val arabicResponse = """
            لقد تحققت من رسائلك ووجدت ثلاث رسائل جديدة من سارة.
            الأولى تتعلق بالاجتماع غداً في الساعة العاشرة صباحاً.
            والثانية تطلب منك مراجعة التقرير قبل نهاية اليوم.
        """.trimIndent()

        val estimated = TokenEstimator.estimateTextTokens(arabicResponse)
        val naive = arabicResponse.length / 4

        // Naive estimate will significantly undercount Arabic text.
        // This test documents the delta.
        println("Arabic response: ${arabicResponse.length} chars")
        println("  Naive /4 estimate: $naive tokens")
        println("  Unicode-aware estimate: $estimated tokens")
        println("  Delta: ${estimated - naive} tokens (+${if (naive > 0) ((estimated - naive) * 100 / naive) else 0}%)")

        assertTrue(
            "Arabic-aware ($estimated) should be at least 40% higher than naive ($naive)",
            estimated >= naive * 14 / 10  // 1.4x
        )
    }

    // --- CJK ---

    @Test
    fun `chinese text estimates higher than naive div 4`() {
        val chinese = "你好，欢迎使用博瑞松应用"
        val estimated = TokenEstimator.estimateTextTokens(chinese)
        val naive = chinese.length / 4

        // CJK has ~12 chars, naive /4 = 3 tokens.
        // CJK ratio is /1.5, so estimate should be ~8 tokens.
        assertTrue(
            "CJK estimate ($estimated) should be higher than naive ($naive)",
            estimated > naive
        )
    }

    @Test
    fun `japanese mixed kana and kanji estimates correctly`() {
        val japanese = "こんにちは。明日の会議は10時です。"
        val estimated = TokenEstimator.estimateTextTokens(japanese)
        val naive = japanese.length / 4

        assertTrue(
            "Japanese estimate ($estimated) should be higher than naive ($naive)",
            estimated > naive
        )
    }

    // --- Devanagari ---

    @Test
    fun `hindi text estimates higher than naive div 4`() {
        val hindi = "नमस्ते, आपका स्वागत है"
        val estimated = TokenEstimator.estimateTextTokens(hindi)
        val naive = hindi.length / 4

        assertTrue(
            "Hindi estimate ($estimated) should be higher than naive ($naive)",
            estimated > naive
        )
    }

    // --- Mixed script ---

    @Test
    fun `mixed arabic and english estimates correctly`() {
        val mixed = "The user said مرحبا and then continued in English about the weather"
        val estimated = TokenEstimator.estimateTextTokens(mixed)
        val naive = mixed.length / 4

        // Mixed should be somewhat higher than naive due to Arabic portion
        assertTrue(
            "Mixed estimate ($estimated) should be >= naive ($naive)",
            estimated >= naive
        )
    }

    @Test
    fun `code heavy text estimates higher`() {
        // Code with lots of punctuation and special chars
        val code = """fun main() { println("Hello, ${'$'}name!") }"""
        val estimated = TokenEstimator.estimateTextTokens(code)
        val naive = code.length / 4

        // Code has many special chars that tokenize differently.
        // Our estimator should at least match naive for code.
        assertTrue(
            "Code estimate ($estimated) should be >= naive ($naive)",
            estimated >= naive - 2 // Allow small variance
        )
    }

    // --- Full message estimation ---

    @Test
    fun `estimateTokens includes overhead for user message`() {
        val tokens = TokenEstimator.estimateTokens(
            content = "Hello",
            role = "user",
        )
        // Should include: text tokens + ROLE_OVERHEAD (30) + BASE_OVERHEAD (20) + safety margin
        assertTrue("Expected overhead, got $tokens", tokens > 50)
    }

    @Test
    fun `estimateTokens includes higher overhead for system message`() {
        val userTokens = TokenEstimator.estimateTokens(
            content = "Hello",
            role = "user",
        )
        val systemTokens = TokenEstimator.estimateTokens(
            content = "Hello",
            role = "system",
        )
        assertTrue(
            "System ($systemTokens) should be > user ($userTokens)",
            systemTokens > userTokens
        )
    }

    @Test
    fun `estimateTokens includes tool event overhead`() {
        val noTools = TokenEstimator.estimateTokens(
            content = "Result",
            toolEventCount = 0,
        )
        val withTools = TokenEstimator.estimateTokens(
            content = "Result",
            toolEventCount = 3,
        )
        assertTrue(
            "With tools ($withTools) should be much > no tools ($noTools)",
            withTools > noTools + 100
        )
    }

    @Test
    fun `estimateTokens includes thinking overhead`() {
        val noThinking = TokenEstimator.estimateTokens(content = "Hello")
        val withThinking = TokenEstimator.estimateTokens(
            content = "Hello",
            thinkingContent = "Let me think about this carefully",
        )
        assertTrue(
            "With thinking ($withThinking) should be > without ($noThinking)",
            withThinking > noThinking + 5
        )
    }

    // --- Safety margin validation ---

    @Test
    fun `safety margin makes estimates conservative`() {
        // A 100-char English string should estimate to ~25 raw tokens.
        // With 15% margin: ~29. The actual return should be >= 25.
        val text = "a".repeat(100)
        val estimated = TokenEstimator.estimateTextTokens(text)
        val rawEstimate = 100 / 4 // 25
        assertTrue(
            "With safety margin ($estimated) should be >= raw ($rawEstimate)",
            estimated >= rawEstimate
        )
    }

    // --- Edge cases ---

    @Test
    fun `emoji and special characters handled`() {
        val withEmoji = "Hello 👋 how are you? 🎉"
        // Should not crash, should return a positive number
        val estimated = TokenEstimator.estimateTextTokens(withEmoji)
        assertTrue(estimated > 0)
    }

    @Test
    fun `very long text produces proportional estimate`() {
        val short = "Hello world"
        val long = "Hello world ".repeat(100) // ~1200 chars
        val shortEst = TokenEstimator.estimateTextTokens(short)
        val longEst = TokenEstimator.estimateTextTokens(long)
        assertTrue(
            "Long ($longEst) should be >> short ($shortEst)",
            longEst > shortEst * 5
        )
    }
}
