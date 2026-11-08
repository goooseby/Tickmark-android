package io.github.goooseby.tickmark

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.ui.Tickmark
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

class JournalUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var db:TickDatabase
    private lateinit var repo:TickRepository
    @Before fun create(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build();repo=TickRepository(context,db,captureEnabled=false)
        compose.runOnUiThread{compose.activity.enableEdgeToEdge()}
    }
    @After fun close(){compose.activityRule.scenario.close();db.close()}
    private fun openHub(){compose.setContent{Tickmark(repo)};compose.onNodeWithText("记录").performClick()}
    private fun waitEditor(){compose.waitUntil(5000){compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}}
    private fun waitStored(predicate:(List<JournalRow>)->Boolean){compose.waitUntil(5000){runBlocking{predicate(repo.dao.allJournal())}}}
    private fun screenshot(name:String){
        compose.waitForIdle()
        compose.onRoot().captureToImage() // Flush the Compose frame before capturing system bars too.
        android.os.SystemClock.sleep(400) // Allow the real IME/window animation to finish for visual QA.
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"validation").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use{instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/Tickmark-validation").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        instrumentation.uiAutomation.executeShellCommand("cp ${folder.absolutePath}/$name.png /sdcard/Download/Tickmark-validation/$name.png").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
    }
    @Test fun noteDraftSurvivesRestorationAndCanBeCompleted() {
        val restore=StateRestorationTester(compose)
        restore.setContent{Tickmark(repo)}
        compose.onNodeWithText("记录").performClick()
        compose.onNodeWithContentDescription("新建记录").performClick()
        compose.onNodeWithText("写随手记").performClick();waitEditor()
        compose.onNodeWithText("标题（可选）").performTextInput("课后散步")
        compose.onNodeWithText("写下记录").performTextInput("把手机放在包里，走到图书馆再拿出来。")
        screenshot("journal-note-keyboard")
        restore.emulateSavedInstanceStateRestore();waitEditor()
        compose.onNodeWithText("写下记录").assertTextContains("把手机放在包里",substring=true)
        compose.onNodeWithContentDescription("返回并保存").performClick()
        waitStored{it.any{r->r.title=="课后散步" && r.draft}}
        compose.onNodeWithText("课后散步").performClick();waitEditor()
        compose.onNodeWithText("完成").performClick()
        waitStored{it.single().draft.not()}
        assertEquals("把手机放在包里，走到图书馆再拿出来。",runBlocking{repo.dao.allJournal().single().body})
    }
    @Test fun loadedDailyReviewSearchAndActionCompletionAreConsistent() {
        val yesterday=LocalDate.now().minusDays(1)
        runBlocking {
            repo.saveJournal(JournalRow.daily(yesterday).copy(positive="午后没有边走边看手机",adjustment="晚饭后少刷一会儿",action="睡前先读十页",mood="平静"),true)
            repo.saveJournal(JournalRow.note(LocalDate.now()).copy(title="阅读清单",body="先读完正在读的书，再开始下一本。",pinned=true),true)
            repo.saveJournal(JournalRow.note(LocalDate.now().minusDays(3)).copy(title="通勤随记",body="路上听完了一期播客。"),true)
        }
        openHub()
        compose.onNodeWithText("每日复盘").performScrollTo().performClick();waitEditor()
        compose.onNodeWithText("值得保留的一件事").assertTextContains("午后没有边走边看手机")
        screenshot("journal-daily-editor")
        compose.onNodeWithContentDescription("返回并保存").performClick()
        screenshot("journal-hub")
        compose.onNode(hasSetTextAction()).performTextInput("阅读清单")
        compose.onNodeWithText("通勤随记").assertDoesNotExist()
        compose.onNode(hasText("阅读清单") and !hasSetTextAction()).performClick();waitEditor()
        screenshot("journal-note-editor")
        compose.onNodeWithContentDescription("返回并保存").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("阅读清单")
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.onNodeWithContentDescription("按日期筛选").performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("通勤随记").assertDoesNotExist()
        compose.onNodeWithContentDescription("清除日期筛选").performClick()
        compose.onNodeWithText("行动").performClick()
        compose.onNodeWithContentDescription("完成行动：睡前先读十页").performClick()
        waitStored{it.first{r->r.kind=="DAILY"}.actionDone}
        compose.onNodeWithText("没有待完成行动").assertIsDisplayed()
        compose.onNodeWithText("已完成").performClick()
        compose.onNodeWithContentDescription("取消完成：睡前先读十页").performClick()
        waitStored{!it.first{r->r.kind=="DAILY"}.actionDone}
    }
    @Test fun deleteUndoAndPinAreAvailableFromTheRecordList() {
        val note=JournalRow.note(LocalDate.now()).copy(title="周末计划",body="上午去公园，下午整理房间。")
        runBlocking{repo.saveJournal(note,true)}
        openHub()
        compose.onNodeWithContentDescription("记录操作：周末计划").performClick()
        compose.onNodeWithText("置顶").performClick()
        waitStored{it.single().pinned}
        compose.onNodeWithContentDescription("记录操作：周末计划").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除这条记录？").assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()
        waitStored{it.isEmpty()}
        compose.onNodeWithText("撤销").performClick()
        waitStored{it.singleOrNull()?.title=="周末计划"}
        compose.onNodeWithText("周末计划").assertIsDisplayed()
    }
    @Test fun longNotesRemainEditableWithKeyboardAndKeepAllText() {
        val body=(1..20).joinToString("\n\n"){"第${it}段：下午完成手头的事情，再回看手机的使用记录。"}
        val note=JournalRow.note(LocalDate.now()).copy(title="一段需要继续编辑的长记录",body=body)
        runBlocking{repo.saveJournal(note,true)}
        openHub();compose.onNodeWithText(note.title).performClick();waitEditor()
        compose.onNodeWithText("写下记录").performTextInputSelection(androidx.compose.ui.text.TextRange(body.length))
        android.os.SystemClock.sleep(400) // Settle a soft keyboard if this emulator enables it.
        compose.onNodeWithText("写下记录").performTextInput("\n追加一条计划。")
        compose.onNodeWithText("写下记录").assertTextContains("追加一条计划。",substring=true)
        compose.onNodeWithText("完成").assertIsDisplayed()
        screenshot("journal-long-note-keyboard")
        compose.onNodeWithContentDescription("返回并保存").performClick()
        waitStored{it.single().body.endsWith("追加一条计划。")}
        assertTrue(runBlocking{repo.dao.allJournal().single().body}.startsWith(body))
    }
    @Test fun todayActionOpensYesterdayReviewAndReturnsToToday() {
        val yesterday=LocalDate.now().minusDays(1)
        runBlocking{repo.saveJournal(JournalRow.daily(yesterday).copy(action="今天去散步"),true)}
        compose.setContent{Tickmark(repo)}
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("今天去散步"))
        compose.onNodeWithText("今天去散步").performClick();waitEditor()
        compose.onNodeWithText("${yesterday.monthValue}月${yesterday.dayOfMonth}日",substring=true).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回并保存").performClick()
        compose.onNodeWithText("每日概览").assertDoesNotExist()
        compose.onAllNodesWithText("今日").assertCountEquals(2)
    }
}
