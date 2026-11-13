package com.banoku.oplus.autobrightness

import com.banoku.oplus.autobrightness.util.DeviceCompatibility
import com.banoku.oplus.autobrightness.util.LogType
import com.banoku.oplus.autobrightness.util.RootHelper
import com.banoku.oplus.autobrightness.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUnitTest {

    @Test
    fun versionAndTagCheck() {
        assertEquals("1.4.2", AutoBrightnessModule.VERSION)
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
        assertEquals("persist.autobrightness.curve", AutoBrightnessModule.PROP_CURVE_CONFIG)
        assertEquals(8.5f, BrightnessMath.NIGHT_MIN_FLOOR_NITS, 0.001f)
        assertEquals(3500.0f, BrightnessMath.MAX_CURVE_NIT, 0.001f)
        assertEquals(200.0f, BrightnessMath.MAX_CUSTOM_FLOOR_NIT, 0.001f)
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
    fun updateCheckerVersionComparison() {
        assertFalse(UpdateChecker.isVersionNewer("V1.3b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("v1.3b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("1.4.2", "1.4.2"))
        assertTrue(UpdateChecker.isVersionNewer("1.4.2", "1.4.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.4.1", "1.4.0"))
        assertTrue(UpdateChecker.isVersionNewer("1.4", "1.3b"))
        assertTrue(UpdateChecker.isVersionNewer("2.0", "1.4.2"))
    }

    @Test
    fun simulationLogsReversedAndStatsCheck() {
        val sampleLogs = RootHelper.getSampleSimulationLogs()
        assertTrue(sampleLogs.isNotEmpty())
        assertTrue(sampleLogs.any { it.type == LogType.BLOCKED })
        // Newest entry (23:14:01) must be at the top (index 0)
        assertEquals("23:14:01", sampleLogs.first().time)

        val stats = RootHelper.computeStats(sampleLogs)
        assertEquals(2, stats.totalBlocked)
        assertEquals("20:11:49", stats.lastBlockedTime)
    }

    @Test
    fun compatibilitySummaryCheck() {
        val summary = DeviceCompatibility.getCompatibilitySummary()
        assertNotNull(summary.deviceModel)
        assertNotNull(summary.statusText)
        assertNotNull(summary.details)
    }
}
