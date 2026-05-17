package com.borizon.app.ui.screens

import com.borizon.app.ai.inference.ModelManager
import com.borizon.app.ai.tools.BorizonAction
import com.borizon.app.data.models.Conversation
import com.borizon.app.ui.components.ModelConfig

data class VoiceState(
    val isRecording: Boolean = false,
    val voiceAmplitude: Int = 0,
    val transcriptionPartial: String = "",
    val isAudioClipRecording: Boolean = false,
    val audioClipAmplitude: Int = 0,
)

data class StreamingState(
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val streamingThinkingText: String = "",
    val generationStartTime: Long = 0L,
    /** Raw inference TPS (model token speed only, no tool execution time). */
    val streamingTokensPerSecond: Float = 0f,
    /** Wall-clock TPS (total tokens / total time including tool execution). */
    val streamingWallClockTps: Float = 0f,
) {
    val showTypingDots: Boolean get() = isGenerating && streamingText.isBlank() && streamingThinkingText.isBlank()
}

data class ChatModelState(
    val modelState: ModelManager.ModelState = ModelManager.ModelState.Idle,
    val isReinitializing: Boolean = false,
    val isBackgroundProcessing: Boolean = false,
    val backgroundError: String? = null,
    val modelConfig: ModelConfig = ModelConfig(),
)

data class SpeechState(
    val isSpeaking: Boolean = false,
    val speakingMessageIndex: Int = -1,
)

data class ConversationState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: Long = 0L,
    val hasOlderMessages: Boolean = false,
)

data class InteractiveDialogsState(
    val pendingAskAction: BorizonAction.AskUser? = null,
    val pendingConfirmAction: BorizonAction.Confirm? = null,
)
