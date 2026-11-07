package com.goooseby.tickmark

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.goooseby.tickmark.data.*
import com.goooseby.tickmark.ui.Tickmark
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File
import java.time.*

/** Synthetic data lives only in an in-memory test DB, never the installed app DB. */
class ReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db:TickDatabase
    private lateinit var repo:TickRepository
    @Before fun create() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build()
        repo=TickRepository(context,db,captureEnabled=false)
        val today=LocalDate.now()
        val zone=ZoneId.systemDefault()
        val packages=listOf("sample.study","sample.chat","sample.video","sample.browser","sample.music","sample.mail")
        val names=listOf("学习","聊天","视频","浏览器","音乐","邮件")
        repo.dao.putApps(packages.mapIndexed { i,p->AppRow(p,names[i]) })
        val sessions=mutableListOf<SessionRow>()
        val spans=mutableListOf<SpanRow>()
        val coverage=mutableListOf<CoverageRow>()
        for(day in 1..7) {
            val date=today.minusDays(day.toLong())
            val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
            for(hour in listOf(7,8,10,12,13,16,18,20,21,22)) {
                val count=4+(hour+day)%5
                for(part in 0 until count) {
                    val a=start+hour*3_600_000L+part*300_000L
                    sessions+=SessionRow("$day-$hour-$part",packages[(hour+part+day)%packages.size],a,a+180_000L+day*15_000L,false,false)
                    spans+=SpanRow(a,a+180_000L+day*15_000L,"IN_USE")
                }
            }
            coverage+=CoverageRow(start,date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),100)
        }
        repo.dao.putSessions(sessions); repo.dao.putSpans(spans); repo.dao.putCoverage(coverage)
        repo.dao.putConfig(listOf(ConfigRow("paused","false"),ConfigRow("lastSync",System.currentTimeMillis().toString())))
    }
    @After fun close() { db.close() }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"validation").apply { mkdirs() }
        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/Tickmark-validation").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        instrumentation.uiAutomation.executeShellCommand("cp ${directory.absolutePath}/$name.png /sdcard/Download/Tickmark-validation/$name.png").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    @Test fun reportsLeadToAppChartsAndRawRecordsRequireExpansion() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { Tickmark(repo) }
        compose.onNodeWithContentDescription("前一天").performClick()
        compose.onNodeWithText("每小时使用").assertIsDisplayed()
        compose.onNodeWithText("全天应用构成").assertExists()
        compose.onNodeWithText("查看原始会话").assertDoesNotExist()
        screenshot("reports-sample-light")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("浏览器"))
        compose.onNodeWithText("浏览器").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("当天使用分布"))
        compose.onNodeWithText("当天使用分布").assertIsDisplayed()
        screenshot("app-sample-light")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("查看原始会话"))
        compose.onNodeWithText("查看原始会话").performClick()
        compose.onNodeWithText("收起原始会话").assertExists()
    }
    @Test fun reviewShowsWeeklyTrendAndComposition() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { Tickmark(repo) }
        compose.onNodeWithContentDescription("前一天").performClick()
        compose.onNodeWithText("回顾").performClick()
        compose.onNodeWithText("每日使用").assertIsDisplayed()
        screenshot("review-sample")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("整周应用构成"))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("一周时段分布"))
        compose.onNodeWithText("一周时段分布").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("— 记录不足　空白为未来时段"))
        screenshot("week-heatmap-sample")
        val monday=com.goooseby.tickmark.engine.analytics.ReportEngine.weekStart(java.time.LocalDate.now().minusDays(1))
        compose.onNodeWithContentDescription("$monday 8至12时",substring=true).performScrollTo().performClick()
        compose.onNodeWithText("时段复盘").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("每日使用").assertExists()
    }
}
