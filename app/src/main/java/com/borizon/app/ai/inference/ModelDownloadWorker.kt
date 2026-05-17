package com.borizon.app.ai.inference

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager-based model download worker.
 *
 * Key features over the old coroutine approach:
 * - Survives app backgrounding and process death (WorkManager guarantee)
 * - Runs as foreground service with notification
 * - Supports HTTP Range resume for partial downloads
 * - Versioned storage: {modelDir}/{version}/{file}
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ModelDownloadWorker"
        private const val CHANNEL_ID = "borizon_model_download"
        private const val TMP_EXT = "borizontmp"

        // Input keys
        const val KEY_URL = "url"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_VERSION = "version"
        const val KEY_TOTAL_BYTES = "total_bytes"

        // Progress keys
        const val KEY_PROGRESS = "progress"
        const val KEY_RECEIVED_BYTES = "received_bytes"
        const val KEY_SPEED = "speed_bps"
        const val KEY_REMAINING_MS = "remaining_ms"

        // Output keys
        const val KEY_ERROR = "error_message"
        const val KEY_DOWNLOADED_FILE = "downloaded_file"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notificationId = id.hashCode()

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(
            dataOf(KEY_ERROR to "No URL provided")
        )
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure(
            dataOf(KEY_ERROR to "No file name provided")
        )
        val version = inputData.getString(KEY_VERSION) ?: "_"
        val expectedBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)

        return try {
            try {
                setForeground(createForegroundInfo(0, modelName))
            } catch (_: Exception) {
                // Foreground service not allowed (app in background on Android 12+).
                // Continue as regular background work — no notification but download still runs.
                Log.w(TAG, "Foreground service not allowed, downloading in background")
            }

            downloadFile(
                url = url,
                modelName = modelName,
                fileName = fileName,
                version = version,
                expectedBytes = expectedBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(dataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun downloadFile(
        url: String,
        modelName: String,
        fileName: String,
        version: String,
        expectedBytes: Long,
    ): Result {
        val outputDir = File(applicationContext.filesDir, "models/$version")
        if (!outputDir.exists()) outputDir.mkdirs()

        val tmpFile = File(outputDir, "$fileName.$TMP_EXT")
        val finalFile = File(outputDir, fileName)

        // Already downloaded? (e.g. worker re-ran after success)
        if (finalFile.exists() && finalFile.length() > 0) {
            Log.d(TAG, "Model already exists: ${finalFile.absolutePath}")
            return Result.success(dataOf(KEY_DOWNLOADED_FILE to finalFile.absolutePath))
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            // Resume support: if tmp file exists, request remaining bytes
            val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
            if (existingBytes > 0) {
                Log.d(TAG, "Resuming download from byte $existingBytes (${existingBytes / 1024 / 1024}MB already downloaded)")
                setRequestProperty("Range", "bytes=$existingBytes-")
                setRequestProperty("Accept-Encoding", "identity")
            }
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK &&
                responseCode != HttpURLConnection.HTTP_PARTIAL
            ) {
                return Result.failure(
                    dataOf(KEY_ERROR to "Server returned HTTP $responseCode")
                )
            }

            // Determine starting offset
            var downloaded = 0L
            val contentRange = connection.getHeaderField("Content-Range")
            if (contentRange != null) {
                // Content-Range: bytes START-END/TOTAL
                val start = contentRange.substringAfter("bytes ")
                    .substringBefore("-").toLongOrNull() ?: 0L
                downloaded = start
                Log.d(TAG, "Resuming from byte $downloaded (Content-Range: $contentRange)")
            }

            val totalSize = if (contentRange != null) {
                // Total from Content-Range header
                contentRange.substringAfter("/").toLongOrNull() ?: expectedBytes
            } else {
                connection.contentLengthLong.coerceAtLeast(expectedBytes)
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tmpFile, downloaded > 0 /* append if resuming */)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var lastReportTs = 0L
            var lastReportedDownloaded = downloaded
            val speedWindow = mutableListOf<Pair<Long, Long>>() // (timestamp, bytesAtTime)

            while (true) {
                bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                // Report progress every 250ms
                val now = System.currentTimeMillis()
                if (now - lastReportTs > 250) {
                    val progress = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0

                    // Calculate speed from sliding window
                    speedWindow.add(Pair(now, downloaded))
                    // Keep last 5 samples
                    while (speedWindow.size > 5) speedWindow.removeAt(0)
                    val speedBps = if (speedWindow.size >= 2) {
                        val (t0, b0) = speedWindow.first()
                        val (t1, b1) = speedWindow.last()
                        val dt = (t1 - t0).coerceAtLeast(1)
                        (b1 - b0).toFloat() / dt * 1000
                    } else 0f

                    val remainingMs = if (speedBps > 0 && totalSize > downloaded) {
                        ((totalSize - downloaded) / speedBps * 1000).toLong()
                    } else 0L

                    setProgress(Data.Builder()
                        .putInt(KEY_PROGRESS, progress)
                        .putLong(KEY_RECEIVED_BYTES, downloaded)
                        .putLong(KEY_SPEED, speedBps.toLong())
                        .putLong(KEY_REMAINING_MS, remainingMs)
                        .build()
                    )

                    if (progress > 0) {
                        try { setForeground(createForegroundInfo(progress, modelName)) } catch (_: Exception) {}
                    }

                    lastReportTs = now
                    lastReportedDownloaded = downloaded
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Verify download
            if (expectedBytes > 0 && tmpFile.length() < expectedBytes * 0.9) {
                Log.e(TAG, "Download incomplete: ${tmpFile.length()} / $expectedBytes bytes")
                // Don't delete tmp — allows resume on retry
                return Result.failure(
                    dataOf(KEY_ERROR to "Download incomplete (${tmpFile.length() / 1024 / 1024}MB / ${expectedBytes / 1024 / 1024}MB). Will resume on retry.")
                )
            }

            // Rename tmp → final
            if (finalFile.exists()) finalFile.delete()
            val renamed = tmpFile.renameTo(finalFile)
            if (!renamed) {
                return Result.failure(
                    dataOf(KEY_ERROR to "Failed to finalize download file.")
                )
            }

            Log.d(TAG, "Download complete: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
            return Result.success(dataOf(KEY_DOWNLOADED_FILE to finalFile.absolutePath))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0)

    private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
        createNotificationChannel()

        val title = if (modelName != null) "Downloading \"$modelName\"" else "Downloading model"
        val content = if (progress > 0) "Downloading: $progress%" else "Preparing download..."

        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows model download progress"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun dataOf(vararg pairs: Pair<String, String>): Data {
        return Data.Builder().apply {
            pairs.forEach { (key, value) -> putString(key, value) }
        }.build()
    }
}
