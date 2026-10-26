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
        assertEquals("1.2b", AutoBrightnessModule.VERSION)
        assertEquals("AutoBrightnessGuard", AutoBrightnessModule.TAG)
    }

    @Test
    fun updateCheckerVersionComparison() {
        assertTrue(UpdateChecker.isVersionNewer("1.3", "1.2b"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.1", "1.2b"))
        assertTrue(UpdateChecker.isVersionNewer("2.0", "1.2b"))
        assertFalse(UpdateChecker.isVersionNewer("1.2b", "1.2b"))
        assertFalse(UpdateChecker.isVersionNewer("1.1", "1.2b"))
        assertFalse(UpdateChecker.isVersionNewer("", "1.2b"))
    }

    @Test
    fun simulationLogsCheck() {
        val sampleLogs = RootHelper.getSampleSimulationLogs()
        assertTrue(sampleLogs.isNotEmpty())
        assertTrue(sampleLogs.any { it.type == LogType.BLOCKED })
        assertTrue(sampleLogs.any { it.type == LogType.HOOK })
    }

    @Test
    fun compatibilitySummaryCheck() {
        val summary = DeviceCompatibility.getCompatibilitySummary()
        assertNotNull(summary.deviceModel)
        assertNotNull(summary.statusText)
        assertNotNull(summary.details)
    }
}
