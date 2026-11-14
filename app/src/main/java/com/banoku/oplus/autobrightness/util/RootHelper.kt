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
import kotlin.math.abs

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

    suspend fun readLatestStockCurveFromLogs(): List<Float>? = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "grep -F 'STOCK_CURVE:' \$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1) 2>/dev/null | tail -n 1",
            "grep -F 'STOCK_CURVE:' /data/adb/lspd/log/modules*.log 2>/dev/null | tail -n 1",
            "logcat -d -b all -s AutoBrightnessGuard:V 2>/dev/null | grep -F 'STOCK_CURVE:' | tail -n 1"
        )
        for (cmd in cmds) {
            val res = executeSuCommand(cmd)
            if (res.success && res.output.isNotBlank()) {
                val parsed = extractLatestStockCurve(parseLogs(res.output))
                if (parsed != null) return@withContext parsed
            }
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
        val pairRegex = Regex("\\(\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*,\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*\\)")
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
        var liveBrightnessFloat: Float? = null
        var minNit: Float? = null
        var maxNit: Float? = null
        var inIdleMode = false

        val liveNitRegex = Regex(
            "(?<![A-Za-z0-9_])(mCurrentScreenBrightnessNits|mCurrentBrightnessNits|currentSdrNits|mCurrentNits|mCurrentNit|currentNits|currentNit|mScreenBrightnessNits|mTargetNit|sdrNits|mNit)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val liveBrightnessRegex = Regex(
            "(?<![A-Za-z0-9_])(mCurrentScreenBrightness|mScreenBrightness|screenBrightness)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val minNitRegex = Regex(
            "(?<![A-Za-z0-9_])(mMinNits|minNits|mNormalMinNits|desiredMinLuminance)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val maxNitRegex = Regex(
            "(?<![A-Za-z0-9_])(mHbmMaxNits|mMaxNits|maxNits|mNormalMaxNits|mBrightnessMaxNit|desiredMaxLuminance)\\s*[=:]\\s*([0-9]+(?:\\.[0-9]+)?)",
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
                            !lower.contains("nitstobacklight") && !lower.contains("nitstobrightness") -> {
                            when {
                                lower.contains("mdefaultspline") || lower.contains("defaultspline") -> {
                                    if (defaultLuxCurvePairs.isEmpty()) defaultLuxCurvePairs = pairs
                                }
                                lower.contains("mspline") || lower.contains("luxtonits") ||
                                    lower.contains("autobrightness") || lower.contains("curve") -> {
                                    if (activeLuxCurvePairs.isEmpty()) activeLuxCurvePairs = pairs
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
                    if (minNit == null && range[0] in 0.5f..50f) minNit = range[0]
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
            if (blMatch != null && liveBrightnessFloat == null) {
                val v = blMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 0f..1f) {
                    liveBrightnessFloat = v
                }
            }

            val minMatch = minNitRegex.find(line)
            if (minMatch != null && minNit == null) {
                val v = minMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 0.5f..50f) {
                    minNit = v
                }
            }

            val maxMatch = maxNitRegex.find(line)
            if (maxMatch != null) {
                val v = maxMatch.groupValues[2].toFloatOrNull()
                if (v != null && v in 200f..6000f) {
                    maxNit = maxOf(maxNit ?: 0f, v)
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

        val effectiveMinNit = resolvedBacklightSpline.firstOrNull()?.second?.takeIf { it in 0.1f..50f } ?: minNit
        val effectiveMaxNit = resolvedBacklightSpline.lastOrNull()?.second?.takeIf { it >= 100f } ?: maxNit

        if (liveNit == null && liveBrightnessFloat != null && resolvedBacklightSpline.isNotEmpty()) {
            liveNit = interpolateLinearPairs(resolvedBacklightSpline, liveBrightnessFloat)
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
            liveBrightnessFloat = liveBrightnessFloat,
            minNit = effectiveMinNit,
            maxNit = effectiveMaxNit,
            backlightToNits = resolvedBacklightSpline,
            romCurveNits = romCurveNits
        )
    }

    /**
     * Resolves physical screen brightness in nits and whether it came from real system/panel calibration.
     */
    fun resolveScreenNits(
        fraction: Float,
        calibration: DisplayCalibration?,
        fallbackMinNit: Float,
        fallbackMaxNit: Float
    ): Pair<Float?, Boolean> {
        if (fraction < 0f || fraction.isNaN() || fraction.isInfinite()) {
            val live = calibration?.liveNit
            return if (live != null && live >= 0f) live to true else null to false
        }
        if (fraction <= 0f) {
            val exact = calibration != null && (calibration.backlightToNits.isNotEmpty() || calibration.liveNit != null)
            return 0f to exact
        }
        if (calibration != null && calibration.backlightToNits.isNotEmpty()) {
            val mapped = interpolateLinearPairs(calibration.backlightToNits, fraction)
            if (mapped != null && mapped >= 0f) {
                return mapped to true
            }
        }
        val live = calibration?.liveNit
        val calBl = calibration?.liveBrightnessFloat
        if (live != null && live >= 0f && (calBl == null || abs(calBl - fraction) <= 0.025f)) {
            return live to true
        }
        val effectiveMin = calibration?.minNit ?: fallbackMinNit
        val effectiveMax = calibration?.maxNit ?: fallbackMaxNit
        val hasBounds = calibration?.maxNit != null
        return com.banoku.oplus.autobrightness.BrightnessMath.estimateScreenNits(
            fraction,
            effectiveMin,
            effectiveMax
        ) to hasBounds
    }

    fun getSampleSimulationLogs(): List<LogEntry> {
        return listOf(
            LogEntry(
                type = LogType.INFO,
                time = "20:00:10",
                message = "v1.4.4 (ColorOS 17 Verified Engine) starting, sdk=${Build.VERSION.SDK_INT}, build=${Build.FINGERPRINT}"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [1/10] controller resetAutoBrightness installed (variants=2, lambda=true)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [2/10] ambient resetAmbientAdapter(String) installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [3/10] stability anti-drift installed (targets=2)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [4/10] StabilityProcess.getAccelerateScale installed (overloads=3)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [5a/10] OplusFeatureDarkEnvReduceBrightness.shouldSkipProcessing installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [5b/10] OplusBrightnessPostProcessingManager.convertNitToBrightness floor (8.5 nits) installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [6/10] OplusFeatureReduceBrightness.isReduceDisabled installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [7/10] LuxEvent\$LuxEventResult.calculateAuxLux balancer installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [8/10] StabilityProcess hysteresis widener installed (targets=2)"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [9/10] OplusDisplayBrightnessModel.getValueByCurve(String, float) live curve installed"
            ),
            LogEntry(
                type = LogType.HOOK,
                time = "20:00:11",
                message = "Hook [10/10] SystemSensorManager\$SensorEventQueue high_pwm_rgb guard installed"
            ),
            LogEntry(
                type = LogType.INFO,
                time = "20:00:11",
                message = "HOOK_SUMMARY:installed=10/10,version=1.4.4,sdk=${Build.VERSION.SDK_INT},hooks=1:OK,2:OK,3:OK,4:OK,5:OK,6:OK,7:OK,8:OK,9:OK,10:OK"
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
            4 to "Debounce Lock (getAccelerateScale)",
            5 to "Night Floor & DarkEnv (8.5 nit)",
            6 to "App Smart Dimming (isReduceDisabled)",
            7 to "360° Aux Smoother (calculateAuxLux)",
            8 to "Hysteresis Widener (getLargeChangeLimit)",
            9 to "Live Curve (getValueByCurve)",
            10 to "Under-Display PWM (high_pwm_rgb)"
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
                    HookStatusItem(id, title, HookState.UNKNOWN, "Нет данных из system_server")
                }
            )
        }

        // Convert to chronological order and focus on the most recent system_server session
        val chrono = logs.asReversed()
        val startIdx = chrono.indexOfLast {
            it.message.contains("starting, sdk=", ignoreCase = true)
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

        // Optional compact HOOK_SUMMARY parser
        val summaryLine = session.lastOrNull { it.message.contains("HOOK_SUMMARY:") }?.message
        if (summaryLine != null) {
            if (loadedVersion.isEmpty()) {
                loadedVersion = Regex("version=([^,\\s]+)").find(summaryLine)?.groupValues?.get(1).orEmpty()
            }
            val hooksPart = Regex("hooks=([^\\s]+)").find(summaryLine)?.groupValues?.get(1).orEmpty()
            hooksPart.split(',').forEach { token ->
                val parts = token.split(':')
                if (parts.size == 2) {
                    val idx = parts[0].toIntOrNull()
                    if (idx != null) {
                        stateMap[idx] = if (parts[1] == "OK") HookState.INSTALLED else HookState.SKIPPED
                    }
                }
            }
        }

        var has5aOk = false
        var has5bOk = false
        var has5aSkip = false
        var has5bSkip = false

        for (entry in session) {
            val msg = entry.message
            // Hook installation lines
            val hookMatch = Regex("Hook \\[(\\d+)([ab])?/\\d+\\]\\s*(.*)").find(msg)
            if (hookMatch != null) {
                val id = hookMatch.groupValues[1].toIntOrNull() ?: continue
                val sub = hookMatch.groupValues[2]
                val body = hookMatch.groupValues[3]
                val isInstalled = body.contains("installed", ignoreCase = true)
                val isSkipped = body.contains("skipped", ignoreCase = true)

                if (id == 5 && sub.isNotEmpty()) {
                    if (sub == "a") {
                        if (isInstalled) has5aOk = true else if (isSkipped) has5aSkip = true
                    } else if (sub == "b") {
                        if (isInstalled) has5bOk = true else if (isSkipped) has5bSkip = true
                    }
                    stateMap[5] = when {
                        has5aOk && has5bOk -> HookState.INSTALLED
                        has5aSkip || has5bSkip -> HookState.SKIPPED
                        has5aOk || has5bOk -> HookState.INSTALLED
                        else -> HookState.UNKNOWN
                    }
                } else {
                    if (isInstalled) stateMap[id] = HookState.INSTALLED
                    else if (isSkipped) stateMap[id] = HookState.SKIPPED
                }
                detailMap[id] = body
            }

            // Runtime hit counters
            when {
                msg.contains("BLOCKED resetAutoBrightness") -> hitMap[1] = (hitMap[1] ?: 0) + 1
                msg.contains("BLOCKED resetAmbientAdapter") -> hitMap[2] = (hitMap[2] ?: 0) + 1
                msg.contains("anti-drift suppressed") -> hitMap[3] = (hitMap[3] ?: 0) + 1
                msg.contains("debounce accelerateScale") -> hitMap[4] = (hitMap[4] ?: 0) + 1
                msg.contains("DarkEnv") || msg.contains("floor clamped") -> hitMap[5] = (hitMap[5] ?: 0) + 1
                msg.contains("per-app dimming") -> hitMap[6] = (hitMap[6] ?: 0) + 1
                msg.contains("dual-sensor smoothed") -> hitMap[7] = (hitMap[7] ?: 0) + 1
                msg.contains("GUARD hysteresis") -> hitMap[8] = (hitMap[8] ?: 0) + 1
                msg.contains("CURVE override") -> hitMap[9] = (hitMap[9] ?: 0) + 1
                msg.contains("GUARD PWM sensor") -> hitMap[10] = (hitMap[10] ?: 0) + 1
            }
        }

        val items = hookTitles.map { (id, title) ->
            val state = stateMap[id] ?: HookState.UNKNOWN
            val baseDetail = detailMap[id] ?: when (state) {
                HookState.INSTALLED -> "Команда найдена в системе"
                HookState.SKIPPED -> "Метод не найден в этой прошивке"
                HookState.UNKNOWN -> "Ожидание старта / лога"
            }
            val hits = hitMap[id] ?: 0
            val fullDetail = if (hits > 0) "$baseDetail • срабатываний: $hits" else baseDetail
            HookStatusItem(id = id, name = title, state = state, details = fullDetail, runtimeHits = hits)
        }

        val detected = startEntry != null || stateMap.isNotEmpty()
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
    val maxNit: Float? = null,
    val backlightToNits: List<Pair<Float, Float>> = emptyList(),
    val romCurveNits: List<Float>? = null
) {
    val hasRealCalibration: Boolean
        get() = backlightToNits.isNotEmpty() || liveNit != null || maxNit != null
}
