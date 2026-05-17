package com.borizon.app.data.models

import android.graphics.Bitmap
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A single message within a conversation.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["conversationId", "timestamp"]),
    ]
)
@Serializable
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokenCount: Int = 0
)

/**
 * Enum for message sender roles.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Enum for message rendering types.
 * Determines how each message is displayed in the chat UI.
 * Independent of [MessageRole] (who sent it) — this controls how it renders.
 */
enum class MessageType {
    /** Standard text message (default). */
    TEXT,
    /** Chain-of-thought reasoning from Gemma 4. */
    THINKING,
    /** System/status message (centered, muted). */
    SYSTEM,
    /** Tool execution progress panel (collapsible). */
    PROGRESS,
    /** User-sent image message (session-only, not persisted to Room). */
    IMAGE,
    /** User-sent audio clip (session-only, processed by Gemma 4 ASR). */
    AUDIO,
    /** Config change notification . Shows old→new parameter diff. */
    CONFIG_CHANGE,
    /** Inline WebView / rendered content card. . */
    WEBVIEW,
}

/**
 * Chat message used during a reflection session.
 * In-memory only — Room uses [Message] entity for persistence.
 * Bitmaps are session-only and not persisted across app restarts.
 */
data class ChatMessage(
    val role: String,           // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val thinkingContent: String? = null,
    /** In-memory bitmaps attached to this message (session-only). Null = text-only. */
    val imageBitmaps: List<Bitmap>? = null,
    /** Path to recorded audio file (session-only, WAV format). Null = no audio. */
    val audioFilePath: String? = null,
    /** Config parameter changes (old → new). Set when type = CONFIG_CHANGE. */
    val configChanges: Map<String, Pair<String, String>>? = null,
    /** Inference tokens-per-second (raw model speed, excluding tool execution). */
    val tokensPerSecond: Float = 0f,
    /** Wall-clock tokens-per-second (total tokens / total time including tools). More accurate for user perception. */
    val wallClockTps: Float = 0f,
    /** Total tokens generated across all agent loop iterations. */
    val totalTokensGenerated: Int = 0,
    /** WebView content . Set when type = WEBVIEW. */
    val webViewUrl: String? = null,
    val webViewTitle: String? = null,
    val webViewAspectRatio: Float = 1.333f,
    /** Tool events used during this response. Embedded when generation completes so they persist. */
    val toolEvents: List<com.borizon.app.ai.tools.ToolEvent> = emptyList(),
)
