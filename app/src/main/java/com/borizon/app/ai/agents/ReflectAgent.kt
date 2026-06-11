package com.borizon.app.ai.agents

import android.util.Log
import com.borizon.app.util.debugLog
import com.google.ai.edge.litertlm.tool
import com.borizon.app.ai.inference.ModelManager
import com.borizon.app.ai.inference.StreamToken
import com.borizon.app.ai.prompts.AgentSystemPrompt
import com.borizon.app.ai.prompts.StarterTemplate
import com.borizon.app.ai.tools.BorizonAction
import com.borizon.app.ai.tools.ToolCallTracker
import com.borizon.app.ai.tools.WebTools
import com.borizon.app.data.dao.ConversationDao
import com.borizon.app.data.dao.MemoryDao
import com.borizon.app.data.dao.MessageDao
import com.borizon.app.data.dao.ReflectionDao
import com.borizon.app.data.models.ChatMessage
import com.borizon.app.data.models.Conversation
import com.borizon.app.data.models.Message
import com.borizon.app.data.models.MessageRole
import com.borizon.app.data.models.Reflection
import com.borizon.app.ai.harness.AckDetector
import com.borizon.app.skills.SkillManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * REFLECT mode — Borizon's conversational persona.
 *
 * Uses persistent conversation (KV cache reuse via ModelManager).
 *
 * Persists every message to Room (Conversation + Message entities).
 * Reloads last conversation on init so chat history survives app restarts.
 * Uses persistent conversation (KV cache reuse via ModelManager).
 */
class ReflectAgent(
    private val modelManager: ModelManager,
    private val reflectionDao: ReflectionDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao? = null,
    private val skillManager: SkillManager? = null,
    private val contextCompactor: com.borizon.app.ai.harness.ContextCompactor? = null,
) {

    /** Side channel for UI progress feedback during tool execution. */
    private val _actionChannel = kotlinx.coroutines.channels.Channel<BorizonAction>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val actionChannel get() = _actionChannel

    /** True only after initConversation() or reinitWithTools() completes. */
    @Volatile
    private var conversationReady = false

    /** Exposed for UI to disable input until conversation is fully initialized. */
    private val _isConversationReadyState = MutableStateFlow(false)
    val isConversationReadyState: StateFlow<Boolean> = _isConversationReadyState

    private val _isGenerating = MutableStateFlow(false)
    private val generationGuard = AtomicBoolean(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    /**  model is loaded but waiting for first token. Distinct from active streaming. */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing

    private val _generationStartTime = MutableStateFlow(0L)
    val generationStartTime: StateFlow<Long> = _generationStartTime

    private val _lastResponseDurationMs = MutableStateFlow(0L)
    val lastResponseDurationMs: StateFlow<Long> = _lastResponseDurationMs

    private val _sessionMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val sessionMessages: StateFlow<List<ChatMessage>> = _sessionMessages

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private val _streamingThinkingText = MutableStateFlow("")
    val streamingThinkingText: StateFlow<String> = _streamingThinkingText

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Informational status message (compaction, tier changes, etc.).
     *  Displayed with INFO severity, not as an error. */
    private val _lastInfo = MutableStateFlow<String?>(null)
    val lastInfo: StateFlow<String?> = _lastInfo

    private val _isResetting = MutableStateFlow(false)
    val isResetting: StateFlow<Boolean> = _isResetting

    private val _streamingTokensPerSecond = MutableStateFlow(0f)
    val streamingTokensPerSecond: StateFlow<Float> = _streamingTokensPerSecond

    /** Wall-clock TPS for the entire agent loop (including tool execution time). */
    private val _wallClockTps = MutableStateFlow(0f)
    val wallClockTps: StateFlow<Float> = _wallClockTps

    /** Total tokens generated across all agent loop iterations. */
    private val _totalTokensGenerated = MutableStateFlow(0)
    val totalTokensGenerated: StateFlow<Int> = _totalTokensGenerated

    @Volatile
    var activeConversationId: Long = 0L
        private set

    private val _activeConversationIdFlow = MutableStateFlow(0L)
    val activeConversationIdFlow: StateFlow<Long> = _activeConversationIdFlow

    /** Current session tracking: date (yyyy-MM-dd) and session ID (session-HHMM). */
    private val sessionRef = java.util.concurrent.atomic.AtomicReference<Pair<String, String>>(Pair("", ""))

    /** Current session reference string (e.g. "2026-04-16/session-2015"). */
    val currentSessionRef: String get() {
        val (date, id) = sessionRef.get()
        return if (date.isNotBlank() && id.isNotBlank()) "$date/$id" else ""
    }

    /** Current session date — derived from atomic ref. */
    private var currentSessionDate: String
        get() = sessionRef.get().first
        set(value) { sessionRef.getAndUpdate { Pair(value, it.second) } }

    /** Current session ID — derived from atomic ref. */
    private var currentSessionId: String
        get() = sessionRef.get().second
        set(value) { sessionRef.getAndUpdate { Pair(it.first, value) } }

    /** Whether the current session has already been compiled. Prevents duplicate compilation. */
    @Volatile
    private var currentSessionCompiled: Boolean = false

    /**
     * Check if there's an active session that needs compilation.
     * Returns the session ref (date/id) or null if no active session.
     */
    fun getActiveSessionRef(): Pair<String, String>? {
        return if (currentSessionDate.isNotBlank() && currentSessionId.isNotBlank() && !currentSessionCompiled)
            Pair(currentSessionDate, currentSessionId)
        else null
    }

    /**
     * Mark the current session as compiled. Called after successful compilation.
     */
    fun markSessionCompiled() {
        currentSessionCompiled = true
    }

    /** Get current session messages for compilation. */
    fun getSessionMessages(): List<ChatMessage> = _sessionMessages.value

    /** Media refs pending for the next session turn write. */
    private val pendingMediaRefs = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())

    /** Set media refs to include in the next session turn. */
    fun setPendingMediaRefs(refs: List<String>) {
        pendingMediaRefs.set(refs)
    }

    companion object {
        private const val TAG = "ReflectAgent"
        /** Max messages to replay into KV cache on reinit. Keeps context window manageable. */
        private const val MAX_HISTORY_REPLAY = 10
        /** Max total generation time (ms). Prevents infinite tool loops or hung inference. */
        private const val MAX_GENERATION_MS = 300_000L // 5 minutes
        /** Max agent loop iterations — prevents runaway acknowledge loops. */
        private const val MAX_AGENT_ITERATIONS = 3
        /** Max chars of skill instructions to inject into user message.
         * Caps KV cache cost — large skills are truncated with a note. */
        private const val MAX_SKILL_INJECT_CHARS = 1500
        /** Minimum turns between compaction attempts.
         * Prevents re-trigger loop where summary + kept messages + TokenEstimator
         * overhead consumes enough budget to retrigger shouldCompact() every 1-2 turns. */
        private const val MIN_TURNS_BETWEEN_COMPACTIONS = 4
        /** Dedicated tag for agent loop audit logging — filter logcat by "ACK_AUDIT". */
        private const val AUDIT = "ACK_AUDIT"
        /** Atomic counter for assigning unique run IDs to each reflect() call. */
        private val auditRunId = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /**
     * Convert session messages to LiteRT Message objects for KV cache pre-fill.
     * Only includes user/assistant turns (skips system, config_change).
     * Limits to last [MAX_HISTORY_REPLAY] messages and ensures token budget
     * by dropping oldest messages if estimated size exceeds safe limit.
     */
    private fun buildInitialMessages(): List<com.borizon.app.data.models.ChatMessage> {
        val allRelevant = _sessionMessages.value
            .filter { it.role == "user" || it.role == "assistant" }
        if (allRelevant.isEmpty()) return emptyList()

        var selected = allRelevant.takeLast(MAX_HISTORY_REPLAY)

        fun estimateTokens(msgs: List<com.borizon.app.data.models.ChatMessage>): Int {
            return msgs.sumOf { msg ->
                com.borizon.app.util.TokenEstimator.estimateTokens(
                    content = msg.content,
                    thinkingContent = null, // not replayed into KV cache
                    role = msg.role,
                    toolEventCount = msg.toolEvents?.size ?: 0,
                )
            }
        }

        val maxSafeTokens = computeMaxSafeTokens()

        var estimated = estimateTokens(selected)
        while (estimated > maxSafeTokens && selected.size > 2) {
            selected = selected.drop(1)
            estimated = estimateTokens(selected)
        }

        return selected
    }

    private fun computeMaxSafeTokens(): Int {
        val maxNumTokens = modelManager.currentConfig.maxTokens
        val systemPrompt = AgentSystemPrompt.build()
        val systemTokens = com.borizon.app.util.TokenEstimator.estimateTokens(
            content = systemPrompt,
            role = "system",
        )
        // Each ToolSet adds description tokens to context (~50 per tool with @ToolParam descriptions)
        val toolTokenEstimate = registeredExtraTools.size * 50
        // Reserve output budget + safety margin for tool call/response overhead
        val outputBudget = maxNumTokens / 4
        val toolOverhead = registeredExtraTools.size * 20 // constrained decoding overhead
        return (maxNumTokens - outputBudget - systemTokens - toolTokenEstimate - toolOverhead - 100).coerceAtLeast(200)
    }

    /**
     * Compute how many messages to keep after compaction based on actual token cost.
     *
     * The naive approach of always keeping 2 messages fails when those messages are
     * large (e.g., a tool result with 50 SMS messages). The kept messages alone can
     * exceed maxSafeTokens, making compaction useless and causing re-trigger loops.
     *
     * This method walks backward from the most recent messages, accumulating token
     * cost, and stops when adding the next message would exceed 40% of maxSafeTokens.
     * The remaining 60% is reserved for: the summary message, new user input, and
     * the model's response in the next turn.
     */
    private fun computeKeptMessageCount(
        messages: List<com.borizon.app.data.models.ChatMessage>,
        maxSafeTokens: Int,
    ): Int {
        val keepBudget = (maxSafeTokens * 0.4).toInt().coerceAtLeast(100)
        var accumulated = 0
        var count = 0

        for (msg in messages.reversed()) {
            val cost = com.borizon.app.util.TokenEstimator.estimateTokens(
                content = msg.content,
                thinkingContent = msg.thinkingContent,
                role = msg.role,
                toolEventCount = msg.toolEvents?.size ?: 0,
            )
            if (accumulated + cost > keepBudget && count > 0) break
            accumulated += cost
            count++
        }
        return count.coerceAtLeast(1) // Always keep at least the last message
    }

    private suspend fun buildMemoryContext(): String {
        val dao = memoryDao ?: return ""
        val now = System.currentTimeMillis()
        val memories = dao.getRelevant(now, limit = 8)
        if (memories.isEmpty()) return ""
        // Truncate each memory to keep context compact for E2B
        val formatted = memories.take(5).joinToString("\n") { "- ${it.content.take(80)}" }
        return "USER FACTS:\n$formatted\nUse memorySearch to recall more."
    }

    private suspend fun extractSessionSummary(conversationId: Long) {
        if (conversationId == 0L) return
        val existingSummary = conversationDao.getById(conversationId)?.sessionSummary ?: ""
        val recentMessages = _sessionMessages.value.takeLast(6)
        if (recentMessages.isEmpty()) return

        val transcript = recentMessages.joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = buildString {
            append("Extract key facts from this conversation snippet for a session summary.\n")
            if (existingSummary.isNotBlank()) append("Existing summary:\n$existingSummary\n\n")
            append("New turns:\n$transcript\n\n")
            append("Output ONLY the updated summary. Max 500 characters. Cover: topics, user facts, decisions, emotional tone.")
        }
        try {
            val updated = modelManager.generateAnalysis(
                systemPrompt = "You are a session summarizer. Be factual and concise. Max 500 chars.",
                userMessage = prompt,
            )
            if (updated.isNotBlank()) {
                conversationDao.updateSessionSummary(conversationId, updated.take(500))
                debugLog(TAG, "Session summary updated for conversation $conversationId: ${updated.take(80)}...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session summary extraction failed", e)
        }
    }

    suspend fun compileSession() {
        if (currentSessionCompiled) return
        if (activeConversationId == 0L) return
        try {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                extractSessionSummary(activeConversationId)
            }
        } catch (_: Exception) {}
        markSessionCompiled()
    }

    /**
     * Proactive context compaction — check if context window is approaching capacity
     * and reinitialize with a summary + recent turns if needed.
     * Called before each generation to prevent empty responses from context overflow.
     *
     * Re-trigger protection:
     *   - Cooldown: skips check for MIN_TURNS_BETWEEN_COMPACTIONS after compaction.
     *   - Failed compaction: trims session messages to MAX_HISTORY_REPLAY to prevent
     *     immediate re-trigger on the same oversized history.
     *   - Without these guards, compaction fires every 1-2 turns because the summary +
     *     kept messages + new TokenEstimator overhead (15% margin, per-message overhead)
     *     can consume enough of the budget to retrigger shouldCompact().
     */
    private var turnsSinceCompaction = 0
    private var compactionActive = false

    private suspend fun checkAndCompact() {
        val compactor = contextCompactor ?: return

        // Cooldown guard: don't recheck until enough turns have passed
        if (compactionActive) {
            turnsSinceCompaction++
            if (turnsSinceCompaction < MIN_TURNS_BETWEEN_COMPACTIONS) return
            compactionActive = false
            turnsSinceCompaction = 0
        }

        val messages = _sessionMessages.value
        val maxSafe = computeMaxSafeTokens()
        val estTokens = messages.sumOf { msg ->
            com.borizon.app.util.TokenEstimator.estimateTokens(
                content = msg.content,
                thinkingContent = msg.thinkingContent,
                role = msg.role,
                toolEventCount = msg.toolEvents?.size ?: 0,
            )
        }
        val pct = (estTokens * 100f / maxSafe).toInt()
        Log.i(AUDIT, "[COMPACT_CHECK] est=$estTokens safe=$maxSafe pct=$pct% msgs=${messages.size} active=$compactionActive")

        if (!compactor.shouldCompact(messages, maxSafe, modelManager.currentConfig.maxTokens)) return

        val level = compactor.compactionLevel(messages, maxSafe, modelManager.currentConfig.maxTokens)
        Log.i(AUDIT, "[COMPACT_DO] msgs=${messages.size} level=$level")
        debugLog(TAG, "Context compaction triggered (${messages.size} messages, level=$level)")
        _isResetting.value = true
        try {
            val keepCount = computeKeptMessageCount(messages, maxSafe)

            // Multi-level compaction: cheapest first, most expensive last resort
            val result = when (level) {
                1 -> {
                    // Level 1: just drop oldest messages — no model call
                    compactor.trimToLevel(messages, maxSafe)
                }
                2 -> {
                    // Level 2: quick inline compaction — no model call, ≤10 msgs
                    compactor.compact(messages, keepCount)
                }
                else -> {
                    // Level 3: full model-based summary — expensive, last resort
                    compactor.compact(messages, keepCount)
                }
            }

            val memoryContext = buildMemoryContext()
            val skillsList = if (isSkillTier) skillManager?.getSkillsListForPrompt() ?: "" else ""
            val systemPrompt = AgentSystemPrompt.build(templateSuffix = memoryContext, skillsList = skillsList, webEnabled = isWebEnabled)
            val toolProviders = registeredExtraTools.map { tool(it) }

            if (result != null) {
                modelManager.initConversation(systemPrompt, toolProviders, result.initialMessages)
                _sessionMessages.value = result.initialMessages

                val summaryText = result.initialMessages.firstOrNull()?.content
                    ?.removePrefix("Previous conversation summary: ") ?: ""
                if (summaryText.isNotBlank() && activeConversationId != 0L) {
                    conversationDao.updateSummary(activeConversationId, summaryText)
                    conversationDao.updateSessionSummary(activeConversationId, summaryText.take(500))
                }

                val afterTokens = result.initialMessages.sumOf { msg ->
                    com.borizon.app.util.TokenEstimator.estimateTokens(
                        content = msg.content, role = msg.role,
                    )
                }
                Log.i(AUDIT, "[COMPACT_OK] level=$level compacted=${result.messagesCompacted} kept=${result.initialMessages.size} est_after=$afterTokens")
                debugLog(TAG, "Compaction applied: ${result.messagesCompacted} summarized, ${result.initialMessages.size} replayed")
                _lastInfo.value = "Context compacted — ${result.messagesCompacted} messages summarized to continue."
            } else {
                Log.w(AUDIT, "[COMPACT_FAIL] reason=null_result")
                val history = buildInitialMessages()
                modelManager.initConversation(systemPrompt, toolProviders, history)
                _sessionMessages.value = history
                Log.w(TAG, "Compaction returned null — trimmed to ${history.size} messages")
            }

            compactionActive = true
            turnsSinceCompaction = 0
            conversationReady = true
            _isConversationReadyState.value = true
        } catch (e: Exception) {
            Log.e(AUDIT, "[COMPACT_FAIL] reason=exception msg=${e.message}")
            Log.e(TAG, "Compaction failed, continuing without", e)
        } finally {
            _isResetting.value = false
        }
    }

    /**
     * Reinitialize the conversation with the current ModelManager config.
     * Preserves session messages (chat history survives config changes).
     *  KV cache is reset but history is replayed via initialMessages.
     */
    /** Extra tools registered via reinitWithTools — preserved across config reinits. */
    @Volatile
    private var registeredExtraTools: List<com.google.ai.edge.litertlm.ToolSet> = emptyList()

    suspend fun reinitWithConfig() = withContext(Dispatchers.IO) {
        reinitWithTools(registeredExtraTools)
    }

    /**
     * Reinitialize conversation with additional ToolSets.
     *  dynamic tool registration — rebuild ConversationConfig with updated tools.
     */
    suspend fun reinitWithTools(extraTools: List<com.google.ai.edge.litertlm.ToolSet> = emptyList()) = withContext(Dispatchers.IO) {
        Log.i(TAG, "reinitWithTools: ${extraTools.size} tools, modelLoaded=${modelManager.isModelLoaded()}")
        registeredExtraTools = extraTools
        val memoryContext = buildMemoryContext()
        val skillsList = if (isSkillTier) skillManager?.getSkillsListForPrompt() ?: "" else ""
        val systemPrompt = com.borizon.app.ai.prompts.AgentSystemPrompt.build(
            templateSuffix = memoryContext,
            skillsList = skillsList,
            webEnabled = isWebEnabled,
        )
        val toolProviders = extraTools.map { com.google.ai.edge.litertlm.tool(it) }
        val history = buildInitialMessages()
        modelManager.resetConversation()
        modelManager.initConversation(systemPrompt, toolProviders, history)
        conversationReady = true
        _isConversationReadyState.value = true
        debugLog(TAG, "Reinitialized conversation with ${toolProviders.size} tool providers, replayed ${history.size} messages")
    }

    /**
     * Add a system message to the session (e.g., config change notification).
     * Not persisted to Room — display-only.
     */
    fun addSystemMessage(message: ChatMessage) {
        _sessionMessages.update { it + message }
    }

    /**
     * Embed tool events into the last assistant message so they persist
     * across subsequent generations.  per-message tool history.
     */
    fun embedToolEvents(events: List<com.borizon.app.ai.tools.ToolEvent>) {
        val messages = _sessionMessages.value
        val lastAssistantIdx = messages.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            val updated = messages[lastAssistantIdx].copy(toolEvents = events)
            _sessionMessages.update { it.toMutableList().apply { this[lastAssistantIdx] = updated } }
        }
    }

    private fun setActiveConversationId(id: Long) {
        activeConversationId = id
        _activeConversationIdFlow.value = id
    }

    /**
     * Initialize the persistent conversation with the system prompt and tools.
     * Called once after loadModel() succeeds, and after newConversation().
     */
    suspend fun initConversation(template: StarterTemplate = StarterTemplate.DEFAULT) = withContext(Dispatchers.IO) {
        val memoryContext = buildMemoryContext()
        val skillsList = if (isSkillTier) skillManager?.getSkillsListForPrompt() ?: "" else ""
        val systemPrompt = AgentSystemPrompt.build(
            templateSuffix = memoryContext,
            skillsList = skillsList,
            webEnabled = isWebEnabled,
        )
        val toolProviders = registeredExtraTools.map { tool(it) }
        val history = buildInitialMessages()
        modelManager.initConversation(systemPrompt, toolProviders, history)

        // Ensure a session is active
        if (currentSessionDate.isBlank()) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val sessionId = "session-" + java.text.SimpleDateFormat("HHmm", java.util.Locale.US).format(java.util.Date())
            currentSessionDate = date
            currentSessionId = sessionId
        }

        debugLog(TAG, "Conversation initialized with ${toolProviders.size} tools, history=${history.size} messages")
        conversationReady = true
        _isConversationReadyState.value = true
    }

    suspend fun loadLastConversation() = withContext(Dispatchers.IO) {
        val last = conversationDao.getLatestConversation()
        if (last != null) {
            setActiveConversationId(last.id)
            val messages = messageDao.getRecentMessages(last.id, 200).reversed()
            val chatMessages = messages.map { msg ->
                ChatMessage(
                    role = msg.role.name.lowercase(),
                    content = msg.content,
                    timestamp = msg.timestamp
                )
            }.toMutableList()
            if (last.sessionSummary.isNotBlank()) {
                chatMessages.add(0, ChatMessage(
                    role = "system",
                    content = "Session context: ${last.sessionSummary}",
                    type = com.borizon.app.data.models.MessageType.SYSTEM,
                ))
            } else if (last.summary.isNotBlank()) {
                chatMessages.add(0, ChatMessage(
                    role = "system",
                    content = "Previous conversation summary: ${last.summary}",
                    type = com.borizon.app.data.models.MessageType.SYSTEM,
                ))
            }
            _sessionMessages.value = chatMessages
            reinitWithTools(registeredExtraTools)
            debugLog(TAG, "Loaded ${messages.size} messages from conversation ${last.id}")
        }
    }

    suspend fun newConversation(template: StarterTemplate = StarterTemplate.DEFAULT) {
        _isResetting.value = true
        conversationReady = false
        _isConversationReadyState.value = false
        try {


            setActiveConversationId(0L)
            _sessionMessages.value = emptyList()
            contextCompactor?.reset()
            compactionActive = false
            turnsSinceCompaction = 0
            _streamingText.value = ""
            _streamingThinkingText.value = ""
            _lastError.value = null
            _lastInfo.value = null
            WebTools.clearCache()
            com.borizon.app.ai.harness.ToolResultCache.clear()

            // Start new session
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val sessionId = "session-" + java.text.SimpleDateFormat("HHmm", java.util.Locale.US).format(java.util.Date())
            currentSessionDate = date
            currentSessionId = sessionId
            currentSessionCompiled = false

            modelManager.resetConversation()
            initConversation(template)
            debugLog(TAG, "New conversation started, session: $date/$sessionId")
        } finally {
            _isResetting.value = false
        }
    }

    fun stopResponse() {
        modelManager.stopResponse()
    }

    /**
     * Add a pre-built user message to the session (e.g., one with images).
     * Does NOT trigger generation — call [reflectFromLastUser] after.
     */
    suspend fun addUserMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        ensureConversation(message.content)
        _sessionMessages.update { it + message }
        persistMessage(MessageRole.USER, message.content)
    }

    /**
     * Generate a response to the last user message.
     * Detects attached images and sends them multimodally (images first, text after — Gemma convention).
     * Used after [addUserMessage] to trigger model generation.
     */
    /**
     * Generate a response from the last user message (which may include images).
     * Thin wrapper — the user message is already added to sessionMessages.
     */
    suspend fun reflectFromLastUser(): String = withContext(Dispatchers.IO) {
        val lastUserMsg = _sessionMessages.value.lastOrNull { it.role == "user" }
            ?: return@withContext ""
        val imageBytes = lastUserMsg.imageBitmaps?.mapNotNull { bmp ->
            try {
                val scaled = scaleBitmapForInference(bmp)
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                if (scaled !== bmp) scaled.recycle()
                baos.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compress bitmap", e)
                null
            }
        } ?: emptyList()

        reflect(lastUserMsg.content, imageBytes, skipUserPersist = true)
    }

    private suspend fun ensureConversation(firstMessage: String) {
        if (activeConversationId == 0L) {
            val title = firstMessage.take(50).replace('\n', ' ')
            val sessionRef = if (currentSessionDate.isNotBlank()) "$currentSessionDate/$currentSessionId" else ""
            val conversation = Conversation(title = title, sessionRef = sessionRef)
            setActiveConversationId(conversationDao.insert(conversation))
            debugLog(TAG, "Created conversation $activeConversationId: $title (sessionRef=$sessionRef)")
        }
    }

    private suspend fun persistMessage(role: MessageRole, content: String) {
        if (activeConversationId == 0L) return
        val message = Message(
            conversationId = activeConversationId,
            role = role,
            content = content
        )
        messageDao.insert(message)
        val count = messageDao.countForConversation(activeConversationId)
        conversationDao.updateMessageCount(activeConversationId, count)
    }

    /**
     * AGENT LOOP — the ONE method that generates responses.
     * OBSERVE → THINK → ACT → RESPOND.
     *
     * @param userText The user's message
     * @param imageBytes Optional images for multimodal
     * @param skipUserPersist If true, user message already in session (reflectFromLastUser path)
     */
    suspend fun reflect(
        userText: String,
        imageBytes: List<ByteArray> = emptyList(),
        skipUserPersist: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        if (!generationGuard.compareAndSet(false, true)) {
            Log.w(TAG, "reflect() called while already generating — dropping duplicate")
            return@withContext ""
        }
        _isGenerating.value = true

        if (!conversationReady) {
            Log.w(TAG, "conversationReady=false at reflect() entry, attempting recovery init")
            try {
                reinitWithTools(registeredExtraTools)
            } catch (e: Exception) {
                Log.e(TAG, "Recovery init failed", e)
                _lastError.value = "Still setting up. Please wait a moment."
                _isGenerating.value = false
                generationGuard.set(false)
                return@withContext ""
            }
        }

        _preparing.value = true
        _generationStartTime.value = System.currentTimeMillis()
        _lastError.value = null
        _streamingTokensPerSecond.value = 0f
        _wallClockTps.value = 0f
        _totalTokensGenerated.value = 0
        val runId = auditRunId.incrementAndGet()
        val startWallMs = System.currentTimeMillis()
        Log.i(AUDIT, "[RUN_START] id=$runId msg_len=${userText.length} img_count=${imageBytes.size} skip_persist=$skipUserPersist msg='${userText.take(200).replace("\n", "\\n")}'")
        try {
            checkAndCompact()

            if (!skipUserPersist) {
                ensureConversation(userText)
                val userMsg = ChatMessage(role = "user", content = userText)
                _sessionMessages.update { it + userMsg }
                persistMessage(MessageRole.USER, userText)
            }

            // Auto-inject matching skill instructions into the user message
            // so the model can execute immediately without a loadSkill round-trip.
            // Only fires for HIGH/MEDIUM confidence matches. See getMatchedSkill() docs.
            // Instructions are capped at MAX_SKILL_INJECT_CHARS to control KV cache cost.
            val matchedSkill = getMatchedSkill(userText)
            if (matchedSkill != null) {
                Log.i(AUDIT, "[SKILL] run=$runId skill=${matchedSkill.name}")
            }
            val skillAugmentedText = if (matchedSkill != null) {
                val rawInstructions = matchedSkill.instructions
                val cappedInstructions = if (rawInstructions.length > MAX_SKILL_INJECT_CHARS) {
                    debugLog(TAG, "Skill ${matchedSkill.name} instructions truncated: ${rawInstructions.length} -> $MAX_SKILL_INJECT_CHARS chars")
                    rawInstructions.take(MAX_SKILL_INJECT_CHARS) + "\n[...truncated]"
                } else {
                    rawInstructions
                }
                debugLog(TAG, "Auto-injecting skill: ${matchedSkill.name} (${cappedInstructions.length} chars)")
                buildString {
                    append(userText)
                    append("\n\n[INSTRUCTIONS — execute these steps now, do NOT acknowledge, just call the tools:]\n")
                    append(cappedInstructions)
                }
            } else {
                userText
            }

            // === REACT LOOP (Pi-style) ===
            // Pi's loop: while(hasMoreToolCalls) { stream → execute tools → repeat }
            // Borizon difference: LiteRT handles the inner tool loop inside sendMessageAsync.
            // Our outer loop handles the E4B failure mode: model acknowledges instead of acting.
            //
            // Loop continues when: model produced text but called NO tools (acknowledgment).
            // Loop stops when: model called tools (acted), or response is substantive (real answer).
            var finalResponse = ""
            var accumulatedThinking = ""
            var prompt = skillAugmentedText
            var usedImageBytes = imageBytes
            var totalTokensThisLoop = 0

            // hasMoreTurns mirrors Pi's hasMoreToolCalls, but inverted:
            // Pi loops while tools ARE called. We loop when tools are NOT called (ack case).
            var hasMoreTurns = true
            var iteration = 0

            while (hasMoreTurns && iteration < MAX_AGENT_ITERATIONS) {
                hasMoreTurns = false // Assume we stop unless we detect acknowledgment
                val iterStartMs = System.currentTimeMillis()
                debugLog(TAG, "Agent loop iteration $iteration")
                Log.i(AUDIT, "[ITER_START] run=$runId iter=$iteration")
                ToolCallTracker.reset()

                val result = if (iteration == 0) {
                    runAgentTurn(prompt, usedImageBytes)
                } else {
                    // Follow-up: model acknowledged without acting, force tool use
                    debugLog(TAG, "Force turn with prompt: ${prompt.take(80)}")
                    runAgentTurn(prompt, emptyList())
                }
                usedImageBytes = emptyList()
                accumulatedThinking += result.thinking
                totalTokensThisLoop += result.tokensGenerated
                _totalTokensGenerated.value = totalTokensThisLoop

                // Update wall-clock TPS: total tokens / total elapsed time (includes tool execution)
                val elapsedMs = System.currentTimeMillis() - _generationStartTime.value
                if (elapsedMs > 0 && totalTokensThisLoop > 0) {
                    _wallClockTps.value = totalTokensThisLoop / (elapsedMs / 1000f)
                }

                val toolsCalled = ToolCallTracker.get()
                val iterMs = System.currentTimeMillis() - iterStartMs
                val textLen = result.text.length
                val thoughtLen = result.thinking.length
                val isAck = if (toolsCalled > 0) "N/A_tools_called" else if (result.text.isBlank()) "N/A_empty" else looksLikeAcknowledgment(result.text).toString()
                debugLog(TAG, "Iteration $iteration: tokens=${result.tokensGenerated}, tools=$toolsCalled, text='${result.text.take(80)}'")

                Log.i(AUDIT, "[TURN_DONE] run=$runId iter=$iteration " +
                    "tokens=${result.tokensGenerated} tools=$toolsCalled " +
                    "text_len=$textLen thought_len=$thoughtLen " +
                    "ack_classification=$isAck " +
                    "iter_ms=$iterMs " +
                    "error=${"null"}")

                when {
                    // === STOP: Error ===
                    result.error != null && result.text.isBlank() -> {
                        Log.i(AUDIT, "[BRANCH] run=$runId iter=$iteration branch=ERROR")
                        finalResponse = "I ran into an issue. Could you try again?"
                    }

                    // === STOP: Model called tools and produced text ===
                    toolsCalled > 0 && result.text.isNotBlank() -> {
                        Log.i(AUDIT, "[BRANCH] run=$runId iter=$iteration branch=TOOLS_AND_TEXT")
                        finalResponse = result.text
                    }

                    // === STOP: Model called tools but produced no text (silent tool execution) ===
                    toolsCalled > 0 && result.text.isBlank() -> {
                        Log.i(AUDIT, "[BRANCH] run=$runId iter=$iteration branch=SILENT_TOOLS")
                        debugLog(TAG, "Tools called ($toolsCalled) but no text generated, forcing followup")
                        finalResponse = runFollowupTurn()
                    }

                    // === STOP: Substantive response without tools (pure text answer) ===
                    result.text.isNotBlank() && !looksLikeAcknowledgment(result.text) -> {
                        Log.i(AUDIT, "[BRANCH] run=$runId iter=$iteration branch=SUBSTANTIVE_TEXT")
                        finalResponse = result.text
                    }

                    // === CONTINUE: Acknowledgment without tools ===
                    result.text.isNotBlank() && looksLikeAcknowledgment(result.text) -> {
                        Log.i(AUDIT, "[BRANCH] run=$runId iter=$iteration branch=ACK_FORCE_TURN")
                        debugLog(TAG, "Acknowledgment detected, scheduling force turn")
                        prompt = "Do NOT acknowledge. Call the right tool NOW. No text, just the tool call."
                        hasMoreTurns = true
                        iteration++
                    }

                    // === CONTINUE: Skill injected but model ignored it (no tools) ===
                    matchedSkill != null && toolsCalled == 0 && result.text.isNotBlank() -> {
                        Log.i(AUDIT, "[BRANCH] run=$runId iter=$iteration branch=SKILL_IGNORED")
                        debugLog(TAG, "Skill ${matchedSkill.name} ignored by model, force turn")
                        prompt = "You were given instructions. Call the tools NOW. Do NOT reply with text."
                        hasMoreTurns = true
                        iteration++
                    }

                    // === STOP: Empty ===
                    else -> {
                        Log.i(AUDIT, "[BRANCH] run=$runId iter=$iteration branch=EMPTY")
                        finalResponse = result.text.ifBlank { "I couldn't generate a response. Please try again." }
                    }
                }
            }

            // Safety: exhausted loop without response
            if (finalResponse.isBlank()) {
                debugLog(TAG, "Loop exhausted, running final followup")
                finalResponse = runFollowupTurn()
            }

            // If the loop exhausted all iterations with only acknowledgments,
            // the model never acted. Surface this to the user rather than
            // showing the last ack as if it were a real response.
            if (finalResponse.isBlank() || looksLikeAcknowledgment(finalResponse)) {
                debugLog(TAG, "Agent loop gave up after $MAX_AGENT_ITERATIONS iterations")
                finalResponse = finalResponse.ifBlank {
                    "I tried but couldn't complete that action. Could you try rephrasing your request?"
                }
                _lastError.value = "Agent loop exhausted — model did not act after $MAX_AGENT_ITERATIONS attempts."
            }

            if (finalResponse.isBlank()) {
                Log.w(AUDIT, "[RUN_EMPTY] run=$runId final_response_empty")
                _lastError.value = "Empty response."
                return@withContext ""
            }

            val totalMs = System.currentTimeMillis() - startWallMs
            Log.i(AUDIT, "[RUN_DONE] run=$runId iter_total=$iteration final_len=${finalResponse.length} final_ack=${looksLikeAcknowledgment(finalResponse).toString()} total_ms=$totalMs resp='${finalResponse.take(200).replace("\n","\\n")}'")

            val assistantMsg = ChatMessage(
                role = "assistant",
                content = finalResponse,
                thinkingContent = accumulatedThinking.ifBlank { null },
                tokensPerSecond = _streamingTokensPerSecond.value,
                wallClockTps = _wallClockTps.value,
                totalTokensGenerated = _totalTokensGenerated.value,
            )
            _sessionMessages.update { it + assistantMsg }
            _streamingText.value = ""
            persistMessage(MessageRole.ASSISTANT, finalResponse)

            val reflection = Reflection(
                userText = userText,
                borizonResponse = finalResponse,
                conversationId = activeConversationId,
                sessionRef = currentSessionRef,
                isProcessed = true
            )
            reflectionDao.insert(reflection)
            debugLog(TAG, "Reflection saved: ${finalResponse.take(60)}...")
            contextCompactor?.recordTurn()
            finalResponse
        } finally {
            _lastResponseDurationMs.value = if (_generationStartTime.value > 0)
                System.currentTimeMillis() - _generationStartTime.value else 0L
            _generationStartTime.value = 0L
            _isGenerating.value = false
            _preparing.value = false
            generationGuard.set(false)
        }
    }

    /**
     * Data class for agent turn result.
     */
    private data class AgentTurnResult(
        val text: String,
        val thinking: String,
        val tokensGenerated: Int,
        val error: Exception? = null,
    )

    /**
     * Run one agent turn: send message, collect tokens, handle errors.
     * The LiteRT SDK handles tool_call → execute → inject automatically.
     * Our job is to collect whatever text the model produces.
     */
    private suspend fun runAgentTurn(userText: String, imageBytes: List<ByteArray> = emptyList()): AgentTurnResult {
        val responseBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        var lastDoneToken: StreamToken? = null
        var firstTokenReceived = false
        _streamingText.value = ""
        _streamingThinkingText.value = ""
        var lastTextEmitMs = 0L
        var lastThinkingEmitMs = 0L
        val emitIntervalMs = 100L

        try {
            withTimeout(MAX_GENERATION_MS) {
                val stream = if (imageBytes.isNotEmpty()) {
                    debugLog(TAG, "Multimodal: ${imageBytes.size} images + text")
                    modelManager.generateStreamMultimodal(userText, imageBytes)
                } else {
                    modelManager.generateStream(userText)
                }
                stream.collect { token: StreamToken ->
                    val now = System.currentTimeMillis()
                    if (!firstTokenReceived && (token.text.isNotEmpty() || !token.thinking.isNullOrEmpty())) {
                        firstTokenReceived = true
                        _preparing.value = false
                    }
                    if (token.text.isNotEmpty()) {
                        responseBuilder.append(token.text)
                        if (now - lastTextEmitMs >= emitIntervalMs || token.done) {
                            _streamingText.value = responseBuilder.toString()
                            lastTextEmitMs = now
                        }
                    }
                    if (!token.thinking.isNullOrEmpty()) {
                        thinkingBuilder.append(token.thinking)
                        if (now - lastThinkingEmitMs >= emitIntervalMs || token.done) {
                            _streamingThinkingText.value = thinkingBuilder.toString()
                            lastThinkingEmitMs = now
                        }
                        debugLog(TAG, "Thinking: ${token.thinking.take(80)}...")
                    }
                    if (token.tokensPerSecond > 0) {
                        _streamingTokensPerSecond.value = token.tokensPerSecond
                    }
                    if (token.done) {
                        lastDoneToken = token
                        _streamingText.value = responseBuilder.toString()
                        _streamingThinkingText.value = thinkingBuilder.toString()
                        debugLog(TAG, "Generation done. tokens: ${token.tokenCount}, tps: ${token.tokensPerSecond}")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Agent turn timed out after ${MAX_GENERATION_MS}ms")
            _lastError.value = "Generation timed out."
        } catch (e: CancellationException) {
            debugLog(TAG, "Agent turn cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Agent turn failed", e)
            _lastError.value = e.message ?: "Generation failed"
            _preparing.value = false
            // Recover the conversation state
            try {
                modelManager.recoverFromError()
                initConversation()
            } catch (recoveryError: Exception) {
                Log.e(TAG, "Recovery failed", recoveryError)
            }
            return AgentTurnResult(
                text = responseBuilder.toString(),
                thinking = thinkingBuilder.toString(),
                tokensGenerated = lastDoneToken?.tokenCount ?: 0,
                error = e,
            )
        }

        return AgentTurnResult(
            text = responseBuilder.toString(),
            thinking = thinkingBuilder.toString().trim(),
            tokensGenerated = lastDoneToken?.tokenCount ?: 0,
        )
    }

    /**
     * Follow-up turn when the model used tools but didn't respond.
     * Sends a followup message that forces the model to summarize its tool results.
     * This is the key fix for E2B's tendency to go silent after tool calls.
     */
    private fun looksLikeAcknowledgment(text: String): Boolean =
        AckDetector.isAcknowledgment(text)

    private suspend fun runFollowupTurn(): String {
        _preparing.value = true
        val retryBuilder = StringBuilder()
        try {
            withTimeout(30_000L) {
                modelManager.generateStream("Summarize what you did and the results. If some steps failed, say so. Be concise.").collect { token: StreamToken ->
                    if (token.text.isNotEmpty()) {
                        retryBuilder.append(token.text)
                        _streamingText.value = retryBuilder.toString()
                    }
                    if (token.done) {
                        _streamingText.value = retryBuilder.toString()
                    }
                }
            }
        } catch (e: Exception) {
            debugLog(TAG, "Followup turn failed: ${e.message}")
        }
        _preparing.value = false
        return retryBuilder.toString()
    }

    suspend fun reflectAudio(audioFile: java.io.File): Pair<String, String> =
        withContext(Dispatchers.IO) {
        if (!generationGuard.compareAndSet(false, true)) {
            Log.w(TAG, "reflectAudio() called while already generating — dropping duplicate")
            return@withContext Pair("", "")
        }

            if (!conversationReady) {
                Log.w(TAG, "conversationReady=false at reflectAudio() entry, attempting recovery init")
                try {
                    reinitWithTools(registeredExtraTools)
                } catch (e: Exception) {
                    Log.e(TAG, "Recovery init failed", e)
                    _lastError.value = "Still setting up. Please wait a moment."
                    return@withContext Pair("", "")
                }
            }
            _isGenerating.value = true
            _preparing.value = true
            _generationStartTime.value = System.currentTimeMillis()
            _lastError.value = null
            _streamingText.value = ""
            _streamingThinkingText.value = ""
            _streamingTokensPerSecond.value = 0f
            _wallClockTps.value = 0f
            _totalTokensGenerated.value = 0

            try {
                checkAndCompact()

                val audioBytes = audioFile.readBytes()
                val stream = modelManager.generateStreamWithAudio(audioBytes)

                val responseBuilder = StringBuilder()
                val thinkingBuilder = StringBuilder()
                var lastTextEmitMs = 0L
                var lastThinkingEmitMs = 0L

                try {
                    withTimeout(MAX_GENERATION_MS) {
                        stream.collect { token: StreamToken ->
                            val now = System.currentTimeMillis()
                            if (token.text.isNotEmpty()) {
                                responseBuilder.append(token.text)
                                if (now - lastTextEmitMs >= 100L || token.done) {
                                    _streamingText.value = responseBuilder.toString()
                                    lastTextEmitMs = now
                                }
                            }
                            if (!token.thinking.isNullOrEmpty()) {
                                thinkingBuilder.append(token.thinking)
                                if (now - lastThinkingEmitMs >= 100L || token.done) {
                                    _streamingThinkingText.value = thinkingBuilder.toString()
                                    lastThinkingEmitMs = now
                                }
                            }
                            if (token.done) {
                                _streamingText.value = responseBuilder.toString()
                                _streamingThinkingText.value = thinkingBuilder.toString()
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Audio generation timed out")
                    _lastError.value = "Generation timed out."
                } catch (e: CancellationException) {
                    debugLog(TAG, "Audio generation cancelled")
                } catch (e: Exception) {
                    Log.e(TAG, "Audio generation failed", e)
                    _lastError.value = e.message ?: "Audio generation failed"
                    if (responseBuilder.isEmpty()) {
                        responseBuilder.append("I couldn't process that audio. Could you try again?")
                    }
                    // Recovery: reset conversation to clear corrupted state
                    try {
                        modelManager.recoverFromError()
                        initConversation()
                    } catch (recoveryError: Exception) {
                        Log.e(TAG, "Audio recovery failed", recoveryError)
                    }
                }

                val response = responseBuilder.toString()
                val thinking = thinkingBuilder.toString().trim()
                _streamingText.value = ""

                if (response.isEmpty()) {
                    _lastError.value = "Audio response was empty."
                    val fallback = ChatMessage(role = "assistant", content = "I couldn't process that audio. Please try again.")
                    _sessionMessages.update { it + fallback }
                    persistMessage(MessageRole.ASSISTANT, fallback.content)
                    return@withContext Pair("", "")
                }

                // Note: user message is already added by BorizonViewModel.sendMessageWithAudio()
                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = response,
                    thinkingContent = thinking.ifBlank { null },
                )
                _sessionMessages.update { it + assistantMsg }
                persistMessage(MessageRole.ASSISTANT, response)

                // Session turn recorded
                val userText = "[Audio message]"

                val reflection = Reflection(
                    userText = userText,
                    borizonResponse = response,
                    conversationId = activeConversationId,
                    sessionRef = currentSessionRef,
                    isProcessed = true
                )
                reflectionDao.insert(reflection)

                // Session turn recorded
                contextCompactor?.recordTurn()
                Pair(userText, response)
            } finally {
                _lastResponseDurationMs.value = if (_generationStartTime.value > 0)
                    System.currentTimeMillis() - _generationStartTime.value else 0L
                _generationStartTime.value = 0L
                _isGenerating.value = false
                _preparing.value = false
                generationGuard.set(false)
            }
        }

    fun clearSession() {
        _sessionMessages.value = emptyList()
    }

    suspend fun loadConversation(id: Long) = withContext(Dispatchers.IO) {
        val conv = conversationDao.getById(id) ?: return@withContext
        setActiveConversationId(conv.id)
        // Restore session refs from conversation
        if (conv.sessionRef.isNotBlank()) {
            val parts = conv.sessionRef.split("/", limit = 2)
            if (parts.size == 2) {
                currentSessionDate = parts[0]
                currentSessionId = parts[1]
                currentSessionCompiled = true // Already compiled since we're loading history
            }
        }
        val messages = messageDao.getRecentMessages(id, 200).reversed()
        val chatMessages = messages.map { msg ->
            ChatMessage(
                role = msg.role.name.lowercase(),
                content = msg.content,
                timestamp = msg.timestamp
            )
        }.toMutableList()
        if (conv.sessionSummary.isNotBlank()) {
            chatMessages.add(0, ChatMessage(
                role = "system",
                content = "Session context: ${conv.sessionSummary}",
                type = com.borizon.app.data.models.MessageType.SYSTEM,
            ))
        } else if (conv.summary.isNotBlank()) {
            chatMessages.add(0, ChatMessage(
                role = "system",
                content = "Previous conversation summary: ${conv.summary}",
                type = com.borizon.app.data.models.MessageType.SYSTEM,
            ))
        }
        _sessionMessages.value = chatMessages
        // Reinitialize KV cache with loaded history so the model has context
        reinitWithConfig()
        debugLog(TAG, "Loaded conversation $id: ${messages.size} messages (sessionRef=${conv.sessionRef})")
    }

    suspend fun loadOlderMessages(): Int = withContext(Dispatchers.IO) {
        val messages = _sessionMessages.value
        val oldestTimestamp = messages
            .filter { it.role != "system" }
            .minOfOrNull { it.timestamp }
            ?: return@withContext 0
        val convId = activeConversationIdFlow.value
        if (convId <= 0) return@withContext 0

        val older = messageDao.getMessagesBefore(convId, oldestTimestamp, 50)
        if (older.isEmpty()) return@withContext 0

        val chatMessages = older.reversed().map { msg ->
            ChatMessage(role = msg.role.name.lowercase(), content = msg.content, timestamp = msg.timestamp)
        }
        val insertIndex = if (messages.isNotEmpty() && messages[0].role == "system") 1 else 0
        _sessionMessages.update { current ->
            current.toMutableList().apply { addAll(insertIndex, chatMessages) }
        }
        debugLog(TAG, "Loaded ${chatMessages.size} older messages for conversation $convId")
        chatMessages.size
    }

    suspend fun regenerate(): String = withContext(Dispatchers.IO) {
        if (!generationGuard.compareAndSet(false, true)) {
            Log.w(TAG, "regenerate() called while already generating — dropping duplicate")
            return@withContext ""
        }
        _isGenerating.value = true
        _preparing.value = true
        _generationStartTime.value = System.currentTimeMillis()
        _streamingTokensPerSecond.value = 0f
        _wallClockTps.value = 0f
        _totalTokensGenerated.value = 0
        try {
            checkAndCompact()
            val messages = _sessionMessages.value.toMutableList()
            if (messages.isEmpty() || messages.last().role != "assistant") {
                return@withContext ""
            }
            val removed = messages.removeAt(messages.lastIndex)
            _sessionMessages.update { messages }

            // Delete the old assistant message from Room so it doesn't reappear on reload
            if (activeConversationId > 0L) {
                messageDao.deleteLastAssistantMessage(activeConversationId)
            }

            val lastUserText = messages.lastOrNull { it.role == "user" }?.content ?: return@withContext ""

            val responseBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            _streamingText.value = ""
            _streamingThinkingText.value = ""
            var lastTextEmitMs = 0L
            var lastThinkingEmitMs = 0L
            try {
                withTimeout(MAX_GENERATION_MS) {
                    modelManager.generateStream(lastUserText).collect { token: StreamToken ->
                        val now = System.currentTimeMillis()
                        if (token.text.isNotEmpty()) {
                            responseBuilder.append(token.text)
                            if (now - lastTextEmitMs >= 100L || token.done) {
                                _streamingText.value = responseBuilder.toString()
                                lastTextEmitMs = now
                            }
                        }
                        if (!token.thinking.isNullOrEmpty()) {
                            thinkingBuilder.append(token.thinking)
                            if (now - lastThinkingEmitMs >= 100L || token.done) {
                                _streamingThinkingText.value = thinkingBuilder.toString()
                                lastThinkingEmitMs = now
                            }
                        }
                        if (token.done) {
                            _streamingText.value = responseBuilder.toString()
                            _streamingThinkingText.value = thinkingBuilder.toString()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Regeneration timed out")
            } catch (e: CancellationException) {
                debugLog(TAG, "Regeneration cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Regenerate streaming failed", e)
                if (responseBuilder.isEmpty()) {
                    responseBuilder.append("I'm having trouble thinking right now. Could you try again?")
                }
            }

            val response = responseBuilder.toString()
            val thinking = thinkingBuilder.toString().trim()
            _streamingText.value = ""

            if (response.isEmpty()) {
                _lastError.value = "Regeneration produced empty response."
                return@withContext ""
            }

            val assistantMsg = ChatMessage(
                role = "assistant",
                content = response,
                thinkingContent = thinking.ifBlank { null },
            )
            _sessionMessages.update { it + assistantMsg }
            persistMessage(MessageRole.ASSISTANT, response)

            debugLog(TAG, "Regenerated response for conversation $activeConversationId")
            contextCompactor?.recordTurn()
            response
        } finally {
            _lastResponseDurationMs.value = if (_generationStartTime.value > 0)
                System.currentTimeMillis() - _generationStartTime.value else 0L
            _generationStartTime.value = 0L
            _isGenerating.value = false
            _preparing.value = false
            generationGuard.set(false)
        }
    }

    private fun scaleBitmapForInference(bitmap: android.graphics.Bitmap, maxDim: Int = 1024): android.graphics.Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(w, h)
        return android.graphics.Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    /** Whether WebTools is registered (Brave API key configured). */
    private val isWebEnabled: Boolean
        get() = registeredExtraTools.any { it is com.borizon.app.ai.tools.WebTools }

    /**
     * Whether skill features are available on the current model tier.
     * SkillTools (listSkills/loadSkill) are E4B-only, and auto-inject
     * must be gated to match — otherwise E2B gets skill instructions
     * injected but no skill tools to act on them, wasting ~1500 chars
     * of KV cache budget on a model that already has a tight budget.
     */
    private val isSkillTier: Boolean
        get() = modelManager.selectedModelKey == "E4B"

    /**
     * Find a skill whose trigger phrases match the user's message with confidence.
     *
     * Matching strategy (3 tiers, first wins):
     *   1. EXACT MATCH (confidence HIGH): user message equals the trigger phrase.
     *      e.g., user says "good morning" → morning-briefing.
     *   2. WHOLE-WORD SUBSTRING (confidence MEDIUM): trigger appears as a complete
     *      word/phrase within the user message, bounded by word boundaries.
     *      e.g., "give me my morning briefing" → morning-briefing.
     *   3. PARTIAL CONTAINS (confidence LOW): trigger appears anywhere as substring.
     *      Only used if the trigger is 4+ words long (specific enough to avoid false positives).
     *      e.g., "how is my phone" → phone-status.
     *
     * Auto-inject only fires for HIGH and MEDIUM confidence matches.
     * LOW confidence matches are logged but NOT injected — the model can still
     * use loadSkill if it recognizes the intent.
     *
     * Token budget: skill instructions are capped at [MAX_SKILL_INJECT_CHARS] to
     * prevent blowing the KV cache on large skill payloads.
     *
     * @return Matched skill, or null if no confident match.
     */
    private fun getMatchedSkill(userText: String): com.borizon.app.proto.Skill? {
        val sm = skillManager ?: return null
        if (!isSkillTier) return null  // E2B: no skill tools registered, skip auto-inject
        val selected = sm.getSelectedSkills()
        if (selected.isEmpty()) return null

        val lower = userText.lowercase().trim()

        var bestMatch: com.borizon.app.proto.Skill? = null
        var bestConfidence = Confidence.NONE

        for (skill in selected) {
            val triggers = extractTriggers(skill)
            for (trigger in triggers) {
                val confidence = classifyMatch(lower, trigger)
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestMatch = skill
                    if (confidence == Confidence.HIGH) break // Can't beat exact
                }
            }
            if (bestConfidence == Confidence.HIGH) break
        }

        return when (bestConfidence) {
            Confidence.HIGH, Confidence.MEDIUM -> {
                debugLog(TAG, "Skill match: ${bestMatch?.name} (confidence=$bestConfidence, msg='${lower.take(40)}')")
                bestMatch
            }
            Confidence.LOW -> {
                debugLog(TAG, "Skill low-confidence: ${bestMatch?.name} — NOT injecting, model may loadSkill")
                null
            }
            Confidence.NONE -> null
        }
    }

    /**
     * Classify how confidently a trigger phrase matches the user message.
     */
    private fun classifyMatch(lowerMessage: String, trigger: String): Confidence {
        val lowerTrigger = trigger.lowercase().trim()
        if (lowerTrigger.length < 2) return Confidence.NONE

        // Tier 1: EXACT — entire message is the trigger
        if (lowerMessage == lowerTrigger) return Confidence.HIGH

        // Tier 2: WHOLE-WORD — trigger appears bounded by word boundaries
        // Use \b for single-word triggers, or check phrase is not part of a larger word
        val escaped = Regex.escape(lowerTrigger)
        val wholeWordPattern = if (lowerTrigger.contains(" ")) {
            // Multi-word trigger: just check it appears as a contiguous phrase
            // surrounded by non-alphanumeric (or string boundaries)
            Regex("(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])")
        } else {
            // Single-word trigger: strict word boundary
            Regex("\\b$escaped\\b")
        }
        if (wholeWordPattern.containsMatchIn(lowerMessage)) return Confidence.MEDIUM

        // Tier 3: PARTIAL — substring match, but only for long/specific triggers
        // Short triggers ("travel", "trip") are too ambiguous as substrings.
        val triggerWords = lowerTrigger.split(" ").filter { it.length > 2 }
        if (triggerWords.size >= 4 && lowerMessage.contains(lowerTrigger)) {
            return Confidence.LOW
        }

        return Confidence.NONE
    }

    private enum class Confidence {
        NONE, LOW, MEDIUM, HIGH
    }

    /**
     * Extract trigger phrases from a skill.
     *
     * Priority:
     *   1. Explicit `triggers` field from proto (parsed from SKILL.md frontmatter).
     *   2. Legacy: quoted phrases in the description field.
     *   3. Fallback: skill name split into words.
     */
    private fun extractTriggers(skill: com.borizon.app.proto.Skill): List<String> {
        // Explicit triggers from frontmatter
        if (skill.triggersCount > 0) {
            return skill.triggersList.filter { it.length in 2..60 }
        }

        // Legacy: parse quoted phrases from description
        val fromQuotes = Regex("\"([^\"]+)\"").findAll(skill.description)
            .map { it.groupValues[1] }
            .filter { it.length in 2..60 }
            .toList()
        if (fromQuotes.isNotEmpty()) return fromQuotes

        // Fallback: skill name as trigger
        return listOf(skill.name)
    }

    fun getGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Hey there"
        } + ". What's on your mind?"
    }
}
