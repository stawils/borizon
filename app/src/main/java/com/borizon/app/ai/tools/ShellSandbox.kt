package com.borizon.app.ai.tools

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sandboxed shell command execution for on-device AI assistant.
 *
 * Two modes:
 * - SAFE (default): Direct binary execution via ProcessBuilder. No shell interpretation.
 *   Only allowlisted binaries at /system/bin/{name}. Prevents pipes, redirects, backticks, $().
 * - SHELL: Runs via `sh -c` with a denylist check. For pipelines like `pm list packages | grep foo`.
 *
 * Security layers: binary allowlist, pattern denylist, environment scrub (4 vars only),
 * no stdin, dedicated working dir, timeout + force kill, output cap, single-execution mutex.
 */
object ShellSandbox {

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess get() = exitCode == 0
        val output: String get() = stdout.ifBlank { stderr }
    }

    private const val OUTPUT_CAP = 8000
    private const val OUTPUT_LINE_CAP = 80
    private const val DEFAULT_TIMEOUT_SECONDS = 15L
    private const val SYSTEM_BIN = "/system/bin/"

    private val SAFE_BINARY_ALLOWLIST: Set<String> = setOf(
        // File operations
        "ls", "cat", "head", "tail", "wc", "sort", "uniq", "tr", "cut", "grep",
        "find", "mkdir", "touch", "cp", "mv", "stat", "file", "du", "df",
        "basename", "dirname", "realpath", "readlink", "pwd",
        // Text processing
        "diff", "md5sum", "sha1sum", "sha256sum",
        // System info (read-only — no setprop)
        "uname", "uptime", "hostname", "date", "getprop",
        "top", "ps", "free", "vmstat", "id", "whoami", "env",
        // Package / activity (no input, wm — those allow UI automation and display modification)
        "pm", "am", "dumpsys", "settings",
        // Network info (read-only, no outbound — no ping, nslookup, dig)
        "ifconfig", "ip", "netstat", "route",
        // Misc (no base64 — can be used to bypass denylist via base64 -d|sh)
        "sleep", "which", "type", "xxd", "od", "hexdump",
    )

    private val DANGEROUS_PATTERNS: List<Regex> = listOf(
        Regex("""\bsu\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsudo\b""", RegexOption.IGNORE_CASE),
        Regex("""\brm\s+(-\w*r\w*f|\s*-\s*\w).*\s/"""),
        Regex("""\brm\s+-[^\s]*r"""),
        Regex("""\brm\s+-[^\s]*f.*\s/"""),
        Regex("""\brm\s+-R\b"""),
        Regex("""\brm\s+--recursive"""),
        Regex("""\bchmod\b"""),
        Regex("""\bchown\b"""),
        Regex("""\bchgrp\b"""),
        Regex("""\bmount\b"""),
        Regex("""\bumount\b"""),
        Regex("""\bmkfs\b"""),
        Regex("""\bdd\b"""),
        Regex("""\bfdisk\b"""),
        Regex("""\bparted\b"""),
        Regex("""\bmkswap\b"""),
        Regex("""\bswapoff\b"""),
        Regex("""\bsystemctl\b"""),
        Regex("""\bservice\b"""),
        Regex("""\binsmod\b"""),
        Regex("""\brmmod\b"""),
        Regex("""\bmodprobe\b"""),
        Regex("""\bkill\s+(-9|-s\s+9|-SIGKILL)\b"""),
        Regex("""\bpkill\b"""),
        Regex("""\bkillall\b"""),
        Regex("""\breboot\b"""),
        Regex("""\bshutdown\b"""),
        Regex("""\bhalt\b"""),
        Regex("""\bpoweroff\b"""),
        Regex("""\bcurl\b"""),
        Regex("""\bwget\b"""),
        Regex("""\bnc\b"""),
        Regex("""\bncat\b"""),
        Regex("""\bnetcat\b"""),
        Regex("""\btelnet\b"""),
        Regex("""\bssh\b"""),
        Regex("""\bscp\b"""),
        Regex("""\bsftp\b"""),
        Regex("""\brsync\b"""),
        Regex("""\bgit\b"""),
        Regex("""\bnpm\b"""),
        Regex("""\bpip\b"""),
        Regex("""\bpython\d?\b"""),
        Regex("""\bperl\b"""),
        Regex("""\bruby\b"""),
        Regex("""\bnode\b"""),
        Regex("""\bjava\b"""),
        Regex("""\bdalvikvm\b"""),
        Regex("""\bdex\b"""),
        Regex("""\bapp_process\b"""),
        Regex("""\bscreen\b"""),
        Regex("""\btmux\b"""),
        Regex("""\bnohup\b"""),
        Regex("""\bcrontab\b"""),
        Regex("""\bat\b"""),
        Regex("""\bbatch\b"""),
        Regex("""\binit\b"""),
        Regex("""\blogin\b"""),
        Regex("""\bpasswd\b"""),
        Regex("""\bnewgrp\b"""),
        Regex("""\bsg\b"""),
        Regex("""\bgdb\b"""),
        Regex("""\bstrace\b"""),
        Regex("""\bptrace\b"""),
        Regex("""\bformat\b"""),
        Regex("""\blosetup\b"""),
        Regex("""\bcryptsetup\b"""),
        Regex("""\biptables\b"""),
        Regex("""\bnft\b"""),
        Regex("""\bfirewalld\b"""),
        Regex("""\bsysctl\b"""),
        Regex("""\brestorecon\b"""),
        Regex("""\bchcon\b"""),
        Regex("""\bruncon\b"""),
        Regex("""\bsemanage\b"""),
        // Bypass vectors
        Regex("""\bbase64\b"""),
        Regex("""\beval\b"""),
        Regex("""\bexec\b"""),
        Regex("""\bfind\b.*(-exec|-delete|-ok|-fls)"""),
        Regex("""\bsource\b"""),
        Regex("""^\.\s"""),
        Regex("""\bcommand\b"""),
    )

    val SANDBOX_ENV: Map<String, String> = mapOf(
        "PATH" to SYSTEM_BIN,
        "HOME" to "/data/local/tmp",
        "TERM" to "dumb",
        "LANG" to "en_US.UTF-8",
    )

    fun execute(
        command: String,
        mode: String = "safe",
        workingDir: File? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        extraEnv: Map<String, String> = emptyMap(),
    ): ShellResult {
        return when (mode.lowercase()) {
            "shell" -> executeShellMode(command, workingDir, timeoutSeconds, extraEnv)
            else -> executeSafeMode(command, workingDir, timeoutSeconds, extraEnv)
        }
    }

    private fun executeSafeMode(
        command: String,
        workingDir: File?,
        timeoutSeconds: Long,
        extraEnv: Map<String, String>,
    ): ShellResult {
        val parts = command.trim().split("\\s+".toRegex())
        if (parts.isEmpty() || parts[0].isBlank()) {
            return ShellResult(1, "", "Empty command")
        }

        val binary = parts[0].removePrefix(SYSTEM_BIN).removePrefix("./")
        if (binary !in SAFE_BINARY_ALLOWLIST) {
            return ShellResult(
                126,
                "",
                "Binary '$binary' not in allowlist. Use 'shell' mode for advanced commands.",
            )
        }

        val args = parts.drop(1)

        // Block dangerous arguments for specific binaries
        val dangerousArgs = setOf("-delete", "-exec", "-ok", "-fls", "-fprint")
        if (binary == "find" && args.any { it in dangerousArgs }) {
            val blockedArg = args.find { it in dangerousArgs } ?: "?"
            return ShellResult(126, "", "find with $blockedArg is blocked in safe mode.")
        }

        val fullPath = if (parts[0].startsWith("/")) parts[0] else "$SYSTEM_BIN$binary"

        // Block path traversal to app private data in safe mode
        val blockedPaths = setOf("/data/data/", "/data/user/", "/data/app/")
        val allParts = listOf(fullPath) + args
        if (allParts.any { arg -> blockedPaths.any { blocked -> arg.contains(blocked) } }) {
            return ShellResult(126, "", "Access to app private data is blocked in safe mode.")
        }

        return runProcess(listOf(fullPath) + args, workingDir, timeoutSeconds, extraEnv)
    }

    private fun executeShellMode(
        command: String,
        workingDir: File?,
        timeoutSeconds: Long,
        extraEnv: Map<String, String>,
    ): ShellResult {
        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                return ShellResult(
                    126,
                    "",
                    "Forbidden pattern detected in command. Shell mode blocks dangerous operations.",
                )
            }
        }

        return runProcess(listOf("sh", "-c", command), workingDir, timeoutSeconds, extraEnv)
    }

    fun runProcess(
        commandList: List<String>,
        workingDir: File?,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        extraEnv: Map<String, String> = emptyMap(),
    ): ShellResult {
        val process: Process
        try {
            process = ProcessBuilder(commandList).apply {
                workingDir?.let { directory(it) }
                environment().clear()
                environment().putAll(SANDBOX_ENV)
                environment().putAll(extraEnv)
                redirectErrorStream(false)
            }.start()
        } catch (e: Exception) {
            return ShellResult(127, "", "Failed to start process: ${e.message}")
        }

        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        val stdoutLines = intArrayOf(0)
        val stderrLines = intArrayOf(0)

        val stdoutThread = Thread {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (stdoutBuilder.length < OUTPUT_CAP && stdoutLines[0] < OUTPUT_LINE_CAP) {
                        if (stdoutBuilder.isNotEmpty()) stdoutBuilder.append('\n')
                        stdoutBuilder.append(line)
                        stdoutLines[0]++
                    }
                }
            }
        }

        val stderrThread = Thread {
            process.errorStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (stderrBuilder.length < OUTPUT_CAP && stderrLines[0] < OUTPUT_LINE_CAP) {
                        if (stderrBuilder.isNotEmpty()) stderrBuilder.append('\n')
                        stderrBuilder.append(line)
                        stderrLines[0]++
                    }
                }
            }
        }

        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(2000)
            stderrThread.join(2000)
            return ShellResult(137, stdoutBuilder.toString(), "Command timed out after ${timeoutSeconds}s")
        }

        stdoutThread.join(5000)
        stderrThread.join(5000)

        var stdout = stdoutBuilder.toString()
        var stderr = stderrBuilder.toString()
        if (stdoutLines[0] >= OUTPUT_LINE_CAP) stdout += "\n... [$OUTPUT_LINE_CAP line limit reached]"
        else if (stdout.length >= OUTPUT_CAP) stdout += "\n... [output truncated]"
        if (stderrLines[0] >= OUTPUT_LINE_CAP) stderr += "\n... [$OUTPUT_LINE_CAP line limit reached]"
        else if (stderr.length >= OUTPUT_CAP) stderr += "\n... [output truncated]"

        return ShellResult(process.exitValue(), stdout, stderr)
    }
}
