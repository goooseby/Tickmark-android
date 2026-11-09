package io.github.goooseby.tickmark

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.report.Records
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID

class DataManagementTest {
    private val zone=ZoneId.of("Europe/Berlin")
    private val date=LocalDate.of(2026,10,25)
    private val options=UsageExportOptions(date,date,zone)
    private val a=options.from
    private val b=options.to
    private fun session(id:String,pkg:String,start:Long=a,end:Long=b)=SessionRow(id,pkg,start,end,false,false)
    private fun summary(id:String,from:Long=a,to:Long=b,interval:String="day")=SystemUsageSummary(id,"visible",from,to,3_600_000,interval,b)
    private val records get()=Records(
        sessions=listOf(session("cross","visible",a-1000,b+1000),session("hidden","hidden"),session("secret","secret"),session("anon","@excluded"),session("outside","outside",b,b+1000)),
        spans=listOf(SpanRow(a,a+1000,"IN_USE"),SpanRow(a+1000,a+2000,"SCREEN_OFF")),
        apps=listOf(AppRow("visible","=SUM(A1)"),AppRow("hidden","排行隐藏示例",hidden=true),AppRow("secret","秘密应用",excluded=true),AppRow("outside","范围外应用")),
        journal=listOf(JournalRow.daily(date).copy(positive="私人内容",draft=false),JournalRow.note(date).copy(body="私人草稿"),JournalRow.daily(date.minusDays(1)).copy(positive="范围外日记",draft=false)),
        annotations=listOf(AnnotationRow("note",a-100,b+100,"学习","跨界备注")),
        config=mapOf("unrelated" to "秘密设置"),summaries=listOf(summary("day"),summary("month",a-86_400_000,b,"month")))

    @Test fun rangeExportClipsSessionsAndDefaultsToPrivateDataExclusion() {
        val report=UsageExport(records,options,b)
        assertEquals(1,report.sessions.size);assertEquals(a,report.sessions.single().start);assertEquals(b,report.sessions.single().end)
        val json=JSONObject(report.text())
        assertEquals(1000L,json.getJSONObject("summary").getLong("phoneUsedMs"))
        assertEquals(1,json.getJSONArray("apps").length())
        assertFalse(json.has("sessions"));assertFalse(json.has("journal"));assertFalse(json.has("config"));assertFalse(json.has("events"))
        listOf("私人内容","排行隐藏示例","秘密应用","范围外应用","秘密设置").forEach{assertFalse(report.text().contains(it))}
        assertEquals(1,json.getJSONArray("systemSummaries").length())
        assertEquals(25,json.getJSONArray("days").getJSONObject(0).getJSONArray("hours").length())
        assertEquals(25*3_600_000L,b-a)
    }
    @Test fun explicitNotesAndDetailsStayWithinRangeAndExcludeDrafts() {
        val json=JSONObject(UsageExport(records,options.copy(details=true,notes=true,hidden=true),b).text())
        assertEquals(2,json.getJSONArray("sessions").length())
        assertEquals(1,json.getJSONArray("journal").length())
        assertFalse(json.toString().contains("私人草稿"));assertFalse(json.toString().contains("范围外日记"))
        assertEquals(Instant.ofEpochMilli(a).atZone(zone).toOffsetDateTime().toString(),json.getJSONArray("annotations").getJSONObject(0).getString("from"))
    }
    @Test fun coarseOnlyDataKeepsUnknownDeviceMetricsAndDoesNotInventSessions() {
        val only=Records(apps=records.apps,summaries=listOf(summary("old")))
        val json=JSONObject(UsageExport(only,options,b).text())
        assertEquals(0,json.getJSONArray("days").length());assertEquals(0,json.getJSONArray("apps").length())
        assertTrue(json.getJSONObject("summary").isNull("phoneUsedMs"))
        assertEquals("missing",json.getJSONObject("summary").getString("coverage"))
        assertEquals("summaryOnly",json.getJSONArray("systemSummaries").getJSONObject(0).getString("quality"))
        assertFalse(UsageExport(only,options.copy(first=date.plusDays(1),last=date.plusDays(1)),b+86_400_000).available)
    }
    @Test fun csvClipsRealMillisecondsEscapesNamesAndSeparatesCoarseBuckets() {
        val csv=UsageExport(records,options.copy(format=UsageExportFormat.CSV),b).text()
        assertTrue(csv.contains("\"'=SUM(A1)\""));assertTrue(csv.contains("\"${b-a}\""));assertEquals(3,csv.split("\r\n").size)
        val coarse=UsageExport(records,options.copy(format=UsageExportFormat.SUMMARY_CSV),b).text()
        assertTrue(coarse.contains("summaryOnly"));assertFalse(coarse.contains("month"))
        assertFalse(coarse.contains("秘密应用"))
    }
    @Test fun historyScansAcrossEmptyGapsWithoutAnArbitraryAgeLimit() {
        val windows=HistoryImport.windows(b,0)
        assertEquals(b,windows.first().second);assertEquals(0L,windows.last().first)
        assertTrue(windows.zipWithNext().all{(recent,old)->recent.first==old.second})
        assertTrue(windows.all{it.first<it.second})
        assertEquals(a,HistoryImport.windows(b,a).last().first)
        val saved=EventRow("old",a,"RESUME","pkg","A",0,0)
        val same=saved.copy(id="new")
        assertTrue(HistoryImport.additions(listOf(saved),listOf(same)).isEmpty())
        assertEquals(1,HistoryImport.additions(listOf(saved),listOf(same,same.copy(id="another"))).size)
    }
    @Test fun fullBackupRestoresSummariesAndOlderBackupsRemainReadable()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build()
        try {
            val repo=TickRepository(context,db,captureEnabled=false);repo.dao.putSummaries(listOf(summary("old")))
            val transfer=DataTransfer(repo);val text=transfer.backup();val parsed=transfer.parse(text)
            assertEquals(1,parsed.summaries.size)
            repo.clear(true);assertTrue(repo.dao.allSummaries().isEmpty());transfer.restore(parsed)
            assertEquals(parsed.summaries,repo.dao.allSummaries())
            val old=JSONObject(text).put("schemaVersion",2);old.remove("systemSummaries")
            assertTrue(transfer.parse(old.toString()).summaries.isEmpty())
            try {transfer.parse(UsageExport(records,options,b).text());fail("report is not a backup")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("分析报告"))}
            repo.clear(false)
            val config=repo.dao.allConfig().associate{it.key to it.value}
            assertEquals("true",config["historyImported"]);assertEquals(config["captureStart"],config["captureFloor"])
        } finally {db.close()}
    }
    @Test fun roomMigrationAddsSummaryTableWithoutLosingOldRows() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="summary-migration-${UUID.randomUUID()}.db"
        fun helper(version:Int)=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db:SupportSQLiteDatabase){db.execSQL("CREATE TABLE config (`key` TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")}
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){MIGRATION_3_4.migrate(db)}
        }).build())
        try {
            helper(3).use{it.writableDatabase.execSQL("INSERT INTO config VALUES ('keep','old value')")}
            helper(4).use{h->val db=h.writableDatabase
                db.query("SELECT value FROM config").use{assertTrue(it.moveToFirst());assertEquals("old value",it.getString(0))}
                db.execSQL("INSERT INTO system_summaries VALUES ('id','pkg',1,2,1,'day',3)")
                db.query("SELECT COUNT(*) FROM system_summaries").use{it.moveToFirst();assertEquals(1,it.getInt(0))}
            }
        } finally {context.deleteDatabase(name)}
    }
}
