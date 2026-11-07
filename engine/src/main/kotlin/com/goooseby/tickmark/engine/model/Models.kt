package com.goooseby.tickmark.engine.model

enum class EventKind { RESUME, PAUSE, STOP, SCREEN_ON, SCREEN_OFF, UNLOCK, LOCK, STARTUP, SHUTDOWN, OBSERVE_BOOT, OBSERVE_SCREEN_ON, OBSERVE_SCREEN_OFF, OBSERVE_UNLOCKED, OBSERVE_LOCKED }

data class UsageEvent(val time: Long, val kind: EventKind, val packageName: String = "", val activity: String = "", val instance: Int = 0)
data class Session(val packageName: String, val start: Long, val end: Long, val inferred: Boolean = false, val ongoing: Boolean = false) {
    val duration: Long get() = (end - start).coerceAtLeast(0)
}
enum class DeviceState { IN_USE, LOCKED, SCREEN_OFF, UNKNOWN }
data class DeviceSpan(val start: Long, val end: Long, val state: DeviceState)
data class Reconstruction(val sessions: List<Session>, val deviceSpans: List<DeviceSpan>)

fun overlap(start: Long, end: Long, from: Long, to: Long): Long = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)

fun unionDuration(intervals: List<Pair<Long, Long>>): Long {
    var total = 0L
    var end = Long.MIN_VALUE
    for ((start, stop) in intervals.filter { it.second > it.first }.sortedBy { it.first }) {
        total += (stop - maxOf(start, end)).coerceAtLeast(0)
        end = maxOf(end, stop)
    }
    return total
}
