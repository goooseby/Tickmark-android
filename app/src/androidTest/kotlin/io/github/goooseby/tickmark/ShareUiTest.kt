package io.github.goooseby.tickmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

class ShareUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var db:TickDatabase
    private lateinit var repo:TickRepository
    private val chooser=AtomicReference<Intent>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val monitor=object:Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
            if(intent.action!=Intent.ACTION_CHOOSER) return null
            chooser.set(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
        }
    }
    @Before fun create() {
        db=Room.inMemoryDatabaseBuilder(instrumentation.targetContext,TickDatabase::class.java).build()
        repo=TickRepository(instrumentation.targetContext,db,captureEnabled=false)
        compose.runOnUiThread{compose.activity.enableEdgeToEdge()}
        instrumentation.addMonitor(monitor)
    }
    @After fun close(){instrumentation.removeMonitor(monitor);compose.activityRule.scenario.close();db.close()}
    private fun shared():Intent {
        compose.waitUntil(10_000){chooser.get()!=null}
        return IntentCompat.getParcelableExtra(chooser.getAndSet(null),Intent.EXTRA_INTENT,Intent::class.java)!!
    }
    private fun uri(intent:Intent)=IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,Uri::class.java)!!

    private fun scroll(text:String):SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    @Test fun overviewSharesAFixedReportWithALinkWithoutOpeningAFilePicker() {
        compose.setContent{Tickmark(repo)}
        compose.onNodeWithContentDescription("分享每日概览").performClick()
        val intent=shared();assertEquals("image/png",intent.type)
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://github.com/goooseby/Tickmark-android"))
        val bitmap=instrumentation.targetContext.contentResolver.openInputStream(uri(intent))!!.use{BitmapFactory.decodeStream(it)}
        assertEquals(1080,bitmap.width);assertEquals(1440,bitmap.height);bitmap.recycle()
    }

    @Test fun backupAndCsvShareDirectlyAndSavingToAFileRemainsAvailable() {
        compose.setContent{Tickmark(repo)}
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("数据管理").performClick()
        scroll("创建完整备份").performClick()
        compose.onNodeWithText("分享备份").performClick()
        val backup=shared();assertEquals("application/json",backup.type)
        val json=instrumentation.targetContext.contentResolver.openInputStream(uri(backup))!!.bufferedReader().use{JSONObject(it.readText())}
        assertEquals(3,json.getInt("schemaVersion"));assertTrue(json.has("journal"))
        scroll("创建完整备份").performClick()
        compose.onNodeWithText("保存备份").assertIsDisplayed()
        compose.onNodeWithText("分享备份").assertIsDisplayed()

    }

    @Test fun interruptedTabTransitionsKeepOneLiveDestinationAndBackReturnsToToday() {
        compose.mainClock.autoAdvance=false
        compose.setContent{Tickmark(repo)}
        compose.mainClock.advanceTimeBy(700)
        listOf("记录","设置","记录","回顾","记录").forEach { label->
            compose.onNode(hasText(label) and hasClickAction()).performClick()
            compose.mainClock.advanceTimeBy(48)
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("还没有记录").assertIsDisplayed()
        compose.runOnUiThread{compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.mainClock.advanceTimeBy(900)
        compose.onAllNodesWithText("今日").assertCountEquals(2)
    }
}
