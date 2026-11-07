package com.goooseby.tickmark.engine

import com.goooseby.tickmark.engine.analytics.ReportEngine
import com.goooseby.tickmark.engine.model.Session
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReportEngineTest {
    @Test fun frequentSwitchesAggregateWithoutLosingTime() {
        val sessions=(0 until 120).map { Session(if(it%2==0) "a" else "b",it*30_000L,(it+1)*30_000L) }
        val composition=ReportEngine.composition(sessions,0,3_600_000)
        assertEquals(listOf("a" to 1_800_000L,"b" to 1_800_000L),composition)
        assertEquals(3_600_000L,ReportEngine.buckets(sessions.map { it.start to it.end },listOf(0,3_600_000)).single().duration)
    }
    @Test fun bucketBoundariesSplitAndDoNotDoubleCountConcurrency() {
        val buckets=ReportEngine.buckets(listOf(500L to 2500L,1000L to 2000L),listOf(0,1000,2000,3000))
        assertEquals(listOf(500L,1000L,500L),buckets.map { it.duration })
        assertEquals(2000L,buckets.sumOf { it.duration })
    }
    @Test fun missingAndFutureBinsStayZero() {
        assertEquals(listOf(0L,200L,0L),ReportEngine.buckets(listOf(1100L to 1300L),listOf(0,1000,2000,3000)).map { it.duration })
    }
    @Test fun naturalWeekStartsMondayAndCrossesYear() {
        assertEquals(LocalDate.of(2026,12,28),ReportEngine.weekStart(LocalDate.of(2027,1,3)))
        assertEquals(LocalDate.of(2027,1,4),ReportEngine.weekStart(LocalDate.of(2027,1,4)))
    }
    @Test fun dailyContributionsSumToWeekAcrossDst() {
        val zone=ZoneId.of("America/New_York")
        val bounds=ReportEngine.weekBoundaries(LocalDate.of(2026,11,1),zone)
        assertEquals(169*3_600_000L,bounds.last()-bounds.first())
        val intervals=listOf(bounds.first()-1000 to bounds[2]+500,bounds[2]-200 to bounds.last()+1000)
        assertEquals(bounds.last()-bounds.first(),ReportEngine.buckets(intervals,bounds).sumOf { it.duration })
    }
}
