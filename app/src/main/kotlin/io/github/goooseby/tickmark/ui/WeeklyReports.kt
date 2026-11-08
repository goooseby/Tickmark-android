package io.github.goooseby.tickmark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.engine.analytics.ReportEngine
import io.github.goooseby.tickmark.engine.model.unionDuration
import java.time.*
import kotlin.math.roundToInt

private fun reportIntervals(records:Records,pkg:String?) = if(pkg==null) records.deviceSpans.filter { it.state==io.github.goooseby.tickmark.engine.model.DeviceState.IN_USE }.map { it.start to it.end } else records.sessions.filter { it.pkg==pkg }.map { it.start to it.end }
private fun hasEvidence(records:Records,from:Long,to:Long,pkg:String?) = records.deviceSpans.any { it.state!=io.github.goooseby.tickmark.engine.model.DeviceState.UNKNOWN && it.start<to && it.end>from } || (pkg!=null && records.sessions.any { it.pkg==pkg && it.start<to && it.end>from })
private fun clippedDuration(intervals:List<Pair<Long,Long>>,from:Long,to:Long) = unionDuration(intervals.map { maxOf(it.first,from) to minOf(it.second,to) })

@Composable internal fun WeeklyReports(records:Records,date:LocalDate,onApp:(String)->Unit,onDay:(LocalDate)->Unit,onRange:(Long,Long)->Unit,onJournal:(LocalDate)->Unit={}) {
    val monday=ReportEngine.weekStart(date)
    val bounds=ReportEngine.weekBoundaries(date,ZoneId.systemDefault())
    val now=System.currentTimeMillis()
    val until=minOf(bounds.last(),now)
    val total=clippedDuration(reportIntervals(records,null),bounds.first(),until)
    val elapsed=bounds.dropLast(1).count { it<now }
    val recorded=bounds.zipWithNext().count { (a,b)->a<now && hasEvidence(records,a,minOf(b,now),null) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            Row(Modifier.padding(horizontal=20.dp,vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Metric("累计使用",duration(total),Modifier.weight(1.4f))
                Metric("已记录日期","$recorded / $elapsed 天",Modifier.weight(1f))
            }
            Text(if(now<bounds.last()) "本周截至当前时刻 · 按已确认记录汇总" else "按已确认记录汇总",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ChartTitle("每日使用") }
        item { WeeklyTrend(records,monday,onDay=onDay) }
        item { ChartTitle("整周应用构成") }
        item { CompositionChart(records,bounds.first(),until,onApp) }
        item { ChartTitle("一周时段分布") }
        item { WeekHeatmap(records,monday,onSelect=onRange) }
        item {JournalWeek(records,date,onJournal)}
        item { ChartTitle("整周应用分类") }
        item { CategoryReport(records,bounds.first(),until) }
        item { Text("已记录日期只表示有状态依据；部分日期可能缺少记录。",Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun WeeklyTrend(records:Records,monday:LocalDate,pkg:String?=null,onDay:(LocalDate)->Unit) {
    val dates=(0..6).map { monday.plusDays(it.toLong()) }
    val bounds=ReportEngine.weekBoundaries(monday,ZoneId.systemDefault())
    val now=System.currentTimeMillis()
    val intervals=reportIntervals(records,pkg)
    val values=(0..6).map { i -> val to=minOf(bounds[i+1],now); if(bounds[i]>=now || !hasEvidence(records,bounds[i],to,pkg)) null else clippedDuration(intervals,bounds[i],to) }
    val reveal by chartReveal(listOf(monday,pkg,values))
    val maxValue=maxOf(3_600_000L,values.filterNotNull().maxOrNull() ?: 0)
    val primary=MaterialTheme.colorScheme.primary; val grid=MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(horizontal=20.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(duration(maxValue),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(if(pkg==null) "手机使用" else "应用用时",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Canvas(Modifier.fillMaxWidth().height(128.dp).padding(horizontal=8.dp,vertical=10.dp).semantics { contentDescription=dates.zip(values).joinToString("；") { (d,v)->"$d：${if(dayStart(d)>now) "尚未开始" else v?.let { duration(it) } ?: "记录不足"}" } }.pointerInput(monday,values) { detectTapGestures { p -> val i=(p.x/size.width*6).roundToInt().coerceIn(0,6); if(bounds[i]<now) onDay(dates[i]) } }) {
            listOf(0f,.5f,1f).forEach { drawLine(grid,Offset(0f,it*size.height),Offset(size.width,it*size.height),1.dp.toPx()) }
            val points=values.mapIndexed { i,v->v?.let { Offset(i*size.width/6,size.height-it.toFloat()/maxValue*size.height) } }
            clipRect(left=-5.dp.toPx(),top=-5.dp.toPx(),right=size.width*reveal+5.dp.toPx(),bottom=size.height+5.dp.toPx()) {
                points.zipWithNext().forEach { (a,b)->if(a!=null && b!=null) drawLine(primary,a,b,2.dp.toPx()) }
                points.forEach { if(it!=null) drawCircle(primary,4.dp.toPx(),it) }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            dates.forEachIndexed { i,d->Column(Modifier.weight(1f).clickable(enabled=bounds[i]<now) { onDay(d) }.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(listOf("一","二","三","四","五","六","日")[i],style=MaterialTheme.typography.labelSmall,color=if(d==LocalDate.now()) primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${d.dayOfMonth}日",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        Text("断点为记录不足或尚未开始，点击日期查看当天",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun WeekHeatmap(records:Records,monday:LocalDate,pkg:String?=null,onSelect:(Long,Long)->Unit) {
    val zone=ZoneId.systemDefault(); val now=System.currentTimeMillis()
    val primary=MaterialTheme.colorScheme.primary; val empty=MaterialTheme.colorScheme.surfaceVariant
    val intervals=reportIntervals(records,pkg)
    val reveal by chartReveal(listOf(monday,pkg,intervals))
    Column(Modifier.padding(horizontal=12.dp)) {
        Text("分钟 · 每格4小时${if(pkg!=null) " · 含推算" else ""}",Modifier.padding(start=8.dp,bottom=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth()) { Spacer(Modifier.width(40.dp)); (0..5).forEach { i -> Box(Modifier.weight(1f).padding(vertical=6.dp),contentAlignment=Alignment.Center) { Text("%02d".format(i*4),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
        repeat(7) { day ->
            val date=monday.plusDays(day.toLong())
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.width(40.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text("周${listOf("一","二","三","四","五","六","日")[day]}",style=MaterialTheme.typography.labelSmall); Text("${date.dayOfMonth}日",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                repeat(6) { column ->
                    val from=date.atStartOfDay().plusHours(column*4L).atZone(zone).toInstant().toEpochMilli()
                    val end=date.atStartOfDay().plusHours((column+1)*4L).atZone(zone).toInstant().toEpochMilli()
                    val until=minOf(end,now)
                    val future=from>=now
                    val evidence=!future && hasEvidence(records,from,until,pkg)
                    val amount=if(evidence) clippedDuration(intervals,from,until) else 0
                    val ratio=if(end>from) (amount.toFloat()/(end-from)).coerceIn(0f,1f) else 0f
                    val text=when { future -> ""; !evidence -> "—"; amount in 1..59_999 -> "<1"; else -> (amount/60_000).toString() }
                    val bg=if(future) Color.Transparent else if(!evidence || amount==0L) empty else primary.copy(alpha=.18f+.82f*ratio)
                    Box(Modifier.weight(1f).height(48.dp).padding(1.dp).background(if(future) bg else lerp(empty,bg,reveal),MaterialTheme.shapes.extraSmall).semantics { contentDescription="$date ${column*4}至${(column+1)*4}时：${when { future->"尚未开始"; !evidence->"记录不足"; else->duration(amount) }}" }.clickable(enabled=!future) { onSelect(from,end) },contentAlignment=Alignment.Center) {
                        Text(text,style=MaterialTheme.typography.labelSmall,color=if(ratio>.6f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Text("— 记录不足　空白为未来时段",Modifier.padding(start=8.dp,top=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
