package io.github.goooseby.tickmark.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

class DataTransfer(private val repo: TickRepository) {
    private fun obj(vararg values: Pair<String, Any>) = JSONObject().apply { values.forEach { put(it.first, it.second) } }
    private fun <T> array(rows: List<T>, block: (T) -> JSONObject) = JSONArray().apply { rows.forEach { put(block(it)) } }
    suspend fun backup(): String = repo.exclusive {
        val d = repo.dao
        obj("schemaVersion" to 1, "algorithmVersion" to 2, "zoneId" to ZoneId.systemDefault().id,
            "createdAt" to System.currentTimeMillis(),
            "events" to array(d.events()) { obj("id" to it.id, "time" to it.time, "kind" to it.kind, "pkg" to it.pkg, "activity" to it.activity, "instance" to it.instance, "sequence" to it.sequence) },
            "sessions" to array(d.allSessions()) { obj("id" to it.id, "pkg" to it.pkg, "start" to it.start, "end" to it.end, "inferred" to it.inferred, "ongoing" to it.ongoing) },
            "spans" to array(d.allSpans()) { obj("start" to it.start, "end" to it.end, "state" to it.state) },
            "annotations" to array(d.allAnnotations()) { obj("id" to it.id, "start" to it.start, "end" to it.end, "purpose" to it.purpose, "note" to it.note) },
            "apps" to array(d.allApps()) { obj("pkg" to it.pkg, "name" to it.name, "hidden" to it.hidden, "excluded" to it.excluded, "purpose" to it.purpose) },
            "coverage" to array(d.allCoverage()) { obj("start" to it.start, "end" to it.end, "eventCount" to it.eventCount) },
            "config" to array(d.allConfig()) { obj("key" to it.key, "value" to it.value) }).toString(2)
    }
    data class Restore(val events: List<EventRow>, val sessions: List<SessionRow>, val spans: List<SpanRow>, val annotations: List<AnnotationRow>, val apps: List<AppRow>, val coverage: List<CoverageRow>, val config: List<ConfigRow>, val zone: String)
    fun parse(text: String): Restore {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1 && root.getInt("algorithmVersion") in 1..2) { "不支持此备份版本" }
        fun <T> read(key: String, block: (JSONObject) -> T): List<T> {
            val a = root.getJSONArray(key)
            require(a.length() <= 1_000_000) { "备份记录过多" }
            return (0 until a.length()).map { block(a.getJSONObject(it)) }
        }
        fun valid(start: Long, end: Long) { require(start >= 0 && end > start) { "备份包含无效时间区间" } }
        val result = Restore(
            read("events") { val kind = it.getString("kind"); io.github.goooseby.tickmark.engine.model.EventKind.valueOf(kind)
                EventRow(it.getString("id"), it.getLong("time"), kind, it.getString("pkg"), it.getString("activity"), it.getInt("instance"), it.getInt("sequence")) },
            read("sessions") { SessionRow(it.getString("id"), it.getString("pkg"), it.getLong("start"), it.getLong("end"), it.getBoolean("inferred"), it.getBoolean("ongoing")) },
            read("spans") { val state = it.getString("state"); io.github.goooseby.tickmark.engine.model.DeviceState.valueOf(state); SpanRow(it.getLong("start"), it.getLong("end"), state) },
            read("annotations") { AnnotationRow(it.getString("id"), it.getLong("start"), it.getLong("end"), it.getString("purpose"), it.getString("note")) },
            read("apps") { AppRow(it.getString("pkg"), it.getString("name"), it.getBoolean("hidden"), it.getBoolean("excluded"), it.getString("purpose")) },
            read("coverage") { CoverageRow(it.getLong("start"), it.getLong("end"), it.getInt("eventCount")) },
            read("config") { ConfigRow(it.getString("key"), it.getString("value")) }, root.getString("zoneId"))
        ZoneId.of(result.zone)
        result.sessions.forEach { valid(it.start, it.end) }
        result.spans.forEach { valid(it.start, it.end) }
        result.coverage.forEach { valid(it.start, it.end); require(it.eventCount >= 0) }
        result.events.forEach { require(it.time >= 0 && (it.sequence >= 0 || it.kind=="OBSERVE_BOOT" && it.sequence == -1)) }
        result.annotations.forEach { valid(it.start, it.end); require(it.note.length <= 2000 && it.purpose.length <= 40) }
        val purposes = result.annotations.filter { it.purpose.isNotBlank() }.sortedBy { it.start }
        require(purposes.zipWithNext().all { (a,b) -> a.end <= b.start }) { "备份用途区间存在重叠" }
        require(result.events.distinctBy { it.id }.size == result.events.size && result.sessions.distinctBy { it.id }.size == result.sessions.size && result.annotations.distinctBy { it.id }.size == result.annotations.size) { "备份包含重复记录" }
        val config = result.config.associate { it.key to it.value }
        listOf("lastSync", "captureStart").forEach { key -> config[key]?.let { require(it.toLongOrNull()?.let { v -> v >= 0 } == true) { "备份采集配置无效" } } }
        return result
    }
    suspend fun restore(data: Restore) = repo.exclusive {
        repo.db.withTransaction {
            repo.wipe()
            repo.dao.putEvents(data.events); repo.dao.putSessions(data.sessions); repo.dao.putSpans(data.spans)
            repo.dao.putAnnotations(data.annotations); repo.dao.putApps(data.apps); repo.dao.putCoverage(data.coverage)
            // Imported history is kept until the user explicitly resumes capture.
            repo.dao.putConfig(data.config.filter { it.key != "paused" && !it.key.startsWith("observedBoot:") } + ConfigRow("paused", "true"))
        }
    }
    suspend fun csv(): String = repo.exclusive {
        val names = repo.dao.allApps().associate { it.pkg to it.name }
        fun cell(s: String): String {
            val safe = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'" + s else s
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        buildString {
            append("\uFEFFpackage,name,start_ms,end_ms,start_iso,end_iso,duration_ms,quality,ongoing\r\n")
            repo.dao.allSessions().forEach { s ->
                append(listOf(s.pkg, names[s.pkg] ?: s.pkg, s.start.toString(), s.end.toString(), Instant.ofEpochMilli(s.start).toString(), Instant.ofEpochMilli(s.end).toString(), (s.end-s.start).toString(), if (s.inferred) "inferred" else "observed", s.ongoing.toString()).joinToString(",", transform = ::cell))
                append("\r\n")
            }
        }
    }
}
