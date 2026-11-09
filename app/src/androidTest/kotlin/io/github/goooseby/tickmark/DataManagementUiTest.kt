package io.github.goooseby.tickmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.IntentCompat
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.ui.Tickmark
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*
import java.util.concurrent.atomic.AtomicReference

class DataManagementUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var db:TickDatabase
    private lateinit var repo:TickRepository
    private val outgoing=AtomicReference<Intent>()
    private val monitor=object:Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
            if(intent.action !in listOf(Intent.ACTION_CHOOSER,Intent.ACTION_CREATE_DOCUMENT)) return null
            outgoing.set(Intent(intent));return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
        }
    }
    @Before fun create()=runBlocking<Unit> {
        val context=instrumentation.targetContext
        db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build();repo=TickRepository(context,db,captureEnabled=false)
        val date=LocalDate.now();val start=date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo.dao.putApps(listOf(AppRow("sample.reader","阅读")))
        repo.dao.putSessions(listOf(SessionRow("today","sample.reader",start+60_000,start+120_000,false,false),SessionRow("old","sample.reader",start-3_600_000,start-1_800_000,false,false)))
        repo.dao.putSpans(listOf(SpanRow(start,start+180_000,"IN_USE")))
        repo.dao.putJournal(listOf(JournalRow.daily(date).copy(positive="可选私人复盘",draft=false)))
        repo.dao.putSummaries(listOf(SystemUsageSummary("month","sample.reader",date.minusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),start-1,3_600_000,"month",start)))
        instrumentation.addMonitor(monitor)
        compose.runOnUiThread{compose.activity.enableEdgeToEdge()}
        compose.setContent{Tickmark(repo)}
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("数据管理").performClick()
    }
    @After fun close(){instrumentation.removeMonitor(monitor);compose.activityRule.scenario.close();db.close()}
    private fun share():Pair<Intent,String> {
        scroll("分享数据").performClick()
        compose.waitUntil(10_000){outgoing.get()!=null}
        val intent=IntentCompat.getParcelableExtra(outgoing.getAndSet(null),Intent.EXTRA_INTENT,Intent::class.java)!!
        val uri=IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,Uri::class.java)!!
        return intent to instrumentation.targetContext.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()}
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"validation").apply{mkdirs()}
        File(directory,"data-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun scroll(text:String):SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    @Test fun todayDefaultsToPrivateSummaryAndCsvOnlyIncludesToday() {
        screenshot("top")
        val (intent,text)=share();assertEquals("application/json",intent.type)
        val json=JSONObject(text);assertEquals(LocalDate.now().toString(),json.getJSONObject("range").getString("firstDate"))
        assertFalse(text.contains("可选私人复盘"));assertFalse(json.has("config"));assertFalse(json.has("sessions"))
        scroll("CSV 会话").performClick()
        val (csv,rows)=share();assertEquals("text/csv",csv.type);assertEquals(3,rows.split("\r\n").size)
        assertTrue(rows.contains("\"60000\""))
    }
    @Test fun allRangeIncludesCoarseHistoryAndNotesAreExplicit() {
        scroll("全部").performClick()
        scroll("附带复盘与备注").performClick()
        val (_,text)=share();val json=JSONObject(text)
        assertEquals(1,json.getJSONArray("journal").length());assertEquals(1,json.getJSONArray("systemSummaries").length())
        scroll("系统历史汇总").performClick()
        compose.onNodeWithText("月汇总").performClick();compose.onNodeWithText("阅读").assertIsDisplayed()
        screenshot("history")
        compose.runOnUiThread{compose.activity.onBackPressedDispatcher.onBackPressed()}
        scroll("完整备份与恢复").assertIsDisplayed()
    }
    @Test fun customDatePickerAndCancelledSaveLeaveThePageUsable() {
        compose.onNodeWithText("自选日期").performClick();compose.onNodeWithText("导出日期").assertIsDisplayed()
        screenshot("range")
        compose.onNodeWithText("确定").performClick()
        scroll("保存到文件").performClick()
        compose.waitUntil(10_000){outgoing.get()!=null}
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,outgoing.getAndSet(null).action)
        share()
        scroll("创建完整备份").performClick()
        compose.onNodeWithText("保存备份").assertIsDisplayed();screenshot("backup")
    }
}
