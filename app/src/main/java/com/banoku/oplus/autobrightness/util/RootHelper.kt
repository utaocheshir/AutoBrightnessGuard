package com.banoku.oplus.autobrightness.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    suspend fun launchLsposedManager(context: Context) = withContext(Dispatchers.IO) {
        // 1. Try standard launcher intent
        val launchIntent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return@withContext
            } catch (ignored: Throwable) {
            }
        }

        // 2. Try explicit component intent
        try {
            val explicitIntent = Intent().apply {
                component = ComponentName("org.lsposed.manager", "org.lsposed.manager.ui.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(explicitIntent)
            return@withContext
        } catch (ignored: Throwable) {
        }

        // 3. Try standard secret code broadcast (android_secret_code://5776733)
        try {
            val secretIntent = Intent("android.telephony.action.SECRET_CODE").apply {
                data = Uri.parse("android_secret_code://5776733")
                addFlags(0x00400000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
            }
            context.sendBroadcast(secretIntent)
        } catch (ignored: Throwable) {
        }

        // 4. Try root command broadcast (exact method used by KernelSU / KSUN)
        executeSuCommand("am start -n org.lsposed.manager/.ui.MainActivity 2>/dev/null || am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733")
    }

    suspend fun setSystemProperty(key: String, enabled: Boolean): Boolean {
        val valStr = if (enabled) "1" else "0"
        val res = executeSuCommand("setprop $key $valStr")
        return res.success
    }

    suspend fun setSystemPropertyString(key: String, value: String): Boolean {
        val safeVal = value.replace("'", "'\\''")
        val res = executeSuCommand("setprop $key '$safeVal'")
        return res.success
    }

    suspend fun getSystemPropertyString(key: String, defaultValue: String = ""): String {
        val res = executeSuCommand("getprop $key")
        return if (res.success && res.output.isNotBlank()) res.output.trim() else defaultValue
    }

    suspend fun readModuleLogs(): List<LogEntry> = withContext(Dispatchers.IO) {
        // 1. Try reading the most recent LSPosed module log file directly (/data/adb/lspd/log/modules_*.log)
        val lspCmd = "cat \$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1) 2>/dev/null | grep -i AutoBrightnessGuard | tail -n 120"
        var result = executeSuCommand(lspCmd)
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output).reversed()
        }

        // 2. Try wildcard LSPosed log files
        result = executeSuCommand("cat /data/adb/lspd/log/modules*.log 2>/dev/null | grep -i AutoBrightnessGuard | tail -n 120")
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output).reversed()
        }

        // 3. Try logcat across all buffers (-b all includes system buffer where system_server lives)
        result = executeSuCommand("logcat -d -b all -s AutoBrightnessGuard:V | tail -n 120")
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output).reversed()
        }

        // 4. Try logcat grep across all buffers
        result = executeSuCommand("logcat -d -b all | grep -i AutoBrightnessGuard | tail -n 120")
        if (result.success && result.output.isNotBlank()) {
            return@withContext parseLogs(result.output).reversed()
        }

        emptyList()
    }

    fun getSampleSimulationLogs(): List<LogEntry> {
        return listOf(
            LogEntry(
                type = LogType.INFO,
                time = "20:00:10",
                message = "v1.4.2 (ColorOS 17 Verified Engine) starting, sdk=${Build.VERSION.SDK_INT}, build=${Build.FINGERPRINT}"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [1/9] controller resetAutoBrightness installed (variants=2, lambda=true)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [8/9] StabilityProcess hysteresis widener installed (targets=2)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [9/9] OplusDisplayBrightnessModel.getValueByCurve(String, float) live curve installed"
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
                type = LogType.INFO,
                time = "20:15:03",
                message = "GUARD hysteresis (1.8x) at 45.0 lx: [34.0..58.0] -> [23.2..72.4]"
            ),
            LogEntry(
                type = LogType.PASS,
                time = "23:14:01",
                message = "passed resetAutoBrightness #1 from VMStack.getThreadStackTrace"
            )
        ).reversed()
    }

    fun computeStats(logs: List<LogEntry>): GuardStats {
        val blockedLogs = logs.filter { it.type == LogType.BLOCKED }
        val count = blockedLogs.size
        // Logs are ordered newest-first, so the first BLOCKED item is the most recent.
        val latest = blockedLogs.firstOrNull()
        return GuardStats(
            totalBlocked = count,
            lastBlockedTime = latest?.time ?: "",
            lastBlockedMessage = latest?.message ?: ""
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
