package com.borizon.app.ai.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference
import com.borizon.app.ai.tools.ToolCallTracker

class InstalledAppsTools(
    private val context: Context,
    private val actionChannel: Channel<BorizonAction>,
) : ToolSet {

    companion object {
        private const val TAG = "InstalledAppsTools"
        private const val CACHE_TTL_MS = 60_000L // 1 minute

        private val cachedApps = AtomicReference<List<CachedApp>>(emptyList())
        private var lastRefreshMs = 0L

        data class CachedApp(
            val info: ApplicationInfo,
            val label: String,
            val packageName: String,
            val isSystem: Boolean,
        )

        @Synchronized
        fun refreshIfNeeded(pm: PackageManager, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastRefreshMs < CACHE_TTL_MS && cachedApps.get().isNotEmpty()) return
            try {
                cachedApps.set(
                    pm.getInstalledApplications(PackageManager.MATCH_ALL).map { info ->
                        CachedApp(
                            info = info,
                            label = pm.getApplicationLabel(info).toString(),
                            packageName = info.packageName,
                            isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }
                )
                lastRefreshMs = now
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh app cache", e)
            }
        }

        fun getCachedApps(pm: PackageManager): List<CachedApp> {
            refreshIfNeeded(pm)
            return cachedApps.get()
        }
    }

    private val pm: PackageManager get() = context.packageManager

    @Tool(description = "List installed apps or get details about a specific app.")
    fun appInfo(
        @ToolParam(description = "list or details") action: String,
        @ToolParam(description = "Category filter (list: system/user/all) or app name (details)") query: String = "all",
    ): Map<String, String> {
        ToolCallTracker.increment()
        val toolType = if (action == "details") ToolType.APP_DETAILS else ToolType.APP_LIST
        actionChannel.trySend(
            BorizonAction.Progress(label = "Reading apps", isInProgress = true, toolType = toolType),
        )

        return try {
            val allApps = getCachedApps(pm)

            when (action) {
                "details" -> {
                    val match = allApps.firstOrNull {
                        it.label.contains(query, ignoreCase = true)
                    } ?: allApps.firstOrNull {
                        it.packageName.contains(query, ignoreCase = true)
                    }
                    if (match == null) {
                        actionChannel.trySend(
                            BorizonAction.Progress(label = "App not found", isInProgress = false, toolType = toolType),
                        )
                        return mapOf("result" to "not_found", "message" to "App '$query' not found")
                    }
                    val size = try {
                        match.info.sourceDir?.let { java.io.File(it).length() } ?: 0L
                    } catch (_: Exception) { 0L }
                    val sizeStr = if (size > 1_000_000) "%.1fMB".format(size / 1_000_000.0)
                        else "%.0fKB".format(size / 1_000.0)
                    val version = try {
                        pm.getPackageInfo(match.packageName, 0).versionName ?: "unknown"
                    } catch (_: Exception) { "unknown" }

                    actionChannel.trySend(
                        BorizonAction.Progress(label = match.label, isInProgress = false, toolType = toolType),
                    )
                    mapOf(
                        "result" to "found",
                        "name" to match.label,
                        "package" to match.packageName,
                        "version" to version,
                        "size" to sizeStr,
                        "type" to if (match.isSystem) "system" else "user",
                    )
                }
                else -> {
                    val category = query.lowercase()
                    val filtered = when (category) {
                        "system" -> allApps.filter { it.isSystem }
                        "user" -> allApps.filter { !it.isSystem }
                        else -> allApps
                    }
                    val results = filtered
                        .sortedBy { it.label.lowercase() }
                        .take(30)
                        .map { "${it.label} (${it.packageName}) - ${if (it.isSystem) "System" else "User"}" }
                    actionChannel.trySend(
                        BorizonAction.Progress(
                            label = "Found ${results.size} apps",
                            isInProgress = false,
                            toolType = toolType,
                        ),
                    )
                    mapOf(
                        "result" to "found",
                        "count" to results.size.toString(),
                        "apps" to results.joinToString("\n"),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read apps", e)
            mapOf("result" to "error", "error" to "Failed to read installed apps: ${e.message}")
        }
    }
}
