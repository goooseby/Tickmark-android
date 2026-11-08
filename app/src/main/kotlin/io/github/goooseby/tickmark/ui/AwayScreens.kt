package io.github.goooseby.tickmark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.engine.analytics.AwayPeriod
import io.github.goooseby.tickmark.engine.analytics.DeviceReport
import io.github.goooseby.tickmark.engine.model.DeviceState
import java.time.*

internal const val AWAY_INFO="这里统计有息屏或锁屏依据的时段，不把记录缺失算作未使用。相邻的息屏与锁屏可合并为一段，遇到使用或未知状态会断开。跨日只计当天部分；进行中的时段截至最近一次采集，不持续猜测。息屏或锁屏不代表学习、睡眠或专注，用途可由你补记。"

@Composable internal fun AwayOverview(report:DeviceReport,onOpen:()->Unit) {
    ChartTitle("未使用手机","查看时段",onOpen)
    Row(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Metric("累计",duration(report.notUsed),Modifier.weight(1f))
        Metric("最长一段",duration(report.longestAway?.duration ?: 0),Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun AwayPage(records:Records,from:Long,to:Long,onAnnotate:(Long,Long)->Unit) {
    val report=remember(records.spans,from,to){records.deviceReport(from,to)}
    var showAll by rememberSaveable(from,to){mutableStateOf(false)}
    var chronological by rememberSaveable(from,to){mutableStateOf(false)}
    val periods=report.away.filter{showAll || it.duration>=5*60_000}.let {if(chronological) it else it.sortedByDescending{p->p.duration}}
    val date=Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("${date.monthValue}月${date.dayOfMonth}日 · ${periodLabel(from,to)}",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                StatisticsInfoButton("未使用时段统计说明",AWAY_INFO)
            }
        }
        item {Row(Modifier.padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Metric("累计未使用",duration(report.notUsed),Modifier.weight(1f))
            Metric("最长一段",duration(report.longestAway?.duration ?: 0),Modifier.weight(1f))
        }}
        item {ChartTitle("全天分布")}
        item {AwayTimeline(report)}
        item {Row(Modifier.padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(showAll,onClick={showAll=!showAll},label={Text(if(showAll) "全部时段" else "至少5分钟")})
            FilterChip(chronological,onClick={chronological=!chronological},label={Text(if(chronological) "时间顺序" else "最长优先")})
        }}
        if(periods.isEmpty()) item {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(when {report.spans.all{it.state==DeviceState.UNKNOWN}->"这段日期尚无足够记录";report.away.isEmpty()->"暂无息屏或锁屏时段";else->"暂无5分钟以上的时段"},style=MaterialTheme.typography.bodyMedium)
                if(report.away.isNotEmpty() && !showAll) TextButton(onClick={showAll=true}){Text("查看全部")}
            }
        }
        items(periods,key={it.start}) { period ->
            val note=records.annotations.filter{it.start<period.end && it.end>period.start}.map{it.note.ifBlank{it.purpose}}.filter{it.isNotBlank()}.distinct().joinToString(" · ")
            ListItem(
                headlineContent={Text("${clock(period.start)} – ${clock(period.end)}")},
                supportingContent={Text(listOfNotNull(when {period.screenOffDuration>0 && period.lockedDuration>0->"息屏与锁屏";period.screenOffDuration>0->"息屏";else->"锁屏"},if(period.originalStart<from) "跨日延续" else null,note.takeIf{it.isNotBlank()}).joinToString(" · "),maxLines=2)},
                leadingContent={Icon(if(period.screenOffDuration>0) Icons.Outlined.NightsStay else Icons.Outlined.Lock,null,tint=MaterialTheme.colorScheme.secondary)},
                trailingContent={Text(duration(period.duration),style=MaterialTheme.typography.labelLarge)},
                modifier=Modifier.clickable{onAnnotate(period.start,period.end)})
        }
        records.config["lastObserved"]?.toLongOrNull()?.let { observed -> item {
            Text("截至 ${clock(observed)}",Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
    }
}

@Composable private fun AwayTimeline(report:DeviceReport) {
    val off=MaterialTheme.colorScheme.secondary
    val locked=MaterialTheme.colorScheme.tertiary
    val used=MaterialTheme.colorScheme.outlineVariant
    val unknown=MaterialTheme.colorScheme.surfaceContainerHighest
    val reveal by chartReveal(report)
    Column(Modifier.padding(horizontal=20.dp)) {
        Canvas(Modifier.fillMaxWidth().height(64.dp).semantics{contentDescription="息屏${duration(report.screenOff)}，锁屏${duration(report.locked)}，记录空档${duration(report.unknown)}"}) {
            val range=(report.until-report.from).coerceAtLeast(1)
            report.spans.forEach { span ->
                val x=(span.start-report.from).toFloat()/range*size.width
                val width=(span.end-span.start).toFloat()/range*size.width
                drawRect(when(span.state){DeviceState.SCREEN_OFF->off;DeviceState.LOCKED->locked;DeviceState.IN_USE->used;else->unknown},Offset(x,0f),Size(width,size.height),alpha=reveal)
            }
        }
        val crossesDate=Instant.ofEpochMilli(report.until).atZone(ZoneId.systemDefault()).toLocalDate()>
            Instant.ofEpochMilli(report.from).atZone(ZoneId.systemDefault()).toLocalDate()
        Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(clock(report.from),style=MaterialTheme.typography.labelSmall)
            Text((if(crossesDate) "次日 " else "")+clock(report.until),style=MaterialTheme.typography.labelSmall)
        }
        FlowRow(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            ChartLegend(off,"息屏");ChartLegend(locked,"锁屏");ChartLegend(used,"使用");ChartLegend(unknown,"空档")
        }
    }
}
