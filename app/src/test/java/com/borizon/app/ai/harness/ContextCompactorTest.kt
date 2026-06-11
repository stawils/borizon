package com.borizon.app.ai.harness

import com.borizon.app.data.models.ChatMessage
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {

    private fun msg(role: String, content: String) = ChatMessage(role = role, content = content)

    // ── shouldCompact ────────────────────────────────────────────

    @Test
    fun `shouldCompact returns false when projected under budget`() {
        val msgs = listOf(msg("user", "hello"), msg("assistant", "hi"))
        // estimated ≈ 100 tokens, projected = 100 + 200 + 768 = 1068, safe=2000 → false
        assertFalse(ContextCompactor(mockk(relaxed = true)).shouldCompact(msgs, 2000, 3072))
    }

    @Test
    fun `shouldCompact returns true when projected exceeds budget`() {
        val large = "x".repeat(5000) // ~1250 tokens
        val msgs = listOf(msg("user", large), msg("assistant", large))
        // estimated ≈ 2500, projected = 2500 + 200 + 768 = 3468, safe=2000 → true
        assertTrue(ContextCompactor(mockk(relaxed = true)).shouldCompact(msgs, 2000, 3072))
    }

    // ── compactionLevel ──────────────────────────────────────────

    @Test
    fun `compactionLevel returns 0 when projected under 60%`() {
        val msgs = listOf(msg("user", "hello"), msg("assistant", "hi"))
        assertEquals(0, ContextCompactor(mockk(relaxed = true)).compactionLevel(msgs, 2000, 3072))
    }

    @Test
    fun `compactionLevel returns 3 when projected over 95%`() {
        val large = "x".repeat(5000)
        val msgs = listOf(msg("user", large), msg("assistant", large))
        assertEquals(3, ContextCompactor(mockk(relaxed = true)).compactionLevel(msgs, 2000, 3072))
    }

    // ── fidelity check ───────────────────────────────────────────

    @Test
    fun `fidelity rejects summary under min length`() {
        val compactor = ContextCompactor(mockk(relaxed = true))
        // MIN_SUMMARY_LENGTH = 100
        assertFalse(compactor.testFidelity("short", "long transcript with enough chars here"))
    }

    @Test
    fun `fidelity rejects summary under 10 percent ratio`() {
        val compactor = ContextCompactor(mockk(relaxed = true))
        val transcript = "x".repeat(2000) // 2000 chars
        val summary = "y".repeat(150)      // 150 chars = 7.5% < 10%
        assertFalse(compactor.testFidelity(summary, transcript))
    }

    @Test
    fun `fidelity rejects generic summary`() {
        val compactor = ContextCompactor(mockk(relaxed = true))
        assertFalse(compactor.testFidelity("The user asked questions about various topics", "x".repeat(1000)))
    }

    @Test
    fun `fidelity accepts valid summary`() {
        val compactor = ContextCompactor(mockk(relaxed = true))
        val transcript = "x".repeat(500)
        val summary = "- User asked about weather in Tokyo\n- Assistant searched web via Brave API\n- Found temperature data: 72 degrees Fahrenheit with sunny conditions expected throughout the afternoon"
        assertTrue(compactor.testFidelity(summary, transcript))
    }

    // ── trimToLevel ──────────────────────────────────────────────

    @Test
    fun `trimToLevel drops oldest messages to meet budget`() {
        val msgs = (1..10).map { i -> msg("user", "message number $i with some extra text to fill tokens") }
        val compactor = ContextCompactor(mockk(relaxed = true))
        val result = compactor.trimToLevel(msgs, 500)
        assertNotNull(result)
        assertTrue(result!!.messagesCompacted > 0)
        assertTrue(result.initialMessages.size < 10)
    }

    @Test
    fun `trimToLevel returns null when under budget`() {
        val msgs = listOf(msg("user", "hi"), msg("assistant", "hello"))
        val compactor = ContextCompactor(mockk(relaxed = true))
        val result = compactor.trimToLevel(msgs, 5000)
        assertNull(result) // nothing to trim
    }

    @Test
    fun `trimToLevel always keeps at least 2 messages`() {
        val msgs = (1..20).map { i -> msg("user", "very long message that pushes token count up significantly number $i") }
        val compactor = ContextCompactor(mockk(relaxed = true))
        val result = compactor.trimToLevel(msgs, 200)
        assertNotNull(result)
        assertTrue(result!!.initialMessages.size >= 2)
    }
}
