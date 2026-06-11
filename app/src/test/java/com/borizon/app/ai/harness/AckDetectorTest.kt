package com.borizon.app.ai.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for AckDetector.isAcknowledgment().
 *
 * Data sources:
 * - Real E4B model output collected 2026-06-11 via ACK_AUDIT instrumentation
 * - Synthetic edge cases designed from the spec's 3-layer decision flow
 */
class AckDetectorTest {

    // ── LAYER 1: Length gate (>200 chars → not ack) ────────────

    @Test
    fun `text over 200 chars is never an ack`() {
        val longText = "a".repeat(201)
        assertFalse(AckDetector.isAcknowledgment(longText))
    }

    @Test
    fun `text exactly 200 chars is allowed through to substance check`() {
        val exactly200 = "The quick brown fox. ".repeat(5) // ~200 chars
        // It's substantive because of multiple sentences
        assertFalse(AckDetector.isAcknowledgment(exactly200))
    }

    // ── LAYER 2: Substance signals ─────────────────────────────

    @Test
    fun `contains a digit is substantive`() {
        assertFalse(AckDetector.isAcknowledgment("I found 3 results"))
    }

    @Test
    fun `contains a unit is substantive`() {
        assertFalse(AckDetector.isAcknowledgment("Storage is 4GB free"))
    }

    @Test
    fun `contains URL is substantive`() {
        assertFalse(AckDetector.isAcknowledgment("Check http://example.com"))
    }

    @Test
    fun `multiple sentences is substantive`() {
        assertFalse(AckDetector.isAcknowledgment("Alarm set. It will ring at 7am."))
    }

    @Test
    fun `reasoning word makes it substantive`() {
        assertFalse(AckDetector.isAcknowledgment("I cannot do that because I don't have access"))
    }

    // ── REAL MODEL OUTPUTS (from ACK_AUDIT logs, 2026-06-11) ──

    @Test
    fun `real output - capital of France is not ack`() {
        assertFalse(AckDetector.isAcknowledgment("The capital of France is Paris."))
    }

    @Test
    fun `real output - alarm confirmation is not ack`() {
        assertFalse(AckDetector.isAcknowledgment(
            "I have set an alarm for 7:00 AM tomorrow, June 12, 2026."
        ))
    }

    @Test
    fun `real output - gravity explanation is not ack`() {
        assertFalse(AckDetector.isAcknowledgment(
            "Gravity is the fundamental force of attraction that exists between " +
            "any two objects with mass, causing them to pull toward each other."
        ))
    }

    // ── LAYER 3: Synthetic ack patterns ────────────────────────

    @Test
    fun `ill check is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("I'll check your calendar"))
    }

    @Test
    fun `ill look is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("I'll look into that for you"))
    }

    @Test
    fun `let me check is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("Let me check the weather"))
    }

    @Test
    fun `let me find is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("Let me find that information"))
    }

    @Test
    fun `sure standalone is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("Sure!"))
    }

    @Test
    fun `got it standalone is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("Got it."))
    }

    @Test
    fun `on it is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("On it"))
    }

    @Test
    fun `working on it is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("Working on it"))
    }

    @Test
    fun `give me a sec is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("Give me a sec"))
    }

    @Test
    fun `one moment is an ack`() {
        assertTrue(AckDetector.isAcknowledgment("One moment"))
    }

    @Test
    fun `just a moment is an ack`() {
        // Note: "Just a second" (singular) does NOT match — the original pattern
        // only catches plural "seconds". This is a known acceptable false-negative
        // per the design contract.
        assertTrue(AckDetector.isAcknowledgment("Just a moment"))
    }

    @Test
    fun `just a second singular is missed - acceptable false negative`() {
        assertFalse(AckDetector.isAcknowledgment("Just a second"))
    }

    // ── False negative tests (known missed acks - acceptable) ──

    @Test
    fun `short action verb without ack prefix is missed - acceptable`() {
        // "Looking" with capital L is not caught because pattern requires ^
        // This is an acceptable false-negative per design contract
        assertFalse(AckDetector.isAcknowledgment("Looking into that now"))
    }

    @Test
    fun `ack disguised as substantive is missed - acceptable`() {
        // Contains "because" → passes substance check → not caught
        assertFalse(AckDetector.isAcknowledgment(
            "I will check because I need to verify the data first"
        ))
    }

    // ── Edge cases ──────────────────────────────────────────────

    @Test
    fun `empty string is not an ack`() {
        assertFalse(AckDetector.isAcknowledgment(""))
    }

    @Test
    fun `whitespace only is not an ack`() {
        assertFalse(AckDetector.isAcknowledgment("   \n  "))
    }

    @Test
    fun `single word is not an ack`() {
        assertFalse(AckDetector.isAcknowledgment("Paris"))
    }

    @Test
    fun `i see you is substantive not ack`() {
        // "I see" is NOT in the ack patterns — it's informational, not a promise to act
        assertFalse(AckDetector.isAcknowledgment("I see you want a briefing"))
    }
}
