package io.github.goooseby.tickmark.engine.sync

import org.junit.Assert.*
import org.junit.Test

class SyncReportEngineTest {
    @Test fun deviceUnionDeduplicatesAndOverlapCountsAnyTwoDevices() {
        val result = SyncReportEngine.summarize(listOf(
            SyncDeviceActivity("phone", listOf(0L to 100L)),
            SyncDeviceActivity("tablet", listOf(50L to 150L)),
            SyncDeviceActivity("desktop", listOf(75L to 125L)),
        ), 0, 200)
        assertEquals(100L, result.perDevice["phone"])
        assertEquals(100L, result.perDevice["tablet"])
        assertEquals(50L, result.perDevice["desktop"])
        assertEquals(150L, result.combined)
        assertEquals(75L, result.overlap)
    }

    @Test fun overlappingIntervalsFromOneDeviceDoNotCreateDeviceOverlap() {
        val result = SyncReportEngine.summarize(listOf(
            SyncDeviceActivity("phone", listOf(0L to 100L, 50L to 150L)),
        ), 0, 200)
        assertEquals(150L, result.perDevice["phone"])
        assertEquals(150L, result.combined)
        assertEquals(0L, result.overlap)
    }

    @Test fun clippingAndFutureDoNotInventUsage() {
        val result = SyncReportEngine.summarize(listOf(
            SyncDeviceActivity("phone", listOf(-10L to 20L, 90L to 200L)),
        ), 0, 100, now = 95)
        assertEquals(25L, result.combined)
        assertEquals(0L, result.overlap)
    }
}
