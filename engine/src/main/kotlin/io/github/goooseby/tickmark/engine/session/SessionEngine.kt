package io.github.goooseby.tickmark.engine.session

import io.github.goooseby.tickmark.engine.model.*

/** Replay is deterministic; gaps with unknown state remain explicit. */
object SessionEngine {
    fun reconstruct(events: List<UsageEvent>, until: Long, screenOnly: Boolean = false): Reconstruction {
        val ordered = events.withIndex().filter { it.value.time <= until }
            .sortedWith(compareBy({ it.value.time }, { it.index })).map { it.value }
        if (ordered.isEmpty()) return Reconstruction(emptyList(), emptyList())
        val active = mutableMapOf<String, UsageEvent>()
        val pieces = mutableListOf<Session>()
        val spans = mutableListOf<DeviceSpan>()
        var screen: Boolean? = null
        var unlocked: Boolean? = null
        var pendingStartup = false
        var shutdownTime = Long.MIN_VALUE
        var screenObservedAfterShutdown = false
        var keyguardObservedAfterShutdown = false
        var previous = ordered.first().time
        fun state(): DeviceState = when {
            screen == false -> DeviceState.SCREEN_OFF
            unlocked == false -> DeviceState.LOCKED
            screen == true && (unlocked == true || screenOnly) -> DeviceState.IN_USE
            else -> DeviceState.UNKNOWN
        }
        fun append(to: Long, ongoing: Boolean = false) {
            if (to <= previous) return
            val deviceState = state()
            val last = spans.lastOrNull()
            if (last != null && last.end == previous && last.state == deviceState)
                spans[spans.lastIndex] = last.copy(end = to)
            else spans += DeviceSpan(previous, to, deviceState)
            if (screen != false && unlocked != false) {
                active.values.map { it.packageName }.distinct().forEach { pkg ->
                    pieces += Session(pkg, previous, to, deviceState == DeviceState.UNKNOWN, ongoing)
                }
            }
        }
        for (event in ordered) {
            if((event.kind==EventKind.STARTUP && !pendingStartup) || event.kind==EventKind.OBSERVE_BOOT) {
                // With no shutdown boundary, the previous event is the last reliable endpoint.
                // Never extend old foreground activity across an unobserved reboot gap.
                active.clear(); screen=null; unlocked=null
            }
            append(event.time)
            val key = "${event.packageName}/${event.activity}/${event.instance}"
            when (event.kind) {
                EventKind.RESUME -> active[key] = event
                EventKind.PAUSE, EventKind.STOP -> {
                    if (event.instance != 0) active.remove(key)
                    else active.entries.removeAll { it.value.packageName == event.packageName && it.value.activity == event.activity }
                }
                EventKind.SCREEN_ON, EventKind.OBSERVE_SCREEN_ON -> { screen = true; if(pendingStartup && event.time>shutdownTime) screenObservedAfterShutdown=true }
                EventKind.SCREEN_OFF, EventKind.OBSERVE_SCREEN_OFF -> { screen = false; active.clear(); if(pendingStartup && event.time>shutdownTime) screenObservedAfterShutdown=true }
                EventKind.UNLOCK, EventKind.OBSERVE_UNLOCKED -> { unlocked = true; if(pendingStartup && event.time>shutdownTime) keyguardObservedAfterShutdown=true }
                EventKind.LOCK, EventKind.OBSERVE_LOCKED -> { unlocked = false; active.clear(); if(pendingStartup && event.time>shutdownTime) keyguardObservedAfterShutdown=true }
                EventKind.SHUTDOWN -> {
                    active.clear(); screen = null; unlocked = null
                    pendingStartup=true; shutdownTime=event.time
                    screenObservedAfterShutdown=false; keyguardObservedAfterShutdown=false
                }
                EventKind.STARTUP -> {
                    // Android inserts STARTUP when its usage service initializes, which can be
                    // after queued screen events. Preserve independent post-shutdown evidence.
                    if(!pendingStartup) active.clear()
                    if(!pendingStartup || !screenObservedAfterShutdown) screen=null
                    if(!pendingStartup || !keyguardObservedAfterShutdown) unlocked=null
                    pendingStartup=false
                }
                EventKind.OBSERVE_BOOT -> { active.clear(); screen=null; unlocked=null; pendingStartup=false }
            }
            previous = event.time
        }
        append(until, ongoing = true)
        // Merge only contiguous pieces; never invent time across another app or a lock boundary.
        val merged = mutableListOf<Session>()
        for ((_, group) in pieces.groupBy { it.packageName }) {
            var current: Session? = null
            for (piece in group) {
                val old = current
                if (old != null && old.end == piece.start && old.inferred == piece.inferred) {
                    current = old.copy(end = piece.end, ongoing = piece.ongoing)
                } else {
                    if (old != null) merged += old
                    current = piece
                }
            }
            current?.let { merged += it }
        }
        return Reconstruction(merged.sortedWith(compareBy({ it.start }, { it.packageName })), spans)
    }
}
