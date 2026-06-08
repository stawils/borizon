package com.borizon.app.ai.inference

import android.content.Context
import android.util.Log
import com.borizon.app.util.debugLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiteRT-LM inference engine for Gemma 4 E4B.
 *
 *  persistent conversation pattern:
 * - ONE Conversation is kept alive across all messages (KV cache reuse)
 * - System prompt is set once in [initConversation], not per-message
 * - Constrained decoding enabled for faster token selection
 * - [stopResponse] calls cancelProcess() and preserves partial text
 * - GPU-first with CPU fallback; NPU support via [preferredBackend]
 *
 */
class LiteRTInferenceEngine(
    private val context: Context,
    private val modelFile: File,
    private val preferredBackend: Backend? = null,
    private val maxNumTokens: Int = 3072,
    private val enableMtp: Boolean = true,
) : InferenceEngine {

    companion object {
        private const val TAG = "LiteRTInference"

        /** Check if the model file supports speculative decoding (MTP). */
        fun supportsMtp(modelPath: String): Boolean = try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (_: Exception) { false }
    }

    private val closed = AtomicBoolean(false)

    /** Mutex to prevent concurrent conversation creation (LiteRT supports only one session). */
    private val conversationLock = Mutex()

    /** Mutex to prevent analyze() from closing conversation while streaming is active.
     *  All inference operations acquire this for their entire duration. */
    private val generationMutex = Mutex()

    /** The LiteRT Engine — created once in init, never recreated unless GPU→CPU fallback. */
    @Volatile private var engine: Engine? = null
    @Volatile private var _visionBackend: Backend? = null
    /** Whether this engine supports vision (image input). E4B has 3 vision signatures which LiteRT rejects. */
    val supportsVision: Boolean
        get() = _visionBackend != null

    /** The persistent Conversation — created in initConversation, reused across messages. */
    @Volatile
    private var conversation: Conversation? = null

    /** Generation counter — tracks how many times the engine has been recreated.
     *  Prevents closing a conversation from a dead engine (native SIGSEGV). */
    private var engineGeneration: Long = 0
    private var conversationGeneration: Long = -1

    /** System prompt set during initConversation. Cached to avoid rebuilds. */
    private var cachedSystemPrompt: String = ""

    /** Cached tools for GPU→CPU fallback. */
    private var cachedTools: List<ToolProvider> = emptyList()

    /** Whether thinking mode is enabled (controlled by ModelConfig). */
    var enableThinking: Boolean = false
        private set

    /** Whether the engine is currently running on GPU. */
    var isGpu = false
        private set

    /** Whether the engine is running on NPU. */
    var isNpu = false
        private set

    /** Set to true when GPU/NPU fails at runtime and falls back to CPU. ModelManager persists this. */
    val fellBackToCpu = AtomicBoolean(false)

    /** Whether MTP (speculative decoding) is actually active on the current engine. */
    var isMtpActive: Boolean = false
        private set

    /** Whether the model file supports MTP (checked once at load time). */
    var modelSupportsMtp: Boolean = false
        private set

    /** Cached sampler config for GPU→CPU fallback re-init. */
    private var cachedSamplerConfig: SamplerConfig? = null

    /**
     * Streaming state machine for Gemma 4 thinking output.
     * Tracks whether we're inside a <|channel>thought block in the raw token stream.
     * The LiteRT SDK does NOT parse channel tags into message.channels — everything
     * comes through message.toString() as raw text tokens.
     *
     * States:
     *   TEXT       — normal response text
     *   CHANNEL_OPEN  — saw "<|channel>", waiting for channel name
     *   THINKING   — inside <|channel>thought block, tokens are thinking content
     *   CHANNEL_CLOSE — saw "<channel|>", back to normal text
     */
    private enum class StreamState { TEXT, CHANNEL_OPEN, THINKING }

    /** Per-stream state machine for parsing <|channel>thought blocks. */
    private class StreamParser {
        var state: StreamState = StreamState.TEXT
        val tagBuffer = StringBuilder()

        fun parse(rawText: String): String {
            if (rawText.startsWith("<ctrl") || rawText == "<pad>" ||
                rawText.startsWith("<|tool_call") || rawText.startsWith("</|tool_call") ||
                rawText.startsWith("<|tool_outputs") || rawText.startsWith("</|tool_outputs") ||
                rawText == "<|tool_call|>" || rawText == "</|tool_call|>" ||
                rawText == "<|tool_outputs|>" || rawText == "</|tool_outputs|>" ||
                rawText == "<|channel>" || rawText == "<channel|>"
            ) {
                return ""
            }

            tagBuffer.append(rawText)
            val buf = tagBuffer.toString()
            var textDelta = ""
            var consumed = 0

            while (consumed < buf.length) {
                when (state) {
                    StreamState.TEXT -> {
                        val openIdx = buf.indexOf("<|channel>", consumed)
                        if (openIdx == -1) {
                            textDelta += buf.substring(consumed)
                            consumed = buf.length
                        } else {
                            textDelta += buf.substring(consumed, openIdx)
                            consumed = openIdx + "<|channel>".length
                            state = StreamState.CHANNEL_OPEN
                        }
                    }
                    StreamState.CHANNEL_OPEN -> {
                        if (consumed < buf.length) {
                            val remaining = buf.substring(consumed)
                            if (remaining.startsWith("thought")) {
                                consumed += "thought".length
                                if (consumed < buf.length && buf[consumed] == '\n') consumed++
                                state = StreamState.THINKING
                            } else if (remaining.startsWith("\n") || remaining.startsWith("<channel|>")) {
                                val closeIdx = buf.indexOf("<channel|>", consumed)
                                if (closeIdx != -1) consumed = closeIdx + "<channel|>".length
                                state = StreamState.TEXT
                            } else {
                                break
                            }
                        }
                    }
                    StreamState.THINKING -> {
                        val closeIdx = buf.indexOf("<channel|>", consumed)
                        if (closeIdx == -1) {
                            consumed = buf.length
                        } else {
                            consumed = closeIdx + "<channel|>".length
                            state = StreamState.TEXT
                        }
                    }
                }
            }
            tagBuffer.delete(0, consumed)
            return textDelta
        }
    }

    /** Model init state machine  */
    enum class InitState {
        NOT_INITIALIZED, INITIALIZING, INITIALIZED, ERROR
    }

    private val _initState = AtomicBoolean(false) // true = initialized
    val isInitialized: Boolean get() = _initState.get()

    init {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
    }

    /**
     * Load the engine with GPU → CPU fallback (NPU only if explicitly selected).
     * Skips NPU by default to avoid wasting ~2.5GB on failed attempts before fallback.
     * Explicitly closes failed engines to free native memory immediately.
     */
    fun loadEngine() {
        if (engine != null) return
        engineGeneration++

        val wantNpu = preferredBackend is Backend.NPU
        if (wantNpu) {
            val npu = tryCreateEngine(Backend.NPU(), Backend.CPU(), "NPU")
            if (npu != null) { isNpu = true; engine = npu; return }
            debugLog(TAG, "NPU failed, falling back to GPU")
        }

        val gpu = tryCreateEngine(Backend.GPU(), Backend.GPU(), "GPU")
        if (gpu != null) { isGpu = true; engine = gpu; return }

        Log.w(TAG, "GPU failed, falling back to CPU")
        engine = tryCreateEngine(Backend.CPU(), null, "CPU")
            ?: throw IllegalStateException("All engine backends failed")
        isGpu = false
        isNpu = false
    }

    private fun tryCreateEngine(
        backend: Backend,
        visionBackend: Backend?,
        label: String,
    ): Engine? {
        return try {
            _visionBackend = visionBackend
            // Check MTP support from model file (once per load cycle)
            if (!modelSupportsMtp) {
                modelSupportsMtp = supportsMtp(modelFile.absolutePath)
                Log.i(TAG, "MTP: model file hasSpeculativeDecodingSupport = $modelSupportsMtp (file=${modelFile.name}, size=${modelFile.length() / 1024 / 1024}MB)")
            }
            // Enable speculative decoding (MTP) if supported, requested, and backend is CPU/GPU.
            // MTP is NOT supported on NPU per LiteRT-LM docs.
            val useMtp = enableMtp && modelSupportsMtp && backend !is Backend.NPU
            Log.i(TAG, "MTP decision: enableMtp=$enableMtp, modelSupports=$modelSupportsMtp, backend=$label → useMtp=$useMtp")
            if (useMtp) {
                @OptIn(ExperimentalApi::class)
                ExperimentalFlags.enableSpeculativeDecoding = true
            }
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                visionBackend = visionBackend,
                audioBackend = Backend.CPU(),
                maxNumTokens = maxNumTokens,
            )
            val eng = Engine(config)
            eng.initialize()
            // Reset flag immediately — it was read at Engine() construction time
            @OptIn(ExperimentalApi::class)
            ExperimentalFlags.enableSpeculativeDecoding = false
            isMtpActive = useMtp
            debugLog(TAG, "Engine initialized ($label) with ${modelFile.name}, maxTokens=$maxNumTokens, MTP=$useMtp")
            eng
        } catch (e: Exception) {
            @OptIn(ExperimentalApi::class)
            ExperimentalFlags.enableSpeculativeDecoding = false
            _visionBackend = null
            isMtpActive = false
            debugLog(TAG, "$label init failed: ${e.message}")
            null
        }
    }

    /**
     * Switch from GPU/NPU to CPU after a runtime failure.
     * Returns true if fallback happened.
     */
    @OptIn(ExperimentalApi::class)
    private fun fallbackToCpu(): Boolean {
        if (!isGpu && !isNpu) return false
        Log.w(TAG, "${if (isNpu) "NPU" else "GPU"} inference failed — switching to CPU")
        fellBackToCpu.set(true)
        // Don't close old conversation — it belongs to the dead engine and would SIGSEGV
        conversation = null
        conversationGeneration = -1
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        try { engine?.close() } catch (_: Exception) {}
        engineGeneration++
        engine = tryCreateEngine(Backend.CPU(), null, "CPU")
        isGpu = false
        isNpu = false
        return engine != null
    }

    /**
     * Create a persistent conversation with the given system prompt.
     * Called once after load, and again after resetConversation().
     *
     * @param systemPrompt The system instruction for the conversation.
     * @param tools Optional list of ToolProviders for agentic tool calling.
     */
    @OptIn(ExperimentalApi::class)
    override suspend fun initConversation(
        systemPrompt: String,
        tools: List<ToolProvider>
    ): Unit = withContext(Dispatchers.IO) {
        // Delegate to the full version with default params
        initConversation(systemPrompt, tools, samplerConfig = null, thinkingEnabled = false)
    }

    /**
     * Full version with all options, used by ModelManager.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun initConversation(
        systemPrompt: String,
        tools: List<ToolProvider>,
        samplerConfig: SamplerConfig? = null,
        thinkingEnabled: Boolean = true,
        initialMessages: List<com.google.ai.edge.litertlm.Message>? = null,
    ): Unit = generationMutex.withLock {
        conversationLock.withLock {
            initConversationLocked(systemPrompt, tools, samplerConfig, thinkingEnabled, initialMessages)
        }
    }

    /** Internal: creates conversation without acquiring conversationLock. Caller must hold the lock. */
    @OptIn(ExperimentalApi::class)
    private fun initConversationLocked(
        systemPrompt: String,
        tools: List<ToolProvider>,
        samplerConfig: SamplerConfig? = null,
        thinkingEnabled: Boolean = true,
        initialMessages: List<com.google.ai.edge.litertlm.Message>? = null,
    ) {
        cachedTools = tools
        cachedSamplerConfig = samplerConfig
        if (closed.get()) error("Engine closed")

        // Close existing conversation only if it belongs to the current engine generation
        // Calling close() on a conversation from a dead engine causes native SIGSEGV
        if (conversation != null && conversationGeneration == engineGeneration) {
            try { conversation?.close() } catch (_: Exception) {}
        }
        conversation = null

        val eng = engine ?: error("Engine not loaded — call loadEngine() first")
        cachedSystemPrompt = systemPrompt
        enableThinking = thinkingEnabled

        // Stream parsing state is now per-stream via StreamParser — no shared reset needed

        // Enable constrained decoding — required for reliable tool calling 
        val hasTools = tools.isNotEmpty()
        if (hasTools) {
            ExperimentalFlags.enableConversationConstrainedDecoding = true
        }
        val systemContents = if (systemPrompt.isNotBlank()) Contents.of(systemPrompt) else null
        // NPU handles sampling internally — pass null SamplerConfig 
        val resolvedSampler = if (isNpu) null else (samplerConfig ?: SamplerConfig(
            topK = 40,
            topP = 0.90,
            temperature = 0.75,
        ))
        val conversationConfig = ConversationConfig(
            systemInstruction = systemContents,
            samplerConfig = resolvedSampler,
            tools = tools,
            initialMessages = initialMessages ?: emptyList(),
            automaticToolCalling = true,
        )

        Log.i(TAG, "Creating conversation: automaticToolCalling=true, tools=${tools.size}")

        conversation = eng.createConversation(conversationConfig)
        conversationGeneration = engineGeneration
        // Flag stays true for the conversation lifetime — cleared on reset/close/fallback

        _initState.set(true)
        val msgCount = initialMessages?.size ?: 0
        debugLog(TAG, "Conversation initialized with system prompt (${systemPrompt.length} chars), ${tools.size} tools, thinking=$thinkingEnabled, history=$msgCount messages")
    }

    override suspend fun generate(userMessage: String): String = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            if (closed.get()) error("Engine closed")
            val conv =
                conversation ?: error("Conversation not initialized — call initConversation() first")

            try {
                val response = StringBuilder()
                debugLog(TAG, "Generating (${if (isGpu) "GPU" else "CPU"})...")
                conv.sendMessageAsync(userMessage, thinkingContext ?: emptyMap())
                    .collect { message ->
                        val text = extractText(message)
                        if (text.isNotEmpty()) response.append(text)
                    }
                debugLog(TAG, "Done: ${response.length} chars")
                response.toString()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    debugLog(TAG, "Generation cancelled (partial text preserved)")
                    throw e
                }
                var didFallback = false
                conversationLock.withLock {
                    if (isGpu) {
                        didFallback = fallbackToCpu()
                        if (didFallback) initConversationLocked(
                            cachedSystemPrompt,
                            cachedTools,
                            cachedSamplerConfig,
                            enableThinking
                        )
                    }
                }
                if (didFallback) {
                    val retryConv = conversation
                        ?: throw IllegalStateException("CPU fallback failed: conversation is null after initConversation")
                    val retryResponse = StringBuilder()
                    retryConv.sendMessageAsync(userMessage, thinkingContext ?: emptyMap())
                        .collect { message ->
                            val text = extractText(message)
                            if (text.isNotEmpty()) retryResponse.append(text)
                        }
                    retryResponse.toString()
                } else {
                    throw e
                }
            }
        }
    }

    override fun generateStream(userMessage: String): Flow<StreamToken> = flow {
        generationMutex.withLock {
        if (closed.get()) error("Engine closed")
        val conv = conversation ?: error("Conversation not initialized — call initConversation() first")

        val parser = StreamParser()

        var tokenCount = 0
        val startTime = System.currentTimeMillis()

        fun tps(): Float {
            val elapsed = System.currentTimeMillis() - startTime
            return if (elapsed > 0 && tokenCount > 0) tokenCount / (elapsed / 1000f) else 0f
        }

        var lastRawToken = ""
        var repeatCount = 0
        val MAX_REPETITIONS = 10
        var hitRepetition = false
        var gpuStall = false
        val STALL_TIMEOUT_MS = 180_000L // 3 min — multi-tool chains (calendar+notifs+memory+web) can take 60+ seconds
        var lastTokenTime = System.currentTimeMillis()

        try {
            debugLog(TAG, "Streaming (${if (isGpu) "GPU" else "CPU"}, thinking=$enableThinking, maxTokens=$maxNumTokens)...")
            // Stall detection: GPU hangs silently — no tokens, no error.
            // Only run watchdog on GPU — CPU doesn't have this issue.
            // Launch a watchdog that cancels the generation if no token arrives for 30s.
            // Tools can take time to execute (shell, web), but between tool result and
            // next token the GPU should respond within seconds.
            if (isGpu) {
            kotlinx.coroutines.coroutineScope {
                val tokenChannel = kotlinx.coroutines.channels.Channel<com.google.ai.edge.litertlm.Message>(capacity = 64)

                // Producer: collects sendMessageAsync and forwards to channel
                val producer = launch(Dispatchers.IO) {
                    try {
                        conv.sendMessageAsync(userMessage, thinkingContext ?: emptyMap()).collect { message ->
                            tokenChannel.send(message)
                        }
                    } catch (_: Exception) {
                        // Collect cancelled (stall or external)
                    } finally {
                        tokenChannel.close()
                    }
                }

                // Watchdog: detects GPU stall and triggers CPU fallback
                val watchdog = launch(Dispatchers.Default) {
                    while (true) {
                        kotlinx.coroutines.delay(5_000L)
                        val elapsed = System.currentTimeMillis() - lastTokenTime
                        // Don't trigger stall if tools are actively executing —
                        // LiteRT doesn't emit tokens during automaticToolCalling tool execution.
                        val toolActivityMs = com.borizon.app.ai.tools.ToolCallTracker.lastToolActivityMs
                        val toolElapsed = if (toolActivityMs > 0L) System.currentTimeMillis() - toolActivityMs else Long.MAX_VALUE
                        if (elapsed > STALL_TIMEOUT_MS && toolElapsed > STALL_TIMEOUT_MS && !gpuStall) {
                            gpuStall = true
                            debugLog(TAG, "GPU stall: no tokens for ${elapsed}ms, cancelling")
                            // Trigger GPU→CPU fallback so next generation uses CPU
                            try {
                                conversationLock.withLock {
                                    if (isGpu) {
                                        fallbackToCpu()
                                        initConversationLocked(cachedSystemPrompt, cachedTools, cachedSamplerConfig, enableThinking)
                                        debugLog(TAG, "GPU stall → CPU fallback completed")
                                    }
                                }
                            } catch (e: Exception) {
                                debugLog(TAG, "GPU stall fallback failed: ${e.message}")
                            }
                            producer.cancel()
                            try { conv.cancelProcess() } catch (_: Exception) {}
                            tokenChannel.close()
                            break
                        }
                    }
                }

                // Consumer: read from channel and emit tokens
                try {
                    for (message in tokenChannel) {
                        lastTokenTime = System.currentTimeMillis()
                        tokenCount++
                        val parsed = parseMessage(message, tokenCount, tps(), parser)
                        emit(parsed)
                        // Repetition detection
                        val raw = parsed.text
                        if (raw.isNotEmpty() && raw == lastRawToken) {
                            repeatCount++
                            if (repeatCount >= MAX_REPETITIONS) {
                                hitRepetition = true
                                debugLog(TAG, "Repetition detected: '$raw' repeated $repeatCount times, stopping")
                                try { conv.cancelProcess() } catch (_: Exception) {}
                                break
                            }
                        } else {
                            repeatCount = 0
                            lastRawToken = raw
                        }
                    }
                } finally {
                    watchdog.cancel()
                    producer.cancel()
                }
            } // end coroutineScope (GPU path with watchdog)
            } else {
            // CPU path — no watchdog needed, simple collect
            conv.sendMessageAsync(userMessage, thinkingContext ?: emptyMap()).collect { message ->
                tokenCount++
                val parsed = parseMessage(message, tokenCount, tps(), parser)
                emit(parsed)
                val raw = parsed.text
                if (raw.isNotEmpty() && raw == lastRawToken) {
                    repeatCount++
                    if (repeatCount >= MAX_REPETITIONS) {
                        hitRepetition = true
                        debugLog(TAG, "Repetition detected: '$raw' repeated $repeatCount times, stopping")
                        try { conv.cancelProcess() } catch (_: Exception) {}
                    }
                } else {
                    repeatCount = 0
                    lastRawToken = raw
                }
            }
            } // end if (isGpu) ... else

            emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
            if (gpuStall) {
                debugLog(TAG, "GPU stall terminated generation. tokens: $tokenCount, tps: ${tps()}")
            } else {
                debugLog(TAG, "Streaming done. tokens: $tokenCount, tps: ${tps()}")
            }
        } catch (e: Exception) {
            if (hitRepetition || gpuStall) {
                emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
                debugLog(TAG, "Streaming stopped (${if (gpuStall) "GPU stall" else "repetition"}). tokens: $tokenCount, tps: ${tps()}")
            } else if (e is CancellationException) {
                emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
            } else {
                var didFallback = false
                conversationLock.withLock {
                    if (isGpu) {
                        didFallback = fallbackToCpu()
                        if (didFallback) initConversationLocked(cachedSystemPrompt, cachedTools, cachedSamplerConfig, enableThinking)
                    }
                }
                if (didFallback) {
                    val retryConv = conversation
                        ?: throw IllegalStateException("CPU fallback failed: conversation is null after initConversation")
                    val retryParser = StreamParser()
                    retryConv.sendMessageAsync(userMessage, thinkingContext ?: emptyMap()).collect { message ->
                        tokenCount++
                        emit(parseMessage(message, tokenCount, tps(), retryParser))
                    }
                    emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
                } else {
                throw e
            }
        }
        }
        }
    }.flowOn(Dispatchers.IO)

    /** Extra context for sendMessageAsync — controls thinking mode .
     *  Must use Map<String, String> to match the JNI string serialization. */
    private val thinkingContext: Map<String, String>?
        get() = if (enableThinking) mapOf("enable_thinking" to "true") else null

    /**
     * Extract visible text from a LiteRT Message (for non-streaming methods).
     * Filters control tokens and channel tags.
     */
    private fun extractText(message: com.google.ai.edge.litertlm.Message): String {
        val rawText = message.toString()
        return when {
            rawText.startsWith("<ctrl") || rawText == "<pad>" -> ""
            rawText.startsWith("<|channel>") || rawText == "<channel|>" -> ""
            rawText.startsWith("<|tool_call") || rawText.startsWith("</|tool_call") -> ""
            rawText.startsWith("<|tool_outputs") || rawText.startsWith("</|tool_outputs") -> ""
            rawText == "<|tool_call|>" || rawText == "</|tool_call|>" -> ""
            rawText == "<|tool_outputs|>" || rawText == "</|tool_outputs|>" -> ""
            else -> rawText
        }
    }

    /**
     * Parse a LiteRT-LM Message into a StreamToken using the streaming state machine.
     *
     * Gemma 4 thinking output: SDK parses thinking into message.channels["thought"]
     * when "enable_thinking"="true" is passed via extraContext .
     * Falls back to streaming state machine if channels is empty.
     */
    private fun parseMessage(message: com.google.ai.edge.litertlm.Message, tokenCount: Int, tps: Float, parser: StreamParser): StreamToken {
        val rawText = message.toString()
        val channels = message.channels

        // Primary: use message.channels["thought"] directly
        val thinkingStr = channels["thought"]

        val text = if (thinkingStr != null) {
            // Thinking came through channels — toString() still has channel tags, filter them
            extractText(message)
        } else {
            // Fallback: parse raw stream for <|channel>thought blocks
            parser.parse(rawText)
        }

        if (tokenCount <= 5) {
            debugLog(TAG, "parseMessage #$tokenCount: raw='${rawText.take(80)}' channels={${
                channels.entries.joinToString(", ") { "${it.key}='${it.value.take(30)}'" }
            }} think=${thinkingStr != null} text='${text.take(40)}'")
        }

        return StreamToken(
            text = text,
            thinking = thinkingStr,
            tokenCount = tokenCount,
            tokensPerSecond = tps,
        )
    }

    override fun generateStreamMultimodal(text: String, imageBytes: List<ByteArray>): Flow<StreamToken> = flow {
        generationMutex.withLock {
        if (closed.get()) error("Engine closed")
        val conv = conversation ?: error("Conversation not initialized")

        val parser = StreamParser()

        val contents = mutableListOf<Content>()
        for (img in imageBytes) contents.add(Content.ImageBytes(img))
        if (text.isNotEmpty()) contents.add(Content.Text(text))

        var tokenCount = 0
        val startTime = System.currentTimeMillis()

        fun tps(): Float {
            val elapsed = System.currentTimeMillis() - startTime
            return if (elapsed > 0 && tokenCount > 0) tokenCount / (elapsed / 1000f) else 0f
        }

        var lastRawToken = ""
        var repeatCount = 0
        val MAX_REPETITIONS = 10
        var hitRepetition = false
        try {
            debugLog(TAG, "Streaming multimodal (${imageBytes.size} images + text, thinking=$enableThinking, maxTokens=$maxNumTokens)...")
            conv.sendMessageAsync(Contents.of(contents), thinkingContext ?: emptyMap()).collect { message ->
                tokenCount++
                val parsed = parseMessage(message, tokenCount, tps(), parser)
                emit(parsed)
                val raw = parsed.text
                if (raw.isNotEmpty() && raw == lastRawToken) {
                    repeatCount++
                    if (repeatCount >= MAX_REPETITIONS) {
                        hitRepetition = true
                        debugLog(TAG, "Repetition detected in multimodal: '$raw' repeated $repeatCount times")
                        try { conv.cancelProcess() } catch (_: Exception) {}
                    }
                } else {
                    repeatCount = 0
                    lastRawToken = raw
                }
            }
            emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
        } catch (e: Exception) {
            if (hitRepetition) {
                emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
                debugLog(TAG, "Multimodal streaming stopped (repetition). tokens: $tokenCount")
            } else if (e is CancellationException) {
                emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
            } else {
                var didFallback = false
                conversationLock.withLock {
                    if (isGpu) {
                        didFallback = fallbackToCpu()
                        if (didFallback) initConversationLocked(cachedSystemPrompt, cachedTools, cachedSamplerConfig, enableThinking)
                    }
                }
                if (didFallback) {
                    val retryConv = conversation
                        ?: throw IllegalStateException("CPU fallback failed: conversation is null after initConversation")
                    val retryParser = StreamParser()
                    val retryContents = mutableListOf<Content>()
                    for (img in imageBytes) retryContents.add(Content.ImageBytes(img))
                    if (text.isNotEmpty()) retryContents.add(Content.Text(text))
                    retryConv.sendMessageAsync(Contents.of(retryContents), thinkingContext ?: emptyMap()).collect { message ->
                        tokenCount++
                        emit(parseMessage(message, tokenCount, tps(), retryParser))
                    }
                    emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
                } else {
                throw e
            }
        }
        }
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalApi::class)
    override fun generateStreamWithAudio(audioBytes: ByteArray, prompt: String): Flow<StreamToken> =
        flow {
        generationMutex.withLock {
        if (closed.get()) error("Engine closed")
        val conv = conversation ?: error("Conversation not initialized")

        val parser = StreamParser()

        val contents = mutableListOf<Content>(Content.AudioBytes(audioBytes))
        if (prompt.isNotBlank()) contents.add(Content.Text(prompt))

        var tokenCount = 0
        val startTime = System.currentTimeMillis()

        fun tps(): Float {
            val elapsed = System.currentTimeMillis() - startTime
            return if (elapsed > 0 && tokenCount > 0) tokenCount / (elapsed / 1000f) else 0f
        }

        try {
            debugLog(TAG, "Streaming audio (${audioBytes.size} bytes, thinking=$enableThinking, maxTokens=$maxNumTokens)...")
            conv.sendMessageAsync(Contents.of(contents), thinkingContext ?: emptyMap()).collect { message ->
                tokenCount++
                emit(parseMessage(message, tokenCount, tps(), parser))
            }
            emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
        } catch (e: Exception) {
            if (e is CancellationException) {
                emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
            } else {
                var didFallback = false
                conversationLock.withLock {
                    if (isGpu) {
                        didFallback = fallbackToCpu()
                        if (didFallback) initConversationLocked(cachedSystemPrompt, cachedTools, cachedSamplerConfig, enableThinking)
                    }
                }
                if (didFallback) {
                    debugLog(TAG, "Retrying audio on CPU after GPU failure")
                    val retryConv = conversation
                        ?: throw IllegalStateException("CPU fallback failed: conversation is null after initConversation")
                    val retryParser = StreamParser()
                    val retryContents = mutableListOf<Content>(Content.AudioBytes(audioBytes))
                    if (prompt.isNotBlank()) retryContents.add(Content.Text(prompt))
                    retryConv.sendMessageAsync(Contents.of(retryContents), thinkingContext ?: emptyMap()).collect { message ->
                        tokenCount++
                        emit(parseMessage(message, tokenCount, tps(), retryParser))
                    }
                    emit(StreamToken(text = "", done = true, tokenCount = tokenCount, tokensPerSecond = tps()))
                } else {
                throw e
            }
        }
        }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reset the conversation (clear KV cache).
     * Call this when starting a new chat session.
     * Must call [initConversation] after this before generating.
     */
    @OptIn(ExperimentalApi::class)
    override suspend fun resetConversation(): Unit = withContext(Dispatchers.IO) {
        conversationLock.withLock {
            if (conversation != null && conversationGeneration == engineGeneration) {
                try {
                    conversation?.close()
                } catch (_: Exception) {
                }
            }
            conversation = null
            _initState.set(false)
            ExperimentalFlags.enableConversationConstrainedDecoding = false
            debugLog(TAG, "Conversation reset")
        }
    }

    override fun stopResponse() {
        val conv = conversation ?: return
        try {
            conv.cancelProcess()
            debugLog(TAG, "Generation stopped via cancelProcess()")
        } catch (e: Exception) {
            Log.w(TAG, "cancelProcess failed: ${e.message}")
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun analyze(systemPrompt: String, userMessage: String): String = withContext(Dispatchers.IO) {
        generationMutex.withLock {
        if (closed.get()) error("Engine closed")
        val eng = engine ?: error("Engine not loaded")

        // LiteRT single-session: reset persistent conversation before creating temp one
        conversationLock.withLock { conversation?.close(); conversation = null }

        val systemContents = if (systemPrompt.isNotBlank()) Contents.of(systemPrompt) else null
        val config = ConversationConfig(
            systemInstruction = systemContents,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.90, temperature = 0.1),
        )
        val tempConversation = conversationLock.withLock { eng.createConversation(config) }
        try {
            val response = StringBuilder()
            tempConversation.sendMessageAsync(userMessage).collect { message ->
                val text = extractText(message)
                if (text.isNotEmpty()) response.append(text)
            }
            response.toString()
        } finally {
            conversationLock.withLock { tempConversation.close() }
            // Reinitialize persistent conversation — we already hold generationMutex,
            // so call the internal version to avoid deadlock (kotlinx Mutex is NOT reentrant)
            conversationLock.withLock {
                initConversationLocked(cachedSystemPrompt, cachedTools, cachedSamplerConfig, enableThinking)
            }
        }
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        generationMutex.withLock {
        if (!audioFile.exists()) return@withContext ""
        if (closed.get()) error("Engine closed")
        val eng = engine ?: error("Engine not loaded")

        val audioBytes = audioFile.readBytes()

        val conv = conversation ?: error("Conversation not initialized — call initConversation() first")
        try {
            val contents = mutableListOf<Content>(
                Content.AudioBytes(audioBytes),
            )
            val response = StringBuilder()
            conv.sendMessageAsync(Contents.of(contents), thinkingContext ?: emptyMap()).collect { message ->
                val text = extractText(message)
                if (text.isNotEmpty()) response.append(text)
            }
            val transcription = response.toString().trim()
            debugLog(TAG, "Audio transcribed: ${transcription.length} chars from ${audioFile.name}")
            transcription
        } catch (e: Exception) {
            Log.e(TAG, "Audio transcription failed", e)
            ""
        }
        }
    }

    /**
     * Generate a response from audio input using the persistent conversation.
     *  send audio + text directly through the main conversation.
     * The model processes audio natively alongside the text prompt.
     */
    @OptIn(ExperimentalApi::class)
    override suspend fun generateWithAudio(audioBytes: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        generationMutex.withLock {
        if (closed.get()) error("Engine closed")
        val conv = conversation ?: error("Conversation not initialized")

        try {
            debugLog(TAG, "Generating with audio (${audioBytes.size} bytes) on ${if (isGpu) "GPU" else "CPU"}")
            val contents = mutableListOf<Content>(
                Content.AudioBytes(audioBytes),
            )
            if (prompt.isNotBlank()) {
                contents.add(Content.Text(prompt))
            }

            val response = StringBuilder()
            conv.sendMessageAsync(Contents.of(contents), thinkingContext ?: emptyMap()).collect { message ->
                val text = extractText(message)
                if (text.isNotEmpty()) response.append(text)
            }
            debugLog(TAG, "Audio response: ${response.length} chars")
            response.toString()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                debugLog(TAG, "Audio generation cancelled")
                throw e
            }
            Log.e(TAG, "Audio generation failed", e)
            var didFallback = false
            conversationLock.withLock {
                if (isGpu) {
                    didFallback = fallbackToCpu()
                    if (didFallback) initConversationLocked(cachedSystemPrompt, cachedTools, cachedSamplerConfig, enableThinking)
                }
            }
            if (didFallback) {
                val retryConv = conversation
                    ?: throw IllegalStateException("CPU fallback failed: conversation is null after initConversation")
                val retryContents = mutableListOf<Content>(Content.AudioBytes(audioBytes))
                if (prompt.isNotBlank()) retryContents.add(Content.Text(prompt))
                val retryResponse = StringBuilder()
                retryConv.sendMessageAsync(Contents.of(retryContents), thinkingContext ?: emptyMap()).collect { message ->
                    val text = extractText(message)
                    if (text.isNotEmpty()) retryResponse.append(text)
                }
                retryResponse.toString()
            } else {
                throw e
            }
        }
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        generationMutex.withLock {
        if (closed.get()) error("Engine closed")
        val eng = engine ?: error("Engine not loaded")

        // LiteRT single-session: reset persistent conversation before creating temp one
        conversationLock.withLock { conversation?.close(); conversation = null }

        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 40, topP = 0.90, temperature = 0.3),
        )
        val tempConversation = conversationLock.withLock { eng.createConversation(config) }
        try {
            val contents = listOf(
                Content.ImageBytes(imageBytes),
                Content.Text(prompt)
            )
            val response = StringBuilder()
            tempConversation.sendMessageAsync(Contents.of(contents)).collect { message ->
                val text = extractText(message)
                if (text.isNotEmpty()) response.append(text)
            }
            response.toString()
        } finally {
            conversationLock.withLock { tempConversation.close() }
            // Reinitialize — call internal version to avoid deadlock (Mutex not reentrant)
            conversationLock.withLock {
                initConversationLocked(cachedSystemPrompt, cachedTools, cachedSamplerConfig, enableThinking)
            }
        }
        }
    }

    /**
     * Lightweight health check — verifies the native engine is still responsive.
     * Returns true if the engine appears alive, false if it's dead (OS reclaimed memory, etc.).
     * This is cheaper than a full reload and avoids the "model reloads out of nowhere" problem.
     */
    fun isAlive(): Boolean {
        val eng = engine ?: return false
        if (closed.get()) return false
        return try {
            // Attempt to create a trivial temp conversation — exercises the native layer
            val testConv = eng.createConversation()
            testConv.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        debugLog(TAG, "Closing engine ")
        // Step 1: Close conversation in its own try-catch 
        if (conversation != null && conversationGeneration == engineGeneration) {
            try { conversation?.close() } catch (e: Exception) { Log.w(TAG, "Conv close error: ${e.message}") }
        }
        conversation = null
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        // Step 2: Close engine in its own try-catch — separate from conversation close
        try { engine?.close() } catch (e: Exception) { Log.w(TAG, "Engine close error: ${e.message}") }
        engine = null
        _initState.set(false)
    }
}
