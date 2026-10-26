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
        assertEquals("1.3b", AutoBrightnessModule.VERSION)
        assertEquals("AutoBrightnessGuard", AutoBrightnessModule.TAG)
        assertEquals("persist.autobrightness.block_reset", AutoBrightnessModule.PROP_BLOCK_RESET)
        assertEquals("persist.autobrightness.block_ambient", AutoBrightnessModule.PROP_BLOCK_AMBIENT)
    }

    @Test
    fun updateCheckerVersionComparison() {
        assertTrue(UpdateChecker.isVersionNewer("1.4", "1.3b"))
        assertTrue(UpdateChecker.isVersionNewer("2.0", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("1.3b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("1.2b", "1.3b"))
        assertFalse(UpdateChecker.isVersionNewer("1.1", "1.3b"))
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
