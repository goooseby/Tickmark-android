package io.github.goooseby.tickmark.widget

import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.engine.analytics.ReportEngine
import io.github.goooseby.tickmark.engine.model.DeviceState
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class WidgetSnapshot(
    val date: String,
    val updated: Long,
    val used: Long,
    val away: Long,
    val longest: Long,
    val longestStart: Long,
    val longestEnd: Long,
    val latest: Long,
    val latestStart: Long,
    val latestEnd: Long,
    val goalMinutes: Long,
    val action: String,
    val access: Boolean,
    val paused: Boolean,
    val hasEvidence: Boolean,
    val usageBars: List<Long>,
    val awayBars: List<Long>,
) {
    fun encode(): String = JSONObject().apply {
        put("schema",1);put("date",date);put("updated",updated);put("used",used);put("away",away)
        put("longest",longest);put("longestStart",longestStart);put("longestEnd",longestEnd)
        put("latest",latest);put("latestStart",latestStart);put("latestEnd",latestEnd)
        put("goalMinutes",goalMinutes);put("action",action);put("access",access);put("paused",paused)
        put("hasEvidence",hasEvidence);put("usageBars",JSONArray(usageBars));put("awayBars",JSONArray(awayBars))
    }.toString()
    companion object {
        fun create(records: Records, access: Boolean, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): WidgetSnapshot {
            val date=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val report=records.dayReport(date,zone,now)
            val start=date.atStartOfDay(zone)
            val end=date.plusDays(1).atStartOfDay(zone)
            val boundaries=generateSequence(start){it.plusHours(1)}.takeWhile{it<end}
                .map{it.toInstant().toEpochMilli()}.toList()+end.toInstant().toEpochMilli()
            fun bars(away:Boolean)=ReportEngine.buckets(report.spans.filter {
                if(away) it.state==DeviceState.SCREEN_OFF || it.state==DeviceState.LOCKED else it.state==DeviceState.IN_USE
            }.map{it.start to it.end},boundaries).map{it.duration}
            val longest=report.longestAway;val latest=report.latestAway
            val plan=records.journal.find{it.id=="day:${date.minusDays(1)}" && !it.draft}
            val action=plan?.let{if(it.actionDone && it.action.isNotBlank()) "已完成 · ${it.action}" else it.action}.orEmpty()
            return WidgetSnapshot(date.toString(),records.config["lastObserved"]?.toLongOrNull() ?: records.config["lastSync"]?.toLongOrNull() ?: 0,
                report.used,report.notUsed,longest?.duration ?: 0,longest?.start ?: 0,longest?.end ?: 0,
                latest?.duration ?: 0,latest?.start ?: 0,latest?.end ?: 0,
                records.config["dailyGoalMinutes"]?.toLongOrNull()?.coerceIn(0,1440) ?: 0,
                action.take(160),access,records.config["paused"]=="true",
                report.spans.any{it.state!=DeviceState.UNKNOWN},bars(false),bars(true))
        }
        fun decode(text:String?):WidgetSnapshot? = runCatching {
            val j=JSONObject(text ?: return null)
            require(j.getInt("schema")==1)
            LocalDate.parse(j.getString("date"))
            fun list(name:String):List<Long> {val a=j.getJSONArray(name);require(a.length() in 23..25);return (0 until a.length()).map{a.getLong(it).coerceAtLeast(0)}}
            WidgetSnapshot(j.getString("date"),j.getLong("updated"),j.getLong("used"),j.getLong("away"),j.getLong("longest"),j.getLong("longestStart"),j.getLong("longestEnd"),j.getLong("latest"),j.getLong("latestStart"),j.getLong("latestEnd"),j.getLong("goalMinutes"),j.getString("action"),j.getBoolean("access"),j.getBoolean("paused"),j.getBoolean("hasEvidence"),list("usageBars"),list("awayBars"))
        }.getOrNull()
    }
}
