package com.borizon.app.ai.tools

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared counter for tracking tool executions within a single agent turn.
 * Reset before each turn, incremented by each tool when it runs.
 * Used by ReflectAgent to detect acknowledgment-only responses.
 */
object ToolCallTracker {
    private val counter = AtomicInteger(0)
    /** Per-tool rate limiter — prevents API quota exhaustion. */
    private val perToolCounts = ConcurrentHashMap<String, AtomicInteger>()
    private const val MAX_CALLS_PER_TOOL_PER_TURN = 5

    /** Timestamp of last tool execution — used by GPU stall watchdog. */
    @Volatile
    var lastToolActivityMs: Long = 0L
        private set

    fun increment() {
        counter.incrementAndGet()
        lastToolActivityMs = System.currentTimeMillis()
    }
    fun get(): Int = counter.get()
    fun reset() {
        counter.set(0)
        perToolCounts.clear()
        lastToolActivityMs = 0L
    }

    /** Returns true if the named tool hasn't exceeded its per-turn limit. */
    fun canCall(toolName: String): Boolean {
        val count = perToolCounts.computeIfAbsent(toolName) { AtomicInteger(0) }
        return count.incrementAndGet() <= MAX_CALLS_PER_TOOL_PER_TURN
    }
}

/**
 * Actions emitted by tool execution (PhoneTools, WebTools, SkillTools).
 * Consumed by ChatScreen via LaunchedEffect on the action channel.
 */
sealed class BorizonAction {
    /** Tool is executing — show progress indicator in chat. */
    data class Progress(
        val label: String,
        val isInProgress: Boolean,
        val toolType: ToolType,
        val navigationTarget: ToolNavigationTarget = ToolNavigationTarget.None,
        val detailDescription: String = "",
    ) : BorizonAction()

    /** Tool produced a dashboard result — show inline WebView. */
    data class Dashboard(
        val url: String,
        val title: String,
        val aspectRatio: Float = 1.333f,
    ) : BorizonAction()

    /** Tool needs user input — show dialog, resume tool with answer. . */
    data class AskUser(
        val dialogTitle: String,
        val fieldLabel: String,
        val result: CompletableDeferred<String> = CompletableDeferred(),
    ) : BorizonAction()

    /** Tool needs user confirmation before a destructive operation. . */
    data class Confirm(
        val message: String,
        val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    ) : BorizonAction()
}

/** Identifies which Borizon tool produced an action. */
enum class ToolType {
    GET_TIME_CONTEXT,
    // Phone tools
    SET_ALARM,
    CREATE_REMINDER,
    SHARE_TEXT,
    OPEN_CALENDAR,
    OPEN_URL,
    OPEN_SETTINGS,
    SEND_EMAIL,
    CREATE_CONTACT,
    PHONE_CALL,
    SEND_SMS,
    READ_CONTACTS,
    OPEN_APP,
    // Skill tools
    LOAD_SKILL,
    LIST_SKILLS,
    RUN_JS,
    // Web tools
    WEB_SEARCH,
    WEB_READ,
    // Memory tools
    MEMORY_SAVE,
    MEMORY_SEARCH,
    MEMORY_FORGET,
    // Notification tools
    NOTIFICATION_READ,
    // SMS + Call tools
    SMS_READ,
    SMS_CONVERSATION,
    CALL_LOG_READ,
    CALL_LOG_CONTACT,
    // Installed apps tools
    APP_LIST,
    APP_DETAILS,
    // Shell tools
    SHELL_EXECUTE,
}

/** Where tapping a tool result should navigate the user. */
sealed class ToolNavigationTarget {
    data object None : ToolNavigationTarget()
}

/** UI-facing event for the tool timeline. Accumulated from [BorizonAction.Progress] pairs. */
data class ToolEvent(
    val id: Int,
    val label: String,
    val toolType: ToolType,
    val isInProgress: Boolean,
    val navigationTarget: ToolNavigationTarget = ToolNavigationTarget.None,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L,
    val detailDescription: String = "",
) {
    /** Duration in milliseconds, or 0 if not yet completed. */
    val durationMs: Long get() = if (endTimeMs > startTimeMs) endTimeMs - startTimeMs else 0L
}
