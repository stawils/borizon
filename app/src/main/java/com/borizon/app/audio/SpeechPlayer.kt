package com.borizon.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-Speech wrapper for speaking Borizon's responses aloud.
 */
class SpeechPlayer(context: Context) {

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private var tts: TextToSpeech? = null
    private var isReady = false
    @Volatile
    private var isShutdown = false
    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private val queueDepth = AtomicInteger(0)

    init {
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (!isShutdown) _isSpeaking.value = true
                    }
                    override fun onDone(utteranceId: String?) {
                        if (queueDepth.decrementAndGet() <= 0) {
                            queueDepth.set(0)
                            if (!isShutdown) _isSpeaking.value = false
                        }
                    }
                    @Suppress("DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        if (queueDepth.decrementAndGet() <= 0) {
                            queueDepth.set(0)
                            if (!isShutdown) _isSpeaking.value = false
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (queueDepth.decrementAndGet() <= 0) {
                            queueDepth.set(0)
                            if (!isShutdown) _isSpeaking.value = false
                        }
                    }
                })
                // Drain all queued text
                while (!pendingQueue.isEmpty()) {
                    pendingQueue.poll()?.let { speakInternal(it) }
                }
            }
        })
    }

    fun speak(text: String) {
        if (isShutdown) return
        if (!isReady) {
            pendingQueue.add(text)
            return
        }
        speakInternal(text)
    }

    private fun speakInternal(text: String) {
        val cleanText = text
            .replace(MARKDOWN_BOLD, "$1")
            .replace(MARKDOWN_ITALIC, "$1")
            .replace(MARKDOWN_CODE, "$1")
            .replace(MARKDOWN_HEADERS, "")
            .replace(MARKDOWN_BLOCKQUOTE, "")
            .replace(MARKDOWN_LIST, "")

        queueDepth.incrementAndGet()
        tts?.speak(cleanText, TextToSpeech.QUEUE_ADD, null, "borizon_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        queueDepth.set(0)
        _isSpeaking.value = false
    }

    fun shutdown() {
        isShutdown = true
        tts?.stop()
        tts?.shutdown()
        tts = null
        queueDepth.set(0)
        _isSpeaking.value = false
    }

    companion object {
        private val MARKDOWN_BOLD = Regex("""\*\*(.+?)\*\*""")
        private val MARKDOWN_ITALIC = Regex("""\*(.+?)\*""")
        private val MARKDOWN_CODE = Regex("""`(.+?)`""")
        private val MARKDOWN_HEADERS = Regex("""^#{1,3}\s+""", setOf(RegexOption.MULTILINE))
        private val MARKDOWN_BLOCKQUOTE = Regex("""^>\s+""", setOf(RegexOption.MULTILINE))
        private val MARKDOWN_LIST = Regex("""^[-*]\s+""", setOf(RegexOption.MULTILINE))
    }
}
