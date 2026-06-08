package com.borizon.app.ai.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.borizon.app.util.debugLog
import com.google.ai.edge.litertlm.ToolProvider
import com.borizon.app.ui.components.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the Gemma 4 E4B model lifecycle — loading, conversation init, inference, unloading.
 *
 * - State machine: NOT_LOADED → LOADING → LOADED → ERROR
 * - Cleanup race handling via cleanUpAfterInit flag
 * - Session reset with retry loop (200ms delay)
 * - Error recovery: full cleanup → reinit → warning message
 * - Persistent conversation via [initConversation]
 */
open class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val DEFAULT_MAX_TOKENS = 8192
        private const val DEFAULT_TEMPERATURE = 0.75f
        private const val DEFAULT_TOP_P = 0.90f
        private const val RESET_RETRY_DELAY_MS = 200L
        /** Per-model minimum total device RAM (GB). */
        private val MIN_DEVICE_RAM_GB = mapOf("E2B" to 8f, "E4B" to 12f)
        private const val BYTES_IN_GB = 1024f * 1024 * 1024

        /** Scale KV cache to device RAM minus model footprint.
         *
         * maxNumTokens is the TOTAL KV cache shared by input + output (system prompt,
         * tool schemas, conversation history, tool call/response, AND the model's text output).
         *
         * With 11 ToolSets (20+ tools), tool schemas alone consume ~3000-4000 tokens,
         * so a 4096 cache leaves barely 100-200 tokens for the response.
         *
         * However, going above 8192 can cause lmkd kills on devices with <12GB RAM.
         * The safe approach: use 8192 max, rely on tool schema trimming for headroom.
         *
         * E4B model ≈ 4-5GB, E2B ≈ 2-3GB.
         */
        fun computeMaxTokens(context: Context, modelKey: String = "E4B"): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return DEFAULT_MAX_TOKENS
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalGb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                memInfo.advertisedMem / BYTES_IN_GB
            } else {
                memInfo.totalMem / BYTES_IN_GB
            }
            val isLargeModel = modelKey.contains("E4B", ignoreCase = true)
            val modelFootprintGb = if (isLargeModel) 5f else 3f  // model + runtime overhead
            val usableGb = totalGb - modelFootprintGb
            // E2B on high-RAM devices can use 8192 — smaller model leaves more headroom.
            // E4B is capped at 4096: Gemma 4's local attention layers use a 512-token sliding
            // window, and pushing beyond 4096 can cause EOS mid-response when tool context
            // fills the KV cache. The model card benchmarks use 2048.
            // User can always choose less via settings; this is the ceiling.
            return when {
                !isLargeModel && usableGb >= 10f -> 8192  // E2B on 16GB+: full context
                !isLargeModel && usableGb >= 7f -> 6144   // E2B on 12GB+
                usableGb >= 16f -> 8192                   // E4B on 24GB+ (future tablets)
                usableGb >= 12f -> 4096
                usableGb >= 8f -> 3072
                usableGb >= 5f -> 2048
                else -> 2048
            }.also { debugLog(TAG, "Adaptive maxTokens: ${totalGb.toInt()}GB RAM - ${modelFootprintGb.toInt()}GB model = ${usableGb.toInt()}GB usable → $it tokens") }
        }
    }

    /**
     * Model initialization state machine.
     */
    enum class InitState {
        NOT_LOADED, LOADING, LOADED, ERROR
    }

    sealed class ModelState {
        data object Idle : ModelState()
        data object Loading : ModelState()
        data class Ready(val backend: String, val modelName: String = "Gemma 4", val mtpActive: Boolean = false) : ModelState()
        data class Error(val message: String) : ModelState()
    }

    // Public alias for navigation to check state type
    object ModelStateTypes {
        val Loading = ModelState.Loading
        val Ready = ModelState.Ready::class
        val Error = ModelState.Error::class
    }

    private val _state = MutableStateFlow<ModelState>(ModelState.Idle)
    val state: StateFlow<ModelState> = _state

    private val _initState = MutableStateFlow(InitState.NOT_LOADED)
    val initState: StateFlow<InitState> = _initState

    private var inferenceEngine: InferenceEngine? = null

    /** Whether the model is currently initializing. */
    private val isInitializing = AtomicBoolean(false)

    /** Flag to handle cleanup race: set when cleanup is requested during init. */
    private val cleanUpAfterInit = AtomicBoolean(false)

    /** Whether a generation is currently in progress. */
    private val isGenerating = AtomicBoolean(false)

    /** Current model configuration — drives SamplerConfig and accelerator selection. */
    @Volatile var currentConfig: ModelConfig = ModelConfig()
        private set

    /** Selected model variant key ("E2B" or "E4B"). */
    @Volatile var selectedModelKey: String = "E4B"

    /** Cached tools from last initConversation — needed for reinit on config change. */
    private var cachedTools: List<ToolProvider> = emptyList()

    /** Persists the last backend that worked, to skip known-broken backends on next launch. */
    private val backendPrefs = context.getSharedPreferences("borizon_backend", Context.MODE_PRIVATE)

    /**
     * Load the model from assets or internal storage.
     * Respects [currentConfig].accelerator for backend selection.
     */
    suspend fun loadModel(config: ModelConfig = currentConfig) = withContext(Dispatchers.IO) {
        // If accelerator is "auto" but we know GPU/NPU fails at runtime on this device, skip it
        var effectiveConfig = config
        if (config.accelerator == "auto") {
            val lastWorking = backendPrefs.getString("last_working_backend", null)
            if (lastWorking != null) {
                debugLog(TAG, "Using persisted backend: $lastWorking (previous runtime failure)")
                effectiveConfig = config.copy(accelerator = lastWorking)
            }
        }
        // Apply adaptive maxTokens ceiling based on device RAM.
        // User can choose less (via settings slider), but not more than device can handle.
        val adaptiveTokens = computeMaxTokens(context, selectedModelKey)
        if (effectiveConfig.maxTokens > adaptiveTokens) {
            debugLog(TAG, "Adaptive maxTokens: ${effectiveConfig.maxTokens} → $adaptiveTokens (device RAM ceiling)")
            effectiveConfig = effectiveConfig.copy(maxTokens = adaptiveTokens)
        } else {
            debugLog(TAG, "Using user-configured maxTokens: ${effectiveConfig.maxTokens} (ceiling: $adaptiveTokens)")
        }
        currentConfig = effectiveConfig
        // Skip if already loaded or currently loading
        val state = _initState.value
        if (state == InitState.LOADED) return@withContext
        if (state == InitState.LOADING) {
            debugLog(TAG, "loadModel: already loading, skipping")
            return@withContext
        }

        // Cleanup stale engine (ERROR state) before loading
        if (state == InitState.ERROR) {
            cleanupModel()
            System.gc()
            delay(300)
        }

        // Double-init guard  — atomic CAS prevents race
        if (!isInitializing.compareAndSet(false, true)) {
            debugLog(TAG, "loadModel: CAS failed, skipping")
            return@withContext
        }

        _state.value = ModelState.Loading
        _initState.value = InitState.LOADING
            val variant = ModelDownloader.variant(selectedModelKey)
            debugLog(TAG, "Loading ${variant.displayName} model...")

        try {
            // Memory check : warn but don't block — let OS handle via LMKD
            val memWarning = checkAvailableMemory(variant)
            if (memWarning != null) {
                Log.w(TAG, memWarning)
            }

            val modelFile = ModelDownloader(context).getModelFile(selectedModelKey)
            if (!modelFile.exists()) {
                val msg = "${variant.displayName} model is missing. Borizon needs this AI model to work. The model should be downloaded automatically. If the download failed, please check your internet connection and restart the app."
                Log.e(TAG, msg)
                Log.e(TAG, "Expected path: ${modelFile.absolutePath}, exists=${modelFile.exists()}, size=${modelFile.length()}")
                _state.value = ModelState.Error(msg)
                _initState.value = InitState.ERROR
                return@withContext
            }

            val preferredBackend = configToBackend(effectiveConfig.accelerator)
            val engine = LiteRTInferenceEngine(
                context = context,
                modelFile = modelFile,
                preferredBackend = preferredBackend,
                maxNumTokens = effectiveConfig.maxTokens,
                enableMtp = effectiveConfig.enableMtp,
            )
            try {
                engine.loadEngine()
            } catch (e: OutOfMemoryError) {
                // OOM during engine init — retry with halved KV cache
                Log.w(TAG, "OOM loading engine with maxTokens=${effectiveConfig.maxTokens}, retrying with ${effectiveConfig.maxTokens / 2}")
                engine.close()
                System.gc()
                delay(300)
                val fallback = LiteRTInferenceEngine(
                    context = context,
                    modelFile = modelFile,
                    preferredBackend = preferredBackend,
                    maxNumTokens = effectiveConfig.maxTokens / 2,
                    enableMtp = effectiveConfig.enableMtp,
                )
                fallback.loadEngine()
                inferenceEngine = fallback
                _state.value = ModelState.Ready(backend = when {
                    fallback.isNpu -> "NPU"; fallback.isGpu -> "GPU"; else -> "CPU"
                }, modelName = variant.displayName, mtpActive = fallback.isMtpActive)
                _initState.value = InitState.LOADED
                debugLog(TAG, "Model loaded with reduced maxTokens (${config.maxTokens / 2})")
                return@withContext
            }
            inferenceEngine = engine

            val backend = when {
                engine.isNpu -> "NPU"
                engine.isGpu -> "GPU"
                else -> "CPU"
            }
            _state.value = ModelState.Ready(backend = backend, modelName = variant.displayName, mtpActive = engine.isMtpActive)
            _initState.value = InitState.LOADED
            // NOTE: Do NOT persist backend at engine-init time.
            // GPU engine init can succeed (OpenCL kernels compile) but inference may still hang.
            // Backend is now persisted only after a successful generation completes
            // (see persistWorkingBackend() called from generateStream).
            if (effectiveConfig.accelerator == "auto" && backend == "CPU") {
                // CPU fallback at init — clear any stale persisted GPU/NPU preference
                backendPrefs.edit().remove("last_working_backend").apply()
                debugLog(TAG, "CPU fallback at init — cleared persisted backend")
            }
            debugLog(TAG, "Model loaded successfully ($backend)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            val msg = "Failed to initialize AI model: ${e.message}. This may be due to corrupted files or incompatible hardware. Try reinstalling the app."
            _state.value = ModelState.Error(msg)
            _initState.value = InitState.ERROR
        } finally {
            isInitializing.set(false)
            if (cleanUpAfterInit.getAndSet(false)) {
                if (_initState.value == InitState.LOADED) {
                    // Load succeeded — stale cleanup request, keep the engine alive
                    debugLog(TAG, "cleanUpAfterInit set but load succeeded — keeping engine")
                } else {
                    debugLog(TAG, "cleanUpAfterInit set and load failed — cleaning up")
                    cleanupModel()
                }
            }
        }
    }

    /**
     * Initialize the persistent conversation with a system prompt and optional tools.
     * Uses [currentConfig] for sampler settings and thinking mode.
     * @param initialMessages Optional chat history to pre-fill the KV cache (for reinit/history replay).
     */
    suspend fun initConversation(
        systemPrompt: String,
        tools: List<ToolProvider> = emptyList(),
        initialMessages: List<com.borizon.app.data.models.ChatMessage>? = null,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "initConversation called: tools=${tools.size}, msgs=${initialMessages?.size}")
        val engine = inferenceEngine ?: error("Engine not loaded")
        cachedTools = tools
        val samplerConfig = if (currentConfig.topK > 0) {
            com.google.ai.edge.litertlm.SamplerConfig(
                topK = currentConfig.topK,
                topP = currentConfig.topP.toDouble(),
                temperature = currentConfig.temperature.toDouble(),
            )
        } else null

        val mappedMessages = initialMessages?.map { msg ->
            if (msg.role.lowercase() == "user") {
                com.google.ai.edge.litertlm.Message.user(msg.content)
            } else {
                com.google.ai.edge.litertlm.Message.model(msg.content)
            }
        }

        if (engine is LiteRTInferenceEngine) {
            engine.initConversation(systemPrompt, tools, samplerConfig, currentConfig.enableThinking, mappedMessages)
        } else {
            engine.initConversation(systemPrompt, tools)
        }
        val msgCount = initialMessages?.size ?: 0
        debugLog(TAG, "Conversation initialized with ${tools.size} tools, history=$msgCount, config=$currentConfig")
    }

    /**
     * Apply a new model config. Reinitializes the engine/conversation as needed.
     * Returns a map of changed parameters (old → new) for UI feedback.
     *
     *  accelerator change = full engine reload,
     * sampler/thinking change = conversation reinit only.
     */
    suspend fun applyConfig(newConfig: ModelConfig): Map<String, Pair<String, String>> {
        val changes = newConfig.diffFrom(currentConfig)
        if (changes.isEmpty()) return emptyMap()

        val oldConfig = currentConfig
        currentConfig = newConfig

        val acceleratorChanged = oldConfig.accelerator != newConfig.accelerator
        if (acceleratorChanged) {
            // Any accelerator change clears persisted fallback so the user's new choice is respected
            backendPrefs.edit().remove("last_working_backend").apply()
        }
        val maxTokensChanged = oldConfig.maxTokens != newConfig.maxTokens
        val samplerChanged = oldConfig.temperature != newConfig.temperature ||
            oldConfig.topK != newConfig.topK ||
            oldConfig.topP != newConfig.topP
        val thinkingChanged = oldConfig.enableThinking != newConfig.enableThinking
        val mtpChanged = oldConfig.enableMtp != newConfig.enableMtp

        if (acceleratorChanged || maxTokensChanged || mtpChanged) {
            // Full engine reload required — maxTokens, accelerator, and MTP are set at engine level
            debugLog(TAG, "Engine-level config changed: accelerator=${oldConfig.accelerator}→${newConfig.accelerator}, maxTokens=${oldConfig.maxTokens}→${newConfig.maxTokens}, mtp=${oldConfig.enableMtp}→${newConfig.enableMtp}, reloading engine")
            cleanupModel()
            loadModel(newConfig)
        }

        if (samplerChanged || thinkingChanged || acceleratorChanged || maxTokensChanged || mtpChanged) {
            // Reinit conversation with new sampler config
            // Note: ReflectAgent will call initConversation() with system prompt after this
            debugLog(TAG, "Config applied: $changes")
        }

        return changes
    }

    /**
     * Persist the current backend as working AFTER successful inference.
     * Only call this after generateStream completes without error.
     * This prevents persisting a backend where engine init succeeds but inference hangs.
     */
    fun persistWorkingBackend() {
        val engine = inferenceEngine ?: return
        val backend = when {
            engine is LiteRTInferenceEngine && engine.isNpu -> "npu"
            engine is LiteRTInferenceEngine && engine.isGpu -> "gpu"
            else -> return // never persist CPU
        }
        if (currentConfig.accelerator == "auto") {
            backendPrefs.edit().putString("last_working_backend", backend).apply()
            debugLog(TAG, "Persisted working backend after successful inference: $backend")
        }
    }

    /**
     * Stream tokens as they're generated using the persistent conversation.
     * KV cache is reused — no history replay needed.
     */
    fun generateStream(userMessage: String): kotlinx.coroutines.flow.Flow<StreamToken> {
        val engine = inferenceEngine ?: throw IllegalStateException("Model not loaded — call loadModel() first")
        isGenerating.set(true)
        return engine.generateStream(userMessage).onCompletion {
            isGenerating.set(false)
            if (engine is LiteRTInferenceEngine && engine.fellBackToCpu.getAndSet(false)) {
                debugLog(TAG, "GPU→CPU fallback detected — updating UI state")
                val variant = ModelDownloader.variant(selectedModelKey)
                _state.value = ModelState.Ready(backend = "CPU", modelName = variant.displayName, mtpActive = engine.isMtpActive)
            } else {
                // Only persist backend after a generation that didn't fall back to CPU
                persistWorkingBackend()
            }
        }
    }

    /**
     * Stream tokens with multimodal input (text + images) using the persistent conversation.
     * Images are sent alongside text so the model can see them.
     */
    fun generateStreamMultimodal(text: String, imageBytes: List<ByteArray>): kotlinx.coroutines.flow.Flow<StreamToken> {
        val engine = inferenceEngine ?: throw IllegalStateException("Model not loaded — call loadModel() first")
        isGenerating.set(true)
        return engine.generateStreamMultimodal(text, imageBytes).onCompletion { isGenerating.set(false) }
    }

    /**
     * Generate a complete response using the persistent conversation.
     */
    suspend fun generate(userMessage: String): String = withContext(Dispatchers.IO) {
        val engine = inferenceEngine ?: throw IllegalStateException("Model not loaded — call loadModel() first")
        engine.generate(userMessage)
    }

    /**
     * Stream tokens from audio input with thinking support.
     *  audio-only content sent through persistent conversation.
     */
    fun generateStreamWithAudio(audioBytes: ByteArray): Flow<StreamToken> {
        val engine = inferenceEngine ?: throw IllegalStateException("Model not loaded")
        return engine.generateStreamWithAudio(audioBytes, "")
    }

    /**
     * Reset the conversation (clear KV cache) for a new chat.
     * Retries up to 3 times with exponential backoff.
     * Must call initConversation() after this before generating.
     */
    suspend fun resetConversation() = withContext(Dispatchers.IO) {
        stopResponse()
        val maxAttempts = 3
        repeat(maxAttempts) { attempt ->
            try {
                inferenceEngine?.resetConversation()
                debugLog(TAG, "Conversation reset (attempt ${attempt + 1})")
                return@withContext
            } catch (e: Exception) {
                Log.w(TAG, "Reset failed (attempt ${attempt + 1}/$maxAttempts): ${e.message}")
                if (attempt == maxAttempts - 1) {
                    Log.e(TAG, "Reset failed after $maxAttempts attempts", e)
                } else {
                    delay(RESET_RETRY_DELAY_MS * (1L shl attempt))
                }
            }
        }
    }

    /**
     * Stop the current generation. Partial text is preserved.
     */
    fun stopResponse() {
        inferenceEngine?.stopResponse()
        isGenerating.set(false)
    }

    /**
     * Process audio input using the 
     * Send audio + prompt directly through the persistent conversation.
     * The model processes audio natively — no separate transcription step.
     */
    suspend fun processAudioInput(
        audioFile: File,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val engine = inferenceEngine ?: run {
            loadModel()
            inferenceEngine ?: throw IllegalStateException("Model failed to load")
        }

        if (!audioFile.exists()) {
            return@withContext Pair("", "Audio file not found. Please try recording again.")
        }

        val audioBytes = audioFile.readBytes()
        if (audioBytes.isEmpty()) {
            return@withContext Pair("", "Audio recording was empty. Please try again.")
        }

        //  send audio directly to the model.
        // No text prompt when user sends audio only — the model processes audio natively.
        // Adding a text prompt like "Listen to this audio" confuses the model into
        // thinking it can't access the audio.
        val response = engine.generateWithAudio(audioBytes, "")

        // Extract a transcription hint from the response for the UI
        // The model naturally processes the audio content in its response
        Pair("[Audio message]", response)
    }

    /**
     * Error recovery: full cleanup → reinitialize.
     * Returns true if recovery succeeded.
     */
    suspend fun recoverFromError(): Boolean {
        debugLog(TAG, "Error recovery: cleaning up and reinitializing")
        cleanupModel()
        return try {
            loadModel()
            _initState.value == InitState.LOADED
        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed", e)
            false
        }
    }

    /**
     * Cleanup the model. Handles race condition with init.
     */
    fun cleanupModel() {
        if (isInitializing.get()) {
            // Can't cleanup while initializing — set flag for post-init cleanup
            cleanUpAfterInit.set(true)
            debugLog(TAG, "Cleanup requested during init — flagged for cleanup after init completes")
            return
        }
        // capture reference at call time.
        // If loadModel() created a new engine between capture and null-out,
        // we must NOT null the new engine's reference.
        val engineToCleanUp = inferenceEngine
        if (engineToCleanUp == null) {
            _state.value = ModelState.Idle
            _initState.value = InitState.NOT_LOADED
            return
        }
        engineToCleanUp.close()
        if (inferenceEngine === engineToCleanUp) {
            inferenceEngine = null
            _state.value = ModelState.Idle
            _initState.value = InitState.NOT_LOADED
        }
        debugLog(TAG, "Model cleaned up")
    }

    /**
     * Unload the model to free memory.
     */
    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        cleanupModel()
        //  give native allocator time to reclaim pages before next load
        System.gc()
        delay(500)
        debugLog(TAG, "Model unloaded (native memory freed)")
    }

    /**
     * Get the InferenceEngine (for direct access if needed).
     */
    fun getEngine(): InferenceEngine? = inferenceEngine

    fun isModelLoaded(): Boolean = _initState.value == InitState.LOADED

    /**
     * Check if the engine is actually alive (not just that Kotlin thinks it's loaded).
     * The OS can reclaim native memory while the app is backgrounded, leaving the Kotlin
     * state as LOADED but the native engine dead. This detects that case.
     */
    fun isEngineAlive(): Boolean {
        val engine = inferenceEngine ?: return false
        return engine is LiteRTInferenceEngine && engine.isAlive()
    }

    /**
     * Process an image and extract text/concepts using vision.
     * Uses a one-shot temp conversation — does NOT affect the persistent conversation.
     * Falls back to basic extraction if vision fails.
     */
    suspend fun processImageInput(imageBytes: ByteArray, prompt: String = "Describe what's in this image clearly and concisely. Extract any text, key concepts, or information the user would want to know."): String = withContext(Dispatchers.IO) {
        val engine = inferenceEngine ?: run {
            loadModel()
            inferenceEngine ?: throw IllegalStateException("Model failed to load")
        }
        try {
            engine.analyzeImage(imageBytes, prompt)
        } catch (e: Exception) {
            Log.w(TAG, "Image analysis failed: ${e.message}")
            "[Image analysis unavailable]"
        }
    }

    /**
     * One-shot analysis with a specific system prompt.
     * Creates a temporary conversation — does NOT affect the persistent conversation.
     * Used for reflection classification and content analysis.
     */
    open suspend fun generateAnalysis(systemPrompt: String, userMessage: String): String = withContext(Dispatchers.IO) {
        kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            val engine = inferenceEngine ?: run {
                loadModel()
                inferenceEngine ?: throw IllegalStateException("Model failed to load")
            }
            engine.analyze(systemPrompt, userMessage)
        } ?: run {
            debugLog(TAG, "generateAnalysis timed out after 30s")
            "Summary unavailable — timed out."
        }
    }

    private fun getModelFile(): File {
        return ModelDownloader(context).getModelFile(selectedModelKey)
    }

    /**
     * Check if the device has enough free memory for model loading.
     * Returns false if available memory is below [MIN_FREE_MEMORY_MB].
     */
    /**
     * Check if device has enough total RAM for the model .
     * Uses totalMem (not availMem) with per-model thresholds.
     * Returns a warning message if device RAM is below threshold, null if OK.
     */
    private fun checkAvailableMemory(variant: ModelDownloader.ModelVariant): String? {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null // Can't check — allow loading
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val minGb = MIN_DEVICE_RAM_GB[variant.key] ?: return null
        val deviceGb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            memInfo.advertisedMem / BYTES_IN_GB
        } else {
            memInfo.totalMem / BYTES_IN_GB
        }
        debugLog(TAG, "Device RAM: ${String.format("%.1f", deviceGb)}GB (min for ${variant.displayName}: ${minGb}GB)")
        return if (deviceGb < minGb) {
            "${variant.displayName} works best on devices with ${minGb.toInt()}GB+ RAM (yours has ${String.format("%.1f", deviceGb)}GB). Performance may be limited."
        } else null
    }

    /** Convert accelerator string to LiteRT Backend. */
    private fun configToBackend(accelerator: String): com.google.ai.edge.litertlm.Backend? {
        return when (accelerator) {
            "cpu" -> com.google.ai.edge.litertlm.Backend.CPU()
            "gpu" -> com.google.ai.edge.litertlm.Backend.GPU()
            "npu" -> com.google.ai.edge.litertlm.Backend.NPU()
            else -> null // auto = null → fallback chain
        }
    }
}
