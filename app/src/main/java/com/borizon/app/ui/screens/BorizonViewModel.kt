package com.borizon.app.ui.screens

import android.Manifest
import android.content.Intent
import android.util.Log
import com.borizon.app.util.debugLog
import androidx.core.content.FileProvider
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.borizon.app.ai.agents.ReflectAgent
import com.borizon.app.ai.prompts.StarterTemplate
import com.borizon.app.ai.tools.BorizonAction
import com.borizon.app.ai.tools.ToolEvent
import com.borizon.app.ai.inference.ModelDownloader
import com.borizon.app.ai.inference.ModelManager
import com.borizon.app.ai.inference.ModelManager.ModelState
import com.borizon.app.audio.AudioRecorder
import com.borizon.app.audio.SpeechPlayer
import com.borizon.app.audio.SpeechTranscriber
import com.borizon.app.data.database.BorizonDatabase
import com.borizon.app.data.models.ChatMessage
import com.borizon.app.data.models.MessageType
import com.borizon.app.data.models.Conversation
import com.borizon.app.data.models.MemoryCategory
import com.borizon.app.data.models.MemoryEntry
import com.borizon.app.data.models.Reflection
import com.borizon.app.data.PreferencesManager
import com.borizon.app.di.AppLifecycleProvider
import com.borizon.app.ui.components.ModelConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central ViewModel that owns all agents and provides state to screens.
 */
@HiltViewModel
class BorizonViewModel @Inject constructor(
    private val database: BorizonDatabase,
    private val modelManager: ModelManager,
    private val prefs: PreferencesManager,
    val lifecycleProvider: AppLifecycleProvider,
    private val skillManager: com.borizon.app.skills.SkillManager,
    private val jsBridge: com.borizon.app.ai.tools.JavascriptBridge,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "BorizonViewModel"

        /** Downscale bitmap so max(width, height) <= maxSize. Returns original if already small enough. */
        private fun downscaleBitmap(bitmap: android.graphics.Bitmap, maxSize: Int): android.graphics.Bitmap {
            if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap
            val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            return android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
    }

    private val sendMutex = Mutex()
    private val modelDownloader = ModelDownloader(appContext)
    private val audioRecorder = AudioRecorder(appContext, viewModelScope)
    private val speechPlayer = SpeechPlayer(appContext)
    private val speechTranscriber = SpeechTranscriber(appContext)


    private val contextCompactor = com.borizon.app.ai.harness.ContextCompactor(modelManager)

    private val reflectAgent = ReflectAgent(
        modelManager = modelManager,
        reflectionDao = database.reflectionDao(),
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        memoryDao = database.memoryDao(),
        skillManager = skillManager,
        contextCompactor = contextCompactor,
    )

    // PhoneTools — intent-based phone actions + SMS + call log (merged)
    private val phoneTools = com.borizon.app.ai.tools.PhoneTools(appContext, reflectAgent.actionChannel)

    // WebTools — combined search + read (merged)
    private val webTools = com.borizon.app.ai.tools.WebTools(
        actionChannel = reflectAgent.actionChannel,
        apiKeyProvider = { _currentBraveApiKey },
    )

    // MemoryTools — save/search/forget facts about the user
    private val memoryTools = com.borizon.app.ai.tools.MemoryTools(
        memoryDao = database.memoryDao(),
        actionChannel = reflectAgent.actionChannel,
        getActiveConversationId = { reflectAgent.activeConversationId },
    )

    // NotificationTools — read/search phone notification history
    private val notificationTools = com.borizon.app.ai.tools.NotificationTools(
        notificationDao = database.notificationDao(),
        actionChannel = reflectAgent.actionChannel,
        context = appContext,
    )

    // SkillTools — combined list + load (merged)
    private val skillTools: com.borizon.app.ai.tools.SkillTools? =
        skillManager?.let { sm -> jsBridge?.let { bridge -> com.borizon.app.ai.tools.SkillTools(sm, bridge, reflectAgent.actionChannel) } }

    // Device data tools
    private val shellTools = com.borizon.app.ai.tools.ShellTools(appContext, reflectAgent.actionChannel)


    /** Cached API key — updated from PreferencesManager flow. */
    @Volatile
    private var _currentBraveApiKey: String = ""

    /** Reactive flow of the user's Brave Search API key. */
    val braveApiKey: kotlinx.coroutines.flow.Flow<String> = prefs.braveApiKey

    /** Update the stored API key. Called from Settings screen. */
    fun setBraveApiKey(key: String) {
        viewModelScope.launch {
            prefs.setBraveApiKey(key)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        _isBiometricEnabled.value = enabled  // immediate UI feedback
        viewModelScope.launch {
            prefs.setBiometricEnabled(enabled)
        }
    }

    /** Core tools — always registered. */
    private val coreTools get() = listOfNotNull(
        shellTools, memoryTools, webTools, phoneTools, notificationTools,
    )

    /** Extended tools — only registered for E4B model. */
    private val extendedTools get() = listOfNotNull(
        skillTools,
    )

    /** All extra tools — core for E2B, all for E4B. */
    private val extraTools: List<com.google.ai.edge.litertlm.ToolSet>
        get() = if (modelManager.selectedModelKey == "E4B") {
            coreTools + extendedTools
        } else {
            coreTools
        }

    /** Reinitialize with visible loading state. Wraps all reinit paths. */
    private suspend fun safeReinit(tools: List<com.google.ai.edge.litertlm.ToolSet>) {
        _isReinitializing.value = true
        try {
            reflectAgent.reinitWithTools(tools)
        } finally {
            _isReinitializing.value = false
        }
    }


    private var generateJob: kotlinx.coroutines.Job? = null

    // ── Preferences State ─────────────────────────────────────────

    /** Whether preferences have been loaded from DataStore (not just defaults). */
    private val _prefsLoaded = MutableStateFlow(false)
    val prefsLoaded: StateFlow<Boolean> = _prefsLoaded

    val isFirstLaunch: StateFlow<Boolean> = prefs.isFirstLaunch
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isOnboardingComplete: StateFlow<Boolean> = prefs.isOnboardingComplete
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val userName: StateFlow<String> = prefs.userName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val isTosAccepted: StateFlow<Boolean> = prefs.isTosAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isBiometricEnabled = MutableStateFlow(false)
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled

    // Tool events — declared before init so they're available during initialization
    private val _toolEvents = MutableStateFlow<List<ToolEvent>>(emptyList())
    val toolEvents: StateFlow<List<ToolEvent>> = _toolEvents
    private val _frozenToolEvents = MutableStateFlow<List<ToolEvent>>(emptyList())
    val frozenToolEvents: StateFlow<List<ToolEvent>> = _frozenToolEvents
    private var toolEventIdCounter = 0

    // Model download state — declared before init for same reason
    private val _modelDownloaded = MutableStateFlow(false)
    val selectedModel: StateFlow<String> = prefs.selectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "E2B")

    // Memory UI state — declared before init
    private val memoryDao = database.memoryDao()
    private val _memories = MutableStateFlow<List<com.borizon.app.data.models.MemoryEntry>>(emptyList())
    val memories: StateFlow<List<com.borizon.app.data.models.MemoryEntry>> = _memories
    @Volatile
    private var memorySearchQuery: String? = null

    // State collections — declared before init
    private val _modelConfig = MutableStateFlow(ModelConfig())
    val modelConfig: StateFlow<ModelConfig> = _modelConfig
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations
    private val _recentReflections = MutableStateFlow<List<Reflection>>(emptyList())
    val recentReflections: StateFlow<List<Reflection>> = _recentReflections
    private val _pendingAskAction = MutableStateFlow<BorizonAction.AskUser?>(null)
    val pendingAskAction: StateFlow<BorizonAction.AskUser?> = _pendingAskAction
    private val _pendingConfirmAction = MutableStateFlow<BorizonAction.Confirm?>(null)
    val pendingConfirmAction: StateFlow<BorizonAction.Confirm?> = _pendingConfirmAction

    init {
        // ── Prefs loading ────────────────────────────────────────
        if (prefs.hasExistingSettings) {
            // Fast path for returning users — but we still need the first
            // emission from critical prefs before declaring loaded.
            viewModelScope.launch {
                prefs.isBiometricEnabled.first()
                _prefsLoaded.value = true
            }
        } else {
            viewModelScope.launch {
                prefs.isOnboardingComplete.first()
                _prefsLoaded.value = true
            }
        }

        // ── State collection ─────────────────────────────────────
        viewModelScope.launch {
            prefs.modelConfig.collect { _modelConfig.value = it }
        }
        viewModelScope.launch {
            prefs.braveApiKey.collect { _currentBraveApiKey = it }
        }
        viewModelScope.launch {
            prefs.isBiometricEnabled.collect { _isBiometricEnabled.value = it }
        }
        viewModelScope.launch {
            database.reflectionDao().getAllReflections()
                .collect { _recentReflections.value = it }
        }
        viewModelScope.launch {
            database.conversationDao().getAllConversations()
                .collect { _conversations.value = it }
        }

        // ── Keystore key invalidation: notify user of data loss ───
        if (BorizonDatabase.wasDataLostDueToKeyInvalidation) {
            BorizonDatabase.wasDataLostDueToKeyInvalidation = false
            reportBackgroundError("Your device security settings changed and encrypted data could not be recovered. A fresh database was created.")
        }

        // ── Foreground lifecycle: compile session, recover engine ─
        viewModelScope.launch {
            var backgroundedAt = 0L
            lifecycleProvider.isInForeground.collect { inForeground ->
                if (!inForeground) {
                    backgroundedAt = System.currentTimeMillis()
                    compileCurrentSession()
                } else if (backgroundedAt > 0) {
                    val bgDuration = System.currentTimeMillis() - backgroundedAt
                    if (modelManager.isModelLoaded() && bgDuration > 30_000 && !modelManager.isEngineAlive()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            sendMutex.withLock {
                                debugLog(TAG, "App foregrounded after ${bgDuration}ms — native engine dead, reloading")
                                modelManager.unloadModel()
                                try { modelManager.loadModel() } catch (_: Exception) {}
                                if (modelManager.isModelLoaded()) {
                                    safeReinit(extraTools)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Action channel processing ────────────────────────────
        viewModelScope.launch {
            for (action in reflectAgent.actionChannel) {
                when (action) {
                    is BorizonAction.Progress -> {
                        if (action.isInProgress) {
                            toolEventIdCounter++
                            _toolEvents.update { it + ToolEvent(
                                id = toolEventIdCounter,
                                label = action.label,
                                toolType = action.toolType,
                                isInProgress = true,
                                startTimeMs = System.currentTimeMillis(),
                                detailDescription = action.detailDescription,
                            ) }
                        } else {
                            _toolEvents.update { events ->
                                events.map { event ->
                                    if (event.toolType == action.toolType && event.isInProgress) {
                                        event.copy(
                                            label = action.label,
                                            isInProgress = false,
                                            navigationTarget = action.navigationTarget,
                                            endTimeMs = System.currentTimeMillis(),
                                            detailDescription = action.detailDescription.ifBlank { event.detailDescription },
                                        )
                                    } else event
                                }
                            }
                        }
                    }
                    is BorizonAction.Dashboard -> {
                        val webViewMsg = ChatMessage(
                            role = "system",
                            content = action.title,
                            type = MessageType.WEBVIEW,
                            webViewUrl = action.url,
                            webViewTitle = action.title,
                            webViewAspectRatio = action.aspectRatio,
                        )
                        reflectAgent.addSystemMessage(webViewMsg)
                    }
                    is BorizonAction.AskUser -> { _pendingAskAction.value = action }
                    is BorizonAction.Confirm -> { _pendingConfirmAction.value = action }
                }
            }
        }

        viewModelScope.launch {
            reflectAgent.isGenerating.collect { generating ->
                if (!generating) {
                    if (_toolEvents.value.isNotEmpty()) {
                        val events = _toolEvents.value
                        _frozenToolEvents.value = events
                        _toolEvents.value = emptyList()
                        reflectAgent.embedToolEvents(events)
                    }
                } else {
                    _frozenToolEvents.value = emptyList()
                }
            }
        }

        // ── Skills (load early, independent of model) ──────
        viewModelScope.launch {
            skillManager.loadSkills()
        }

        // ── Model loading ────────────────────────────────────────
        viewModelScope.launch {
            val savedConfig = prefs.modelConfig.first()
            val key = prefs.selectedModel.first()
            modelManager.selectedModelKey = key
            try {
                modelManager.loadModel(savedConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed on init: ${e.message}")
            }
            if (modelManager.isModelLoaded()) {
                reflectAgent.reinitWithTools(extraTools)
            }
        }

        // ── Model download state ─────────────────────────────────
        viewModelScope.launch {
            prefs.isOnboardingComplete.first()
            selectedModel.collect { key ->
                _modelDownloaded.value = modelDownloader.isModelDownloaded(key)
                modelDownloader.checkInitialState(key)
            }
        }
        viewModelScope.launch {
            modelDownloader.state.collect { state ->
                if (state is ModelDownloader.DownloadState.Complete) {
                    _modelDownloaded.value = true
                    // Auto-load model after download completes
                    loadDownloadedModel()
                } else if (state is ModelDownloader.DownloadState.Idle) {
                    _modelDownloaded.value = modelDownloader.isModelDownloaded(selectedModel.value)
                }
            }
        }
        viewModelScope.launch {
            prefs.isOnboardingComplete.first()
            val key = selectedModel.first()
            if (modelDownloader.isModelDownloaded(key)) {
                try {
                    loadDownloadedModel()
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-load failed: ${e.message}")
                }
            }
        }

        // ── Memory search ────────────────────────────────────────
        viewModelScope.launch {
            memoryDao.getAllFlow().collect { all ->
                val q = memorySearchQuery
                _memories.value = if (q.isNullOrBlank()) all else all.filter {
                    it.content.contains(q, ignoreCase = true) ||
                        it.category.name.contains(q, ignoreCase = true)
                }
            }
        }
    }

    // ── Chat State ────────────────────────────────────────────

    val sessionMessages: StateFlow<List<ChatMessage>> = reflectAgent.sessionMessages
    val isConversationReady: StateFlow<Boolean> = reflectAgent.isConversationReadyState
    val isGenerating: StateFlow<Boolean> = reflectAgent.isGenerating
    val streamingText: StateFlow<String> = reflectAgent.streamingText
    val generationStartTime: StateFlow<Long> = reflectAgent.generationStartTime
    val lastResponseDurationMs: StateFlow<Long> = reflectAgent.lastResponseDurationMs
    val modelState: StateFlow<ModelManager.ModelState> = modelManager.state
    val modelInitState: StateFlow<ModelManager.InitState> = modelManager.initState
    val greeting: String get() = reflectAgent.getGreeting()
    val activeConversationId: StateFlow<Long> = reflectAgent.activeConversationIdFlow
    val streamingThinkingText: StateFlow<String> = reflectAgent.streamingThinkingText
    val lastError: StateFlow<String?> = reflectAgent.lastError

    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages

    fun loadOlderMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = reflectAgent.loadOlderMessages()
            if (loaded == 0) _hasOlderMessages.value = false
        }
    }

    /** Background processing errors — shown as snackbar. */
    private val _backgroundError = MutableStateFlow<String?>(null)
    val backgroundError: StateFlow<String?> = _backgroundError

    /** Emit background errors as snackbar messages. */
    private fun reportBackgroundError(message: String) {
        _backgroundError.value = message
    }

    /** Clear the background error after it has been shown. */
    fun clearBackgroundError() {
        _backgroundError.value = null
    }

    val isResetting: StateFlow<Boolean> = reflectAgent.isResetting
    val streamingTokensPerSecond: StateFlow<Float> = reflectAgent.streamingTokensPerSecond
    val wallClockTps: StateFlow<Float> = reflectAgent.wallClockTps
    val totalTokensGenerated: StateFlow<Int> = reflectAgent.totalTokensGenerated

    // -- CompletableDeferred bridge: pending UI actions from tools --
    fun completeAskAction(answer: String) {
        _pendingAskAction.value?.result?.complete(answer)
        _pendingAskAction.value = null
    }

    fun dismissAskAction() {
        _pendingAskAction.value?.result?.complete("")
        _pendingAskAction.value = null
    }

    fun completeConfirmAction(approved: Boolean) {
        _pendingConfirmAction.value?.result?.complete(approved)
        _pendingConfirmAction.value = null
    }

    /** Whether background ingestion/vision is running (non-blocking). */
    private val _isBackgroundProcessing = MutableStateFlow(false)
    val isBackgroundProcessing: StateFlow<Boolean> = _isBackgroundProcessing

    // ── Input History ───────────────────────────────────────────

    val inputHistory: StateFlow<List<String>> = prefs.textInputHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearInputHistory() {
        viewModelScope.launch { prefs.clearInputHistory() }
    }

    /** Search across all conversations — title match + reflection content match. */
    fun searchConversations(query: String, callback: (List<ConversationSearchResult>) -> Unit) {
        if (query.isBlank()) { callback(emptyList()); return }
        viewModelScope.launch(Dispatchers.IO) {
            val results = mutableListOf<ConversationSearchResult>()
            // 1. Title matches from loaded conversations
            val titleMatches = _conversations.value.filter {
                it.title.contains(query, ignoreCase = true)
            }
            for (conv in titleMatches) {
                results.add(ConversationSearchResult(
                    conversationId = conv.id,
                    title = conv.title,
                    snippet = conv.sessionSummary.take(100).ifBlank { conv.title },
                    timestamp = conv.updatedAt,
                ))
            }
            // 2. Content matches from reflections
            val reflectionMatches = try {
                database.reflectionDao().searchReflections(query, limit = 20)
            } catch (_: Exception) { emptyList() }
            val seenIds = results.map { it.conversationId }.toMutableSet()
            for (ref in reflectionMatches) {
                val convId = ref.conversationId ?: continue
                if (convId in seenIds) continue
                seenIds.add(convId)
                val conv = database.conversationDao().getById(convId) ?: continue
                // Build snippet from the matching turn
                val snippet = buildString {
                    val userSnippet = ref.userText.take(60)
                    val respSnippet = ref.borizonResponse.take(60)
                    append(userSnippet)
                    if (respSnippet.isNotBlank()) append(" → $respSnippet")
                }
                results.add(ConversationSearchResult(
                    conversationId = convId,
                    title = conv.title,
                    snippet = snippet,
                    timestamp = ref.timestamp,
                ))
            }
            results.sortByDescending { it.timestamp }
            callback(results)
        }
    }

    /** A single search result from conversation content search. */
    data class ConversationSearchResult(
        val conversationId: Long,
        val title: String,
        val snippet: String,
        val timestamp: Long,
    )

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (reflectAgent.lastError.value != null && !modelManager.isModelLoaded()) {
            _backgroundError.value = "Model not loaded. Check Settings > AI Model."
            return
        }
        generateJob = viewModelScope.launch(Dispatchers.IO) {
            prefs.saveTextInput(trimmed)
            sendMutex.withLock {
                reflectAgent.reflect(trimmed)
                prefs.incrementReflectionCount()
            }
        }
    }

    /**
     * Send a message that may include images.
     * Images appear as a user message in chat AND are processed for vision.
     *  chat response generated first, then vision runs in background.
     */
    fun sendMessageWithImages(text: String, images: List<android.graphics.Bitmap>) {
        val trimmed = text.trim()
        if (trimmed.isBlank() && images.isEmpty()) return

        generateJob = viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                val userMsg = ChatMessage(
                    role = "user",
                    content = if (trimmed.isNotBlank()) trimmed else "[Image]",
                    imageBitmaps = images.ifEmpty { null },
                )
                reflectAgent.addUserMessage(userMsg)
                if (trimmed.isNotBlank()) prefs.saveTextInput(trimmed)
                prefs.incrementReflectionCount()

                reflectAgent.reflectFromLastUser()
            }

            processImagesInBackground(images)
        }
    }

    /**
     * Process a selected text document — opens a chat with the document
     * pre-loaded for LLM analysis.
     */
    fun processDocument(text: String, fileName: String) {
        handleIngestedContent(text, fileName)
    }

    fun startChatWithTemplate(template: StarterTemplate, prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        generateJob = viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                reflectAgent.newConversation(template)
                prefs.saveTextInput(trimmed)
                reflectAgent.reflect(trimmed)
                prefs.incrementReflectionCount()
            }
        }
    }

    /**
     * Handle content shared from another app (via IngestActivity).
     * Starts a new conversation with the content pre-loaded for LLM analysis.
         */
    fun handleIngestedContent(text: String, source: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        generateJob = viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                reflectAgent.newConversation()
                val prompt = buildString {
                    appendLine("I just shared this content with you from another app. Please read it and extract the key information.")
                    appendLine()
                    appendLine("Source: $source")
                    appendLine()
                    appendLine("---")
                    appendLine(trimmed.take(3000))
                    appendLine("---")
                    appendLine()
                    appendLine("Focus on facts, insights, and anything worth remembering.")
                }
                prefs.saveTextInput(prompt)
                reflectAgent.reflect(prompt)
                prefs.incrementReflectionCount()
            }
        }
    }

    fun handleIngestedImage(uri: android.net.Uri) {
        generateJob = viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                try {
                    val inputStream = appContext.contentResolver.openInputStream(uri) ?: return@withLock
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withLock
                    val downscaled = downscaleBitmap(bitmap, 1024)
                    val stream = java.io.ByteArrayOutputStream()
                    downscaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
                    if (downscaled !== bitmap) downscaled.recycle()
                    bitmap.recycle()

                    reflectAgent.newConversation()
                    val description = modelManager.processImageInput(stream.toByteArray())
                    val prompt = buildString {
                        appendLine("I shared an image with you from another app.")
                        if (description.isNotBlank()) {
                            appendLine("Image description: $description")
                        }
                        appendLine()
                        appendLine("Please analyze this image and extract any useful information.")
                    }
                    prefs.saveTextInput(prompt)
                    reflectAgent.reflect(prompt)
                    prefs.incrementReflectionCount()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process ingested image", e)
                }
            }
        }
    }

    fun stopGeneration() {
        generateJob?.cancel()
        generateJob = null
        reflectAgent.stopResponse()
    }

    override fun onCleared() {
        super.onCleared()
        generateJob?.cancel()
        generateJob = null
        _pendingAskAction.value?.result?.complete("")
        _pendingAskAction.value = null
        _pendingConfirmAction.value?.result?.complete(false)
        _pendingConfirmAction.value = null
        reflectAgent.stopResponse()
        speechPlayer.shutdown()
        audioRecorder.release()
        speechTranscriber.destroy()
        jsBridge?.destroy()
    }

    /**
     * Process images through vision in background. Shows errors via snackbar.
     * Non-blocking — user can send new messages immediately.
     */
    private fun processImagesInBackground(images: List<android.graphics.Bitmap>) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackgroundProcessing.value = true
            try {
                for (bitmap in images) {
                    try {
                        val downscaled = downscaleBitmap(bitmap, 1024)
                        val stream = java.io.ByteArrayOutputStream()
                        downscaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
                        if (downscaled !== bitmap) downscaled.recycle()
                        val bytes = stream.toByteArray()
                        val description = modelManager.processImageInput(bytes)
                        if (description.isBlank() && !modelManager.isModelLoaded()) {
                            reportBackgroundError("Image analysis skipped — model not loaded. Check Settings > AI Model.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Image vision failed", e)
                        reportBackgroundError("Image processing failed: ${e.message}")
                    }
                }
            } finally {
                _isBackgroundProcessing.value = false
            }
        }
    }

    fun regenerate() {
        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                reflectAgent.regenerate()
            }
        }
    }

    fun newChat(template: StarterTemplate = StarterTemplate.DEFAULT) {
        _frozenToolEvents.value = emptyList()
        _hasOlderMessages.value = false
        generateJob?.cancel()
        generateJob = null
        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                reflectAgent.newConversation(template)
            }
        }
    }

    // ── Voice Input ───────────────────────────────────────────

    val isRecording: StateFlow<Boolean> = speechTranscriber.isListening
    val voiceAmplitude: StateFlow<Int> = speechTranscriber.amplitude
    val transcriptionPartial: StateFlow<String> = speechTranscriber.partialText

    private val _micPermissionNeeded = MutableStateFlow(false)
    val micPermissionNeeded: StateFlow<Boolean> = _micPermissionNeeded

    fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            _micPermissionNeeded.value = true
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        if (granted) startListening()
    }

    private fun startListening() {
        viewModelScope.launch {
            try {
                val text = withTimeout(30_000L) { speechTranscriber.transcribe() }
                if (text.isNotBlank()) sendMessage(text)
            } catch (e: Exception) { Log.w(TAG, "Voice transcription failed", e); _backgroundError.value = "Voice recognition failed. Please try again." }
        }
    }

    fun stopListening() {
        speechTranscriber.stopListening()
    }

    // ── Audio Clip Recording (long-press mic) ──────────────────────

    @Volatile
    private var pendingPcmBytes: ByteArray? = null
    private val MAX_AUDIO_BYTES = 2 * 1024 * 1024 // 2 MB cap for safety

    val isAudioRecording: StateFlow<Boolean> = audioRecorder.isRecording
    val audioAmplitude: StateFlow<Int> = audioRecorder.amplitude

    fun startAudioRecording() {
        try {
            audioRecorder.startRecording()
            debugLog(TAG, "Audio recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
        }
    }

    fun stopAudioRecording(): java.io.File? {
        return try {
            val pcmBytes = audioRecorder.stopRecording()
            if (pcmBytes.isEmpty()) {
                Log.w(TAG, "No audio data recorded")
                return null
            }
            if (pcmBytes.size > MAX_AUDIO_BYTES) {
                Log.w(TAG, "Audio recording too large (${pcmBytes.size} bytes), discarding")
                return null
            }
            pendingPcmBytes = pcmBytes

            // Write WAV to temp file for AudioPlaybackPanel preview
            val wavFile = java.io.File(appContext.cacheDir, "audio_${System.currentTimeMillis()}.wav")
            com.borizon.app.audio.AudioRecorder.writeWavFile(pcmBytes, wavFile)
            debugLog(TAG, "Audio saved: ${pcmBytes.size} bytes PCM -> ${wavFile.absolutePath}")
            wavFile
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed", e)
            null
        }
    }

    fun cancelAudioRecording() {
        try { audioRecorder.stopRecording() } catch (e: Exception) { Log.w(TAG, "Failed to cancel audio recording", e) }
        pendingPcmBytes = null
    }

    fun sendMessageWithAudio(audioFile: java.io.File) {
        generateJob = viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                pendingPcmBytes = null

                // Show user message with audio playback in chat
                val userMsg = ChatMessage(
                    role = "user",
                    content = "\uD83C\uDF99\uFE0F Audio message",
                    type = MessageType.AUDIO,
                    audioFilePath = audioFile.absolutePath,
                )
                reflectAgent.addUserMessage(userMsg)
                prefs.incrementReflectionCount()

                // Process audio through model: transcribe -> reflect
                try {
                    reflectAgent.reflectAudio(audioFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Audio processing failed", e)
                }
            }
        }
    }

    // ── Text-to-Speech ─────────────────────────────────────────

    val isSpeaking: StateFlow<Boolean> = speechPlayer.isSpeaking
    private var _speakingIndex = MutableStateFlow(-1)
    val speakingMessageIndex: StateFlow<Int> = _speakingIndex

    fun speakMessage(text: String, index: Int) {
        _speakingIndex.value = index
        speechPlayer.speak(text)
    }

    fun stopSpeaking() {
        speechPlayer.stop()
        _speakingIndex.value = -1
    }

    // ── Conversations ──────────────────────────────────────────

    fun loadConversation(id: Long) {
        generateJob?.cancel()
        generateJob = null
        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                reflectAgent.loadConversation(id)
                val total = database.messageDao().countForConversation(id)
                _hasOlderMessages.value = total > 200
            }
        }
    }


    fun deleteConversation(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversation = database.conversationDao().getById(id) ?: return@launch
                database.withTransaction {
                    database.conversationDao().deleteWithMessages(conversation, database.messageDao())
                }
                _conversations.update { it.filter { c -> c.id != id } }
            } catch (e: Exception) {
                android.util.Log.e("BorizonViewModel", "Delete conversation failed", e)
            }
            if (reflectAgent.activeConversationId == id) {
                sendMutex.withLock { reflectAgent.newConversation() }
            }
        }
    }

    // ── Insights State (kept for backward compat during migration) ──

    /** Whether the model is currently reinitializing due to a config change. */
    private val _isReinitializing = MutableStateFlow(false)
    val isReinitializing: StateFlow<Boolean> = _isReinitializing

    fun updateModelConfig(config: ModelConfig) {
        val oldConfig = _modelConfig.value
        _modelConfig.value = config
        generateJob?.cancel()
        generateJob = null
        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                prefs.updateModelConfig(config)
                val changes = modelManager.applyConfig(config)
                if (changes.isNotEmpty()) {
                    _isReinitializing.value = true
                    try {
                        //  reload model if accelerator changed, then reinit conversation
                        if (!modelManager.isModelLoaded()) {
                            modelManager.loadModel(config)
                        }
                        if (modelManager.isModelLoaded()) {
                            reflectAgent.reinitWithConfig()
                        }
                        // Show config change in chat
                        if (reflectAgent.activeConversationId > 0L) {
                            val configMsg = ChatMessage(
                                role = "system",
                                content = "Configs updated",
                                type = MessageType.CONFIG_CHANGE,
                                configChanges = changes,
                            )
                            reflectAgent.addSystemMessage(configMsg)
                        }
                    } finally {
                        _isReinitializing.value = false
                    }
                }
            }
        }
    }

    private fun compileCurrentSession() {
        val sessionRef = reflectAgent.getActiveSessionRef() ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                reflectAgent.compileSession()
            } catch (e: Exception) {
                Log.w(TAG, "Session compilation failed", e)
            }
        }
    }



    // ── Onboarding ────────────────────────────────────────────

    fun completeOnboarding(name: String) {
        viewModelScope.launch {
            prefs.completeOnboarding(name)
            // Save user's name to AI memory so the model knows it
            if (name.isNotBlank()) {
                memoryDao.insert(
                    MemoryEntry(
                        content = "User's name is $name",
                        category = MemoryCategory.PREFERENCE,
                        importance = 1.0f,
                    )
                )
            }
        }
    }

    fun acceptTos() {
        viewModelScope.launch { prefs.acceptTos() }
    }

    // ── Export ────────────────────────────────────────────────

    fun exportConversation() {
        val messages = sessionMessages.value
        if (messages.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val exportDir = File(appContext.getExternalFilesDir(null), "exports")
            exportDir.mkdirs()
            val file = File(exportDir, "borizon_conversation_${System.currentTimeMillis()}.txt")
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            file.bufferedWriter().use { writer ->
                writer.write("Borizon Conversation\n")
                writer.write("=".repeat(40) + "\n\n")
                for (msg in messages) {
                    val label = if (msg.role == "user") "You" else "Borizon"
                    writer.write("[$label - ${timeFormat.format(Date(msg.timestamp))}]\n")
                    writer.write(msg.content + "\n\n")
                }
            }

            withContext(Dispatchers.Main) {
                try {
                    val uri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "text/plain"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    appContext.startActivity(
                        Intent.createChooser(shareIntent, "Share conversation")
                    )
                } catch (e: Exception) { Log.w(TAG, "Failed to export conversation", e); _backgroundError.value = "Export failed: ${e.message}" }
            }
        }
    }

    // ── Skills State ──────────────────────────────────────────

    val skills: StateFlow<List<com.borizon.app.proto.Skill>> = skillManager.skills

    fun toggleSkill(name: String, selected: Boolean) {
        viewModelScope.launch {
            skillManager.setSkillSelected(name, selected)
            safeReinit(extraTools)
        }
    }

    fun importSkillFromDirectory(uri: android.net.Uri) {
        viewModelScope.launch {
            skillManager.importSkillFromDirectory(uri)
                .onSuccess { name ->
                    debugLog(TAG, "Imported skill: $name")
                    safeReinit(extraTools)
                }
                .onFailure { e ->
                    Log.e(TAG, "Import failed: ${e.message}")
                    reportBackgroundError("Import failed: ${e.message}")
                }
        }
    }

    fun importSkillFromFile(uri: android.net.Uri) {
        viewModelScope.launch {
            skillManager.importSkillFromFile(uri)
                .onSuccess { name ->
                    debugLog(TAG, "Imported skill: $name")
                    safeReinit(extraTools)
                }
                .onFailure { e ->
                    Log.e(TAG, "Import failed: ${e.message}")
                    reportBackgroundError("Import failed: ${e.message}")
                }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch {
            skillManager.deleteSkill(name)
                .onSuccess {
                    debugLog(TAG, "Deleted skill: $name")
                    safeReinit(extraTools)
                }
                .onFailure { e ->
                    Log.e(TAG, "Delete failed: ${e.message}")
                    reportBackgroundError("Delete failed: ${e.message}")
                }
        }
    }

    // ── Settings Actions ──────────────────────────────────────

    data class ModelInfo(
        val isDownloaded: Boolean,
        val fileSizeMb: Long,
        val state: ModelManager.ModelState,
        val modelKey: String = "E4B",
        val needsUpdate: Boolean = false,
        val installedVersion: String? = null,
        val currentVersion: String = "v1",
    )

    val modelInfo: StateFlow<ModelInfo> = combine(
        modelManager.state,
        _modelDownloaded,
        selectedModel
    ) { state, downloaded, modelKey ->
        val file = modelDownloader.getModelFile(modelKey)
        val v = ModelDownloader.variant(modelKey)
        ModelInfo(
            isDownloaded = downloaded,
            fileSizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0,
            state = state,
            modelKey = modelKey,
            needsUpdate = modelDownloader.needsUpdate(modelKey),
            installedVersion = modelDownloader.getInstalledVersion(modelKey),
            currentVersion = v.version,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, run {
        val key = selectedModel.value
        val f = modelDownloader.getModelFile(key)
        val v = ModelDownloader.variant(key)
        ModelInfo(
            _modelDownloaded.value, if (f.exists()) f.length() / (1024 * 1024) else 0,
            ModelManager.ModelState.Idle, key,
            modelDownloader.needsUpdate(key),
            modelDownloader.getInstalledVersion(key),
            v.version,
        )
    })

    val downloadState: StateFlow<ModelDownloader.DownloadState> = modelDownloader.state

    fun downloadModel() {
        viewModelScope.launch { modelDownloader.downloadModel(selectedModel.value) }
    }

    fun cancelDownload() {
        modelDownloader.cancelDownload()
    }

    fun loadDownloadedModel() {
        generateJob?.cancel()
        generateJob = null
        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                modelManager.selectedModelKey = selectedModel.value
                try {
                    modelManager.loadModel()
                } catch (e: Exception) {
                    Log.e(TAG, "loadDownloadedModel failed: ${e.message}")
                }
                if (modelManager.isModelLoaded()) {
                    skillManager.loadSkills()
                    safeReinit(extraTools)
                }
            }
        }
    }

    fun retryModelLoad() {
        generateJob?.cancel()
        generateJob = null
        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                modelManager.recoverFromError()
                if (modelManager.isModelLoaded()) {
                    skillManager.loadSkills()
                    safeReinit(extraTools)
                }
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            modelManager.unloadModel()
            modelDownloader.deleteModel(selectedModel.value)
        }
    }

    fun setSelectedModel(key: String) {
        generateJob?.cancel()
        generateJob = null
        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                prefs.setSelectedModel(key)
                modelManager.selectedModelKey = key
                //  fully unload + GC + delay before loading new model
                if (modelManager.isModelLoaded()) {
                    modelManager.unloadModel()
                }
                // Refresh download state for new variant
                _modelDownloaded.value = modelDownloader.isModelDownloaded(key)
                // Auto-load if already downloaded — loadModel does its own cleanup guard
                if (modelDownloader.isModelDownloaded(key)) {
                    try {
                        modelManager.loadModel()
                    } catch (e: Exception) {
                        Log.e(TAG, "Switch to $key failed: ${e.message}")
                    }
                    if (modelManager.isModelLoaded()) {
                        skillManager.loadSkills()
                        safeReinit(extraTools)
                    }
                }
            }
        }
    }

    /** Clear all data and reset to fresh state. */
    fun clearAllDataAndReset() {
        viewModelScope.launch {
            try {
                database.withTransaction {
                    database.conversationDao().deleteAllConversations()
                    database.messageDao().deleteAllMessages()
                    database.reflectionDao().deleteAllReflections()
                    memoryDao.deleteAll()
                    database.notificationDao().deleteAll()
                }
            } catch (e: Exception) {
                android.util.Log.e("BorizonViewModel", "Data clear failed", e)
            }
            prefs.clearAll()
        }
    }

    // ── Memory UI state ──

    fun searchMemories(query: String) {
        memorySearchQuery = query.ifBlank { null }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryDao.delete(id)
        }
    }

}
