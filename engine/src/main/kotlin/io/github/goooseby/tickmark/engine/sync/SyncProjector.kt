package io.github.goooseby.tickmark.engine.sync

import io.github.goooseby.tickmark.engine.analytics.DeviceReportEngine
import io.github.goooseby.tickmark.engine.model.DeviceState
import io.github.goooseby.tickmark.engine.model.DeviceSpan
import io.github.goooseby.tickmark.engine.model.Session
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object SyncProjector {
    fun project(input: SyncProjectionInput, day: LocalDate, notBeforeMs: Long = Long.MIN_VALUE): SyncDayPayload {
        val dayStart = maxOf(utcStart(day), notBeforeMs)
        val dayEnd = utcStart(day.plusDays(1))
        val until = minOf(dayEnd, input.now)
        val coverage = clipCoverage(input.coverage, dayStart, until)
        val deviceIntervals = if (coverage.isEmpty()) emptyList() else {
            val source = input.spans.filter { it.end > it.start && it.start < input.now }
            val from = source.minOfOrNull { it.start } ?: dayStart
            val to = minOf(source.maxOfOrNull { it.end } ?: dayEnd, input.now)
            val normalized = if (from < to) DeviceReportEngine.project(source, from, to, input.now).spans else emptyList()
            normalized.flatMap { span -> clipCoverage(coverage, span.start, span.end).map { range ->
                val start = range.first
                val end = range.last + 1
                val state = when (span.state) {
                    DeviceState.IN_USE -> SyncDeviceState.ACTIVE
                    DeviceState.LOCKED, DeviceState.SCREEN_OFF -> SyncDeviceState.OFFLINE
                    DeviceState.UNKNOWN -> SyncDeviceState.GAP
                }
                SyncDeviceInterval(start, end, state, when (span.state) {
                    DeviceState.IN_USE -> null
                    DeviceState.LOCKED -> "locked"
                    DeviceState.SCREEN_OFF -> "screen_off"
                    DeviceState.UNKNOWN -> "unknown"
                })
            } }
        }
        val appIntervals = if (coverage.isEmpty()) emptyList() else {
            input.sessions.asSequence()
                .filter { it.packageName.isNotBlank() && !it.packageName.startsWith("@") && it.packageName !in input.hiddenPackages }
                .flatMap { session -> clipCoverage(coverage, session.start, minOf(session.end, input.now)).map { range ->
                    session.copy(start = range.first, end = range.last + 1)
                } }
                .groupBy { it.packageName }
                .toSortedMap()
                .values.flatMap { sessions -> normalizeSessions(sessions, input.appNames) }
                .sortedWith(compareBy({ it.startMs }, { it.appId }))
        }
        return SyncDayPayload(
            utcDate = day,
            zoneId = input.sourceTimeZone.id,
            deviceIntervals = mergeDeviceIntervals(deviceIntervals),
            appIntervals = appIntervals,
        )
    }

    fun utcDays(from: LocalDate, through: LocalDate?): List<LocalDate> {
        if (through != null && through < from) return emptyList()
        val last = through ?: LocalDate.now(ZoneOffset.UTC)
        val days = mutableListOf<LocalDate>()
        var day = from
        while (day <= last) {
            days += day
            day = day.plusDays(1)
        }
        return days
    }

    fun utcDay(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()

    fun affectedDays(previous: SyncProjectionInput?, current: SyncProjectionInput): Set<LocalDate>? {
        if (previous == null) return null
        if (previous.sourceTimeZone.id != current.sourceTimeZone.id ||
            previous.appNames != current.appNames || previous.hiddenPackages != current.hiddenPackages
        ) return null
        val days = mutableSetOf<LocalDate>()
        fun add(start: Long, end: Long) {
            if (end <= start) return
            var day = utcDay(start)
            val last = utcDay((end - 1).coerceAtLeast(start))
            while (day <= last) {
                days += day
                day = day.plusDays(1)
            }
        }
        changedItems(previous.spans, current.spans).forEach { add(it.start, it.end) }
        changedItems(previous.sessions, current.sessions).forEach { add(it.start, it.end) }
        changedItems(previous.coverage, current.coverage).forEach { add(it.first, it.last + 1) }
        return days
    }

    private fun normalizeSessions(sessions: List<Session>, names: Map<String, String>): List<SyncAppInterval> {
        data class Edge(val time: Long, val observed: Int, val inferred: Int)
        val edges = mutableListOf<Edge>()
        sessions.forEach { session ->
            val quality = if (session.inferred) SyncSessionQuality.INFERRED else SyncSessionQuality.OBSERVED
            val observed = if (quality == SyncSessionQuality.OBSERVED) 1 else 0
            val inferred = if (quality == SyncSessionQuality.INFERRED) 1 else 0
            edges += Edge(session.start, observed, inferred)
            edges += Edge(session.end, -observed, -inferred)
        }
        val groups = edges.groupBy { it.time }.toSortedMap()
        val entries = groups.entries.toList()
        var observed = 0
        var inferred = 0
        val result = mutableListOf<SyncAppInterval>()
        for (index in 0 until entries.lastIndex) {
            val (start, deltas) = entries[index]
            deltas.forEach { observed += it.observed; inferred += it.inferred }
            val end = entries[index + 1].key
            if (observed <= 0 && inferred <= 0) continue
            val quality = if (observed > 0) SyncSessionQuality.OBSERVED else SyncSessionQuality.INFERRED
            val appId = sessions.first().packageName
            val previous = result.lastOrNull()
            if (previous != null && previous.endMs == start && previous.quality == quality) {
                result[result.lastIndex] = previous.copy(endMs = end)
            } else {
                result += SyncAppInterval(start, end, appId, quality, names[appId] ?: appId)
            }
        }
        return result
    }

    private fun mergeDeviceIntervals(source: List<SyncDeviceInterval>): List<SyncDeviceInterval> {
        val result = mutableListOf<SyncDeviceInterval>()
        source.sortedWith(compareBy({ it.startMs }, { it.endMs })).forEach { interval ->
            val previous = result.lastOrNull()
            if (previous != null && previous.endMs == interval.startMs && previous.state == interval.state && previous.reason == interval.reason) {
                result[result.lastIndex] = previous.copy(endMs = interval.endMs)
            } else result += interval
        }
        return result
    }

    private fun clipCoverage(coverage: List<LongRange>, from: Long, to: Long): List<LongRange> {
        if (to <= from) return emptyList()
        return coverage.filter { it.last >= from && it.first < to }
            .map { maxOf(it.first, from) until minOf(it.last + 1, to) }
            .filter { it.last >= it.first }
            .sortedBy { it.first }
            .fold(mutableListOf<LongRange>()) { merged, interval ->
                val previous = merged.lastOrNull()
                if (previous != null && interval.first <= previous.last + 1) {
                    merged[merged.lastIndex] = previous.first until maxOf(previous.last, interval.last) + 1
                } else merged += interval
                merged
            }
    }

    private fun <T> changedItems(previous: List<T>, current: List<T>): List<T> {
        val oldCounts = previous.groupingBy { it }.eachCount()
        val newCounts = current.groupingBy { it }.eachCount()
        val changed = (oldCounts.keys + newCounts.keys).filter { oldCounts[it] != newCounts[it] }.toSet()
        return previous.filter { it in changed } + current.filter { it in changed }
    }

    private fun utcStart(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
