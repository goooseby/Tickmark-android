package io.github.goooseby.tickmark.data

import android.app.usage.UsageStatsManager
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject

/** System-owned bucket boundaries. Never convert these aggregates into sessions or device spans. */
@Entity(tableName="system_summaries",indices=[Index("start")])
data class SystemUsageSummary(
    @PrimaryKey val id:String,val pkg:String,val start:Long,val end:Long,val foregroundMs:Long,
    val interval:String,val capturedAt:Long,
)

object SummaryCodec {
    val intervals=linkedMapOf("day" to UsageStatsManager.INTERVAL_DAILY,"week" to UsageStatsManager.INTERVAL_WEEKLY,
        "month" to UsageStatsManager.INTERVAL_MONTHLY,"year" to UsageStatsManager.INTERVAL_YEARLY)
    fun encode(row:SystemUsageSummary)=JSONObject().put("id",row.id).put("pkg",row.pkg).put("start",row.start)
        .put("end",row.end).put("foregroundMs",row.foregroundMs).put("interval",row.interval).put("capturedAt",row.capturedAt)
    fun decode(j:JSONObject)=SystemUsageSummary(j.getString("id"),j.getString("pkg"),j.getLong("start"),j.getLong("end"),
        j.getLong("foregroundMs"),j.getString("interval"),j.getLong("capturedAt")).also {
        require(it.start>=0 && it.end>it.start && it.foregroundMs>=0 && it.interval in intervals && it.capturedAt>=0){"系统汇总记录无效"}
    }
    fun query(manager:UsageStatsManager,floor:Long,now:Long,excluded:Set<String>):List<SystemUsageSummary> =
        intervals.flatMap{(name,type)->
            val rows=manager.queryUsageStats(type,floor,now) ?: error("系统暂时无法提供历史汇总")
            rows.filter{it.firstTimeStamp>=floor && it.lastTimeStamp>it.firstTimeStamp && it.lastTimeStamp<=now && it.totalTimeInForeground>0 &&
                it.packageName !in excluded}.map{row->SystemUsageSummary("$name:${row.firstTimeStamp}:${row.packageName}",row.packageName,
                    row.firstTimeStamp,row.lastTimeStamp,row.totalTimeInForeground,name,now)}
        }.distinctBy{it.id}
}

const val SUMMARY_TABLE_SQL="CREATE TABLE IF NOT EXISTS system_summaries (id TEXT NOT NULL, pkg TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, foregroundMs INTEGER NOT NULL, interval TEXT NOT NULL, capturedAt INTEGER NOT NULL, PRIMARY KEY(id))"
