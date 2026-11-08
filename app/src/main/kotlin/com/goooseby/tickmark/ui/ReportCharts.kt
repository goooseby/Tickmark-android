package com.goooseby.tickmark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goooseby.tickmark.data.report.Records
import com.goooseby.tickmark.data.SessionRow
import com.goooseby.tickmark.engine.analytics.ReportEngine
import com.goooseby.tickmark.engine.model.Session
import com.goooseby.tickmark.engine.model.overlap
import com.goooseby.tickmark.engine.model.unionDuration
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

private val palette = listOf(Color(0xFF4378C5),Color(0xFF3E9487),Color(0xFFD59A36),Color(0xFF9670BC),Color(0xFFCB798A),Color(0xFF8191A2))
internal fun reportColor(key: String): Color = palette[(key.hashCode().toLong().absoluteValue % palette.size).toInt()]
internal fun composition(sessions: List<SessionRow>, from:Long, to:Long): List<Pair<String,Long>> = ReportEngine.composition(sessions.map { Session(it.pkg,it.start,it.end,it.inferred,it.ongoing) },from,to)
internal fun visibleSessions(records:Records) = records.sessions.filter { it.pkg != "@excluded" && records.apps.find { a -> a.pkg==it.pkg }?.hidden != true }
internal fun hourBoundaries(date:LocalDate):List<Long> {
    val start=date.atStartOfDay(ZoneId.systemDefault())
    val end=date.plusDays(1).atStartOfDay(ZoneId.systemDefault())
    return generateSequence(start) { it.plusHours(1) }.takeWhile { it < end }.map { it.toInstant().toEpochMilli() }.toList()+end.toInstant().toEpochMilli()
}

@Composable internal fun ChartTitle(title:String, trailing:String?=null, onClick:()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=14.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
        if(trailing!=null) TextButton(onClick=onClick) { Text(trailing) }
    }
}

/** Device minutes are a union; inferred app minutes occupy a separate series. */
@Composable internal fun HourlyChart(records:Records, date:LocalDate, pkg:String?=null, selectedHour:Int?=null, onSelect:(Long,Long)->Unit) {
    val boundaries=hourBoundaries(date)
    val observed=if(pkg==null) records.dayReport(date).spans.filter { it.state==com.goooseby.tickmark.engine.model.DeviceState.IN_USE }.map { it.start to it.end }
        else records.sessions.filter { it.pkg==pkg && !it.inferred }.map { it.start to it.end }
    val inferred=records.sessions.filter { it.inferred && (pkg==null || it.pkg==pkg) }.map { it.start to it.end }
    val known=ReportEngine.buckets(observed,boundaries)
    val estimated=ReportEngine.buckets(inferred,boundaries)
    val primary=MaterialTheme.colorScheme.primary
    val estimateColor=MaterialTheme.colorScheme.tertiary.copy(alpha=.55f)
    val grid=MaterialTheme.colorScheme.outlineVariant
    val background=MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.45f)
    val now=System.currentTimeMillis()
    val description=known.mapIndexed { i,b -> "${Instant.ofEpochMilli(b.start).atZone(ZoneId.systemDefault()).hour}时：${duration(b.duration)}，推算${duration(estimated[i].duration)}" }.joinToString("；")
    Column(Modifier.padding(horizontal=20.dp)) {
        Text("分钟",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(148.dp)) {
            Column(Modifier.width(24.dp).fillMaxHeight(),verticalArrangement=Arrangement.SpaceBetween) { listOf("60","30","0").forEach { Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
            Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription=description }.pointerInput(boundaries) { detectTapGestures { offset -> val index=(offset.x/size.width*known.size).toInt().coerceIn(known.indices); if(boundaries[index]<now) onSelect(boundaries[index],boundaries[index+1]) } }) {
                val step=size.width/known.size
                listOf(0f,.5f,1f).forEach { drawLine(grid,Offset(0f,size.height*it),Offset(size.width,size.height*it),1.dp.toPx()) }
                known.forEachIndexed { i,b ->
                    val x=i*step+step*.16f
                    if(Instant.ofEpochMilli(b.start).atZone(ZoneId.systemDefault()).hour==selectedHour) drawRect(background,Offset(i*step,0f),Size(step,size.height))
                    val height=(b.duration/3_600_000f).coerceIn(0f,1f)*size.height
                    val extra=(estimated[i].duration/3_600_000f).coerceIn(0f,1f)*size.height
                    if(b.start<now) {
                        if(height>0) drawRoundRect(primary,Offset(x,size.height-height),Size(step*.68f,height),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                        if(extra>0) drawRect(estimateColor,Offset(x,(size.height-height-extra).coerceAtLeast(0f)),Size(step*.68f,extra.coerceAtMost(size.height-height)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=24.dp,top=6.dp),horizontalArrangement=Arrangement.SpaceBetween) { listOf("00","06","12","18","24").forEach { Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        if(estimated.any {it.duration>0}) Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)) { ChartLegend(primary,"使用时长"); ChartLegend(estimateColor,"估算") }
    }
}

@Composable internal fun ChartLegend(color:Color,label:String) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) { Box(Modifier.size(8.dp).background(color,CircleShape)); Text(label,style=MaterialTheme.typography.labelSmall) }
}

@Composable internal fun CompositionChart(records:Records, from:Long, to:Long, onApp:(String)->Unit) {
    val ranking=composition(visibleSessions(records),from,to)
    val shown=ranking.take(4).mapIndexed { i,p -> Triple(p.first,p.second,palette[i]) }.toMutableList()
    val other=ranking.drop(4).sumOf { it.second }
    if(other>0) shown+=Triple("@other",other,palette.last())
    val total=shown.sumOf { it.second }
    if(total==0L) { EmptyReport("这段时间暂无应用记录"); return }
    val description=shown.joinToString("；") { "${if(it.first=="@other") "其他应用" else appName(it.first,records)} ${duration(it.second)}，${(it.second.toDouble()/total*100).roundToInt()}%" }
    val track=MaterialTheme.colorScheme.surfaceVariant
    val sliceNames=shown.map { if(it.first=="@other") "其他应用" else appName(it.first,records) }
    var allApps by remember(from,to) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(180.dp),contentAlignment=Alignment.Center) {
            StatisticsInfoButton("应用构成统计说明",APP_COMPOSITION_INFO,Modifier.align(Alignment.TopEnd))
            Canvas(Modifier.size(164.dp).semantics { contentDescription="应用前台时长构成：$description" }.pointerInput(shown) { detectTapGestures { point ->
                val dx=point.x-size.width/2; val dy=point.y-size.height/2
                val radius=hypot(dx,dy)
                if(radius in size.width*.28f..size.width*.5f) {
                    val angle=((atan2(dy,dx)*180/PI).toFloat()+450)%360
                    var boundary=0f
                    shown.forEach { slice -> val sweep=slice.second.toFloat()/total*360; if(angle>=boundary && angle<boundary+sweep && slice.first!="@other") onApp(slice.first); boundary+=sweep }
                }
            } }) {
                val stroke=24.dp.toPx(); val inset=stroke/2
                drawArc(track,0f,360f,false,Offset(inset,inset),Size(size.width-stroke,size.height-stroke),style=Stroke(stroke))
                var angle=-90f
                shown.forEach { (_,amount,color) -> val sweep=amount.toFloat()/total*360; drawArc(color,angle+.8f,(sweep-1.6f).coerceAtLeast(.1f),false,Offset(inset,inset),Size(size.width-stroke,size.height-stroke),style=Stroke(stroke)); angle+=sweep }
            }
            Column(Modifier.width(112.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text("应用前台",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(duration(total),style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2) }
        }
        shown.forEachIndexed { i,(pkg,amount,color) ->
            Row(Modifier.fillMaxWidth().then(if(pkg!="@other") Modifier.clickable { onApp(pkg) } else Modifier).padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(9.dp).background(color,CircleShape))
                Text(sliceNames[i],Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(duration(amount),style=MaterialTheme.typography.bodyMedium)
                Text("${(amount.toDouble()/total*100).roundToInt()}%",Modifier.widthIn(min=38.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(ranking.size>4) {
            TextButton(onClick={ allApps=!allApps },modifier=Modifier.padding(horizontal=12.dp)) { Text(if(allApps) "收起应用" else "全部 ${ranking.size} 个应用") }
            if(allApps) ranking.drop(4).forEach { (pkg,amount) ->
                Row(Modifier.fillMaxWidth().clickable { onApp(pkg) }.padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(appName(pkg,records),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(duration(amount),style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable internal fun TrendChart(records:Records,date:LocalDate,pkg:String?=null,onDate:(LocalDate)->Unit) {
    val dates=(6 downTo 0).map { date.minusDays(it.toLong()) }
    val values=dates.map { d ->
        val from=hourBoundaries(d).first(); val to=minOf(hourBoundaries(d).last(),System.currentTimeMillis())
        val intervals=if(pkg!=null) records.sessions.filter { it.pkg==pkg }.map { it.start to it.end } else records.deviceSpans.filter { it.state==com.goooseby.tickmark.engine.model.DeviceState.IN_USE }.map { it.start to it.end }
        val hasEvidence=records.deviceSpans.any { it.state!=com.goooseby.tickmark.engine.model.DeviceState.UNKNOWN && it.start<to && it.end>from } || (pkg!=null && records.sessions.any { it.pkg==pkg && it.start<to && it.end>from })
        if(!hasEvidence) null else unionDuration(intervals.map { maxOf(it.first,from) to minOf(it.second,to) })
    }
    val maxValue=maxOf(3_600_000L,values.filterNotNull().maxOrNull() ?: 0)
    val primary=MaterialTheme.colorScheme.primary; val grid=MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(horizontal=20.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(duration(maxValue),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(if(pkg==null) "手机使用" else "应用用时",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical=10.dp).semantics { contentDescription=dates.zip(values).joinToString("；") { (d,v)->"$d：${v?.let { duration(it) } ?: "记录不足"}" } }.pointerInput(dates) { detectTapGestures { p -> onDate(dates[(p.x/size.width*6).roundToInt().coerceIn(0,6)]) } }) {
            listOf(0f,.5f,1f).forEach { drawLine(grid,Offset(0f,size.height*it),Offset(size.width,size.height*it),1.dp.toPx()) }
            val points=values.mapIndexed { i,v -> v?.let { Offset(i*size.width/6,size.height-it.toFloat()/maxValue*size.height) } }
            points.zipWithNext().forEach { (a,b) -> if(a!=null && b!=null) drawLine(primary,a,b,2.dp.toPx()) }
            points.forEach { if(it!=null) drawCircle(primary,4.dp.toPx(),it) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { dates.forEach { d -> Text(d.format(DateTimeFormatter.ofPattern("M/d")),Modifier.clickable { onDate(d) }.padding(vertical=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        if(values.any { it==null }) Text("断点表示记录不足",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Rows answer when each app dominated; exact event order stays in optional details. */
@Composable internal fun AppDistribution(records:Records,from:Long,to:Long,onApp:(String)->Unit) {
    if(to<=from) { EmptyReport("此时段尚未开始"); return }
    val ranking=composition(visibleSessions(records),from,to).take(5)
    val steps=if(to-from<=3_600_000) 12 else 24
    val boundaries=(0..steps).map { from+(to-from)*it/steps }
    Column(Modifier.padding(horizontal=20.dp)) {
        Text("每格约${duration((to-from)/steps)} · 颜色越深，用时越多",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ranking.forEachIndexed { index,(pkg,_) ->
            val values=ReportEngine.buckets(records.sessions.filter { it.pkg==pkg }.map { it.start to it.end },boundaries)
            Column(Modifier.fillMaxWidth().clickable { onApp(pkg) }.padding(vertical=10.dp)) {
                Text(appName(pkg,records),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    values.forEach { b -> val ratio=(b.duration.toFloat()/(b.end-b.start)).coerceIn(0f,1f); Box(Modifier.weight(1f).height(18.dp).background(if(ratio>0) palette[index].copy(alpha=.18f+.82f*ratio) else MaterialTheme.colorScheme.surfaceVariant)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { listOf(from,to).forEach { Text(android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(it)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable internal fun EmptyReport(text:String) { Text(text,Modifier.fillMaxWidth().padding(20.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
