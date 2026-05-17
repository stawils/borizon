package com.borizon.app.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import android.util.Log
import com.borizon.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Inline audio playback panel with waveform visualization.
 * Plays WAV files (16kHz mono 16-bit PCM) using AudioTrack.
 */
@Composable
fun AudioPlaybackPanel(
    wavFile: File,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableFloatStateOf(0f) }
    var waveformData by remember { mutableStateOf(floatArrayOf()) }
    var playbackJob by remember { mutableStateOf<Job?>(null) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }

    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val focusRequest = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setOnAudioFocusChangeListener { }
            .build()
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Parse WAV header and extract waveform on first composition
    LaunchedEffect(wavFile) {
        withContext(Dispatchers.IO) {
            if (!wavFile.exists()) return@withContext
            var raf: RandomAccessFile? = null
            try {
                raf = RandomAccessFile(wavFile, "r")
                raf.seek(12)
                val fmtId = ByteArray(4)
                raf.read(fmtId)
                val fmtSize = raf.readIntLE()
                raf.skipBytes(fmtSize)

                val dataId = ByteArray(4)
                raf.read(dataId)
                val dataSize = raf.readIntLE()

                val sampleRate = 16000
                val bytesPerSample = 2
                val totalSamples = dataSize / bytesPerSample
                durationMs = (totalSamples.toFloat() / sampleRate) * 1000f

                val barCount = 60
                val samplesPerBar = totalSamples / barCount

                val bars = FloatArray(barCount)
                val buffer = ByteBuffer.allocate(bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until barCount) {
                    var maxAmp = 0f
                    for (j in 0 until samplesPerBar.coerceAtMost(200)) {
                        val offset = 12 + 8 + fmtSize + 8 + (i * samplesPerBar + j) * bytesPerSample
                        if (offset >= raf.length() - bytesPerSample) break
                        raf.seek(offset.toLong())
                        raf.read(buffer.array(), 0, bytesPerSample)
                        buffer.rewind()
                        val sample = buffer.short.toFloat() / Short.MAX_VALUE
                        if (kotlin.math.abs(sample) > maxAmp) maxAmp = kotlin.math.abs(sample)
                    }
                    bars[i] = maxAmp
                }
                waveformData = bars
            } catch (e: Exception) {
                Log.e("AudioPlaybackPanel", "WAV parse failed: ${e.message}", e)
                waveformData = FloatArray(60) { 0.3f }
            } finally {
                try { raf?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        val track = audioTrack
        audioTrack = null
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) {}
        isPlaying = false
        progress = 0f
    }

    // Playback coroutine with progress tracking
    fun startPlayback() {
        stopPlayback()
        val focusResult = audioManager.requestAudioFocus(focusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        playbackJob = scope.launch(Dispatchers.IO) {
            var raf: RandomAccessFile? = null
            var track: AudioTrack? = null
            try {
                if (!wavFile.exists()) return@launch

                raf = RandomAccessFile(wavFile, "r")
                raf.seek(12)
                val fmtId = ByteArray(4); raf.read(fmtId)
                val fmtSize = raf.readIntLE()
                raf.skipBytes(fmtSize)
                val dataId = ByteArray(4); raf.read(dataId)
                val dataSize = raf.readIntLE()

                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize * 4)
                    .build()

                withContext(Dispatchers.Main) {
                    audioTrack = track
                    isPlaying = true
                }
                track.play()

                val pcmData = ByteArray(dataSize)
                raf.seek((12 + 8 + fmtSize + 8).toLong())
                raf.read(pcmData, 0, dataSize)

                // Write in chunks for progress tracking
                val chunkSize = 1024
                var offset = 0
                while (isActive && offset < dataSize) {
                    val toWrite = minOf(chunkSize, dataSize - offset)
                    track.write(pcmData, offset, toWrite)
                    offset += toWrite
                    withContext(Dispatchers.Main) {
                        progress = offset.toFloat() / dataSize
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioPlaybackPanel", "Playback failed: ${e.message}", e)
            } finally {
                try { raf?.close() } catch (_: Exception) {}
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    audioTrack = null
                    isPlaying = false
                    progress = 0f
                }
            }
        }
    }

    DisposableEffect(wavFile) {
        onDispose { stopPlayback() }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button
            FilledIconButton(
                onClick = {
                    if (isPlaying) stopPlayback() else startPlayback()
                },
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = accentColor
                )
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.audio_pause) else stringResource(R.string.audio_play),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Waveform + progress
            Column(modifier = Modifier.weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                ) {
                    val barCount = waveformData.size
                    if (barCount == 0) return@Canvas

                    val totalWidth = size.width
                    val totalHeight = size.height
                    val barGap = 2f
                    val barWidth = (totalWidth - (barCount - 1) * barGap) / barCount
                    val playedBars = (progress * barCount).toInt()

                    for (i in 0 until barCount) {
                        val amp = waveformData[i].coerceIn(0.05f, 1f)
                        val barHeight = amp * totalHeight * 0.8f
                        val x = i * (barWidth + barGap)
                        val y = (totalHeight - barHeight) / 2f

                        val barColor = if (i <= playedBars) accentColor
                        else accentColor.copy(alpha = 0.25f)

                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f)
                        )
                    }
                }

                // Time display
                val currentTimeMs = progress * durationMs
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(currentTimeMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Text(
                        text = formatDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Float): String {
    val seconds = (ms / 1000f).toInt()
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

private fun RandomAccessFile.readIntLE(): Int {
    val b = ByteArray(4)
    read(b)
    return (b[0].toInt() and 0xFF) or
           ((b[1].toInt() and 0xFF) shl 8) or
           ((b[2].toInt() and 0xFF) shl 16) or
           ((b[3].toInt() and 0xFF) shl 24)
}
