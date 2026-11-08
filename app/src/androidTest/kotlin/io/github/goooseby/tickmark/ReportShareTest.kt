package io.github.goooseby.tickmark

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.share.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.*

class ReportShareTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val date=LocalDate.of(2026,10,25)
    private val zone=ZoneId.of("Europe/Berlin")
    private val start get()=date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val end get()=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun dailyPosterUsesCanonicalDeviceUnionAndProtectsPrivateData() {
        val a=start+3_600_000
        val records=Records(
            spans=listOf(SpanRow(start,a,"SCREEN_OFF"),SpanRow(a,a+3_600_000,"IN_USE"),SpanRow(a+3_600_000,a+7_200_000,"LOCKED")),
            sessions=listOf(SessionRow("a","visible",a,a+3_600_000,false,false),SessionRow("b","hidden",a,a+3_600_000,false,false),SessionRow("c","excluded",a,a+3_600_000,false,false)),
            apps=listOf(AppRow("visible","阅读"),AppRow("hidden","秘密应用",hidden=true),AppRow("excluded","隐私应用",excluded=true)),
            journal=listOf(JournalRow.daily(date).copy(positive="私人日记内容")),unlocks=listOf(a)
        )
        val data=ReportImageData.create(records,date,false,end,zone)
        assertEquals(records.dayReport(date,zone,end).used,data.used)
        assertEquals(3_600_000L,data.used);assertEquals(7_200_000L,data.away)
        assertEquals(25,data.bars.size);assertEquals(data.used,data.bars.filterNotNull().sum())
        assertEquals(1,data.unlocks);assertEquals(listOf("阅读"),data.slices.map{it.name})
        assertFalse(data.toString().contains("秘密应用"));assertFalse(data.toString().contains("私人日记内容"))
        assertNull(data.bars.last());assertEquals(0L,data.bars.first())
    }

    @Test fun weekAndShortDstDayKeepRealBoundariesAndUnknownValues() {
        val spring=LocalDate.of(2026,3,29)
        val from=spring.atStartOfDay(zone).toInstant().toEpochMilli()
        val until=spring.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val records=Records(spans=listOf(SpanRow(from,until,"IN_USE")))
        val day=ReportImageData.create(records,spring,false,until,zone)
        assertEquals(23,day.bars.size);assertEquals(23*3_600_000L,day.used)
        val week=ReportImageData.create(records,spring,true,until,zone)
        assertEquals(LocalDate.of(2026,3,23),week.date)
        assertEquals(7,week.bars.size);assertEquals(1,week.recordedDays)
        assertEquals(day.used,week.bars.filterNotNull().sum());assertNull(week.bars.first())
    }

    @Test fun posterAlwaysHasFixedSizeAndAggregatesLongAppLists() {
        val apps=(1..150).map{AppRow("sample.$it",if(it==1) "很长的应用名称".repeat(20) else "应用 $it")}
        val sessions=apps.mapIndexed{i,app->SessionRow("$i",app.pkg,start,start+(150-i)*60_000L,false,false)}
        val records=Records(apps=apps,sessions=sessions,spans=listOf(SpanRow(start,start+9*3_600_000L,"IN_USE"),SpanRow(start+9*3_600_000L,end,"SCREEN_OFF")))
        val folder=File(context.getExternalFilesDir(null),"validation").apply{mkdirs()}
        listOf(false,true).forEach { weekly->
            val data=ReportImageData.create(records,date,weekly,end,zone)
            assertEquals(5,data.slices.size);assertEquals("其他应用",data.slices.last().name)
            assertEquals(150,data.appCount)
            val bitmap=ReportImage.render(context,data)
            assertEquals(1080,bitmap.width);assertEquals(1440,bitmap.height)
            File(folder,if(weekly) "share-week.png" else "share-day.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        val empty=ReportImage.render(context,ReportImageData.create(Records(),date,false,end,zone))
        File(folder,"share-empty.png").outputStream().use{empty.compress(Bitmap.CompressFormat.PNG,100,it)};empty.recycle()
    }

    @Test fun sharedFilesAreImmutableAndGrantOnlyTemporaryReadAccess() {
        val first=ShareFiles.text(context,"Tickmark-test.json","{\"journal\":[]}")
        val second=ShareFiles.text(context,"Tickmark-test.json","{\"journal\":[1]}")
        try {
            assertNotEquals(first,second)
            assertEquals("{\"journal\":[]}",context.contentResolver.openInputStream(first)!!.bufferedReader().use{it.readText()})
            val intent=ShareFiles.intent(first,"application/json","Tickmark 备份")
            assertEquals(Intent.ACTION_SEND,intent.action);assertEquals("application/json",intent.type)
            assertEquals(first,intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertNull(intent.`package`)
            val provider=context.packageManager.resolveContentProvider("${context.packageName}.share",0)!!
            assertFalse(provider.exported);assertTrue(provider.grantUriPermissions)
        } finally {
            listOf(first,second).forEach { uri->File(context.cacheDir,"shared/${uri.pathSegments[1]}").deleteRecursively() }
        }
    }
}
