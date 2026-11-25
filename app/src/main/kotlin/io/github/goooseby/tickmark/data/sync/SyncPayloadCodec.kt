package io.github.goooseby.tickmark.data.sync

import io.github.goooseby.tickmark.engine.sync.ANDROID_CAPTURE_PROFILE
import io.github.goooseby.tickmark.engine.sync.SyncAppInterval
import io.github.goooseby.tickmark.engine.sync.SyncDayPayload
import io.github.goooseby.tickmark.engine.sync.SyncDeviceInterval
import io.github.goooseby.tickmark.engine.sync.SyncDeviceState
import io.github.goooseby.tickmark.engine.sync.SyncSessionQuality
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

object SyncPayloadCodec {
    fun encode(payload: SyncDayPayload): String {
        val deviceIntervals = JSONArray()
        payload.deviceIntervals.forEach { interval ->
            deviceIntervals.put(JSONObject().apply {
                put("startMs", interval.startMs)
                put("endMs", interval.endMs)
                put("state", interval.state.name.lowercase())
                interval.reason?.let { put("reason", it) }
            })
        }
        val appIntervals = JSONArray()
        payload.appIntervals.forEach { interval ->
            appIntervals.put(JSONObject().apply {
                put("startMs", interval.startMs)
                put("endMs", interval.endMs)
                put("appId", interval.appId)
                put("quality", interval.quality.name.lowercase())
                interval.displayName?.let { put("displayName", it) }
            })
        }
        return JSONObject().apply {
            put("zoneId", payload.zoneId)
            put("captureProfile", payload.captureProfile)
            put("deviceIntervals", deviceIntervals)
            put("appIntervals", appIntervals)
        }.toString()
    }

    fun decode(text: String, day: LocalDate): SyncDayPayload {
        require(text.length <= 1_000_000) { "同步分片过大" }
        val json = JSONObject(text)
        val zoneId = json.getString("zoneId")
        require(zoneId.length in 1..64) { "同步时区无效" }
        runCatching { ZoneId.of(zoneId) }.getOrElse { throw IllegalArgumentException("同步时区无效") }
        val captureProfile = json.getString("captureProfile")
        require(captureProfile.length in 1..128) { "同步采集口径无效" }
        val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val deviceIntervals = json.getJSONArray("deviceIntervals").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val interval = SyncDeviceInterval(
                    startMs = item.getLong("startMs"),
                    endMs = item.getLong("endMs"),
                    state = enumValue(item.getString("state")),
                    reason = item.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                )
                validateInterval(interval.startMs, interval.endMs, start, end)
                when (interval.state) {
                    SyncDeviceState.ACTIVE -> require(interval.reason == null) { "活跃区间原因无效" }
                    SyncDeviceState.IDLE -> require(interval.reason == null) { "暂停区间原因无效" }
                    SyncDeviceState.OFFLINE -> require(interval.reason in setOf("locked", "screen_off")) { "离线区间原因无效" }
                    SyncDeviceState.GAP -> require(interval.reason == "unknown") { "缺口区间原因无效" }
                }
                interval
            }.sortedBy { it.startMs }
        }
        require(deviceIntervals.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }) { "设备区间重叠" }
        val appIntervals = json.getJSONArray("appIntervals").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val appId = item.getString("appId")
                require(appId.length in 1..255 && !appId.startsWith("@") && appId.none(Char::isWhitespace)) { "应用标识无效" }
                val displayName = item.optString("displayName").takeIf { it.isNotBlank() && it != "null" }
                require(displayName == null || displayName.length <= 255) { "应用名称无效" }
                val interval = SyncAppInterval(
                    startMs = item.getLong("startMs"),
                    endMs = item.getLong("endMs"),
                    appId = appId,
                    quality = enumValue(item.getString("quality")),
                    displayName = displayName,
                )
                validateInterval(interval.startMs, interval.endMs, start, end)
                interval
            }.sortedWith(compareBy({ it.appId }, { it.startMs }))
        }
        require(appIntervals.groupBy { it.appId }.values.all { rows -> rows.zipWithNext().all { (a, b) -> a.endMs <= b.startMs } }) { "同应用区间重叠" }
        return SyncDayPayload(day, zoneId, captureProfile, deviceIntervals, appIntervals)
    }

    private fun validateInterval(start: Long, end: Long, dayStart: Long, dayEnd: Long) {
        require(start >= dayStart && end <= dayEnd && end > start) { "同步区间边界无效" }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValues<T>().singleOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("同步枚举无效")
}
