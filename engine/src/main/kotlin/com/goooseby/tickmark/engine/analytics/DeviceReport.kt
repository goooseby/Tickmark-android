package com.goooseby.tickmark.engine.analytics

import com.goooseby.tickmark.engine.model.DeviceSpan
import com.goooseby.tickmark.engine.model.DeviceState
import java.time.LocalDate
import java.time.ZoneId

data class AwayPeriod(
    val start: Long,
    val end: Long,
    val originalStart: Long,
    val screenOffDuration: Long,
    val lockedDuration: Long,
) {
    val duration: Long get() = end - start
}

data class DeviceReport(
    val from: Long,
    val until: Long,
    val used: Long,
    val screenOff: Long,
    val locked: Long,
    val unknown: Long,
    val away: List<AwayPeriod>,
    val spans: List<DeviceSpan>,
) {
    val notUsed: Long get() = screenOff + locked
    val longestAway: AwayPeriod? get() = away.maxByOrNull { it.duration }
    val latestAway: AwayPeriod? get() = away.lastOrNull()
}

/** One canonical, non-overlapping projection for reports and home-screen widgets. */
object DeviceReportEngine {
    fun day(spans: List<DeviceSpan>, date: LocalDate, zone: ZoneId, now: Long): DeviceReport =
        project(spans, date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)

    fun project(source: List<DeviceSpan>, from: Long, to: Long, now: Long = to): DeviceReport {
        val until = minOf(to, now).coerceAtLeast(from)
        if (until == from) return DeviceReport(from, until, 0, 0, 0, 0, emptyList(), emptyList())
        // Sweep also makes imported overlapping/conflicting state explicit instead of double counting.
        data class Edge(val time: Long, val state: DeviceState, val delta: Int)
        val contextFrom = minOf(from, source.minOfOrNull { it.start } ?: from)
        val edges = mutableListOf(Edge(contextFrom, DeviceState.UNKNOWN, 0), Edge(until, DeviceState.UNKNOWN, 0))
        source.filter { it.end > contextFrom && it.start < until }.forEach {
            edges += Edge(maxOf(it.start, contextFrom), it.state, 1)
            edges += Edge(minOf(it.end, until), it.state, -1)
        }
        val groups = edges.groupBy { it.time }.toSortedMap().entries.toList()
        val active = mutableMapOf<DeviceState, Int>()
        val segments = mutableListOf<DeviceSpan>()
        for (i in 0 until groups.lastIndex) {
            groups[i].value.forEach { edge ->
                if (edge.delta != 0) {
                    val count = (active[edge.state] ?: 0) + edge.delta
                    if (count == 0) active.remove(edge.state) else active[edge.state] = count
                }
            }
            val state = active.keys.singleOrNull() ?: DeviceState.UNKNOWN
            val a = groups[i].key
            val b = groups[i + 1].key
            val previous = segments.lastOrNull()
            if (previous != null && previous.end == a && previous.state == state)
                segments[segments.lastIndex] = previous.copy(end = b)
            else segments += DeviceSpan(a, b, state)
        }
        val away = mutableListOf<AwayPeriod>()
        var originalStart: Long? = null
        for (segment in segments) {
            if (segment.state != DeviceState.SCREEN_OFF && segment.state != DeviceState.LOCKED) {
                originalStart = null
                continue
            }
            if (originalStart == null) originalStart = segment.start
            if (segment.end <= from) continue
            val start = maxOf(segment.start, from)
            val off = if (segment.state == DeviceState.SCREEN_OFF) segment.end - start else 0
            val locked = if (segment.state == DeviceState.LOCKED) segment.end - start else 0
            val last = away.lastOrNull()
            if (last != null && last.end == start) {
                away[away.lastIndex] = last.copy(end = segment.end,
                    screenOffDuration = last.screenOffDuration + off, lockedDuration = last.lockedDuration + locked)
            } else away += AwayPeriod(start, segment.end, originalStart, off, locked)
        }
        val visible = segments.filter { it.end > from }.map { it.copy(start = maxOf(it.start, from)) }
        fun total(state: DeviceState) = visible.filter { it.state == state }.sumOf { it.end - it.start }
        return DeviceReport(from, until, total(DeviceState.IN_USE), total(DeviceState.SCREEN_OFF),
            total(DeviceState.LOCKED), total(DeviceState.UNKNOWN), away, visible)
    }
}
