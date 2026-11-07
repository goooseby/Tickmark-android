package com.goooseby.tickmark.engine

import com.goooseby.tickmark.engine.model.*
import com.goooseby.tickmark.engine.session.SessionEngine
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SessionEngineTest {
    private fun e(t: Long, kind: EventKind, pkg: String = "", cls: String = "A") = UsageEvent(t, kind, pkg, cls)
    private fun replay(vararg events: UsageEvent, until: Long = 100) = SessionEngine.reconstruct(events.toList(), until)
    @Test fun appSwitchHasNoDoubleCount() {
        val r = replay(e(0, EventKind.SCREEN_ON), e(0, EventKind.UNLOCK), e(0, EventKind.RESUME,"a"), e(10, EventKind.PAUSE,"a"), e(10, EventKind.RESUME,"b"), e(15, EventKind.PAUSE,"b"))
        assertEquals(listOf(10L, 5L), r.sessions.map { it.duration })
    }
    @Test fun delayedPauseOnlyClosesMatchingActivity() {
        val r = replay(e(0, EventKind.RESUME,"a","A"), e(10, EventKind.RESUME,"a","B"), e(12, EventKind.PAUSE,"a","A"), e(20, EventKind.PAUSE,"a","B"))
        assertEquals(1, r.sessions.size); assertEquals(20L, r.sessions.single().duration)
    }
    @Test fun screenOffEndsUsage() {
        val r = replay(e(0, EventKind.RESUME,"a"), e(20, EventKind.SCREEN_OFF))
        assertEquals(20L, r.sessions.single().duration)
        assertEquals(DeviceState.SCREEN_OFF, r.deviceSpans.last().state)
    }
    @Test fun latePauseDoesNotCreateGhost() {
        val r = replay(e(0, EventKind.RESUME,"a"), e(20, EventKind.SCREEN_OFF), e(25, EventKind.PAUSE,"a"))
        assertEquals(20L, r.sessions.single().duration)
    }
    @Test fun longSessionsStayIntact() {
        val r = replay(e(0, EventKind.RESUME,"a"), until = 3 * 3600000)
        assertEquals(10800000L, r.sessions.single().duration); assertTrue(r.sessions.single().ongoing)
    }
    @Test fun concurrencyUsesUnion() {
        val r = replay(e(0, EventKind.RESUME,"a"), e(0, EventKind.RESUME,"b"), until=10)
        assertEquals(20L, r.sessions.sumOf { it.duration })
        assertEquals(10L, unionDuration(r.sessions.map { it.start to it.end }))
    }
    @Test fun midnightKeepsOriginalSession() {
        val zone = ZoneId.of("Asia/Shanghai")
        val midnight = LocalDate.of(2026,11,8).atStartOfDay(zone).toInstant().toEpochMilli()
        val s = replay(e(midnight-900000, EventKind.RESUME,"a"), until=midnight+1800000).sessions.single()
        assertEquals(2700000L, s.duration)
        assertEquals(900000L, overlap(s.start,s.end,midnight-86400000,midnight))
        assertEquals(1800000L, overlap(s.start,s.end,midnight,midnight+86400000))
    }
    @Test fun restartClosesOldActivity() {
        val r = replay(e(0,EventKind.RESUME,"a"),e(10,EventKind.SHUTDOWN),e(20,EventKind.STARTUP))
        assertEquals(10L,r.sessions.single().duration)
    }
    @Test fun emptyInputStaysEmpty() { assertTrue(replay().deviceSpans.isEmpty()) }
    @Test fun lockTerminatesCurrentRound() {
        val r = replay(e(0, EventKind.SCREEN_ON),e(0,EventKind.UNLOCK),e(1,EventKind.RESUME,"a"),e(10,EventKind.LOCK))
        assertEquals(9L,r.sessions.single().duration)
    }
    @Test fun futureEventsAreIgnored() { assertTrue(replay(e(101,EventKind.RESUME,"a")).sessions.isEmpty()) }
    @Test fun unknownDeviceStateIsMarked() { assertTrue(replay(e(0,EventKind.RESUME,"a")).sessions.single().inferred) }
    @Test fun firstUnlockDoesNotInventEarlierUse() {
        val r = replay(e(10,EventKind.UNLOCK),e(20,EventKind.SCREEN_ON),e(30,EventKind.RESUME,"a"),until=40)
        assertEquals(DeviceState.UNKNOWN,r.deviceSpans.first().state)
        assertEquals(20L,r.deviceSpans.filter { it.state==DeviceState.IN_USE }.sumOf { it.end-it.start })
    }
    @Test fun deviceWithoutLockUsesExplicitScreenBasis() {
        val events=listOf(e(0,EventKind.SCREEN_ON),e(1,EventKind.RESUME,"a"),e(10,EventKind.SCREEN_OFF))
        val r=SessionEngine.reconstruct(events,20,screenOnly=true)
        assertEquals(10L,r.deviceSpans.filter { it.state==DeviceState.IN_USE }.sumOf { it.end-it.start })
        assertFalse(r.sessions.single().inferred)
        assertTrue(SessionEngine.reconstruct(events,20).sessions.single().inferred)
    }
    @Test fun startupKeepsScreenEventFromNewBootInsteadOfLosingIt() {
        val r=SessionEngine.reconstruct(listOf(e(0,EventKind.SHUTDOWN),e(100,EventKind.SCREEN_ON),e(110,EventKind.STARTUP),e(120,EventKind.RESUME,"a")),200,screenOnly=true)
        assertEquals(100L,r.deviceSpans.filter { it.state==DeviceState.IN_USE }.sumOf { it.end-it.start })
        assertFalse(r.sessions.single().inferred)
    }
    @Test fun startupWithoutShutdownDoesNotReusePreviousBootState() {
        val r=SessionEngine.reconstruct(listOf(e(0,EventKind.SCREEN_ON),e(10,EventKind.STARTUP)),20,screenOnly=true)
        assertEquals(DeviceState.UNKNOWN,r.deviceSpans.last().state)
    }
    @Test fun observationOnlyResolvesStateFromObservationTime() {
        val r=replay(e(0,EventKind.RESUME,"a"),e(50,EventKind.OBSERVE_SCREEN_ON),e(50,EventKind.OBSERVE_UNLOCKED),until=100)
        assertEquals(listOf(true,false),r.sessions.map { it.inferred })
        assertEquals(listOf(50L,50L),r.sessions.map { it.duration })
    }
    @Test fun previousBootActivitiesCannotLeakAcrossShutdown() {
        val r=SessionEngine.reconstruct(listOf(e(0,EventKind.RESUME,"old"),e(10,EventKind.SHUTDOWN),e(100,EventKind.SCREEN_ON),e(110,EventKind.STARTUP)),200,screenOnly=true)
        assertEquals(10L,r.sessions.single().duration)
    }
    @Test fun observedBootStopsOldDeviceAndForegroundAcrossGap() {
        val r=SessionEngine.reconstruct(listOf(e(0,EventKind.SCREEN_ON),e(10,EventKind.RESUME,"old"),e(1000,EventKind.OBSERVE_BOOT),e(1010,EventKind.SCREEN_ON),e(1020,EventKind.RESUME,"new")),1100,screenOnly=true)
        assertTrue(r.sessions.none { it.packageName=="old" && it.end>10 })
        assertEquals(DeviceState.UNKNOWN,r.deviceSpans.find {it.start==10L}?.state)
        assertEquals(100L,r.deviceSpans.filter {it.state==DeviceState.IN_USE}.sumOf {it.end-it.start})
    }
    @Test fun sameMillisecondOrderIsPreserved() {
        val r=replay(e(0,EventKind.RESUME,"a"),e(10,EventKind.PAUSE,"a"),e(10,EventKind.RESUME,"a"),until=20)
        assertEquals(20L,unionDuration(r.sessions.map { it.start to it.end }))
    }
    @Test fun dailyProjectionHandlesDaylightSaving() {
        val zone=ZoneId.of("America/New_York")
        val date=LocalDate.of(2026,11,1)
        val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(25*3600000L,end-start)
        assertEquals(end-start,overlap(start-1000,end+1000,start,end))
    }
    @Test fun unionAndClippingRemainAdditiveForRandomIntervals() {
        val random=kotlin.random.Random(42)
        repeat(200) {
            val intervals=List(20) { val a=random.nextLong(0,1000); a to a+random.nextLong(1,1000) }
            val whole=unionDuration(intervals.map { maxOf(it.first,0) to minOf(it.second,2000) })
            val left=unionDuration(intervals.map { maxOf(it.first,0) to minOf(it.second,750) })
            val right=unionDuration(intervals.map { maxOf(it.first,750) to minOf(it.second,2000) })
            assertEquals(whole,left+right)
            assertTrue(whole in 0..2000)
        }
    }
}
