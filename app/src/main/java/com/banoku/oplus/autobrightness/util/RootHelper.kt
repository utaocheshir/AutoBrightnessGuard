package com.banoku.oplus.autobrightness.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.banoku.oplus.autobrightness.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object RootHelper {

    private val SU_CANDIDATE_PATHS = arrayOf(
        "/debug_ramdisk/su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su"
    )

    // Ordered candidates for exec() probing across Magisk/Alpha/Kitsune, KSU/KSUN/SukiSU/BakaSU, APatch
    private val SU_EXEC_CANDIDATES = arrayOf(
        "su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/vendor/bin/su",
        "/su/bin/su"
    )

    const val MODULE_DISK_LOG_PATH = "/data/system/autobrightness_guard.log"

    private const val MODULE_LOG_CANDIDATES =
        "/data/adb/lspd/log/modules_*.log /data/adb/lspd/log/verbose_*.log " +
            "/data/adb/lsp/log/modules_*.log /data/adb/lsp/log/verbose_*.log " +
            "/data/adb/vector/log/modules_*.log /data/adb/vector/log/verbose_*.log"

    private const val MODULE_BOOT_PATTERN =
        "AutoBrightnessGuard.*(onModuleLoaded|starting, sdk=|Hook \\[|HOOK_SUMMARY:|STOCK_CURVE:|BLOCKED |GUARD_STATS:)"

    private val suProbeMutex = Mutex()

    @Volatile
    private var cachedSuBinary: String? = null

    fun isRootAvailable(): Boolean {
        if (cachedSuBinary != null) return true
        for (path in SU_CANDIDATE_PATHS) {
            if (File(path).exists()) return true
        }
        val pathDirs = System.getenv("PATH")?.split(':') ?: emptyList()
        for (dir in pathDirs) {
            if (dir.isNotBlank() && File(dir, "su").exists()) return true
        }
        return false
    }

    /**
     * Authoritative runtime root check across Magisk (incl. Alpha/Kitsune), KernelSU,
     * KernelSU-Next, BakaSU (SuSFS), SukiSU, and APatch via actual `su -c id -u`.
     * Caches the first working su binary so subsequent commands never re-trigger prompts or redundant probes.
     */
    suspend fun checkRootAccess(forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        ensureSuBinary(forceRefresh) != null
    }

    private suspend fun ensureSuBinary(forceRefresh: Boolean = false): String? = suProbeMutex.withLock {
        val current = if (!forceRefresh) cachedSuBinary else null
        if (current != null) {
            val check = executeSuBinaryCommand(current, "id -u")
            if (check.success && check.output.trim() == "0") {
                return@withLock current
            }
            cachedSuBinary = null
        }

        for (bin in SU_EXEC_CANDIDATES) {
            // Do not pre-gate top candidates on File.exists() because SUSFS / KSU mount hiding
            // can hide `su` from stat() while execve() still succeeds cleanly.
            val check = executeSuBinaryCommand(bin, "id -u")
            if (check.success && check.output.trim() == "0") {
                cachedSuBinary = bin
                return@withLock bin
            }
        }
        null
    }

    private data class ExecOutcome(
        val result: CommandResult,
        val ioMissing: Boolean = false
    )

    private fun executeSuBinaryCommand(suBinary: String, cmd: String): CommandResult {
        return executeSuBinaryOutcome(suBinary, cmd).result
    }

    private fun executeSuBinaryOutcome(suBinary: String, cmd: String): ExecOutcome {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf(suBinary, "-c", cmd))
            try {
                process.outputStream.close()
            } catch (_: Throwable) {
            }

            val error = StringBuilder()
            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (error.length < 4096) {
                                error.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                isDaemon = true
                start()
            }

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append('\n')
                }
            }

            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ExecOutcome(
                    CommandResult(
                        success = false,
                        output = output.toString().trim(),
                        error = "Timed out after 8s"
                    )
                )
            }
            try {
                errThread.join(250)
            } catch (_: Throwable) {
            }
            val exitCode = process.exitValue()
            ExecOutcome(
                CommandResult(
                    success = exitCode == 0,
                    output = output.toString().trim(),
                    error = error.toString().trim()
                )
            )
        } catch (e: IOException) {
            process?.destroyForcibly()
            ExecOutcome(
                CommandResult(
                    success = false,
                    output = "",
                    error = e.message ?: "su binary unavailable"
                ),
                ioMissing = true
            )
        } catch (t: Throwable) {
            process?.destroyForcibly()
            ExecOutcome(
                CommandResult(
                    success = false,
                    output = "",
                    error = t.message ?: "Execution failed"
                )
            )
        }
    }

    suspend fun executeSuCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        val resolved = cachedSuBinary ?: ensureSuBinary(forceRefresh = false)
        if (resolved == null) {
            // One direct attempt with "su" in case manager granted just now
            val direct = executeSuBinaryOutcome("su", cmd)
            return@withContext direct.result
        }

        val outcome = executeSuBinaryOutcome(resolved, cmd)
        if (outcome.ioMissing) {
            cachedSuBinary = null
            val reResolved = ensureSuBinary(forceRefresh = true) ?: return@withContext outcome.result
            return@withContext executeSuBinaryOutcome(reResolved, cmd).result
        }
        outcome.result
    }

    suspend fun launchLsposedManager(context: Context) = withContext(Dispatchers.IO) {
        val knownManagers = listOf(
            "org.matrix.vector.manager" to "org.matrix.vector.manager.ui.MainActivity",
            "org.lsposed.manager" to "org.lsposed.manager.ui.MainActivity",
            "io.github.lsposed.manager" to "io.github.lsposed.manager.ui.MainActivity",
            "org.lsposed.lspatch" to "org.lsposed.lspatch.ui.activity.MainActivity"
        )

        for ((pkg, _) in knownManagers) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return@withContext
                } catch (_: Throwable) {
                }
            }
        }

        for ((pkg, activityCls) in knownManagers) {
            try {
                val explicitIntent = Intent().apply {
                    component = ComponentName(pkg, activityCls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(explicitIntent)
                return@withContext
            } catch (_: Throwable) {
            }
        }

        // Standard secret code broadcast for parasitic LSPosed / Vector manager
        try {
            val secretIntent = Intent("android.telephony.action.SECRET_CODE").apply {
                data = Uri.parse("android_secret_code://5776733")
                addFlags(0x00400000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
            }
            context.sendBroadcast(secretIntent)
        } catch (_: Throwable) {
        }

        // Root shell fallback for standalone or parasitic managers (Vector / LSPosed / ReLSPosed)
        executeSuCommand(
            "am start -n org.matrix.vector.manager/.ui.MainActivity 2>/dev/null " +
                "|| am start -n org.lsposed.manager/.ui.MainActivity 2>/dev/null " +
                "|| am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 2>/dev/null"
        )
    }

    suspend fun setSystemProperty(key: String, enabled: Boolean): Boolean {
        return setSystemPropertiesBatch(mapOf(key to if (enabled) "1" else "0"))
    }

    suspend fun setSystemPropertyString(key: String, value: String): Boolean {
        return setSystemPropertiesBatch(mapOf(key to value))
    }

    /**
     * Writes multiple persist.autobrightness.* properties in ONE single `su -c` shell call,
     * eliminating Magisk root toast storms and startup multi-second latency.
     */
    suspend fun setSystemPropertiesBatch(props: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        if (props.isEmpty()) return@withContext true
        val script = props.entries.joinToString("; ") { (rawKey, rawVal) ->
            val safeKey = rawKey.trim()
            val safeVal = rawVal.replace("'", "'\\''")
            "setprop $safeKey '$safeVal'"
        }
        executeSuCommand(script).success
    }

    suspend fun getSystemPropertyString(key: String, defaultValue: String = ""): String {
        val res = executeSuCommand("getprop $key")
        return if (res.success && res.output.isNotBlank()) res.output.trim() else defaultValue
    }

    /**
     * Single-pass fast module log reader supporting:
     * 1. RAM-independent persistent disk log (/data/system/autobrightness_guard.log) written by module
     * 2. Vector / LSPosed active manager logs (modules_*.log) for injection status
     * 3. Live logcat ring buffer (main/system/crash -s AutoBrightnessGuard:V)
     */
    suspend fun readModuleLogs(): List<LogEntry> = withContext(Dispatchers.IO) {
        val singlePassCmd = buildString {
            // 1. Module's own persistent disk ring log (survives ColorOS screen-lock logd eviction)
            append("if [ -r '$MODULE_DISK_LOG_PATH' ]; then tail -n 350 '$MODULE_DISK_LOG_PATH' 2>/dev/null; fi; ")
            // 2. Current-session Vector/LSPosed module logs (excludes stale log.old boots)
            append("for f in \$(ls -t $MODULE_LOG_CANDIDATES 2>/dev/null | head -n 3); do ")
            append("if [ -r \"\$f\" ]; then ")
            append("grep -iE '$MODULE_BOOT_PATTERN|Loaded module com\\.banoku\\.oplus\\.autobrightness' \"\$f\" 2>/dev/null | tail -n 60; ")
            append("grep -i AutoBrightnessGuard \"\$f\" 2>/dev/null | tail -n 120; ")
            append("fi; done; ")
            // 3. Native tag-filtered logcat (main, system, crash)
            append("logcat -d -b main -b system -b crash -s AutoBrightnessGuard:V 2>/dev/null | tail -n 300")
        }
        val result = executeSuCommand(singlePassCmd)
        if (result.output.isNotBlank()) {
            val parsed = parseLogs(result.output)
            if (parsed.isNotEmpty()) {
                return@withContext parsed.reversed()
            }
        }
        emptyList()
    }

    suspend fun readFrameworkDiagnosticHint(): String = withContext(Dispatchers.IO) {
        val diagCmd =
            "(/data/adb/lspd/cli status 2>/dev/null || /data/adb/lsp/cli status 2>/dev/null || /data/adb/vector/cli status 2>/dev/null) | head -n 12; " +
                "logs=\$(ls -1t /data/adb/lspd/log/*.log /data/adb/lsp/log/*.log /data/adb/vector/log/*.log 2>/dev/null | head -n 4); " +
                "if [ -n \"\$logs\" ]; then " +
                "grep -ihE 'autobrightness|AutoBrightnessGuard|BRIDGE_TRANSACTION|system_server.*(fail|error|skip|deny|not)|not injected' " +
                "/data/adb/lspd/log/modules_*.log /data/adb/lspd/log/verbose_*.log /data/adb/lsp/log/*.log /data/adb/vector/log/*.log 2>/dev/null | tail -n 6; " +
                "else echo 'Logs: none found in /data/adb/{lspd,lsp,vector}/log'; fi; " +
                "ps -A 2>/dev/null | grep -E 'vectord|lspd' | head -n 2"
        val res = executeSuCommand(diagCmd)
        if (res.output.isBlank()) return@withContext ""
        res.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(12)
            .joinToString("\n")
    }

    suspend fun readLatestStockCurveFromLogs(): List<Float>? = withContext(Dispatchers.IO) {
        val cmd =
            "grep -Fh 'STOCK_CURVE:' '$MODULE_DISK_LOG_PATH' $MODULE_LOG_CANDIDATES 2>/dev/null | tail -n 2; " +
                "logcat -d -b main -b system -b crash -s AutoBrightnessGuard:V 2>/dev/null | grep -F 'STOCK_CURVE:' | tail -n 1"
        val res = executeSuCommand(cmd)
        if (res.output.isNotBlank()) {
            return@withContext extractLatestStockCurve(parseLogs(res.output).reversed())
        }
        null
    }

    suspend fun readLiveDisplayCalibration(): DisplayCalibration = withContext(Dispatchers.IO) {
        val res = executeSuCommand("dumpsys display")
        if (res.success && res.output.isNotBlank()) {
            parseDisplayCalibration(res.output)
        } else {
            DisplayCalibration()
        }
    }

    fun parseSplinePairs(line: String): List<Pair<Float, Float>> {
        // Supports both 2-part "(x, y)" and ColorOS/Android 17 3-part "(x, y: slope)" tuples
        val pairRegex = Regex(
            "\\(\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*,\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)(?:\\s*:\\s*[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)?\\s*\\)"
        )
        val list = ArrayList<Pair<Float, Float>>()
        for (match in pairRegex.findAll(line)) {
            val x = match.groupValues[1].toFloatOrNull()
            val y = match.groupValues[2].toFloatOrNull()
            if (x != null && y != null && !x.isNaN() && !y.isNaN() && !x.isInfinite() && !y.isInfinite()) {
                list.add(x to y)
            }
        }
        return list.sortedBy { it.first }
    }

    private fun parseFloatArrayBracket(line: String): List<Float> {
        val start = line.indexOf('[')
        val end = line.indexOf(']', start.coerceAtLeast(0))
        if (start < 0 || end <= start) return emptyList()
        return line.substring(start + 1, end)
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { !it.isNaN() && !it.isInfinite() }
    }

    fun interpolateLinearPairs(points: List<Pair<Float, Float>>, x: Float): Float? {
        if (points.isEmpty() || x.isNaN() || x.isInfinite()) return null
        if (points.size == 1) return points[0].second
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (x <= x1) {
                val span = x1 - x0
                if (span <= 1e-6f) return y1
                val t = ((x - x0) / span).coerceIn(0f, 1f)
                return y0 + t * (y1 - y0)
            }
        }
        return points.last().second
    }

    fun parseDisplayCalibration(dumpsysOutput: String): DisplayCalibration {
        if (dumpsysOutput.isBlank()) return DisplayCalibration()

        var backlightToNits: List<Pair<Float, Float>> = emptyList()
        var invertedNitsToBacklight: List<Pair<Float, Float>> = emptyList()
        var activeLuxCurvePairs: List<Pair<Float, Float>> = emptyList()
        var defaultLuxCurvePairs: List<Pair<Float, Float>> = emptyList()
        var normalizedLuxPairs: List<Pair<Float, Float>> = emptyList()

        var pendingConfigNits: List<Float> = emptyList()
        var pendingConfigBacklight: List<Float> = emptyList()

        var liveNit: Float? = null
        var liveBrightnessRaw: Float? = null
        var minNit: Float? = null
        var normalMaxNit: Float? = null
        var maxNit: Float? = null
        var brightnessMin: Float? = null
        var normalBrightnessMax: Float? = null
        var panelBrightnessMax: Float? = null
        var inIdleMode = false

        val liveNitRegex = Regex(
            "(?<![A-Za-z0-9_])(mCurrentScreenBrightnessNits|mCurrentBrightnessNits|currentSdrNits|mCurrentNits|mCurrentNit|currentNits|currentNit|mScreenBrightnessNits|mTargetNit|sdrNits|mNit)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val liveBrightnessRegex = Regex(
            "(?<![A-Za-z0-9_])(mCurrentScreenBrightness|Display Brightness|mScreenBrightness|mLatestFloatBrightness|screenBrightness)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val minNitRegex = Regex(
            "(?<![A-Za-z0-9_])(mMinNits|mMinNit|minNits|mNormalMinNits|mNormalMinNit|desiredMinLuminance|mMinLuminance)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val normalMaxNitRegex = Regex(
            "(?<![A-Za-z0-9_])(mNormalMaxNits|mNormalMaxNit)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val maxNitRegex = Regex(
            "(?<![A-Za-z0-9_])(mHbmMaxNits|mHbmMaxNit|mMaxNits|mMaxNit|maxNits|mBrightnessMaxNit|desiredMaxLuminance|mMaxLuminance)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val bMinRegex = Regex(
            "(?<![A-Za-z0-9_])(mMinBrightness|mScreenBrightnessRangeMinimum|brightnessMin)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val bNormalMaxRegex = Regex(
            "(?<![A-Za-z0-9_])(mMaxBrightness|mScreenBrightnessNormalMaximum|brightnessMax)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val bPanelMaxRegex = Regex(
            "(?<![A-Za-z0-9_])(mMaxPanelBrightness|mScreenBrightnessRangeMaximum|mTotalBrightness)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )

        for (rawLine in dumpsysOutput.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()

            if (lower.contains("misforidlemode=true")) {
                inIdleMode = true
            } else if (lower.contains("misforidlemode=false") || lower.startsWith("displaypowercontroller")) {
                inIdleMode = false
            }

            if (line.contains("Spline{") || line.contains("[(")) {
                val pairs = parseSplinePairs(line)
                if (pairs.size >= 2) {
                    val lastX = pairs.last().first
                    val lastY = pairs.last().second
                    when {
                        !inIdleMode && (lower.contains("backlighttonits") || lower.contains("brightnesstonits")) &&
                            !lower.contains("ambient") && !lower.contains("lux") &&
                            lastX in 0.5f..1.05f && lastY >= 50f -> {
                            if (pairs.size > backlightToNits.size) {
                                backlightToNits = pairs
                            }
                        }
                        !inIdleMode && (lower.contains("nitstobacklight") || lower.contains("nitstobrightness")) &&
                            lastX >= 50f && lastY in 0.5f..1.05f -> {
                            val inverted = pairs.map { (n, b) -> b to n }.sortedBy { it.first }
                            if (inverted.size > invertedNitsToBacklight.size) {
                                invertedNitsToBacklight = inverted
                            }
                        }
                        !inIdleMode && lastX >= 500f && lastY >= 20f &&
                            !lower.contains("nitstobacklight") && !lower.contains("nitstobrightness") &&
                            !lower.contains("minimumbrightnesscurve") -> {
                            when {
                                lower.contains("mdefaultspline") || lower.contains("defaultspline") -> {
                                    if (defaultLuxCurvePairs.isEmpty()) defaultLuxCurvePairs = pairs
                                }
                                lower.contains("mspline") || lower.contains("splinecurve") ||
                                    lower.contains("luxtonits") || lower.contains("autobrightness") ||
                                    lower.contains("curve") -> {
                                    if (activeLuxCurvePairs.isEmpty() || pairs.size > activeLuxCurvePairs.size) {
                                        activeLuxCurvePairs = pairs
                                    }
                                }
                            }
                        }
                        !inIdleMode && lastX >= 500f && lastY in 0.05f..1.05f &&
                            (lower.contains("mspline") || lower.contains("mdefaultspline")) -> {
                            if (normalizedLuxPairs.isEmpty()) normalizedLuxPairs = pairs
                        }
                    }
                }
            }

            if (pendingConfigNits.isEmpty() && (lower.startsWith("mnits=[") || lower.startsWith("nits=["))) {
                val parsed = parseFloatArrayBracket(line)
                if (parsed.size >= 2 && (parsed.lastOrNull() ?: 0f) >= 50f) {
                    pendingConfigNits = parsed
                }
            } else if (pendingConfigBacklight.isEmpty() &&
                (lower.startsWith("mbacklight=[") || lower.startsWith("mbrightness=[") || lower.startsWith("backlight=["))
            ) {
                val parsed = parseFloatArrayBracket(line)
                if (parsed.size >= 2 && (parsed.lastOrNull() ?: 0f) in 0.5f..1.05f) {
                    pendingConfigBacklight = parsed
                }
            }

            if (lower.contains("mnitsrange=[") || lower.contains("nitsrange=[")) {
                val range = parseFloatArrayBracket(line)
                if (range.size == 2) {
                    if (minNit == null && range[0] in 0.1f..50f) minNit = range[0]
                    if (range[1] in 200f..6000f) maxNit = maxOf(maxNit ?: 0f, range[1])
                }
            }

            val nitMatch = liveNitRegex.find(line)
            if (nitMatch != null) {
                val v = nitMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 0f..6000f && (liveNit == null || (liveNit == 0f && v > 0f))) {
                    liveNit = v
                }
            }

            val blMatch = liveBrightnessRegex.find(line)
            if (blMatch != null && liveBrightnessRaw == null) {
                val v = blMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 0f..20000f) {
                    liveBrightnessRaw = v
                }
            }

            val minMatch = minNitRegex.find(line)
            if (minMatch != null && minNit == null) {
                val v = minMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 0.1f..50f) {
                    minNit = v
                }
            }

            val normalMaxMatch = normalMaxNitRegex.find(line)
            if (normalMaxMatch != null) {
                val v = normalMaxMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 150f..6000f) {
                    normalMaxNit = maxOf(normalMaxNit ?: 0f, v)
                }
            }

            val maxMatch = maxNitRegex.find(line)
            if (maxMatch != null) {
                val v = maxMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 200f..6000f) {
                    maxNit = maxOf(maxNit ?: 0f, v)
                }
            }

            val bMinMatch = bMinRegex.find(line)
            if (bMinMatch != null && brightnessMin == null) {
                val v = bMinMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 0f..100f) {
                    brightnessMin = v
                }
            }

            val bNormMaxMatch = bNormalMaxRegex.find(line)
            if (bNormMaxMatch != null) {
                val v = bNormMaxMatch.groupValues[2].toFloatOrNull()
                if (v != null && v > 10f) {
                    normalBrightnessMax = maxOf(normalBrightnessMax ?: 0f, v)
                }
            }

            val bPanelMaxMatch = bPanelMaxRegex.find(line)
            if (bPanelMaxMatch != null) {
                val v = bPanelMaxMatch.groupValues[2].toFloatOrNull()
                if (v != null && v > 10f) {
                    panelBrightnessMax = maxOf(panelBrightnessMax ?: 0f, v)
                }
            }
        }

        val resolvedBacklightSpline = when {
            backlightToNits.isNotEmpty() -> backlightToNits
            invertedNitsToBacklight.isNotEmpty() -> invertedNitsToBacklight
            pendingConfigNits.isNotEmpty() && pendingConfigNits.size == pendingConfigBacklight.size ->
                pendingConfigBacklight.zip(pendingConfigNits).sortedBy { it.first }
            else -> emptyList()
        }

        val chosenLuxCurvePairs = when {
            activeLuxCurvePairs.isNotEmpty() -> activeLuxCurvePairs
            defaultLuxCurvePairs.isNotEmpty() -> defaultLuxCurvePairs
            normalizedLuxPairs.isNotEmpty() && resolvedBacklightSpline.isNotEmpty() ->
                normalizedLuxPairs.mapNotNull { (lx, bl) ->
                    val n = interpolateLinearPairs(resolvedBacklightSpline, bl) ?: return@mapNotNull null
                    lx to n
                }
            else -> emptyList()
        }

        val curveMinNit = chosenLuxCurvePairs.firstOrNull()?.second?.takeIf { it in 0.5f..50f }
        val curvePeakNit = chosenLuxCurvePairs.lastOrNull()?.second?.takeIf { it >= 200f }

        val effectiveMinNit = resolvedBacklightSpline.firstOrNull()?.second?.takeIf { it in 0.1f..50f }
            ?: curveMinNit
            ?: minNit
        val effectiveMaxNit = listOfNotNull(
            resolvedBacklightSpline.lastOrNull()?.second?.takeIf { it >= 100f },
            curvePeakNit,
            maxNit,
            normalMaxNit
        ).maxOrNull()

        if (liveNit == null && liveBrightnessRaw != null && liveBrightnessRaw in 0f..1f && resolvedBacklightSpline.isNotEmpty()) {
            liveNit = interpolateLinearPairs(resolvedBacklightSpline, liveBrightnessRaw)
        }

        val romCurveNits = if (chosenLuxCurvePairs.size >= 2) {
            val sampled = FloatArray(com.banoku.oplus.autobrightness.BrightnessMath.CURVE_POINT_COUNT) { idx ->
                val anchorLux = com.banoku.oplus.autobrightness.BrightnessMath.CURVE_ANCHOR_LUX[idx]
                interpolateLinearPairs(chosenLuxCurvePairs, anchorLux)
                    ?: com.banoku.oplus.autobrightness.BrightnessMath.DEFAULT_CURVE_NITS[idx]
            }
            com.banoku.oplus.autobrightness.BrightnessMath.enforceMonotonic(sampled).toList()
        } else {
            null
        }

        return DisplayCalibration(
            liveNit = liveNit,
            liveBrightnessFloat = liveBrightnessRaw,
            minNit = effectiveMinNit,
            normalMaxNit = normalMaxNit,
            maxNit = effectiveMaxNit,
            brightnessMin = brightnessMin,
            normalBrightnessMax = normalBrightnessMax,
            panelBrightnessMax = panelBrightnessMax,
            backlightToNits = resolvedBacklightSpline,
            romCurveNits = romCurveNits
        )
    }

    /**
     * Resolves display brightness fraction in [0..1] of the normal slider range (and optional >1 HBM boost)
     * across both AOSP normalized [0..1] floats and ColorOS raw [6..4095..4674] floats.
     */
    fun resolveBrightnessFraction(
        brightnessFloat: Float,
        brightnessInt: Int,
        calibration: DisplayCalibration?
    ): Float {
        if (brightnessFloat in 0f..1f) {
            return brightnessFloat
        }
        if (brightnessFloat > 1.0f) {
            val bMin = calibration?.brightnessMin ?: 0f
            val bNormMax = calibration?.normalBrightnessMax
                ?: DeviceCompatibility.getMultibrightnessMax(brightnessFloat.toInt()).toFloat()
            val span = (bNormMax - bMin).coerceAtLeast(1f)
            return ((brightnessFloat - bMin) / span).coerceIn(0f, 1.5f)
        }
        if (brightnessInt >= 0) {
            val bMin = calibration?.brightnessMin ?: 0f
            val scale = if (brightnessInt > 255) {
                (calibration?.normalBrightnessMax ?: DeviceCompatibility.getMultibrightnessMax(brightnessInt).toFloat())
            } else {
                255f
            }
            val span = (scale - bMin).coerceAtLeast(1f)
            return ((brightnessInt - bMin) / span).coerceIn(0f, 1.5f)
        }
        val calRaw = calibration?.liveBrightnessFloat
        if (calRaw != null) {
            if (calRaw in 0f..1f) return calRaw
            if (calRaw > 1.0f) {
                val bMin = calibration.brightnessMin ?: 0f
                val bNormMax = calibration.normalBrightnessMax
                    ?: DeviceCompatibility.getMultibrightnessMax(calRaw.toInt()).toFloat()
                val span = (bNormMax - bMin).coerceAtLeast(1f)
                return ((calRaw - bMin) / span).coerceIn(0f, 1.5f)
            }
        }
        return -1f
    }

    /**
     * Resolves physical screen brightness in nits and whether it came from real system/panel calibration.
     */
    fun resolveScreenNits(
        fraction: Float,
        calibration: DisplayCalibration?,
        fallbackMinNit: Float,
        fallbackMaxNit: Float,
        rawBrightness: Float = -1f
    ): Pair<Float?, Boolean> {
        if (fraction < 0f || fraction.isNaN() || fraction.isInfinite()) {
            val live = calibration?.liveNit
            return if (live != null && live >= 0f) live to true else null to false
        }
        if (fraction <= 0f) {
            val exact = calibration != null && calibration.hasRealCalibration
            return 0f to exact
        }
        if (calibration != null && calibration.backlightToNits.isNotEmpty() && fraction <= 1.0f) {
            val mapped = interpolateLinearPairs(calibration.backlightToNits, fraction)
            if (mapped != null && mapped >= 0f) {
                return mapped to true
            }
        }
        val live = calibration?.liveNit
        val calBl = calibration?.liveBrightnessFloat
        if (live != null && live >= 0f && (calBl == null || abs(calBl - fraction) <= 0.025f || (rawBrightness > 1f && abs(calBl - rawBrightness) <= 10f))) {
            return live to true
        }
        val effectiveMin = calibration?.minNit ?: fallbackMinNit
        val normalPeak = calibration?.normalMaxNit
        val panelPeak = calibration?.maxNit ?: fallbackMaxNit
        val hasBounds = calibration?.hasRealCalibration == true

        // If ColorOS reports raw brightness above mMaxBrightness (e.g. > 4095 up to 4674 in HBM),
        // interpolate cleanly from normalMaxNit (~798) up to panelPeak (1800..2000).
        if (normalPeak != null && panelPeak > normalPeak + 20f) {
            val bNormMax = calibration?.normalBrightnessMax ?: 4095f
            val bPanelMax = calibration?.panelBrightnessMax?.takeIf { it > bNormMax } ?: bNormMax
            if (rawBrightness > bNormMax && bPanelMax > bNormMax) {
                val hbmT = ((rawBrightness - bNormMax) / (bPanelMax - bNormMax)).coerceIn(0f, 1f)
                return (normalPeak + hbmT * (panelPeak - normalPeak)) to hasBounds
            }
            return com.banoku.oplus.autobrightness.BrightnessMath.estimateScreenNits(
                fraction.coerceIn(0f, 1f),
                effectiveMin,
                normalPeak
            ) to hasBounds
        }

        return com.banoku.oplus.autobrightness.BrightnessMath.estimateScreenNits(
            fraction.coerceIn(0f, 1f),
            effectiveMin,
            panelPeak
        ) to hasBounds
    }

    fun getSampleSimulationLogs(): List<LogEntry> {
        return listOf(
            LogEntry(
                type = LogType.INFO,
                time = "20:00:10",
                message = "v${BuildConfig.VERSION_NAME} (Vector/LSPosed Verified Engine) starting, sdk=${Build.VERSION.SDK_INT}, build=${Build.FINGERPRINT}"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [1/7] controller resetAutoBrightness installed (variants=2, lambda=true)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [2/7] ambient resetAmbientAdapter(String) installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [3/7] stability anti-drift installed (targets=2)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [4a/7] OplusFeatureDarkEnvReduceBrightness.shouldSkipProcessing installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [4b/7] OplusBrightnessPostProcessingManager.convertNitToBrightness floor (8.5 nits) installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [5/7] LuxEvent\$LuxEventResult.calculateAuxLux balancer installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [6/7] OplusDisplayBrightnessModel.getValueByCurve(String, float) live curve installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [7/7] SystemSensorManager\$SensorEventQueue high_pwm_rgb guard installed"
            ),
            LogEntry(
                type = LogType.INFO,
                time = "20:00:11",
                message = "HOOK_SUMMARY:installed=7/7,version=${BuildConfig.VERSION_NAME},sdk=${Build.VERSION.SDK_INT},hooks=1:OK,2:OK,3:OK,4:OK,5:OK,6:OK,7:OK"
            ),
            LogEntry(
                type = LogType.INFO,
                time = "20:00:15",
                message = "STOCK_CURVE:8.5,14,28,70,155,320,600"
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
                message = "GUARD dual-sensor smoothed auxLux (22.0, 48.0) -> 31.1"
            ),
            LogEntry(
                type = LogType.INFO,
                time = "20:15:04",
                message = "GUARD_STATS:reset=1,ambient=1,drift=4,floor=2,darkenv=1,aux=3,curve=6,pwm=1,last_block_ms=0,hooks=7/7,hook_mask=1111111,ver=${BuildConfig.VERSION_NAME}"
            ),
            LogEntry(
                type = LogType.PASS,
                time = "23:14:01",
                message = "passed resetAutoBrightness #1 from OplusDisplayPowerControllerExtImpl.handleBrightnessModeChange"
            )
        ).reversed()
    }

    fun computeStats(logs: List<LogEntry>): GuardStats {
        if (logs.isEmpty()) return GuardStats(0, "", "")

        val chrono = logs.asReversed()
        val startIdx = chrono.indexOfLast {
            it.message.contains("starting, sdk=", ignoreCase = true)
        }
        val session = if (startIdx >= 0) chrono.subList(startIdx, chrono.size) else chrono

        val blockedLogs = logs.filter { it.type == LogType.BLOCKED }
        val latestBlocked = blockedLogs.firstOrNull()

        var logReset = 0
        var logAmbient = 0
        var logDrift = 0
        var logFloorDark = 0
        var logCurve = 0
        var logStabilitySensors = 0

        for (entry in session) {
            val msg = entry.message
            when {
                msg.contains("BLOCKED resetAutoBrightness") -> logReset++
                msg.contains("BLOCKED resetAmbientAdapter") -> logAmbient++
                msg.contains("anti-drift suppressed") -> logDrift++
                msg.contains("DarkEnv") || msg.contains("floor clamped") -> logFloorDark++
                msg.contains("CURVE override") -> logCurve++
                msg.contains("dual-sensor smoothed") ||
                    msg.contains("GUARD PWM sensor") -> logStabilitySensors++
            }
        }

        // Parse GUARD_STATS: snapshots emitted by AutoBrightnessModule (immune to buffer tail eviction)
        var bestSnapshot: Map<String, Long>? = null
        var bestSum = -1L
        for (entry in session) {
            val idx = entry.message.indexOf("GUARD_STATS:")
            if (idx >= 0) {
                val payload = entry.message.substring(idx + "GUARD_STATS:".length).trim()
                val map = HashMap<String, Long>(12)
                payload.split(',').forEach { token ->
                    val eq = token.indexOf('=')
                    if (eq > 0) {
                        val k = token.substring(0, eq).trim()
                        val v = token.substring(eq + 1).trim().toLongOrNull()
                        if (v != null && v >= 0L) map[k] = v
                    }
                }
                val sum = listOf(
                    "reset", "ambient", "drift", "floor",
                    "darkenv", "aux", "curve", "pwm"
                ).sumOf { map[it] ?: 0L }
                if (sum >= bestSum) {
                    bestSum = sum
                    bestSnapshot = map
                }
            }
        }

        val statsReset = (bestSnapshot?.get("reset") ?: 0L).toInt()
        val statsAmbient = (bestSnapshot?.get("ambient") ?: 0L).toInt()
        val statsDrift = (bestSnapshot?.get("drift") ?: 0L).toInt()
        val statsFloor = (bestSnapshot?.get("floor") ?: 0L).toInt()
        val statsDarkEnv = (bestSnapshot?.get("darkenv") ?: 0L).toInt()
        val statsAux = (bestSnapshot?.get("aux") ?: 0L).toInt()
        val statsCurve = (bestSnapshot?.get("curve") ?: 0L).toInt()
        val statsPwm = (bestSnapshot?.get("pwm") ?: 0L).toInt()
        val lastBlockMs = bestSnapshot?.get("last_block_ms") ?: 0L

        val resetCount = maxOf(logReset, statsReset)
        val ambientCount = maxOf(logAmbient, statsAmbient)
        val totalBlocked = maxOf(blockedLogs.size, resetCount + ambientCount)

        val antiDriftCount = maxOf(logDrift, statsDrift)
        val floorDarkCount = maxOf(logFloorDark, statsFloor + statsDarkEnv)
        val curveCount = maxOf(logCurve, statsCurve)
        val stabilitySensorsCount = maxOf(logStabilitySensors, statsAux + statsPwm)
        val totalGuardInterventions = antiDriftCount + floorDarkCount + curveCount + stabilitySensorsCount

        val resolvedLastTime = latestBlocked?.time?.takeIf { it.isNotBlank() }
            ?: if (lastBlockMs > 0L) {
                try {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastBlockMs))
                } catch (_: Throwable) {
                    ""
                }
            } else {
                ""
            }

        return GuardStats(
            totalBlocked = totalBlocked,
            lastBlockedTime = resolvedLastTime,
            lastBlockedMessage = latestBlocked?.message ?: "",
            resetBlockedCount = resetCount,
            ambientBlockedCount = ambientCount,
            totalGuardInterventions = totalGuardInterventions,
            antiDriftCount = antiDriftCount,
            floorAndDarkEnvCount = floorDarkCount,
            customCurveCount = curveCount,
            stabilityAndSensorsCount = stabilitySensorsCount,
            hasExactModuleStats = bestSnapshot != null
        )
    }

    fun extractLatestStockCurve(logs: List<LogEntry>): List<Float>? {
        for (entry in logs) {
            val idx = entry.message.indexOf("STOCK_CURVE:")
            if (idx >= 0) {
                val payload = entry.message.substring(idx + "STOCK_CURVE:".length)
                    .trim()
                    .split(" ", "\t", "\n")
                    .firstOrNull()
                    .orEmpty()
                val parsed = com.banoku.oplus.autobrightness.BrightnessMath.parseNitArray(payload)
                if (parsed != null) {
                    return parsed.toList()
                }
            }
        }
        return null
    }

    fun parseModuleRuntimeStatus(logs: List<LogEntry>, expectedVersion: String): ModuleRuntimeStatus {
        val hookTitles = listOf(
            1 to "Reset Guard (resetAutoBrightness)",
            2 to "Ambient Adapter (resetAmbientAdapter)",
            3 to "Anti-Drift (StableForce / Recover)",
            4 to "Night Floor & DarkEnv (8.5 nit)",
            5 to "360° Aux Smoother (calculateAuxLux)",
            6 to "Live Curve (getValueByCurve)",
            7 to "Under-Display PWM (high_pwm_rgb)"
        )

        if (logs.isEmpty()) {
            return ModuleRuntimeStatus(
                moduleDetected = false,
                loadedVersion = "",
                isVersionMismatch = false,
                lastBootTime = "",
                installedCount = 0,
                totalCount = hookTitles.size,
                hooks = hookTitles.map { (id, title) ->
                    HookStatusItem(id, title, HookState.UNKNOWN, "")
                }
            )
        }

        // Convert to chronological order and focus on the most recent system_server session
        val chrono = logs.asReversed()
        val startIdx = chrono.indexOfLast {
            it.message.contains("starting, sdk=", ignoreCase = true)
        }.takeIf { it >= 0 } ?: chrono.indexOfLast {
            it.message.contains("onModuleLoaded", ignoreCase = true)
        }
        val session = if (startIdx >= 0) chrono.subList(startIdx, chrono.size) else chrono

        val startEntry = if (startIdx >= 0) chrono[startIdx] else null
        var loadedVersion = startEntry?.let { entry ->
            Regex("\\bv([0-9]+\\.[0-9]+(?:\\.[0-9]+)?[a-zA-Z]*)").find(entry.message)?.groupValues?.get(1)
        }.orEmpty()
        val bootTime = startEntry?.time.orEmpty()

        val stateMap = mutableMapOf<Int, HookState>()
        val detailMap = mutableMapOf<Int, String>()
        val hitMap = mutableMapOf<Int, Int>()

        // Optional compact GUARD_STATS embedded hook_mask & ver recovery (immune to boot log eviction)
        val latestGuardStats = session.lastOrNull { it.message.contains("GUARD_STATS:") }?.message
        if (latestGuardStats != null) {
            if (loadedVersion.isEmpty()) {
                loadedVersion = Regex("ver=([^,\\s]+)").find(latestGuardStats)?.groupValues?.get(1).orEmpty()
            }
            val mask = Regex("hook_mask=([01]{7})\\b").find(latestGuardStats)?.groupValues?.get(1)
            if (mask != null && mask.length == hookTitles.size) {
                for (i in mask.indices) {
                    stateMap[i + 1] = if (mask[i] == '1') HookState.INSTALLED else HookState.SKIPPED
                }
            }
        }

        // Optional compact HOOK_SUMMARY parser (overrides mask if present with latest boot state)
        val summaryLine = session.lastOrNull { it.message.contains("HOOK_SUMMARY:") }?.message
        if (summaryLine != null) {
            if (loadedVersion.isEmpty()) {
                loadedVersion = Regex("version=([^,\\s]+)").find(summaryLine)?.groupValues?.get(1).orEmpty()
            }
            val hooksPart = Regex("hooks=([^\\s]+)").find(summaryLine)?.groupValues?.get(1).orEmpty()
            val tokens = hooksPart.split(',')
            if (tokens.size == hookTitles.size) {
                tokens.forEach { token ->
                    val parts = token.split(':')
                    if (parts.size == 2) {
                        val idx = parts[0].toIntOrNull()
                        if (idx != null && idx in 1..hookTitles.size) {
                            stateMap[idx] = if (parts[1] == "OK") HookState.INSTALLED else HookState.SKIPPED
                        }
                    }
                }
            }
        }

        var has4aOk = false
        var has4bOk = false
        var has4aSkip = false
        var has4bSkip = false

        for (entry in session) {
            val msg = entry.message
            // Hook installation lines
            val hookMatch = Regex("Hook \\[(\\d+)([ab])?/7\\]\\s*(.*)").find(msg)
            if (hookMatch != null) {
                val id = hookMatch.groupValues[1].toIntOrNull() ?: continue
                val sub = hookMatch.groupValues[2]
                val body = hookMatch.groupValues[3]
                val isInstalled = body.contains("installed", ignoreCase = true)
                val isSkipped = body.contains("skipped", ignoreCase = true)

                if (id == 4 && sub.isNotEmpty()) {
                    if (sub == "a") {
                        if (isInstalled) has4aOk = true else if (isSkipped) has4aSkip = true
                    } else if (sub == "b") {
                        if (isInstalled) has4bOk = true else if (isSkipped) has4bSkip = true
                    }
                    stateMap[4] = when {
                        has4aOk && has4bOk -> HookState.INSTALLED
                        has4aSkip || has4bSkip -> HookState.SKIPPED
                        has4aOk || has4bOk -> HookState.INSTALLED
                        else -> HookState.UNKNOWN
                    }
                } else if (id in 1..hookTitles.size) {
                    if (isInstalled) stateMap[id] = HookState.INSTALLED
                    else if (isSkipped) stateMap[id] = HookState.SKIPPED
                }
                if (id in 1..hookTitles.size) {
                    detailMap[id] = body
                }
            }

            // Runtime hit counters
            when {
                msg.contains("BLOCKED resetAutoBrightness") -> hitMap[1] = (hitMap[1] ?: 0) + 1
                msg.contains("BLOCKED resetAmbientAdapter") -> hitMap[2] = (hitMap[2] ?: 0) + 1
                msg.contains("anti-drift suppressed") -> hitMap[3] = (hitMap[3] ?: 0) + 1
                msg.contains("DarkEnv") || msg.contains("floor clamped") -> hitMap[4] = (hitMap[4] ?: 0) + 1
                msg.contains("dual-sensor smoothed") -> hitMap[5] = (hitMap[5] ?: 0) + 1
                msg.contains("CURVE override") -> hitMap[6] = (hitMap[6] ?: 0) + 1
                msg.contains("GUARD PWM sensor") -> hitMap[7] = (hitMap[7] ?: 0) + 1
            }
        }

        val items = hookTitles.map { (id, title) ->
            val state = stateMap[id] ?: HookState.UNKNOWN
            val baseDetail = detailMap[id].orEmpty()
            val hits = hitMap[id] ?: 0
            HookStatusItem(id = id, name = title, state = state, details = baseDetail, runtimeHits = hits)
        }

        val hasLoadedInModuleManager = session.any {
            it.message.contains("Loaded module com.banoku.oplus.autobrightness", ignoreCase = true)
        }
        val detected = startEntry != null || stateMap.isNotEmpty() || hasLoadedInModuleManager || latestGuardStats != null
        val installedCount = items.count { it.state == HookState.INSTALLED }
        val mismatch = loadedVersion.isNotEmpty() && !loadedVersion.equals(expectedVersion, ignoreCase = true)

        return ModuleRuntimeStatus(
            moduleDetected = detected,
            loadedVersion = loadedVersion,
            isVersionMismatch = mismatch,
            lastBootTime = bootTime,
            installedCount = installedCount,
            totalCount = hookTitles.size,
            hooks = items
        )
    }

    suspend fun softRebootFramework(): CommandResult {
        return executeSuCommand("killall system_server")
    }

    private data class ParsedSortableLine(
        val sortKey: String,
        val originalIndex: Int,
        val entry: LogEntry
    )

    fun parseLogs(raw: String): List<LogEntry> {
        val parsed = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("--------- beginning of ") }
            .distinct()
            .mapIndexed { idx, line ->
                val cleaned = cleanLogMessage(line)
                val type = when {
                    cleaned.contains("BLOCKED", ignoreCase = true) -> LogType.BLOCKED
                    cleaned.contains("hook installed", ignoreCase = true) || cleaned.contains("Hook [", ignoreCase = true) -> LogType.HOOK
                    cleaned.contains("passed reset", ignoreCase = true) -> LogType.PASS
                    cleaned.contains("failed", ignoreCase = true) || cleaned.contains("error", ignoreCase = true) -> LogType.ERROR
                    else -> LogType.INFO
                }
                val entry = LogEntry(
                    type = type,
                    time = extractTime(line),
                    message = cleaned
                )
                ParsedSortableLine(
                    sortKey = extractSortTimestampKey(line),
                    originalIndex = idx,
                    entry = entry
                )
            }
            .distinctBy { item ->
                if (item.entry.time.isNotEmpty()) item.entry.time to item.entry.message else item.entry.message
            }
            .toList()

        // Sort chronologically when dated timestamps are present so disk log + Vector log + logcat interleave cleanly
        val hasDatedEntries = parsed.all { it.sortKey.isNotEmpty() }
        return if (hasDatedEntries && parsed.size > 1) {
            parsed.sortedWith(compareBy<ParsedSortableLine> { it.sortKey }.thenBy { it.originalIndex })
                .map { it.entry }
        } else {
            parsed.map { it.entry }
        }
    }

    fun serializeLogsForCache(logs: List<LogEntry>, maxLines: Int = 250): String {
        return logs.take(maxLines).joinToString("\n") { entry ->
            val safeMsg = entry.message.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            "${entry.type.name}\t${entry.time}\t$safeMsg"
        }
    }

    fun deserializeLogsFromCache(raw: String): List<LogEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val p1 = line.indexOf('\t')
                val p2 = if (p1 >= 0) line.indexOf('\t', p1 + 1) else -1
                if (p1 <= 0 || p2 <= p1) return@mapNotNull null
                val type = try {
                    LogType.valueOf(line.substring(0, p1))
                } catch (_: Throwable) {
                    LogType.INFO
                }
                val time = line.substring(p1 + 1, p2)
                val msg = line.substring(p2 + 1)
                if (msg.isBlank()) null else LogEntry(type = type, time = time, message = msg)
            }
            .toList()
    }

    fun readCurrentBootId(): String {
        return try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractSortTimestampKey(line: String): String {
        // Matches: 2026-11-16T12:53:15.111 -> 11-16 12:53:15.111
        val isoMatch = Regex("\\d{4}-(\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)").find(line)
        if (isoMatch != null) {
            val timePart = isoMatch.groupValues[2].let { if (it.length == 8) "$it.000" else it }
            return "${isoMatch.groupValues[1]} $timePart"
        }
        // Matches: 11-16 12:53:15.111
        val logcatMatch = Regex("\\b(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)").find(line)
        if (logcatMatch != null) {
            val timePart = logcatMatch.groupValues[2].let { if (it.length == 8) "$it.000" else it }
            return "${logcatMatch.groupValues[1]} $timePart"
        }
        return ""
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

    private fun stripPackagePrefix(msg: String): String {
        val trimmed = msg.trim()
        val pkgPrefix = "com.banoku.oplus.autobrightness:"
        return if (trimmed.startsWith(pkgPrefix)) {
            trimmed.substring(pkgPrefix.length).trim()
        } else {
            trimmed
        }
    }

    fun cleanLogMessage(line: String): String {
        val guardIdx = line.indexOf("AutoBrightnessGuard")
        if (guardIdx != -1) {
            // 1. LSPosed / Vector prefix: `[...,AutoBrightnessGuard,...] <message>`
            // Only treat `]` as prefix terminator if no new `[` starts before `]` (avoids chopping `Hook [1/10]`).
            val afterBracket = line.indexOf(']', guardIdx)
            val nextOpen = line.indexOf('[', guardIdx)
            if (afterBracket != -1 && (nextOpen == -1 || nextOpen > afterBracket) && afterBracket + 1 < line.length) {
                return stripPackagePrefix(line.substring(afterBracket + 1))
            }
            // 2. Logcat prefix: `11-14 20:08:47.276 1000 28821 28821 W AutoBrightnessGuard: <message>`
            val afterColon = line.indexOf(':', guardIdx + "AutoBrightnessGuard".length)
            if (afterColon != -1 && afterColon + 1 < line.length) {
                return stripPackagePrefix(line.substring(afterColon + 1))
            }
        }
        // 3. VectorModuleManager / VectorLegacyBridge bracketed lines
        val vecIdx = line.indexOf("VectorModuleManager")
        if (vecIdx != -1) {
            val afterBracket = line.indexOf(']', vecIdx)
            if (afterBracket != -1 && afterBracket + 1 < line.length) {
                return line.substring(afterBracket + 1).trim()
            }
        }
        return stripPackagePrefix(line)
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
    val lastBlockedMessage: String,
    val resetBlockedCount: Int = 0,
    val ambientBlockedCount: Int = 0,
    val totalGuardInterventions: Int = 0,
    val antiDriftCount: Int = 0,
    val floorAndDarkEnvCount: Int = 0,
    val customCurveCount: Int = 0,
    val stabilityAndSensorsCount: Int = 0,
    val hasExactModuleStats: Boolean = false
)

enum class LogType {
    INFO,
    HOOK,
    BLOCKED,
    PASS,
    ERROR
}

enum class HookState {
    INSTALLED,
    SKIPPED,
    UNKNOWN
}

data class HookStatusItem(
    val id: Int,
    val name: String,
    val state: HookState,
    val details: String,
    val runtimeHits: Int = 0
)

data class ModuleRuntimeStatus(
    val moduleDetected: Boolean,
    val loadedVersion: String,
    val isVersionMismatch: Boolean,
    val lastBootTime: String,
    val installedCount: Int,
    val totalCount: Int,
    val hooks: List<HookStatusItem>
)

data class DisplayCalibration(
    val liveNit: Float? = null,
    val liveBrightnessFloat: Float? = null,
    val minNit: Float? = null,
    val normalMaxNit: Float? = null,
    val maxNit: Float? = null,
    val brightnessMin: Float? = null,
    val normalBrightnessMax: Float? = null,
    val panelBrightnessMax: Float? = null,
    val backlightToNits: List<Pair<Float, Float>> = emptyList(),
    val romCurveNits: List<Float>? = null
) {
    val hasRealCalibration: Boolean
        get() = backlightToNits.isNotEmpty() || liveNit != null || normalMaxNit != null || maxNit != null
}
