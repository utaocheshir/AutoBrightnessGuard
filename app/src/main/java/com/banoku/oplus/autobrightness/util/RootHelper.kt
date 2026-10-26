package com.banoku.oplus.autobrightness.util

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RootHelper {

    fun isRootAvailable(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }

    suspend fun executeSuCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = StringBuilder()
            val error = StringBuilder()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }

            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    error.append(line).append("\n")
                }
            }

            val exitCode = process.waitFor()
            CommandResult(
                success = exitCode == 0,
                output = output.toString().trim(),
                error = error.toString().trim()
            )
        } catch (t: Throwable) {
            CommandResult(
                success = false,
                output = "",
                error = t.message ?: "Execution failed"
            )
        }
    }

    suspend fun readModuleLogs(): List<LogEntry> = withContext(Dispatchers.IO) {
        val result = executeSuCommand("logcat -d -s AutoBrightnessGuard")
        if (result.success && result.output.isNotBlank()) {
            parseLogs(result.output)
        } else {
            emptyList()
        }
    }

    fun getSampleSimulationLogs(): List<LogEntry> {
        return listOf(
            LogEntry(
                type = LogType.INFO,
                time = "06:45:10",
                message = "v1.1 starting, sdk=${Build.VERSION.SDK_INT}, build=${Build.FINGERPRINT}"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "06:45:11",
                message = "hook installed; deopt(reset)=true, lambda=found, deopt=true"
            ),
            LogEntry(
                type = LogType.PASS,
                time = "06:47:30",
                message = "passed reset #1 from DisplayPowerController.updatePowerState"
            ),
            LogEntry(
                type = LogType.BLOCKED,
                time = "06:58:14",
                message = "BLOCKED reset #1 (rule=lambda) [Alarm trigger blocked]"
            ),
            LogEntry(
                type = LogType.BLOCKED,
                time = "07:22:05",
                message = "BLOCKED reset #2 (rule=alarm) [Screen-off 10m threshold protected]"
            )
        )
    }

    private fun parseLogs(raw: String): List<LogEntry> {
        return raw.lineSequence().filter { it.isNotBlank() }.map { line ->
            val type = when {
                line.contains("BLOCKED", ignoreCase = true) -> LogType.BLOCKED
                line.contains("hook installed", ignoreCase = true) -> LogType.HOOK
                line.contains("passed reset", ignoreCase = true) -> LogType.PASS
                line.contains("failed", ignoreCase = true) || line.contains("error", ignoreCase = true) -> LogType.ERROR
                else -> LogType.INFO
            }
            LogEntry(
                type = type,
                time = extractTime(line),
                message = line
            )
        }.toList()
    }

    private fun extractTime(line: String): String {
        val parts = line.split(" ")
        return if (parts.size > 1 && parts[1].contains(":")) parts[1] else ""
    }
}

data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String
)

data class LogEntry(
    val type: LogType,
    val time: String,
    val message: String
)

enum class LogType {
    INFO,
    HOOK,
    BLOCKED,
    PASS,
    ERROR
}
