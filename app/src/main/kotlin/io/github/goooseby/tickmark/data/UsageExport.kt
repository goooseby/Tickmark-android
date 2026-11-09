package io.github.goooseby.tickmark.data

import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.engine.model.unionDuration
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class UsageExportFormat(val label:String,val mime:String,val extension:String) {
    JSON("JSON 报告","application/json","json"), CSV("CSV 会话","text/csv","csv"), SUMMARY_CSV("CSV 汇总","text/csv","csv")
}

/** Inclusive calendar dates, converted in the selected zone; never use fixed 24-hour days. */
data class UsageExportOptions(
    val first:LocalDate,val last:LocalDate,val zone:ZoneId=ZoneId.systemDefault(),
    val format:UsageExportFormat=UsageExportFormat.JSON,val details:Boolean=false,
    val notes:Boolean=false,val hidden:Boolean=false,
) {
    init { require(!last.isBefore(first)) {"结束日期不能早于开始日期"} }
    val from:Long get()=first.atStartOfDay(zone).toInstant().toEpochMilli()
    val to:Long get()=last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val filename:String get()="Tickmark-${if(format==UsageExportFormat.JSON) "report" else if(format==UsageExportFormat.CSV) "sessions" else "system-summary"}-$first${if(first!=last) "_$last" else ""}.${format.extension}"
}

/** A report is deliberately not a restorable backup. No config, raw events or unrelated app metadata. */
class UsageExport(private val records:Records,val options:UsageExportOptions,val now:Long=System.currentTimeMillis()) {
    val until=minOf(options.to,now)
    private val apps=records.apps.associateBy{it.pkg}
    val sessions=records.sessions.filter {
        it.start<until && it.end>options.from && it.pkg.isNotBlank() && !it.pkg.startsWith("@") &&
            apps[it.pkg]?.excluded!=true && (options.hidden || apps[it.pkg]?.hidden!=true)
    }.map{it.copy(start=maxOf(it.start,options.from),end=minOf(it.end,until),ongoing=it.ongoing && it.end<=until)}
    val journal=if(options.notes && options.format==UsageExportFormat.JSON) records.journal.filter {
        !it.draft && it.date>=options.first.toString() && it.date<=options.last.toString()
    } else emptyList()
    val annotations=if(options.notes && options.format==UsageExportFormat.JSON) records.annotations.filter{it.start<until && it.end>options.from}
        .map{it.copy(start=maxOf(it.start,options.from),end=minOf(it.end,until))} else emptyList()
    val summaries=records.summaries.filter{it.start>=options.from && it.end<=until &&
        apps[it.pkg]?.excluded!=true && (options.hidden || apps[it.pkg]?.hidden!=true)}
    val appCount=(sessions.map{it.pkg}+summaries.map{it.pkg}).distinct().size
    val hasDeviceData=records.spans.any{it.start<until && it.end>options.from && it.state!="UNKNOWN"}
    val available:Boolean get()=when(options.format) { UsageExportFormat.CSV->sessions.isNotEmpty();UsageExportFormat.SUMMARY_CSV->summaries.isNotEmpty();else->sessions.isNotEmpty() || hasDeviceData || summaries.isNotEmpty() || journal.isNotEmpty() || annotations.isNotEmpty() }
    private fun obj(vararg values:Pair<String,Any?>)=JSONObject().apply{values.forEach{put(it.first,it.second ?: JSONObject.NULL)}}
    private fun <T> array(rows:List<T>,block:(T)->JSONObject)=JSONArray().apply{rows.forEach{put(block(it))}}
    private fun instant(time:Long)=Instant.ofEpochMilli(time).atZone(options.zone).toOffsetDateTime().toString()
    private fun appTotals(rows:List<SessionRow>,from:Long,to:Long)=array(rows.groupBy{it.pkg}.entries.sortedBy{it.key}) { (pkg,items)->
        val duration=unionDuration(items.map{maxOf(it.start,from) to minOf(it.end,to)})
        val inferred=unionDuration(items.filter{it.inferred}.map{maxOf(it.start,from) to minOf(it.end,to)})
        val observed=unionDuration(items.filterNot{it.inferred}.map{maxOf(it.start,from) to minOf(it.end,to)})
        obj("package" to pkg,"name" to (apps[pkg]?.name ?: pkg),"category" to (apps[pkg]?.appCategory?.title ?: "未分类"),
            "foregroundMs" to duration,"observedMs" to observed,"inferredMs" to inferred)
    }
    fun text():String {
        require(until>options.from && available){"所选日期没有可导出的数据"}
        return when(options.format) { UsageExportFormat.CSV->csv();UsageExportFormat.SUMMARY_CSV->summaryCsv();else->json() }
    }
    private fun json():String {
        val selectedSpans=records.spans.filter{it.start<until && it.end>options.from}
        val selected=records.copy(spans=selectedSpans)
        fun device(from:Long,to:Long):JSONObject {
            val report=selected.deviceReport(from,to,now)
            val known=(report.until-report.from-report.unknown).coerceAtLeast(0)
            return obj("from" to instant(from),"until" to instant(report.until),"phoneUsedMs" to report.used.takeIf{known>0},
                "notUsedMs" to report.notUsed.takeIf{known>0},"unknownMs" to report.unknown,"knownMs" to known,
                "coverage" to if(known==0L) "missing" else if(report.unknown>0) "partial" else "observed",
                "unlockCount" to records.unlocks.count{it>=from && it<report.until}.takeIf{known>0})
        }
        val days=mutableListOf<JSONObject>()
        var date=options.first
        while(date<=options.last && date.atStartOfDay(options.zone).toInstant().toEpochMilli()<until) {
            val from=date.atStartOfDay(options.zone).toInstant().toEpochMilli()
            val to=minOf(date.plusDays(1).atStartOfDay(options.zone).toInstant().toEpochMilli(),until)
            val rows=sessions.filter{it.start<to && it.end>from}
            val day=obj("date" to date.toString(),"device" to device(from,to),"apps" to appTotals(rows,from,to))
            if(rows.isEmpty() && selectedSpans.none{it.start<to && it.end>from && it.state!="UNKNOWN"}) {
                date=date.plusDays(1);continue
            }
            val hours=JSONArray()
            var hour=date.atStartOfDay(options.zone)
            while(hour.toInstant().toEpochMilli()<to) {
                val a=hour.toInstant().toEpochMilli();val b=minOf(hour.plusHours(1).toInstant().toEpochMilli(),to)
                hours.put(device(a,b));hour=hour.plusHours(1)
            }
            day.put("hours",hours);days+=day;date=date.plusDays(1)
        }
        val root=obj("type" to "tickmark.usage-report","reportVersion" to 1,"algorithmVersion" to 2,
            "createdAt" to instant(now),"zoneId" to options.zone.id,
            "range" to obj("firstDate" to options.first.toString(),"lastDate" to options.last.toString(),
                "fromMs" to options.from,"untilMs" to until,"endExclusive" to true),
            "definitions" to obj("unit" to "milliseconds","phoneUsed" to "屏幕可交互且已解锁的区间并集；无安全锁屏时按屏幕可交互统计",
                "apps" to "同应用前台区间去重，包含推算；分屏并行，应用合计可超过手机用时",
                "missing" to "unknownMs不是未使用；缺少状态依据不表示零使用；days省略完全无详细记录的日期",
                "systemSummaries" to "系统原始日/周/月/年桶，范围可能不对齐自然日；不同粒度重叠不可相加，不能与详细会话累计；仅包含完整落在所选日期的桶，不推算小时或设备用时",
                "privacy" to "不含设置、原始事件或排除应用身份；隐藏应用是否包含由includedHiddenApps标识"),
            "screenOnly" to (records.config["screenOnly"]=="true"),"includedHiddenApps" to options.hidden,
            "summary" to device(options.from,until),"apps" to appTotals(sessions,options.from,until),
            "days" to JSONArray(days),"systemSummaries" to array(summaries){row->obj("package" to row.pkg,"name" to (apps[row.pkg]?.name ?: row.pkg),
                "from" to instant(row.start),"until" to instant(row.end),"foregroundMs" to row.foregroundMs,
                "interval" to row.interval,"source" to "android.usage-stats","quality" to "summaryOnly")})
        if(options.details) root.put("sessions",array(sessions){s->obj("package" to s.pkg,"startMs" to s.start,"endMs" to s.end,
            "from" to instant(s.start),"until" to instant(s.end),"durationMs" to s.end-s.start,"inferred" to s.inferred,
            "ongoing" to s.ongoing)})
        if(options.notes) {
            root.put("journal",array(journal,JournalCodec::encode))
            root.put("annotations",array(annotations){a->obj("from" to instant(a.start),"until" to instant(a.end),"purpose" to a.purpose,"note" to a.note)})
        }
        return root.toString(2)
    }
    private fun summaryCsv():String=buildString {
        append("\uFEFFpackage,name,start_ms,end_ms,start_iso,end_iso,foreground_ms,interval,source,quality,zone_id\r\n")
        summaries.forEach{r->append(listOf(r.pkg,apps[r.pkg]?.name ?: r.pkg,r.start.toString(),r.end.toString(),instant(r.start),instant(r.end),
            r.foregroundMs.toString(),r.interval,"android.usage-stats","summaryOnly",options.zone.id).joinToString(",",transform=::csvCell));append("\r\n")}
    }
    private fun csv():String=buildString {
        append("\uFEFFpackage,name,start_ms,end_ms,start_iso,end_iso,duration_ms,quality,ongoing,category,zone_id\r\n")
        sessions.forEach { s->
            append(listOf(s.pkg,apps[s.pkg]?.name ?: s.pkg,s.start.toString(),s.end.toString(),instant(s.start),instant(s.end),
                (s.end-s.start).toString(),if(s.inferred) "inferred" else "observed",s.ongoing.toString(),
                apps[s.pkg]?.appCategory?.title ?: "未分类",options.zone.id).joinToString(",",transform=::csvCell))
            append("\r\n")
        }
    }
}

internal fun csvCell(value:String):String {
    val safe=if(value.trimStart().firstOrNull() in listOf('=','+','-','@','\t','\r')) "'$value" else value
    return "\""+safe.replace("\"","\"\"")+"\""
}
