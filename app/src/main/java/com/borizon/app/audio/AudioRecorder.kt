package com.borizon.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.borizon.app.util.debugLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records raw PCM audio to memory (ByteArrayOutputStream).
 * no file I/O during recording.
 *
 * Configured for Gemma 4 ASR: 16kHz mono 16-bit PCM.
 * Max recording duration is 30 seconds with automatic stop.
 *
 * Usage:
 * ```
 * val recorder = AudioRecorder(context)
 * recorder.startRecording()
 * // ... user speaks ...
 * val pcmBytes = recorder.stopRecording()
 * val wavBytes = AudioRecorder.pcmToWav(pcmBytes, AudioRecorder.SAMPLE_RATE)
 * ```
 */
class AudioRecorder(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    @Volatile
    private var audioStream = ByteArrayOutputStream()

    var onMaxDurationReached: (() -> Unit)? = null

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_DURATION_MS = 30_000L
        private const val BYTES_PER_FRAME = 2

        /**
         * Converts raw PCM data to a complete WAV byte array with proper headers.
         */
        fun pcmToWav(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val channels = 1
            val bitsPerSample: Short = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val pcmSize = pcmData.size
            val wavFileSize = pcmSize + 44 // 44 bytes for the header

            val wav = ByteArrayOutputStream(wavFileSize)

            // RIFF/WAVE header
            wav.write("RIFF".toByteArray())
            wav.write(intToLE(wavFileSize - 8))
            wav.write("WAVE".toByteArray())

            // fmt sub-chunk
            wav.write("fmt ".toByteArray())
            wav.write(intToLE(16))                    // Sub-chunk size
            wav.write(shortToLE(1))                   // Audio format: PCM
            wav.write(shortToLE(channels.toShort()))   // Channels
            wav.write(intToLE(sampleRate))             // Sample rate
            wav.write(intToLE(byteRate))               // Byte rate
            wav.write(shortToLE((channels * bitsPerSample / 8).toShort())) // Block align
            wav.write(shortToLE(bitsPerSample))        // Bits per sample

            // data sub-chunk
            wav.write("data".toByteArray())
            wav.write(intToLE(pcmSize))
            wav.write(pcmData)

            return wav.toByteArray()
        }

        /**
         * Writes PCM data as a WAV file to disk.
         * Used for local playback via AudioPlaybackPanel.
         */
        fun writeWavFile(pcmData: ByteArray, outputFile: File, sampleRate: Int = SAMPLE_RATE) {
            val wavBytes = pcmToWav(pcmData, sampleRate)
            FileOutputStream(outputFile).use { it.write(wavBytes) }
        }

        private fun intToLE(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        private fun shortToLE(value: Short): ByteArray =
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    /**
     * Starts recording audio to an in-memory buffer.
     * Call [stopRecording] to get the raw PCM bytes.
     */
    fun startRecording() {
        if (isRecording.value) return
        checkPermission()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        audioRecord?.release()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize,
        )
        audioRecord = recorder
        audioStream = ByteArrayOutputStream()
        _isRecording.value = true

        val buffer = ByteArray(minBufferSize)

        //  record in coroutine, check recordingState instead of isActive
        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                recorder.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording() failed", e)
                recorder.release()
                audioRecord = null
                _isRecording.value = false
                return@launch
            }
            val startMs = System.currentTimeMillis()

            while (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING && isActive) {
                val bytesRead = recorder.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    audioStream.write(buffer, 0, bytesRead)
                    _amplitude.value = calculatePeakAmplitude(buffer, bytesRead)
                }
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= MAX_DURATION_MS) {
                    break
                }
            }
            _isRecording.value = false
            _amplitude.value = 0
            onMaxDurationReached?.invoke()
        }
    }

    /**
     * Stops recording and returns raw PCM bytes.
     * Instant — no file I/O, no blocking.
     */
    fun stopRecording(): ByteArray {
        // Cancel the recording job FIRST to interrupt any in-flight read()
        recordingJob?.cancel()
        recordingJob = null

        val recorder = audioRecord
        audioRecord = null
        if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            recorder.stop()
        }
        recorder?.release()

        val pcmBytes = audioStream.toByteArray()
        audioStream = ByteArrayOutputStream()

        _isRecording.value = false
        _amplitude.value = 0

        debugLog(TAG, "Stopped. Recorded ${pcmBytes.size} bytes")
        return pcmBytes
    }

    fun release() {
        recordingJob?.cancel()
        recordingJob = null
        if (isRecording.value) {
            try { stopRecording() } catch (_: Exception) { }
        }
        audioRecord = null
        audioStream = ByteArrayOutputStream()
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
    }

    /** Calculate peak amplitude from PCM 16-bit audio buffer . */
    private fun calculatePeakAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val abs = kotlin.math.abs(sample)
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }
}
