package io.github.goooseby.tickmark.engine.sync

import io.github.goooseby.tickmark.engine.model.DeviceSpan
import io.github.goooseby.tickmark.engine.model.DeviceState
import io.github.goooseby.tickmark.engine.model.Session
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SyncProjectorTest {
    private val day = LocalDate.of(2026, 11, 24)
    private val start = at("2026-11-24T00:00:00Z")
    private val end = at("2026-11-25T00:00:00Z")

    @Test fun utcMidnightSplitsWithoutLosingDuration() {
        val input = input(
            spans = listOf(DeviceSpan(start - 60_000, start + 120_000, DeviceState.IN_USE)),
            sessions = listOf(Session("pkg", start - 60_000, start + 120_000)),
            coverage = listOf((start - 60_000)..(start + 120_000)),
        )
        val first = SyncProjector.project(input, day)
        assertEquals(listOf(0L to 120_000L), first.deviceIntervals.map { it.startMs - start to it.endMs - start })
        assertEquals(listOf(0L to 120_000L), first.appIntervals.map { it.startMs - start to it.endMs - start })
        assertEquals(120_000L, first.deviceIntervals.sumOf { it.endMs - it.startMs })
        assertEquals(120_000L, first.appIntervals.sumOf { it.endMs - it.startMs })
    }

    @Test fun unknownIsPublishedOnlyInsideCoverage() {
        val input = input(
            spans = listOf(DeviceSpan(start, end, DeviceState.UNKNOWN)),
            coverage = listOf((start + 100_000)..(start + 200_000)),
        )
        val payload = SyncProjector.project(input, day)
        assertEquals(1, payload.deviceIntervals.size)
        assertEquals(SyncDeviceState.GAP, payload.deviceIntervals.single().state)
        assertEquals("unknown", payload.deviceIntervals.single().reason)
        assertEquals(start + 100_000, payload.deviceIntervals.single().startMs)
        assertEquals(start + 200_001, payload.deviceIntervals.single().endMs)
        assertTrue(SyncProjector.project(input.copy(coverage = emptyList()), day).deviceIntervals.isEmpty())
    }

    @Test fun lockedAndScreenOffAreOfflineWithReasons() {
        val payload = SyncProjector.project(input(
            spans = listOf(
                DeviceSpan(start, start + 100, DeviceState.LOCKED),
                DeviceSpan(start + 100, start + 200, DeviceState.SCREEN_OFF),
                DeviceSpan(start + 200, start + 300, DeviceState.IN_USE),
            ),
            coverage = listOf(start..(start + 300)),
        ), day)
        assertEquals(listOf("locked", "screen_off", null), payload.deviceIntervals.map { it.reason })
        assertEquals(listOf(SyncDeviceState.OFFLINE, SyncDeviceState.OFFLINE, SyncDeviceState.ACTIVE), payload.deviceIntervals.map { it.state })
    }

    @Test fun sameAppOverlapPrefersObservedWhileSplitScreenStaysParallel() {
        val payload = SyncProjector.project(input(
            spans = listOf(DeviceSpan(start, start + 300, DeviceState.IN_USE)),
            sessions = listOf(
                Session("reader", start, start + 200, inferred = true),
                Session("reader", start + 50, start + 150),
                Session("notes", start + 25, start + 175),
            ),
            coverage = listOf(start..(start + 300)),
            names = mapOf("reader" to "Reader", "notes" to "Notes"),
        ), day)
        val reader = payload.appIntervals.filter { it.appId == "reader" }
        assertEquals(listOf(SyncSessionQuality.INFERRED, SyncSessionQuality.OBSERVED, SyncSessionQuality.INFERRED), reader.map { it.quality })
        assertEquals(listOf(0L to 50L, 50L to 150L, 150L to 200L), reader.map { it.startMs - start to it.endMs - start })
        assertEquals(200L, reader.sumOf { it.endMs - it.startMs })
        assertEquals(150L, payload.appIntervals.single { it.appId == "notes" }.let { it.endMs - it.startMs })
        assertEquals(300L, payload.deviceIntervals.single().let { it.endMs - it.startMs })
        assertTrue(payload.appIntervals.all { it.displayName != null })
    }

    @Test fun hiddenAndExcludedIdentitiesNeverEnterPayload() {
        val payload = SyncProjector.project(input(
            spans = listOf(DeviceSpan(start, start + 100, DeviceState.IN_USE)),
            sessions = listOf(Session("secret", start, start + 100), Session("@excluded", start, start + 100)),
            coverage = listOf(start..(start + 100)),
            hidden = setOf("secret"),
            names = mapOf("secret" to "Secret", "@excluded" to "Excluded"),
        ), day)
        assertTrue(payload.appIntervals.isEmpty())
        assertFalse(payload.toString().contains("Secret"))
    }

    @Test fun futureAndOpenSessionsAreClippedToNow() {
        val now = start + 500
        val payload = SyncProjector.project(input(
            spans = listOf(DeviceSpan(start, end, DeviceState.IN_USE)),
            sessions = listOf(Session("pkg", start, end, ongoing = true)),
            coverage = listOf(start..end),
            now = now,
        ), day)
        assertEquals(now, payload.deviceIntervals.single().endMs)
        assertEquals(now, payload.appIntervals.single().endMs)
    }

    @Test fun changeDetectionUsesUtcDaysAndInvalidatesMetadataChanges() {
        val original = input(
            spans = listOf(DeviceSpan(start - 1_000, start + 1_000, DeviceState.IN_USE)),
            coverage = listOf((start - 1_000)..(start + 1_000)),
        )
        assertEquals(emptySet<LocalDate>(), SyncProjector.affectedDays(original, original.copy(now = original.now + 10_000)))
        assertEquals(setOf(day.minusDays(1), day), SyncProjector.affectedDays(original, original.copy(spans = emptyList())))
        assertNull(SyncProjector.affectedDays(original, original.copy(appNames = original.appNames + ("pkg" to "New"))))
        assertNull(SyncProjector.affectedDays(null, original))
    }

    @Test fun sourceTimeZoneIsMetadataNotUtcBoundary() {
        val payload = SyncProjector.project(input(
            spans = listOf(DeviceSpan(start, start + 100, DeviceState.IN_USE)),
            coverage = listOf(start..(start + 100)),
            zone = ZoneId.of("America/New_York"),
        ), day)
        assertEquals("America/New_York", payload.zoneId)
        assertEquals(start, payload.deviceIntervals.single().startMs)
        assertEquals("android.interactive-unlocked.v1", payload.captureProfile)
    }

    private fun input(
        spans: List<DeviceSpan> = emptyList(),
        sessions: List<Session> = emptyList(),
        coverage: List<LongRange> = emptyList(),
        hidden: Set<String> = emptySet(),
        names: Map<String, String> = emptyMap(),
        zone: ZoneId = ZoneId.of("Asia/Shanghai"),
        now: Long = end,
    ) = SyncProjectionInput(spans, sessions, coverage, hidden, names, zone, now)

    private fun at(value: String): Long = Instant.parse(value).toEpochMilli()
}
