package com.borizon.app.ai.inference

import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Interface for model inference backends.
 *
 * persistent conversation pattern:
 * - [loadModel] creates the engine
 * - [initConversation] creates a persistent Conversation with system prompt (once per session)
 * - [generateStream] appends to the existing conversation (reuses KV cache)
 * - [resetConversation] clears the KV cache (new chat only)
 * - [stopResponse] cancels mid-generation (preserves partial text)
 */
interface InferenceEngine {

    /**
     * Create a persistent conversation with the given system prompt.
     * Called once after load, and again after resetConversation().
     *
     * @param systemPrompt The system instruction for the conversation.
     * @param tools Optional list of ToolProviders for agentic tool calling.
     */
    suspend fun initConversation(systemPrompt: String, tools: List<ToolProvider> = emptyList())

    /**
     * Generate a complete response. Appends to the persistent conversation.
     * For streaming, prefer [generateStream].
     */
    suspend fun generate(userMessage: String): String

    /**
     * Stream tokens as they are generated.
     * Each emission is a pair of (text delta, thinking delta?).
     * Appends to the persistent conversation — KV cache is reused.
     */
    fun generateStream(userMessage: String): Flow<StreamToken>

    /**
     * Stream tokens with multimodal input (text + images).
     * Images are sent as part of the user message alongside text.
     * Uses the persistent conversation — KV cache is reused.
     */
    fun generateStreamMultimodal(text: String, imageBytes: List<ByteArray>): Flow<StreamToken>

    /**
     * Reset the conversation (clear KV cache).
     * Call this when starting a new chat session.
     * Must call [initConversation] after this before generating.
     */
    suspend fun resetConversation()

    /**
     * Stop the current generation.
     * Partial text is preserved in the conversation.
     * Triggers CancellationException in the streaming flow — not an error.
     */
    fun stopResponse()

    /**
     * One-shot generation with a separate system prompt.
     * Creates a temporary conversation, generates, closes it.
     * Use for analysis tasks that don't belong to the persistent conversation.
     */
    suspend fun analyze(systemPrompt: String, userMessage: String): String

    /**
     * Transcribe an audio file to text.
     */
    suspend fun transcribe(audioFile: File): String

    /**
     * Generate a response from audio input using the persistent conversation.
     * Sends audio + prompt directly to the model .
     * The model processes audio natively — no separate transcription step.
     *
     * @param audioBytes WAV-formatted audio bytes (header + PCM).
     * @param prompt Text prompt accompanying the audio (e.g., "Respond to this audio").
     * @return The model's text response.
     */
    suspend fun generateWithAudio(audioBytes: ByteArray, prompt: String): String

    /**
     * Stream tokens from audio input, including thinking tokens.
     * Same as [generateStreamMultimodal] but for audio content.
     *
     * @param audioBytes WAV-formatted audio bytes (header + PCM).
     * @param prompt Optional text prompt accompanying the audio.
     * @return Flow of StreamToken with text, thinking, and done signals.
     */
    fun generateStreamWithAudio(audioBytes: ByteArray, prompt: String): Flow<StreamToken>

    /**
     * Analyze an image with a text prompt. One-shot — creates a temp conversation.
     * Use for vision tasks like extracting text from photos, describing images, etc.
     *
     * @param imageBytes PNG-encoded image bytes.
     * @param prompt Text prompt to accompany the image.
     * @return The model's text response.
     */
    suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): String

    /**
     * Release all resources (engine + conversation).
     */
    fun close()
}

/**
 * A token emitted during streaming generation.
 *
 * @property text The visible text delta.
 * @property thinking The thinking/reasoning delta (from message.channels["thought"]).
 *           Null if the model isn't in thinking mode or no thinking tokens this step.
 * @property done True when generation is complete.
 */
data class StreamToken(
    val text: String,
    val thinking: String? = null,
    val done: Boolean = false,
    val tokenCount: Int = 0,
    val tokensPerSecond: Float = 0f,
    /** Number of raw tokens that were filtered as control tokens (not visible text). */
    val filteredCount: Int = 0,
)
