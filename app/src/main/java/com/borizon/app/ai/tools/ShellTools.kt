package com.borizon.app.ai.tools

import android.content.Context
import android.util.Log
import com.borizon.app.BuildConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import com.borizon.app.ai.tools.ToolCallTracker

class ShellTools(
    private val context: Context,
    private val actionChannel: Channel<BorizonAction>,
) : ToolSet {

    companion object {
        private const val TAG = "ShellTools"
    }

    private val executionMutex = ReentrantLock()
    private val appFilesPath = context.filesDir.absolutePath

    @Tool(description = "Run commands on this phone.")
    fun shellExecute(
        @ToolParam(description = "Command to run") command: String,
        @ToolParam(description = "safe or shell mode") mode: String = "safe",
    ): Map<String, String> {
        ToolCallTracker.increment()
        val displayCommand = command.take(60).let { if (command.length > 60) "$it..." else it }
        val modeLabel = mode.lowercase()

        actionChannel.trySend(BorizonAction.Progress(
            label = "shell: $displayCommand",
            isInProgress = true,
            toolType = ToolType.SHELL_EXECUTE,
            detailDescription = "Running in $modeLabel mode",
        ))

        val result = executionMutex.withLock {
                File(context.filesDir, "shell_sandbox").mkdirs()
                ShellSandbox.execute(
                    command = command,
                    mode = mode,
                    workingDir = File(context.filesDir, "shell_sandbox"),
                    extraEnv = mapOf(
                        "APP_FILES" to appFilesPath,
                        "SDCARD" to "/sdcard",
                        "DCIM" to "/sdcard/DCIM",
                        "DOWNLOADS" to "/sdcard/Download",
                        "PICTURES" to "/sdcard/Pictures",
                    ),
                )
        }

        actionChannel.trySend(BorizonAction.Progress(
            label = "shell: $displayCommand",
            isInProgress = false,
            toolType = ToolType.SHELL_EXECUTE,
            detailDescription = if (result.isSuccess) "Done (${result.exitCode})" else "Failed (${result.exitCode})",
        ))

        return if (result.isSuccess) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Shell succeeded: $displayCommand")
            val output = result.output
            val truncated = if (output.length > 800) output.take(800) + "\n... (${output.length} chars total)" else output
            mapOf("result" to "ok", "exit_code" to result.exitCode.toString(), "output" to truncated)
        } else {
            Log.w(TAG, "Shell failed (${result.exitCode}): $displayCommand")
            if (BuildConfig.DEBUG) Log.d(TAG, "stderr: ${result.stderr}")
            val suggestion = when (result.exitCode) {
                126 -> when {
                    modeLabel == "safe" -> "Try mode='shell' — it supports pipes, redirects, and variables like \$APP_FILES."
                    else -> "Command blocked by safety filter. Try a different approach or rephrase the command."
                }
                127 -> "Binary not found. Check spelling. Common commands: ls, cat, find, grep, getprop, df, du, pm."
                137 -> "Command timed out (15s). Try a simpler/faster command, or add | head -20 to limit output."
                1 -> when {
                    result.stderr.contains("No such file") || result.stderr.contains("Not a directory") -> "Path not found. Android paths: /sdcard/DCIM (camera), /sdcard/Download, /sdcard/Pictures, /sdcard/Music. Try 'ls /sdcard/' first."
                    result.stderr.contains("Permission denied") -> "Permission denied for this path. Try /sdcard/ subdirectories instead."
                    else -> "Command failed. Check the command and try again, or try 'shell' mode for complex commands."
                }
                else -> "Try a different command or use 'shell' mode for pipes and redirects."
            }
            mapOf(
                "result" to "error",
                "exit_code" to result.exitCode.toString(),
                "error" to (result.stderr.ifBlank { result.stdout }),
                "try" to suggestion,
            )
        }
    }
}
