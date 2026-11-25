package io.github.goooseby.tickmark.engine.sync

data class SyncDeviceActivity(
    val deviceId: String,
    val intervals: List<Pair<Long, Long>>,
)

data class SyncActivitySummary(
    val perDevice: Map<String, Long>,
    val combined: Long,
    val overlap: Long,
)

object SyncReportEngine {
    fun summarize(source: List<SyncDeviceActivity>, from: Long, to: Long, now: Long = to): SyncActivitySummary {
        val until = minOf(to, now).coerceAtLeast(from)
        if (until == from) return SyncActivitySummary(source.associate { it.deviceId to 0L }, 0, 0)
        val perDevice = source.associate { device ->
            device.deviceId to merge(device.intervals, from, until).sumOf { it.second - it.first }
        }
        val intervals = source.flatMap { merge(it.intervals, from, until) }
        if (intervals.isEmpty()) return SyncActivitySummary(perDevice, 0, 0)
        data class Edge(val time: Long, val delta: Int)
        val edges = intervals.flatMap { (start, end) -> listOf(Edge(start, 1), Edge(end, -1)) }
            .groupBy { it.time }.toSortedMap().entries.toList()
        var active = 0
        var combined = 0L
        var overlap = 0L
        for (index in 0 until edges.lastIndex) {
            val (time, deltas) = edges[index]
            deltas.forEach { active += it.delta }
            val duration = edges[index + 1].key - time
            if (active >= 1) combined += duration
            if (active >= 2) overlap += duration
        }
        return SyncActivitySummary(perDevice, combined, overlap)
    }

    private fun merge(source: List<Pair<Long, Long>>, from: Long, to: Long): List<Pair<Long, Long>> =
        source.map { (start, end) -> maxOf(start, from) to minOf(end, to) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
            .fold(mutableListOf<Pair<Long, Long>>()) { result, interval ->
                val previous = result.lastOrNull()
                if (previous != null && interval.first <= previous.second) {
                    result[result.lastIndex] = previous.first to maxOf(previous.second, interval.second)
                } else result += interval
                result
            }
}
