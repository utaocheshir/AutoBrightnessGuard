package com.banoku.oplus.autobrightness

import com.banoku.oplus.autobrightness.util.CurveProfileRepository
import com.banoku.oplus.autobrightness.util.DeviceCompatibility
import com.banoku.oplus.autobrightness.util.HookState
import com.banoku.oplus.autobrightness.util.LogType
import com.banoku.oplus.autobrightness.util.RootHelper
import com.banoku.oplus.autobrightness.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUnitTest {

    @Test
    fun versionAndTagCheck() {
        assertEquals("1.5.0", AutoBrightnessModule.VERSION)
        assertEquals("autobrightness_config", AutoBrightnessModule.REMOTE_PREFS_GROUP)
        assertEquals("AutoBrightnessGuard", AutoBrightnessModule.TAG)
        assertEquals("persist.autobrightness.block_reset", AutoBrightnessModule.PROP_BLOCK_RESET)
        assertEquals("persist.autobrightness.block_ambient", AutoBrightnessModule.PROP_BLOCK_AMBIENT)
        assertEquals("persist.autobrightness.stability_guard", AutoBrightnessModule.PROP_STABILITY_GUARD)
        assertEquals("persist.autobrightness.min_floor", AutoBrightnessModule.PROP_MIN_FLOOR)
        assertEquals("persist.autobrightness.custom_min_nit", AutoBrightnessModule.PROP_CUSTOM_MIN_NIT)
        assertEquals("persist.autobrightness.debounce_lock", AutoBrightnessModule.PROP_DEBOUNCE_LOCK)
        assertEquals("persist.autobrightness.hysteresis", AutoBrightnessModule.PROP_HYSTERESIS)
        assertEquals("persist.autobrightness.disable_smart_dimming", AutoBrightnessModule.PROP_DISABLE_SMART_DIMMING)
        assertEquals("persist.autobrightness.dual_sensor_guard", AutoBrightnessModule.PROP_DUAL_SENSOR_GUARD)
        assertEquals("persist.autobrightness.disable_main_sensor", AutoBrightnessModule.PROP_DISABLE_MAIN_SENSOR)
        assertEquals("persist.autobrightness.disable_aux_sensor", AutoBrightnessModule.PROP_DISABLE_AUX_SENSOR)
        assertEquals("persist.autobrightness.disable_pwm_sensor", AutoBrightnessModule.PROP_DISABLE_PWM_SENSOR)
        assertEquals("persist.autobrightness.curve", AutoBrightnessModule.PROP_CURVE_CONFIG)
        assertEquals("persist.autobrightness.sync_ts", AutoBrightnessModule.PROP_SYNC_TS)
        assertEquals("pref_sync_ts", AutoBrightnessModule.PREF_SYNC_TS)
        assertEquals("/data/system/autobrightness_guard.log", AutoBrightnessModule.DISK_LOG_PATH)
        assertEquals(8.5f, BrightnessMath.NIGHT_MIN_FLOOR_NITS, 0.001f)
        assertEquals(3500.0f, BrightnessMath.MAX_CURVE_NIT, 0.001f)
        assertEquals(150.0f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT, 0.001f)
    }

    @Test
    fun dualSensorAuxSmoothingCheck() {
        // Proportional blending (60% toward auxLux) instead of hard blocking rear sensor
        assertEquals(76.0f, BrightnessMath.smoothIndoorAuxLux(10f, 120f), 0.001f)
        assertEquals(18.0f, BrightnessMath.smoothIndoorAuxLux(0f, 30f), 0.001f)
        assertEquals(40.0f, BrightnessMath.smoothIndoorAuxLux(50f, 40f), 0.001f)
        assertEquals(22.0f, BrightnessMath.smoothIndoorAuxLux(20f, 21.2f), 2.0f)
        // Bright daylight (>200 lx) passes rear sensor unattenuated
        assertEquals(400.0f, BrightnessMath.smoothIndoorAuxLux(250f, 400f), 0.001f)

        // Temporal EMA step check
        assertEquals(76.0f, BrightnessMath.stepAuxEma(-1.0f, 76.0f, 0.35f), 0.001f)
        assertEquals(33.1f, BrightnessMath.stepAuxEma(10.0f, 76.0f, 0.35f), 0.001f)

        // Indoor Aux deadband filter: ignore tiny <= 3 lx jitter & bright daylight (>= 200 lx)
        assertFalse(BrightnessMath.shouldSmoothIndoorAuxLux(20f, 22f))
        assertFalse(BrightnessMath.shouldSmoothIndoorAuxLux(100f, 103.5f))
        assertTrue(BrightnessMath.shouldSmoothIndoorAuxLux(20f, 35f))
        assertFalse(BrightnessMath.shouldSmoothIndoorAuxLux(250f, 400f))
    }

    @Test
    fun nightFloorThresholdCheck() {
        assertTrue(BrightnessMath.shouldRaiseNitFloor(2.4f, BrightnessMath.NIGHT_MIN_FLOOR_NITS))
        assertTrue(BrightnessMath.shouldRaiseNitFloor(10.0f, 15.0f))
        assertTrue(BrightnessMath.shouldRaiseNitFloor(95.0f, 120.0f))
        assertFalse(BrightnessMath.shouldRaiseNitFloor(0.0f, BrightnessMath.NIGHT_MIN_FLOOR_NITS)) // screen off stays 0
        assertFalse(BrightnessMath.shouldRaiseNitFloor(Float.NaN, BrightnessMath.NIGHT_MIN_FLOOR_NITS))
        assertFalse(BrightnessMath.shouldRaiseNitFloor(12.0f, BrightnessMath.NIGHT_MIN_FLOOR_NITS))
    }

    @Test
    fun curveSerializationAndInterpolationCheck() {
        val sampleNits = floatArrayOf(12f, 45f, 120f, 350f, 850f, 1800f, 3200f)
        val encoded = BrightnessMath.serializeCurveConfig(true, sampleNits)
        assertTrue(encoded.length < 91) // Must fit inside Android PROP_VALUE_MAX (91 bytes)
        assertTrue(encoded.startsWith("1:"))

        val decoded = BrightnessMath.parseCurveConfig(encoded)
        assertTrue(decoded.enabled)
        assertEquals(12f, decoded.nits[0], 0.01f)
        assertEquals(3200f, decoded.nits[6], 0.01f)

        // Interpolation at anchor points and midpoints
        assertEquals(12f, BrightnessMath.interpolatePiecewiseLogLux(0f, BrightnessMath.CURVE_ANCHOR_LUX, decoded.nits), 0.01f)
        val mid = BrightnessMath.interpolatePiecewiseLogLux(50f, BrightnessMath.CURVE_ANCHOR_LUX, decoded.nits)
        assertTrue(mid in 120f..350f)
        assertEquals(3200f, BrightnessMath.interpolatePiecewiseLogLux(20000f, BrightnessMath.CURVE_ANCHOR_LUX, decoded.nits), 0.01f)
    }

    @Test
    fun stockNitArrayFormatAndParseCheck() {
        val stock = floatArrayOf(8.5f, 14f, 28f, 70f, 155f, 320f, 600f)
        val formatted = BrightnessMath.formatNitArray(stock)
        val parsed = BrightnessMath.parseNitArray(formatted)
        assertNotNull(parsed)
        assertEquals(7, parsed!!.size)
        assertEquals(8.5f, parsed[0], 0.01f)
        assertEquals(600f, parsed[6], 0.01f)
        assertNull(BrightnessMath.parseNitArray("1,2,3"))
        assertNull(BrightnessMath.parseNitArray(""))
    }

    @Test
    fun curveProfileExportAndImportCheck() {
        val p1 = CurveProfileRepository.createProfile("День", listOf(10f, 25f, 60f, 140f, 350f, 700f, 1200f))
        val p2 = CurveProfileRepository.createProfile("Ночь \"Комфорт\"", listOf(12f, 20f, 45f, 90f, 220f, 480f, 800f))
        val json = CurveProfileRepository.exportProfilesToJson(listOf(p1, p2), p1.id)

        val imported = CurveProfileRepository.importProfilesFromJson(json)
        assertEquals(2, imported.size)
        assertEquals("День", imported[0].name)
        assertEquals("Ночь \"Комфорт\"", imported[1].name)
        assertEquals(1200f, imported[0].nits[6], 0.1f)

        // Single-line property fallback import + monotonicity enforcement
        val lineImport = CurveProfileRepository.importProfilesFromJson("1:20,15,40,90,200,500,900")
        assertEquals(1, lineImport.size)
        assertTrue(lineImport[0].nits[1] >= lineImport[0].nits[0])

        assertTrue(CurveProfileRepository.importProfilesFromJson("{invalid json}").isEmpty())
    }

    @Test
    fun hysteresisRangeWideningCheck() {
        // Standard indoor bounds around 50 lx: [38, 65] widened at 2.0x -> broader [newLeft, newRight]
        val widened = BrightnessMath.widenRangeBounds(50f, 38f, 65f, 2.0f)
        assertTrue(widened[0] < 38f)
        assertTrue(widened[1] > 65f)

        // Preserve ColorOS negative left sentinel (-1.0f) in very dark conditions
        val darkWidened = BrightnessMath.widenRangeBounds(2f, -1.0f, 6.0f, 2.0f)
        assertEquals(-1.0f, darkWidened[0], 0.001f)
        assertTrue(darkWidened[1] > 6.0f)
    }

    @Test
    fun screenNitEstimationCheck() {
        assertEquals(0f, BrightnessMath.estimateScreenNits(0f, 2f, 800f), 0.01f)
        assertEquals(800f, BrightnessMath.estimateScreenNits(1f, 2f, 800f), 0.01f)
        val mid = BrightnessMath.estimateScreenNits(0.5f, 2f, 800f)
        assertTrue(mid in 140f..210f)
    }

    @Test
    fun updateCheckerVersionComparison() {
        assertFalse(UpdateChecker.isVersionNewer("V1.3b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("v1.5.0", "1.5.0"))
        assertTrue(UpdateChecker.isVersionNewer("1.5.0", "1.4.5"))
        assertTrue(UpdateChecker.isVersionNewer("1.4.5", "1.4.4"))
        assertTrue(UpdateChecker.isVersionNewer("1.4.4", "1.4.3"))
        assertTrue(UpdateChecker.isVersionNewer("1.4", "1.3b"))
        assertTrue(UpdateChecker.isVersionNewer("2.0", "1.5.0"))
    }

    @Test
    fun simulationLogsAndRuntimeStatusCheck() {
        val sampleLogs = RootHelper.getSampleSimulationLogs()
        assertTrue(sampleLogs.isNotEmpty())
        assertTrue(sampleLogs.any { it.type == LogType.BLOCKED })
        // Newest entry (23:14:01) must be at the top (index 0)
        assertEquals("23:14:01", sampleLogs.first().time)

        val stats = RootHelper.computeStats(sampleLogs)
        assertEquals(2, stats.totalBlocked)
        assertEquals("20:11:49", stats.lastBlockedTime)
        assertTrue(stats.hasExactModuleStats)
        assertEquals(4, stats.antiDriftCount)
        assertEquals(3, stats.floorAndDarkEnvCount)
        assertEquals(6, stats.customCurveCount)
        assertEquals(13, stats.stabilityAndSensorsCount)
        assertEquals(26, stats.totalGuardInterventions)

        val runtimeStatus = RootHelper.parseModuleRuntimeStatus(sampleLogs, AutoBrightnessModule.VERSION)
        assertTrue(runtimeStatus.moduleDetected)
        assertEquals(AutoBrightnessModule.VERSION, runtimeStatus.loadedVersion)
        assertFalse(runtimeStatus.isVersionMismatch)
        assertEquals(10, runtimeStatus.totalCount)
        assertEquals(10, runtimeStatus.installedCount)
        assertTrue(runtimeStatus.hooks.all { it.state == HookState.INSTALLED })

        val mismatchStatus = RootHelper.parseModuleRuntimeStatus(sampleLogs, "9.9.9")
        assertTrue(mismatchStatus.isVersionMismatch)

        val stockCurve = RootHelper.extractLatestStockCurve(sampleLogs)
        assertNotNull(stockCurve)
        assertEquals(7, stockCurve!!.size)
        assertEquals(8.5f, stockCurve[0], 0.01f)
        assertEquals(600f, stockCurve[6], 0.01f)
    }

    @Test
    fun guardStatsFallbackWhenBlockedLinesEvictedCheck() {
        val raw = listOf(
            "11-16 09:00:00.001 1000 1234 1234 I AutoBrightnessGuard: v1.5.0 (Vector/LSPosed Verified Engine) starting, sdk=37, remote=true, build=test",
            "11-16 09:00:01.001 1000 1234 1234 I AutoBrightnessGuard: HOOK_SUMMARY:installed=10/10,version=1.5.0,sdk=37,hooks=1:OK,2:OK,3:OK,4:OK,5:OK,6:OK,7:OK,8:OK,9:OK,10:OK",
            "11-16 14:25:00.100 1000 1234 1234 I AutoBrightnessGuard: GUARD_STATS:reset=3,ambient=2,drift=12,debounce=4,floor=5,darkenv=2,dimming=1,aux=8,hyst=7,curve=15,pwm=1,last_block_ms=1731740000000"
        ).joinToString("\n")
        val entries = RootHelper.parseLogs(raw).reversed()
        val stats = RootHelper.computeStats(entries)
        assertEquals(5, stats.totalBlocked)
        assertEquals(3, stats.resetBlockedCount)
        assertEquals(2, stats.ambientBlockedCount)
        assertTrue(stats.lastBlockedTime.isNotBlank())
        assertEquals(12, stats.antiDriftCount)
        assertEquals(7, stats.floorAndDarkEnvCount)
        assertEquals(15, stats.customCurveCount)
        assertEquals(21, stats.stabilityAndSensorsCount)
        assertEquals(55, stats.totalGuardInterventions)
    }

    @Test
    fun vectorAndLogcatBracketParsingCheck() {
        // Logcat line with brackets inside message (Hook [1/10]) must NOT be chopped at first ']'
        val logcatHook = "11-15 08:12:01.123  1000  1234  1234 I AutoBrightnessGuard: Hook [1/10] controller resetAutoBrightness installed"
        assertEquals(
            "Hook [1/10] controller resetAutoBrightness installed",
            RootHelper.cleanLogMessage(logcatHook)
        )

        val logcatHyst = "11-15 08:12:05.456  1000  1234  1234 I AutoBrightnessGuard: GUARD hysteresis (1.8x) at 45.0 lx: [34.0..58.0] -> [23.2..72.4]"
        assertEquals(
            "GUARD hysteresis (1.8x) at 45.0 lx: [34.0..58.0] -> [23.2..72.4]",
            RootHelper.cleanLogMessage(logcatHyst)
        )

        val vectorBoot = "[ 2026-11-15T08:12:00.001 1000:1234:1234 I/Vector(AutoBrightnessGuard) ] v1.5.0 (Vector/LSPosed Verified Engine) starting, sdk=37, remote=true, build=test"
        assertEquals(
            "v1.5.0 (Vector/LSPosed Verified Engine) starting, sdk=37, remote=true, build=test",
            RootHelper.cleanLogMessage(vectorBoot)
        )

        val rawCombined = listOf(
            vectorBoot,
            logcatHook,
            logcatHook, // duplicate line from boot+tail grep should deduplicate
            "11-15 08:12:02.000 1000 1234 1234 I AutoBrightnessGuard: HOOK_SUMMARY:installed=10/10,version=1.5.0,sdk=37,hooks=1:OK,2:OK,3:OK,4:OK,5:OK,6:OK,7:OK,8:OK,9:OK,10:OK"
        ).joinToString("\n")

        val entries = RootHelper.parseLogs(rawCombined).reversed()
        assertEquals(3, entries.size)
        val status = RootHelper.parseModuleRuntimeStatus(entries, "1.5.0")
        assertTrue(status.moduleDetected)
        assertEquals("1.5.0", status.loadedVersion)
        assertFalse(status.isVersionMismatch)
        assertEquals(10, status.installedCount)

        val loadOnlyRaw = "[ 2026-11-16T10:00:00.001 1000:1234:1234 I/Vector(AutoBrightnessGuard) ] onModuleLoaded in system_server (v1.5.0)"
        val loadOnlyStatus = RootHelper.parseModuleRuntimeStatus(RootHelper.parseLogs(loadOnlyRaw), "1.5.0")
        assertTrue(loadOnlyStatus.moduleDetected)
        assertEquals("1.5.0", loadOnlyStatus.loadedVersion)
        assertEquals(0, loadOnlyStatus.installedCount)

        // Real Vector 2.2 XposedInterface.log() output inside Android logcat prepends packageName
        val realVectorLogcat = listOf(
            "[ 2026-11-16T12:53:15.111 1000: 3743: 3743 D/VectorModuleManager ] Loaded module com.banoku.oplus.autobrightness successfully.",
            "11-16 13:46:23.913  3743  4156 I AutoBrightnessGuard: com.banoku.oplus.autobrightness: GUARD_STATS:reset=0,ambient=0,drift=1556,debounce=0,floor=43,darkenv=468,dimming=0,aux=48,hyst=0,curve=82,pwm=0,last_block_ms=0"
        ).joinToString("\n")
        val realEntries = RootHelper.parseLogs(realVectorLogcat).reversed()
        assertEquals(2, realEntries.size)
        assertEquals(
            "GUARD_STATS:reset=0,ambient=0,drift=1556,debounce=0,floor=43,darkenv=468,dimming=0,aux=48,hyst=0,curve=82,pwm=0,last_block_ms=0",
            realEntries.first().message
        )
        assertEquals("Loaded module com.banoku.oplus.autobrightness successfully.", realEntries.last().message)
        val vecStats = RootHelper.computeStats(realEntries)
        assertEquals(1556, vecStats.antiDriftCount)
        assertEquals(43 + 468, vecStats.floorAndDarkEnvCount)
        assertEquals(82, vecStats.customCurveCount)
        assertEquals(48, vecStats.stabilityAndSensorsCount)

        // Even if logd rotated out HOOK_SUMMARY, VectorModuleManager load keeps moduleDetected = true
        val vecRuntime = RootHelper.parseModuleRuntimeStatus(realEntries, "1.5.0")
        assertTrue(vecRuntime.moduleDetected)
    }

    @Test
    fun hookMaskRecoveryChronologicalSortAndCacheRoundTripCheck() {
        // 1. Recovery of 10/10 hook state & version from GUARD_STATS alone when boot/summary rotated out
        val statsOnlyRaw = "11-16 14:30:05.123 3743 4156 I AutoBrightnessGuard: GUARD_STATS:reset=2,ambient=1,drift=14,debounce=0,floor=3,darkenv=1,dimming=0,aux=2,hyst=1,curve=4,pwm=0,last_block_ms=1731740000000,hooks=10/10,hook_mask=1111111111,ver=1.5.0"
        val statsEntries = RootHelper.parseLogs(statsOnlyRaw)
        val recovered = RootHelper.parseModuleRuntimeStatus(statsEntries, "1.5.0")
        assertTrue(recovered.moduleDetected)
        assertEquals("1.5.0", recovered.loadedVersion)
        assertEquals(10, recovered.installedCount)
        assertTrue(recovered.hooks.all { it.state == HookState.INSTALLED })

        // Partial hook_mask (hook #8 skipped = 0)
        val partialRaw = "11-16 14:35:00.000 3743 4156 I AutoBrightnessGuard: GUARD_STATS:reset=0,ambient=0,drift=1,debounce=0,floor=0,darkenv=0,dimming=0,aux=0,hyst=0,curve=0,pwm=0,last_block_ms=0,hooks=9/10,hook_mask=1111111011,ver=1.5.0"
        val partialStatus = RootHelper.parseModuleRuntimeStatus(RootHelper.parseLogs(partialRaw), "1.5.0")
        assertEquals(9, partialStatus.installedCount)
        assertEquals(HookState.SKIPPED, partialStatus.hooks.first { it.id == 8 }.state)
        assertEquals(HookState.INSTALLED, partialStatus.hooks.first { it.id == 10 }.state)

        // 2. Chronological sorting across mixed Vector ISO and Android logcat/disk timestamps
        val mixedRaw = listOf(
            "11-16 13:10:53.200 3743 4156 I AutoBrightnessGuard: GUARD_STATS:reset=0,ambient=0,drift=5,debounce=0,floor=0,darkenv=0,dimming=0,aux=0,hyst=0,curve=0,pwm=0,last_block_ms=0",
            "[ 2026-11-16T12:53:15.111 1000: 3743: 3743 D/VectorModuleManager ] Loaded module com.banoku.oplus.autobrightness successfully.",
            "11-16 13:05:10.050 3743 4156 I AutoBrightnessGuard: BLOCKED resetAutoBrightness #6"
        ).joinToString("\n")
        val sortedAsc = RootHelper.parseLogs(mixedRaw)
        assertEquals(3, sortedAsc.size)
        assertEquals("12:53:15", sortedAsc[0].time)
        assertEquals("13:05:10", sortedAsc[1].time)
        assertEquals("13:10:53", sortedAsc[2].time)

        // 3. TSV cache round-trip preserves order, types, time, and messages with tabs/newlines sanitized
        val cachedTsv = RootHelper.serializeLogsForCache(sortedAsc.reversed())
        val restored = RootHelper.deserializeLogsFromCache(cachedTsv)
        assertEquals(3, restored.size)
        assertEquals("13:10:53", restored[0].time)
        assertEquals(LogType.BLOCKED, restored[1].type)
        assertEquals("Loaded module com.banoku.oplus.autobrightness successfully.", restored[2].message)
    }

    @Test
    fun compatibilitySummaryCheck() {
        val summary = DeviceCompatibility.getCompatibilitySummary()
        assertNotNull(summary.deviceModel)
        assertNotNull(summary.statusText)
        assertNotNull(summary.details)
        assertNotNull(summary.romVersion)
    }

    @Test
    fun displayCalibrationAndSplineParsingCheck() {
        val sampleDumpsys = """
            DISPLAY MANAGER (dumpsys display)
            DisplayPowerController:
              mCurrentScreenBrightness=0.5
              currentSdrNits=420.0
              mMinNits=2.0
              mMaxNits=1600.0
              desiredMaxAverageLuminance=500.0
              desiredMaxLuminance=1600.0
              mIsForIdleMode=false
              mBacklightToNitsSpline=MonotoneCubicSpline{[(0.0, 2.0), (0.5, 420.0), (1.0, 1600.0)]}
              mDefaultSpline=MonotoneCubicSpline{[(0.0, 8.5), (5.0, 16.0), (25.0, 35.0), (100.0, 95.0), (500.0, 260.0), (2000.0, 650.0), (10000.0, 1400.0)]}
              mIsForIdleMode=true
              mBacklightToNitsSpline=MonotoneCubicSpline{[(0.0, 2.0), (1.0, 300.0)]}
        """.trimIndent()

        val cal = RootHelper.parseDisplayCalibration(sampleDumpsys)
        assertTrue(cal.hasRealCalibration)
        assertEquals(2.0f, cal.minNit!!, 0.01f)
        assertEquals(1600.0f, cal.maxNit!!, 0.01f)
        assertEquals(420.0f, cal.liveNit!!, 0.01f)
        assertEquals(0.5f, cal.liveBrightnessFloat!!, 0.001f)
        assertEquals(3, cal.backlightToNits.size)
        assertNotNull(cal.romCurveNits)
        assertEquals(7, cal.romCurveNits!!.size)
        assertEquals(1400.0f, cal.romCurveNits!![6], 0.1f)

        // Exact spline interpolation at 0.75 -> halfway between 420 and 1600 = 1010
        val (nits75, exact75) = RootHelper.resolveScreenNits(0.75f, cal, 2f, 500f)
        assertTrue(exact75)
        assertEquals(1010.0f, nits75!!, 0.5f)

        // Inverse mNitsToBacklightSpline + desiredMaxAverageLuminance=500 must NOT cap maxNit
        val inverseDumpsys = """
            desiredMaxAverageLuminance=500.0
            mNitsToBacklightSpline=MonotoneCubicSpline{[(2.0, 0.0), (200.0, 0.25), (2400.0, 1.0)]}
        """.trimIndent()
        val invCal = RootHelper.parseDisplayCalibration(inverseDumpsys)
        assertEquals(2400.0f, invCal.maxNit!!, 0.1f)
        val (nits100, exact100) = RootHelper.resolveScreenNits(1.0f, invCal, 2f, 500f)
        assertTrue(exact100)
        assertEquals(2400.0f, nits100!!, 0.5f)
    }

    @Test
    fun colorOs17DumpCalibrationCheck() {
        val colorOs17Dump = """
            DISPLAY MANAGER (dumpsys display)
              mMinimumBrightnessCurve=[(0.0, 0.0), (2000.0, 50.0), (4000.0, 90.0)]
            Display States: size=1
              Display Brightness=1880.0
              DisplayDeviceInfo{"Встроенный экран": hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[1, 2, 3, 4], mMaxLuminance=2000.0, mMaxAverageLuminance=2000.0, mMinLuminance=0.2}}
              mMaxBrightness=1.0
            OplusDisplayPowerControllerExtImpl:
                BrightnessModel:
                    mMinBrightness=6.0
                    mMaxBrightness=4095.0
                    mNormalMaxNit=798.2865
                    mTotalBrightness=4674.0
                    mMaxPanelBrightness=4674.0
                    splineCurve=MonotoneCubicSpline{[(0.0, 2.5: 2.5), (5.0, 15.4: 2.6), (25.0, 50.0: 1.0), (100.0, 82.0: 0.23362012), (500.0, 113.0: 0.075), (2000.0, 243.0: 0.092), (10000.0, 500.0: 0.010624999), (50000.0, 700.0: 0.005), (270000.0, 1800.0: 0.005)]}
            BrightnessSynchronizer:
              mLatestIntBrightness=255
              mLatestFloatBrightness=1825.6786
        """.trimIndent()

        val cal = RootHelper.parseDisplayCalibration(colorOs17Dump)
        assertTrue(cal.hasRealCalibration)
        assertEquals(2.5f, cal.minNit!!, 0.01f)
        assertEquals(798.2865f, cal.normalMaxNit!!, 0.01f)
        assertEquals(2000.0f, cal.maxNit!!, 0.01f)
        assertEquals(6.0f, cal.brightnessMin!!, 0.01f)
        assertEquals(4095.0f, cal.normalBrightnessMax!!, 0.01f)
        assertEquals(4674.0f, cal.panelBrightnessMax!!, 0.01f)
        assertEquals(1880.0f, cal.liveBrightnessFloat!!, 0.01f)
        assertNotNull(cal.romCurveNits)
        assertEquals(2.5f, cal.romCurveNits!![0], 0.01f)
        assertEquals(500.0f, cal.romCurveNits!![6], 0.01f)

        // Raw panel float 1880.0 on 6..4095 scale must NOT clamp to 100% from int=255
        val frac = RootHelper.resolveBrightnessFraction(1880.0f, 255, cal)
        assertEquals((1880f - 6f) / (4095f - 6f), frac, 0.005f)
        assertTrue(frac in 0.44f..0.47f)

        val (liveNits, exact) = RootHelper.resolveScreenNits(frac, cal, 2f, 500f, rawBrightness = 1880.0f)
        assertTrue(exact)
        assertNotNull(liveNits)
        assertTrue(liveNits!! in 130f..160f)

        // HBM boost above mMaxBrightness (4674 vs 4095) should map up to panel maxNit (2000)
        val hbmFrac = RootHelper.resolveBrightnessFraction(4674.0f, 255, cal)
        assertTrue(hbmFrac > 1.05f)
        val (hbmNits, hbmExact) = RootHelper.resolveScreenNits(hbmFrac, cal, 2f, 500f, rawBrightness = 4674.0f)
        assertTrue(hbmExact)
        assertEquals(2000.0f, hbmNits!!, 1.0f)
    }
}
