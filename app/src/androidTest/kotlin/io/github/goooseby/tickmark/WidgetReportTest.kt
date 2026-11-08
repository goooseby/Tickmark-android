package io.github.goooseby.tickmark

import android.graphics.Bitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.widget.*
import io.github.goooseby.tickmark.app.reportIntent
import io.github.goooseby.tickmark.app.reportEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.*

class WidgetReportTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():Pair<Records,Long> {
        val day=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val minute=60_000L
        val records=Records(spans=listOf(SpanRow(day,day+35*minute,"IN_USE"),SpanRow(day+35*minute,day+95*minute,"SCREEN_OFF"),SpanRow(day+95*minute,day+100*minute,"LOCKED"),SpanRow(day+100*minute,day+135*minute,"IN_USE"),SpanRow(day+135*minute,day+170*minute,"SCREEN_OFF"),SpanRow(day+170*minute,day+180*minute,"IN_USE")),config=mapOf("lastObserved" to (day+180*minute).toString(),"dailyGoalMinutes" to "120"),journal=listOf(JournalRow.daily(LocalDate.now().minusDays(1)).copy(action="睡前先读十页",draft=false)))
        return records to day+180*minute
    }
    @Test fun widgetAndPageConsumeIdenticalDeviceProjection() {
        val (records,now)=fixture()
        val state=WidgetSnapshot.create(records,true,now)
        val report=records.dayReport(LocalDate.now(),now=now)
        assertEquals(report.used,state.used);assertEquals(report.notUsed,state.away)
        assertEquals(report.longestAway!!.duration,state.longest)
        assertEquals(state.used,state.usageBars.sum());assertEquals(state.away,state.awayBars.sum())
        assertEquals(state,WidgetSnapshot.decode(state.encode()))
        assertNull(WidgetSnapshot.decode("corrupt"))
    }
    @Test fun widgetBucketsCoverHalfHourDaylightSavingTransition() {
        val zone=ZoneId.of("Australia/Lord_Howe")
        val date=LocalDate.of(2026,10,4)
        val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val state=WidgetSnapshot.create(Records(spans=listOf(SpanRow(start,end,"SCREEN_OFF"))),true,end-1,zone)
        assertEquals(end-start-1,state.awayBars.sum())
        assertEquals(state.away,state.awayBars.sum())
        assertEquals(state,WidgetSnapshot.decode(state.encode()))
    }
    @Test fun restoredConflictingRecordsUseSameNormalizedIntervalsAcrossReports() {
        val (fixture,now)=fixture()
        val first=fixture.spans.first()
        val records=fixture.copy(spans=fixture.spans+SpanRow(first.start+60_000,first.start+120_000,"SCREEN_OFF"))
        val report=records.dayReport(LocalDate.now(),now=now)
        val state=WidgetSnapshot.create(records,true,now)
        assertEquals(60_000L,report.unknown)
        assertEquals(report.used,state.used)
        assertEquals(report.used,records.deviceSpans.filter{it.state==io.github.goooseby.tickmark.engine.model.DeviceState.IN_USE}.sumOf{it.end-it.start})
        assertEquals(report.notUsed,state.away)
    }
    @Test fun reportIntentsKeepDistinctDestinationsAndRejectInvalidDates() {
        val today=LocalDate.now()
        val intent=reportIntent(context,"away",today.plusDays(2))
        assertEquals(today,intent.reportEntry()!!.date)
        assertEquals("away",intent.reportEntry()!!.destination)
        assertNotEquals(intent.data,reportIntent(context,"today",today).data)
        assertNull(reportIntent(context,"invalid",today).reportEntry())
        assertEquals(today,intent.putExtra("reportDate","bad").reportEntry()!!.date)
    }
    @Test fun databaseSnapshotKeepsUserConfigurationAndDeviceRecordsTogether()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build()
        try {
            val (records,_)=fixture()
            db.dao().putSpans(records.spans);db.dao().putConfig(records.config.map{ConfigRow(it.key,it.value)})
            val loaded=TickRepository(context,db,captureEnabled=false).snapshot()
            assertEquals(records.spans,loaded.spans);assertEquals(records.config,loaded.config)
        } finally {db.close()}
    }
    /** Optional visual QA on actual pinned widgets; only widget cache is replaced temporarily. */
    @Test fun renderPinnedWidgetsForVisualInspection()=runBlocking {
        assumeTrue("Opt in with -e widgetVisual true",InstrumentationRegistry.getArguments().getString("widgetVisual")=="true")
        val manager=GlanceAppWidgetManager(context)
        val today=manager.getGlanceIds(TodayWidget::class.java)
        val away=manager.getGlanceIds(AwayWidget::class.java)
        assumeTrue("Pin widgets on the emulator before running visual QA",today.isNotEmpty() || away.isNotEmpty())
        val (records,now)=fixture();val payload=WidgetSnapshot.create(records,true,now).encode()
        try {
            today.forEach {id->updateAppWidgetState(context,id){it[widgetPayloadKey]=payload};TodayWidget().update(context,id)}
            away.forEach {id->updateAppWidgetState(context,id){it[widgetPayloadKey]=payload};AwayWidget().update(context,id)}
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.executeShellCommand("input keyevent 3").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            Thread.sleep(500)
            // This opt-in launcher fixture is pinned on the next page of the QA emulator.
            val display=context.resources.displayMetrics
            instrumentation.uiAutomation.executeShellCommand("input swipe ${display.widthPixels*85/100} ${display.heightPixels/2} ${display.widthPixels*15/100} ${display.heightPixels/2} 400").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            Thread.sleep(2500)
            val folder=File(context.getExternalFilesDir(null),"validation").apply{mkdirs()}
            File(folder,"widgets-home.png").outputStream().use{instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
            instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/Tickmark-validation").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            instrumentation.uiAutomation.executeShellCommand("cp ${folder.absolutePath}/widgets-home.png /sdcard/Download/Tickmark-validation/widgets-home.png").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        } finally {
            WidgetCoordinator.publish(context)
            // Glance updates run in a session; let the restored real snapshot reach the host.
            Thread.sleep(3000)
        }
        Unit
    }
}
