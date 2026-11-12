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
        assertEquals("1.4.0", AutoBrightnessModule.VERSION)
        assertEquals("AutoBrightnessGuard", AutoBrightnessModule.TAG)
        assertEquals("persist.autobrightness.block_reset", AutoBrightnessModule.PROP_BLOCK_RESET)
        assertEquals("persist.autobrightness.block_ambient", AutoBrightnessModule.PROP_BLOCK_AMBIENT)
        assertEquals("persist.autobrightness.stability_guard", AutoBrightnessModule.PROP_STABILITY_GUARD)
        assertEquals("persist.autobrightness.min_floor", AutoBrightnessModule.PROP_MIN_FLOOR)
        assertEquals("persist.autobrightness.debounce_lock", AutoBrightnessModule.PROP_DEBOUNCE_LOCK)
        assertEquals("persist.autobrightness.disable_smart_dimming", AutoBrightnessModule.PROP_DISABLE_SMART_DIMMING)
        assertEquals("persist.autobrightness.dual_sensor_guard", AutoBrightnessModule.PROP_DUAL_SENSOR_GUARD)
        assertEquals(8.5f, BrightnessMath.NIGHT_MIN_FLOOR_NITS, 0.001f)
    }

    @Test
    fun dualSensorAuxSmoothingCheck() {
        // Indoor low-lux reflection spike: 10 lux front, 120 lux rear -> capped to 10 + (3.5 + 5) = 18.5
        assertEquals(18.5f, BrightnessMath.smoothIndoorAuxLux(10f, 120f), 0.001f)
        // Zero lux front, slight rear bleed (30 lux) -> capped to 5.0
        assertEquals(5.0f, BrightnessMath.smoothIndoorAuxLux(0f, 30f), 0.001f)
        // Normal case where auxLux <= mainLux -> untouched
        assertEquals(40f, BrightnessMath.smoothIndoorAuxLux(50f, 40f), 0.001f)
        // Mild aux boost inside cap -> untouched
        assertEquals(22f, BrightnessMath.smoothIndoorAuxLux(20f, 22f), 0.001f)
    }

    @Test
    fun nightFloorThresholdCheck() {
        assertTrue(BrightnessMath.shouldRaiseNitFloor(2.4f, BrightnessMath.NIGHT_MIN_FLOOR_NITS))
        assertFalse(BrightnessMath.shouldRaiseNitFloor(0.0f, BrightnessMath.NIGHT_MIN_FLOOR_NITS)) // screen off must stay 0
        assertFalse(BrightnessMath.shouldRaiseNitFloor(Float.NaN, BrightnessMath.NIGHT_MIN_FLOOR_NITS))
        assertFalse(BrightnessMath.shouldRaiseNitFloor(12.0f, BrightnessMath.NIGHT_MIN_FLOOR_NITS))
    }

    @Test
    fun updateCheckerVersionComparison() {
        // Must NOT loop on the same version regardless of case or 'v'/'V' prefix
        assertFalse(UpdateChecker.isVersionNewer("V1.3b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("v1.3b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("1.3b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("v1.2b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("V1.2b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("1.1", "1.3b"))

        // Truly newer versions
        assertTrue(UpdateChecker.isVersionNewer("1.4", "1.3b"))
        assertTrue(UpdateChecker.isVersionNewer("v1.4b", "1.3b"))
        assertTrue(UpdateChecker.isVersionNewer("V1.4", "1.3b"))
        assertTrue(UpdateChecker.isVersionNewer("2.0", "1.3b"))
        assertTrue(UpdateChecker.isVersionNewer("1.3", "1.3b")) // stable 1.3 is newer than beta 1.3b
    }

    @Test
    fun simulationLogsAndStatsCheck() {
        val sampleLogs = RootHelper.getSampleSimulationLogs()
        assertTrue(sampleLogs.isNotEmpty())
        assertTrue(sampleLogs.any { it.type == LogType.BLOCKED })

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
