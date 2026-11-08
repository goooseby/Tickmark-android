package io.github.goooseby.tickmark.engine

import io.github.goooseby.tickmark.engine.analytics.DeviceReportEngine
import io.github.goooseby.tickmark.engine.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DeviceReportTest {
    @Test fun adjacentOffAndLockedFormOnePeriodWithSeparateContributions() {
        val r = DeviceReportEngine.project(listOf(DeviceSpan(0,100,DeviceState.SCREEN_OFF),DeviceSpan(100,150,DeviceState.LOCKED),DeviceSpan(150,200,DeviceState.IN_USE)),0,200)
        assertEquals(1,r.away.size);assertEquals(150L,r.notUsed)
        assertEquals(100L,r.away.single().screenOffDuration);assertEquals(50L,r.away.single().lockedDuration)
    }
    @Test fun missingAndUnknownBreakAwayPeriods() {
        val r=DeviceReportEngine.project(listOf(DeviceSpan(0,100,DeviceState.SCREEN_OFF),DeviceSpan(120,200,DeviceState.SCREEN_OFF),DeviceSpan(200,250,DeviceState.UNKNOWN),DeviceSpan(250,300,DeviceState.LOCKED)),0,300)
        assertEquals(3,r.away.size);assertEquals(70L,r.unknown)
        assertEquals(300L,r.used+r.notUsed+r.unknown)
    }
    @Test fun duplicateStatesDoNotInflateAndConflictsStayUnknown() {
        val r=DeviceReportEngine.project(listOf(DeviceSpan(0,100,DeviceState.SCREEN_OFF),DeviceSpan(0,100,DeviceState.SCREEN_OFF),DeviceSpan(50,80,DeviceState.IN_USE)),0,100)
        assertEquals(70L,r.screenOff);assertEquals(30L,r.unknown)
    }
    @Test fun midnightClipsDurationButRetainsOriginalStart() {
        val r=DeviceReportEngine.project(listOf(DeviceSpan(0,200,DeviceState.SCREEN_OFF)),100,300,150)
        assertEquals(50L,r.away.single().duration);assertEquals(0L,r.away.single().originalStart)
        assertEquals(150L,r.until)
    }
    @Test fun emptyAndFutureRangesNeverBecomeNotUsed() {
        val empty=DeviceReportEngine.project(emptyList(),0,100)
        assertEquals(100L,empty.unknown);assertEquals(0L,empty.notUsed)
        val future=DeviceReportEngine.project(emptyList(),200,300,100)
        assertEquals(0L,future.unknown);assertTrue(future.away.isEmpty())
    }
    @Test fun daylightSavingDayUsesActualLocalBoundaries() {
        val date=LocalDate.of(2026,11,1);val zone=ZoneId.of("America/New_York")
        val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val r=DeviceReportEngine.day(listOf(DeviceSpan(start,end,DeviceState.SCREEN_OFF)),date,zone,end)
        assertEquals(25*3_600_000L,r.notUsed)
    }
    @Test fun originalStartCannotCrossAnEarlierConflictingState() {
        val r=DeviceReportEngine.project(listOf(DeviceSpan(0,200,DeviceState.SCREEN_OFF),DeviceSpan(50,80,DeviceState.IN_USE)),100,200)
        assertEquals(80L,r.away.single().originalStart)
    }
    @Test fun randomImportedStateSetsAlwaysPartitionElapsedTime() {
        val random=kotlin.random.Random(7)
        repeat(150) {
            val source=List(30) { val a=random.nextLong(0,900);DeviceSpan(a,a+random.nextLong(1,300),DeviceState.entries[random.nextInt(4)]) }
            val r=DeviceReportEngine.project(source,100,1000,850)
            assertEquals(750L,r.used+r.notUsed+r.unknown)
            assertEquals(r.notUsed,r.away.sumOf{it.duration})
            assertTrue(r.away.zipWithNext().all{(a,b)->a.end<b.start})
        }
    }
}
