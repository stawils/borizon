package com.borizon.app.ai.inference

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.borizon.app.util.debugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Manages model downloads using WorkManager.
 *
 * Key improvements over the old coroutine-based approach:
 * - **Survives app backgrounding**: WorkManager runs the download even if the user
 *   switches to another app or the process is killed.
 * - **Resume support**: If a download is interrupted, the `.borizontmp` file is kept
 *   and the next attempt uses HTTP Range headers to resume from where it left off.
 * - **Versioned storage**: Models are stored under `filesDir/models/{version}/` so
 *   updating the version string triggers a fresh download while old versions remain.
 * - **Foreground service**: Download runs as a foreground service with a notification,
 *   preventing the OS from killing it under memory pressure.
 */
class ModelDownloader(private val context: Context) {

    data class ModelVariant(
        val key: String,
        val displayName: String,
        val fileName: String,
        val url: String,
        val expectedSize: Long,
        /** Version string — bump this when the model file changes on HuggingFace. */
        val version: String,
    )

    companion object {
        private const val TAG = "ModelDownloader"
        private const val TMP_EXT = "borizontmp"
        private const val PREFS_NAME = "borizon_model_versions"
        private const val KEY_INSTALLED_VERSION = "installed_version_"

        val VARIANTS = listOf(
            ModelVariant(
                key = "E2B",
                displayName = "Gemma 4 E2B",
                fileName = "gemma-4-E2B-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                expectedSize = 2_600_000_000L,
                version = "v1",
            ),
            ModelVariant(
                key = "E4B",
                displayName = "Gemma 4 E4B",
                fileName = "gemma-4-E4B-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                expectedSize = 3_700_000_000L,
                version = "v1",
            ),
        )

        fun variant(key: String): ModelVariant =
            VARIANTS.find { it.key == key } ?: VARIANTS[1]
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(
            val progress: Float,
            val downloadedMb: Long = 0,
            val totalMb: Long = 0,
            val speedMbps: Float = 0f,
            val etaSeconds: Long = 0,
        ) : DownloadState()

        data object Verifying : DownloadState()
        data class Complete(val file: File) : DownloadState()
        data class Error(val message: String, val canResume: Boolean = false) : DownloadState()

        /** A partial download exists — can be resumed. */
        data class Partial(val downloadedMb: Long, val totalMb: Long) : DownloadState()

        /** New version available — model is downloaded but outdated. */
        data class UpdateAvailable(
            val currentVersion: String,
            val newVersion: String,
            val fileSizeMb: Long,
        ) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    private val workManager = WorkManager.getInstance(context)
    private val versionPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the final model file path for the CURRENT variant version.
     * `filesDir/models/{version}/{fileName}`
     */
    fun getModelFile(variantKey: String = "E4B"): File {
        val v = variant(variantKey)
        return File(context.filesDir, "models/${v.version}/${v.fileName}")
    }

    /**
     * Get the legacy model file path (pre-versioning, flat in filesDir).
     */
    private fun getLegacyModelFile(variantKey: String): File {
        val v = variant(variantKey)
        return File(context.filesDir, v.fileName)
    }

    /**
     * Get the installed version string, or null if never downloaded.
     */
    fun getInstalledVersion(variantKey: String): String? {
        return versionPrefs.getString(KEY_INSTALLED_VERSION + variantKey, null)
    }

    /**
     * Check if a newer version is available compared to what's installed.
     * Returns true if: model is downloaded AND installed version != current variant version.
     */
    fun needsUpdate(variantKey: String = "E4B"): Boolean {
        if (!isModelDownloaded(variantKey)) return false
        val installed = getInstalledVersion(variantKey) ?: return false
        val current = variant(variantKey).version
        return installed != current
    }

    /**
     * Check if the model is fully downloaded and valid.
     * Handles migration from legacy (flat) to versioned storage.
     */
    fun isModelDownloaded(variantKey: String = "E4B"): Boolean {
        val v = variant(variantKey)

        // Check any versioned location — scan all version dirs for this file
        val modelsDir = File(context.filesDir, "models")
        if (modelsDir.exists()) {
            modelsDir.listFiles { dir -> dir.isDirectory }?.forEach { versionDir ->
                val file = File(versionDir, v.fileName)
                if (file.exists() && file.length() > v.expectedSize * 0.9) {
                    return true
                }
            }
        }

        // Check legacy location — migrate if found
        val legacy = getLegacyModelFile(variantKey)
        if (legacy.exists() && legacy.length() > v.expectedSize * 0.9) {
            val newDir = File(context.filesDir, "models/${v.version}")
            if (newDir != null && !newDir.exists()) newDir.mkdirs()
            val newFile = File(newDir, v.fileName)
            val moved = legacy.renameTo(newFile)
            if (moved) {
                debugLog(TAG, "Migrated ${v.key} from legacy path to ${newFile.absolutePath}")
                // Record as installed version
                recordInstalledVersion(variantKey, v.version)
                return true
            }
            // Rename failed — legacy file is still valid
            return true
        }

        return false
    }

    /**
     * Check the initial state: downloaded, update available, partial, or not downloaded.
     * Call this on startup to set the right UI state.
     */
    fun checkInitialState(variantKey: String = "E4B") {
        val v = variant(variantKey)

        if (isModelDownloaded(variantKey)) {
            if (needsUpdate(variantKey)) {
                val installed = getInstalledVersion(variantKey) ?: "?"
                _state.value = DownloadState.UpdateAvailable(
                    currentVersion = installed,
                    newVersion = v.version,
                    fileSizeMb = getModelFile(variantKey).let { if (it.exists()) it.length() / (1024 * 1024) else 0 },
                )
            } else {
                _state.value = DownloadState.Complete(getModelFile(variantKey))
            }
        } else if (hasPartialDownload(variantKey)) {
            val partial = getPartialInfo(variantKey)
            if (partial != null) _state.value = partial
        } else {
            _state.value = DownloadState.Idle
        }
    }

    /**
     * Check if there's a partial download that can be resumed.
     */
    fun hasPartialDownload(variantKey: String = "E4B"): Boolean {
        val v = variant(variantKey)
        val tmpFile = File(context.filesDir, "models/${v.version}/${v.fileName}.$TMP_EXT")
        return tmpFile.exists() && tmpFile.length() > 0
    }

    /**
     * Get partial download info.
     */
    fun getPartialInfo(variantKey: String = "E4B"): DownloadState.Partial? {
        val v = variant(variantKey)
        val tmpFile = File(context.filesDir, "models/${v.version}/${v.fileName}.$TMP_EXT")
        if (!tmpFile.exists() || tmpFile.length() == 0L) return null
        return DownloadState.Partial(
            downloadedMb = tmpFile.length() / (1024 * 1024),
            totalMb = v.expectedSize / (1024 * 1024),
        )
    }

    /**
     * Start or resume a model download using WorkManager.
     * WorkManager guarantees the download survives app backgrounding and process death.
     */
    fun downloadModel(variantKey: String = "E4B") {
        val v = variant(variantKey)

        // Already downloaded at current version?
        if (isModelDownloaded(variantKey) && !needsUpdate(variantKey)) {
            _state.value = DownloadState.Complete(getModelFile(variantKey))
            return
        }

        // If updating, clean up old version first
        if (needsUpdate(variantKey)) {
            cleanupOldVersions(variantKey)
        }

        _state.value = DownloadState.Downloading(0f)

        val inputData = Data.Builder()
            .putString(ModelDownloadWorker.KEY_URL, v.url)
            .putString(ModelDownloadWorker.KEY_MODEL_NAME, v.displayName)
            .putString(ModelDownloadWorker.KEY_FILE_NAME, v.fileName)
            .putString(ModelDownloadWorker.KEY_VERSION, v.version)
            .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, v.expectedSize)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            "borizon_model_${v.key}",
            ExistingWorkPolicy.REPLACE,
            request,
        )

        // Record version on success, observe progress
        workManager.getWorkInfoByIdLiveData(request.id).observeForever { workInfo ->
            if (workInfo == null) return@observeForever

            when (workInfo.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                    val received = workInfo.progress.getLong(ModelDownloadWorker.KEY_RECEIVED_BYTES, 0L)
                    val speedBps = workInfo.progress.getLong(ModelDownloadWorker.KEY_SPEED, 0L)
                    val remainingMs = workInfo.progress.getLong(ModelDownloadWorker.KEY_REMAINING_MS, 0L)

                    if (received > 0) {
                        _state.value = DownloadState.Downloading(
                            progress = progress / 100f,
                            downloadedMb = received / (1024 * 1024),
                            totalMb = v.expectedSize / (1024 * 1024),
                            speedMbps = speedBps.toFloat() / (1024 * 1024),
                            etaSeconds = if (remainingMs > 0) remainingMs / 1000 else 0,
                        )
                    }
                }

                WorkInfo.State.SUCCEEDED -> {
                    recordInstalledVersion(variantKey, v.version)
                    _state.value = DownloadState.Complete(getModelFile(variantKey))
                    debugLog(TAG, "Download succeeded for ${v.key} @ ${v.version}")
                }

                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed"
                    val canResume = hasPartialDownload(variantKey)
                    _state.value = DownloadState.Error(error, canResume)
                    Log.e(TAG, "Download failed for ${v.key}: $error (canResume=$canResume)")
                }

                WorkInfo.State.CANCELLED -> {
                    _state.value = DownloadState.Idle
                }

                else -> { /* ENQUEUED — waiting */ }
            }
        }
    }

    /**
     * Cancel an in-progress download.
     * The partial `.tmp` file is kept for resume on next attempt.
     */
    fun cancelDownload(variantKey: String = "E4B") {
        val v = variant(variantKey)
        workManager.cancelUniqueWork("borizon_model_${v.key}")
        _state.value = DownloadState.Idle
    }

    /**
     * Delete the model and any partial download files for ALL versions.
     */
    fun deleteModel(variantKey: String = "E4B") {
        val v = variant(variantKey)

        // Delete all versioned copies
        val modelsDir = File(context.filesDir, "models")
        if (modelsDir.exists()) {
            modelsDir.listFiles { dir -> dir.isDirectory }?.forEach { versionDir ->
                val modelFile = File(versionDir, v.fileName)
                val tmpFile = File(versionDir, "$v.fileName.$TMP_EXT")
                modelFile.delete()
                tmpFile.delete()
            }
            // Clean up empty dirs
            modelsDir.listFiles { dir -> dir.isDirectory }?.forEach { versionDir ->
                if (versionDir.listFiles()?.isEmpty() == true) versionDir.delete()
            }
        }

        // Legacy location
        getLegacyModelFile(variantKey).delete()

        // Clear installed version
        versionPrefs.edit().remove(KEY_INSTALLED_VERSION + variantKey).apply()

        _state.value = DownloadState.Idle
    }

    /**
     * Delete old version directories, keeping only the current version.
     */
    private fun cleanupOldVersions(variantKey: String) {
        val v = variant(variantKey)
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return

        modelsDir.listFiles { dir -> dir.isDirectory }?.forEach { versionDir ->
            if (versionDir.name != v.version) {
                val oldFile = File(versionDir, v.fileName)
                val oldSize = if (oldFile.exists()) oldFile.length() / (1024 * 1024) else 0
                debugLog(TAG, "Cleaning up old version ${versionDir.name} ($oldSize MB)")
                versionDir.deleteRecursively()
            }
        }
    }

    private fun recordInstalledVersion(variantKey: String, version: String) {
        versionPrefs.edit().putString(KEY_INSTALLED_VERSION + variantKey, version).apply()
    }
}
