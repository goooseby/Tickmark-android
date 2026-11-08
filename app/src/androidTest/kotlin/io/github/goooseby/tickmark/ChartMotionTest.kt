package io.github.goooseby.tickmark

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.ui.HourlyChart
import io.github.goooseby.tickmark.ui.CompositionChart
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class ChartMotionTest {
    @get:Rule val compose=createComposeRule()
    private val date=LocalDate.now().minusDays(1)
    private val start=date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun barsGrowAndChangingTheDayReplaysTheReveal() {
        val older=date.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val records=Records(spans=listOf(SpanRow(start,start+1_800_000,"IN_USE"),SpanRow(older,older+1_800_000,"IN_USE")))
        val selected=mutableStateOf(date)
        compose.mainClock.autoAdvance=false
        compose.setContent{MaterialTheme{HourlyChart(records,selected.value,onSelect={_,_->})}}
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        val chart=compose.onNode(hasContentDescription("0时",substring=true))
        val early=chart.captureToImage()
        compose.mainClock.advanceTimeBy(600)
        val complete=chart.captureToImage()
        val a=IntArray(early.width*early.height);val b=IntArray(complete.width*complete.height)
        early.readPixels(a);complete.readPixels(b)
        assertFalse("Bar geometry must change during the reveal",a.contentEquals(b))
        compose.runOnUiThread{selected.value=date.minusDays(1)}
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeBy(100)
        val replay=chart.captureToImage();val c=IntArray(replay.width*replay.height);replay.readPixels(c)
        assertFalse("A different day must have a transition",b.contentEquals(c))
        compose.mainClock.advanceTimeBy(600)
    }

    @Test fun donutDrawsProgressivelyAndSettlesWithoutAnInfiniteAnimation() {
        val records=Records(sessions=listOf(SessionRow("a","sample.a",start,start+1_800_000,false,false),SessionRow("b","sample.b",start+1_800_000,start+3_600_000,false,false)))
        compose.mainClock.autoAdvance=false
        compose.setContent{MaterialTheme{CompositionChart(records,start,start+3_600_000){}}}
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeBy(100)
        val chart=compose.onNode(hasContentDescription("应用前台时长构成",substring=true))
        val early=chart.captureToImage()
        compose.mainClock.advanceTimeBy(600)
        val complete=chart.captureToImage()
        val a=IntArray(early.width*early.height);val b=IntArray(complete.width*complete.height)
        early.readPixels(a);complete.readPixels(b);assertFalse(a.contentEquals(b))
        compose.mainClock.advanceTimeBy(1000)
        val settled=chart.captureToImage();val c=IntArray(settled.width*settled.height);settled.readPixels(c)
        assertArrayEquals("The finished chart must stop animating",b,c)
    }
}
