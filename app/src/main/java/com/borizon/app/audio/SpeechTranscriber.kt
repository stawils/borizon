package com.borizon.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Android SpeechRecognizer to provide simple speech-to-text transcription.
 * Returns the final transcribed text when recognition completes.
 */
class SpeechTranscriber(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude

    /**
     * Start listening and return the final transcribed text.
     * Suspends until recognition completes or fails.
     */
    suspend fun transcribe(): String = suspendCancellableCoroutine { continuation ->
        // Destroy any existing recognizer before creating a new one
        speechRecognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                _amplitude.value = convertRmsDbToAmplitude(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _isListening.value = false
            }

            override fun onError(error: Int) {
                _isListening.value = false
                _partialText.value = ""
                if (continuation.isActive) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand speech"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        else -> "Speech recognition error: $error"
                    }
                    continuation.resumeWithException(SpeechException(msg))
                }
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                _partialText.value = ""
                if (continuation.isActive) {
                    continuation.resume(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                _partialText.value = partial?.firstOrNull() ?: ""
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        continuation.invokeOnCancellation {
            recognizer.stopListening()
            recognizer.destroy()
            speechRecognizer = null
            _isListening.value = false
        }

        recognizer.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
        _amplitude.value = 0
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
        _amplitude.value = 0
    }

    companion object {
        private const val MIN_DB = -2.0f
        private const val MAX_DB = 10.0f

        fun convertRmsDbToAmplitude(rmsdB: Float): Int {
            val clamped = rmsdB.coerceIn(MIN_DB, MAX_DB)
            return ((clamped - MIN_DB) / (MAX_DB - MIN_DB) * 100f).toInt().coerceIn(0, 100)
        }
    }
}

class SpeechException(message: String) : Exception(message)
