package com.borizon.app.ai.tools

import android.content.Context
import android.util.Log
import com.borizon.app.BuildConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

        /**
         * Shell-mode commands require explicit user confirmation.
         *
         * Commands matching these patterns are ALWAYS allowed in safe mode
         * (allowlist-only, no shell interpretation). Shell mode (sh -c) is only
         * invoked after the user confirms via [BorizonAction.Confirm].
         *
         * The model CANNOT switch to shell mode autonomously — it must ask the user.
         * This closes the bypass vector where the model could pass mode=shell
         * to circumvent the safe-mode allowlist.
         *
         * The mode parameter is retained for backward compatibility but is ignored
         * when the model passes it. Mode escalation is determined by:
         *   1. Does the command need shell features (pipes, redirects, variables)?
         *   2. If yes → prompt user for confirmation before running in shell mode.
         *   3. If no → run in safe mode (default, no confirmation needed).
         */
    }

    private val executionMutex = ReentrantLock()
    private val appFilesPath = context.filesDir.absolutePath

    /**
     * Detect if a command requires shell features that safe mode cannot provide.
     *
     * Safe mode runs a single binary with args (no shell interpretation).
     * Shell mode runs `sh -c <command>` which enables pipes, redirects,
     * variable expansion, command substitution, etc.
     *
     * Shell features are detected by looking for shell metacharacters.
     */
    private fun requiresShell(command: String): Boolean {
        val shellMetacharacters = listOf('|', '>', '<', '$', '`', ';', '&', '(', ')')
        // Also detect common shell constructs
        val shellPatterns = listOf(
            Regex("""\$\{"""),        // ${var}
            Regex("""\$\("""),        // $(cmd)
            Regex("&&"),
            Regex("||"),
            Regex(">>"),
            Regex("<<"),
            Regex("2>"),            // stderr redirect
        )
        return shellMetacharacters.any { it in command } ||
               shellPatterns.any { it.containsMatchIn(command) }
    }

    @Tool(description = "Run commands on this phone. Safe mode by default (single command, no pipes). For pipes/redirects, the user must approve.")
    fun shellExecute(
        @ToolParam(description = "Command to run") command: String,
        @ToolParam(description = "Execution mode. Ignored — mode is determined automatically.") mode: String = "safe",
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        ToolCallTracker.increment()
        if (!ToolCallTracker.canCall("shellExecute")) {
            return@runBlocking mapOf("result" to "error", "exit_code" to "126",
                "error" to "Rate limit: max 5 shell commands per turn.")
        }
        val displayCommand = command.take(60).let { if (command.length > 60) "$it..." else it }

        val needsShell = requiresShell(command)

        actionChannel.trySend(BorizonAction.Progress(
            label = "shell: $displayCommand",
            isInProgress = true,
            toolType = ToolType.SHELL_EXECUTE,
            detailDescription = if (needsShell) "Requires shell mode — awaiting user approval" else "Running in safe mode",
        ))

        if (needsShell) {
            val confirm = BorizonAction.Confirm(
                message = "Run shell command?\n$command"
            )
            actionChannel.trySend(confirm)

            val approved = try {
                withTimeout(60_000L) {
                    confirm.result.await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shell mode confirmation failed: ${e.message}")
                false
            }

            if (!approved) {
                actionChannel.trySend(BorizonAction.Progress(
                    label = "shell: $displayCommand",
                    isInProgress = false,
                    toolType = ToolType.SHELL_EXECUTE,
                    detailDescription = "Cancelled by user",
                ))
                return@runBlocking mapOf(
                    "result" to "error",
                    "exit_code" to "126",
                    "error" to "Shell command not approved by user. Try rewriting as a simple command without pipes or redirects.",
                )
            }
        }

        val effectiveMode = if (needsShell) "shell" else "safe"

        val result = executionMutex.withLock {
                File(context.filesDir, "shell_sandbox").mkdirs()
                ShellSandbox.execute(
                    command = command,
                    mode = effectiveMode,
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

        if (result.isSuccess) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Shell succeeded: $displayCommand")
            val output = result.output
            val truncated = if (output.length > 800) output.take(800) + "\n... (${output.length} chars total)" else output
            mapOf("result" to "ok", "exit_code" to result.exitCode.toString(), "output" to truncated)
        } else {
            Log.w(TAG, "Shell failed (${result.exitCode}): $displayCommand")
            if (BuildConfig.DEBUG) Log.d(TAG, "stderr: ${result.stderr}")
            val suggestion = when (result.exitCode) {
                126 -> "Command not allowed. Try a simpler command without pipes or redirects."
                127 -> "Binary not found. Check spelling. Common commands: ls, cat, find, grep, getprop, df, du, pm."
                137 -> "Command timed out (15s). Try a simpler/faster command, or add | head -20 to limit output."
                1 -> when {
                    result.stderr.contains("No such file") || result.stderr.contains("Not a directory") -> "Path not found. Android paths: /sdcard/DCIM (camera), /sdcard/Download, /sdcard/Pictures, /sdcard/Music. Try 'ls /sdcard/' first."
                    result.stderr.contains("Permission denied") -> "Permission denied for this path. Try /sdcard/ subdirectories instead."
                    else -> "Command failed. Check the command and try again."
                }
                else -> "Try a different command."
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
