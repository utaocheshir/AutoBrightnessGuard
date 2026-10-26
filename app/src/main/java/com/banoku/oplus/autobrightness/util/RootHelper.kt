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

    suspend fun setSystemProperty(key: String, enabled: Boolean): Boolean {
        val valStr = if (enabled) "1" else "0"
        val res = executeSuCommand("setprop $key $valStr")
        return res.success
    }

    suspend fun readModuleLogs(): List<LogEntry> = withContext(Dispatchers.IO) {
        // 1. Try reading the most recent LSPosed module log file directly (/data/adb/lspd/log/modules_*.log)
        val lspCmd = "cat \$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1) 2>/dev/null | grep -i AutoBrightnessGuard | tail -n 100"
        var result = executeSuCommand(lspCmd)
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output)
        }

        // 2. Try wildcard LSPosed log files
        result = executeSuCommand("cat /data/adb/lspd/log/modules*.log 2>/dev/null | grep -i AutoBrightnessGuard | tail -n 100")
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output)
        }

        // 3. Try logcat across all buffers (-b all includes system buffer where system_server lives)
        result = executeSuCommand("logcat -d -b all -s AutoBrightnessGuard:V | tail -n 100")
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output)
        }

        // 4. Try logcat grep across all buffers
        result = executeSuCommand("logcat -d -b all | grep -i AutoBrightnessGuard | tail -n 100")
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output)
        }

        emptyList()
    }

    fun getSampleSimulationLogs(): List<LogEntry> {
        return listOf(
            LogEntry(
                type = LogType.INFO,
                time = "20:00:10",
                message = "v1.3b starting, sdk=${Build.VERSION.SDK_INT}, build=${Build.FINGERPRINT}"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [1/2] controller installed; deopt(reset)=true, lambda=found, deopt=true"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [2/2] ambient adapter installed; deopt=true"
            ),
            LogEntry(
                type = LogType.BLOCKED,
                time = "20:11:49",
                message = "BLOCKED resetAutoBrightness #1 (rule=lambda)"
            ),
            LogEntry(
                type = LogType.BLOCKED,
                time = "20:11:49",
                message = "BLOCKED resetAmbientAdapter #1 (reason=AutoReset, rule=lambda)"
            ),
            LogEntry(
                type = LogType.PASS,
                time = "23:14:01",
                message = "passed resetAutoBrightness #1 from VMStack.getThreadStackTrace"
            )
        )
    }

    fun computeStats(logs: List<LogEntry>): GuardStats {
        val blockedLogs = logs.filter { it.type == LogType.BLOCKED }
        val count = blockedLogs.size
        val last = blockedLogs.lastOrNull()
        return GuardStats(
            totalBlocked = count,
            lastBlockedTime = last?.time ?: "",
            lastBlockedMessage = last?.message ?: ""
        )
    }

    private fun parseLogs(raw: String): List<LogEntry> {
        return raw.lineSequence().filter { it.isNotBlank() }.map { line ->
            val type = when {
                line.contains("BLOCKED", ignoreCase = true) -> LogType.BLOCKED
                line.contains("hook installed", ignoreCase = true) || line.contains("Hook [", ignoreCase = true) -> LogType.HOOK
                line.contains("passed reset", ignoreCase = true) -> LogType.PASS
                line.contains("failed", ignoreCase = true) || line.contains("error", ignoreCase = true) -> LogType.ERROR
                else -> LogType.INFO
            }
            LogEntry(
                type = type,
                time = extractTime(line),
                message = cleanLogMessage(line)
            )
        }.toList()
    }

    private fun extractTime(line: String): String {
        // Formats like: [ 2026-10-26T20:11:49.628 ... ] or 20:11:49 or 10-26 20:11:49
        val tIdx = line.indexOf('T')
        if (tIdx in 1..20 && tIdx + 9 <= line.length) {
            val candidate = line.substring(tIdx + 1, tIdx + 9)
            if (candidate.contains(':')) return candidate
        }
        val parts = line.split(" ", "\t")
        for (p in parts) {
            val clean = p.trim('[', ']', '(', ')')
            if (clean.count { it == ':' } >= 2 && clean.length in 5..12) {
                return clean.take(8)
            }
        }
        return ""
    }

    private fun cleanLogMessage(line: String): String {
        // Strip LSPosed prefix e.g. [ ... I/LSPosedFramework ] (system)[...AutoBrightnessGuard...] BLOCKED ...
        val guardIdx = line.indexOf("AutoBrightnessGuard")
        if (guardIdx != -1) {
            val afterBracket = line.indexOf(']', guardIdx)
            if (afterBracket != -1 && afterBracket + 1 < line.length) {
                return line.substring(afterBracket + 1).trim()
            }
        }
        return line.trim()
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

data class GuardStats(
    val totalBlocked: Int,
    val lastBlockedTime: String,
    val lastBlockedMessage: String
)

enum class LogType {
    INFO,
    HOOK,
    BLOCKED,
    PASS,
    ERROR
}
