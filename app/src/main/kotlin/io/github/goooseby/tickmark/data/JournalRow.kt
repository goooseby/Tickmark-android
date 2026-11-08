package io.github.goooseby.tickmark.data

import androidx.room.*
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

@Entity(tableName="journal",indices=[Index("date")])
data class JournalRow(
    @PrimaryKey val id:String,
    val date:String,
    val kind:String,
    val title:String="",
    val body:String="",
    val positive:String="",
    val adjustment:String="",
    val action:String="",
    val mood:String="",
    val actionDone:Boolean=false,
    val draft:Boolean=true,
    val pinned:Boolean=false,
    val createdAt:Long=0,
    val updatedAt:Long=0,
) {
    val meaningful:Boolean get()=if(kind=="DAILY") listOf(positive,adjustment,action,mood).any{it.isNotBlank()} else title.isNotBlank() || body.isNotBlank()
    val heading:String get()=if(kind=="DAILY") "每日复盘" else title.ifBlank{body.lineSequence().firstOrNull{it.isNotBlank()}?.take(40) ?: "随手记"}
    val preview:String get()=if(kind=="DAILY") positive.ifBlank{adjustment.ifBlank{action}} else if(title.isBlank()) body.lines().dropWhile{it.isBlank()}.drop(1).joinToString("\n") else body
    val searchable:String get()=listOf(title,body,positive,adjustment,action,mood).joinToString(" ")
    val shareText:String get()=buildList {
        add(if(kind=="DAILY") "每日复盘 · $date" else title.ifBlank{"随手记"}+" · $date")
        if(kind=="NOTE") add(body)
        else {
            if(mood.isNotBlank()) add("状态：$mood")
            if(positive.isNotBlank()) add("值得保留：\n$positive")
            if(adjustment.isNotBlank()) add("想调整的时段：\n$adjustment")
            if(action.isNotBlank()) add("明日行动${if(actionDone) "（已完成）" else ""}：\n$action")
        }
    }.filter{it.isNotBlank()}.joinToString("\n\n")
    companion object {
        val moods=listOf("平静","充实","分心","疲惫","烦躁")
        fun daily(date:LocalDate)=JournalRow("day:$date",date.toString(),"DAILY")
        fun note(date:LocalDate)=JournalRow("note:${UUID.randomUUID()}",date.toString(),"NOTE")
        fun validate(row:JournalRow) {
            val date=LocalDate.parse(row.date)
            require(row.kind in setOf("DAILY","NOTE") && row.id.length in 1..100 && (row.kind!="DAILY" || row.id=="day:$date")) {"记录类型或日期无效"}
            require(row.kind!="NOTE" || row.id.startsWith("note:") && row.id.length>5){"记录标识无效"}
            require(row.title.length<=100 && row.body.length<=8000 && row.positive.length<=2000 && row.adjustment.length<=2000 && row.action.length<=1000) {"记录内容超过长度限制"}
            require(row.mood.isEmpty() || row.mood in moods) {"记录状态无效"}
            require(row.createdAt>=0 && row.updatedAt>=0) {"记录时间无效"}
        }
        fun legacy(config:Map<String,String>):List<JournalRow> = config.keys.mapNotNull { key ->
            Regex("^review:(\\d{4}-\\d{2}-\\d{2}):(good|change|next)$").matchEntire(key)?.groupValues?.get(1)
        }.distinct().mapNotNull { text ->
            val date=runCatching{LocalDate.parse(text)}.getOrNull() ?: return@mapNotNull null
            val prefix="review:$date:"
            daily(date).copy(positive=config[prefix+"good"].orEmpty(),adjustment=config[prefix+"change"].orEmpty(),action=config[prefix+"next"].orEmpty(),draft=false).takeIf{it.meaningful}
        }
    }
}

object JournalCodec {
    fun encode(row:JournalRow):JSONObject=JSONObject().apply {
        put("id",row.id);put("date",row.date);put("kind",row.kind);put("title",row.title);put("body",row.body)
        put("positive",row.positive);put("adjustment",row.adjustment);put("action",row.action);put("mood",row.mood)
        put("actionDone",row.actionDone);put("draft",row.draft);put("pinned",row.pinned);put("createdAt",row.createdAt);put("updatedAt",row.updatedAt)
    }
    fun decode(j:JSONObject):JournalRow=JournalRow(j.getString("id"),j.getString("date"),j.getString("kind"),
        j.getString("title"),j.getString("body"),j.getString("positive"),j.getString("adjustment"),j.getString("action"),j.getString("mood"),
        j.getBoolean("actionDone"),j.getBoolean("draft"),j.getBoolean("pinned"),j.getLong("createdAt"),j.getLong("updatedAt")).also{JournalRow.validate(it)}
}

internal const val JOURNAL_TABLE_SQL="CREATE TABLE IF NOT EXISTS journal (id TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, positive TEXT NOT NULL, adjustment TEXT NOT NULL, action TEXT NOT NULL, mood TEXT NOT NULL, actionDone INTEGER NOT NULL, draft INTEGER NOT NULL, pinned INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
internal fun androidx.sqlite.db.SupportSQLiteDatabase.insertJournal(row:JournalRow) {
    execSQL("INSERT OR REPLACE INTO journal VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(row.id,row.date,row.kind,row.title,row.body,row.positive,row.adjustment,row.action,row.mood,if(row.actionDone) 1 else 0,if(row.draft) 1 else 0,if(row.pinned) 1 else 0,row.createdAt,row.updatedAt))
}
