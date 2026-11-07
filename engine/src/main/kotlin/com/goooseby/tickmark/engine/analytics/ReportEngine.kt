package com.goooseby.tickmark.engine.analytics

import com.goooseby.tickmark.engine.model.Session
import com.goooseby.tickmark.engine.model.unionDuration
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class TimeBucket(val start: Long, val end: Long, val duration: Long)

/** Buckets clip the same millisecond intervals used by details and exports. */
object ReportEngine {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun weekBoundaries(date: LocalDate, zone: ZoneId): List<Long> {
        val monday = weekStart(date)
        return (0..7).map { monday.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
    }
    fun buckets(intervals: List<Pair<Long, Long>>, boundaries: List<Long>): List<TimeBucket> {
        require(boundaries.zipWithNext().all { (a,b) -> b > a })
        return boundaries.zipWithNext().map { (from,to) ->
            TimeBucket(from,to,unionDuration(intervals.map { maxOf(it.first,from) to minOf(it.second,to) }))
        }
    }
    fun composition(sessions: List<Session>, from: Long, to: Long): List<Pair<String,Long>> =
        sessions.groupBy { it.packageName }.mapValues { (_, items) ->
            unionDuration(items.map { maxOf(it.start,from) to minOf(it.end,to) })
        }.filterValues { it > 0 }.toList().sortedByDescending { it.second }
}
