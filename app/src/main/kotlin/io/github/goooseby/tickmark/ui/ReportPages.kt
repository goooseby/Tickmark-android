package io.github.goooseby.tickmark.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.engine.model.unionDuration
import java.time.*

@Composable internal fun TodayReports(records:Records,date:LocalDate,from:Long,to:Long,filtered:Boolean,onHour:(Int)->Unit,onClearRange:()->Unit,onRange:()->Unit,onDetail:()->Unit,onApp:(SessionRow)->Unit,onEdit:(AnnotationRow)->Unit,onGoal:()->Unit={},onJournal:()->Unit={},onAway:()->Unit={}) {
    fun openApp(pkg:String) { records.sessions.firstOrNull { it.pkg==pkg }?.let(onApp) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=20.dp)) {
        item { Summary(records,dayStart(date),minOf(dayStart(date.plusDays(1)),System.currentTimeMillis())) }
        item { GoalProgress(records,date,onGoal) }
        item { ChartTitle("每小时使用", "选择时段",onRange) }
        item { HourlyChart(records,date,selectedHour=if(filtered) Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).hour else null) { start,_ -> onHour(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).hour) } }
        item { AwayOverview(records.deviceReport(from,to),onAway) }
        item {
            ChartTitle(if(filtered) "${clock(from)} – ${clock(to)}" else "全天应用构成",if(filtered) "查看全天" else null,onClearRange)
            if(filtered) {
                val known=records.deviceReport(from,to).used
                Text("已确认使用 ${duration(known)}",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { CompositionChart(records,from,to,::openApp) }
        if(filtered) {
            item { ChartTitle("时段内的应用分布","复盘与标注",onDetail) }
            item { AppDistribution(records,from,to,::openApp) }
        }
        item { ChartTitle("每日复盘","写复盘",onJournal) }
        val intention=records.config["review:$date:next"].orEmpty()
        if(intention.isNotBlank()) item { Text(intention,Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium) }
        val notes=records.annotations.filter { it.start<to && it.end>from }
        if(notes.isNotEmpty()) {
            item { ChartTitle("标注") }
            items(notes) { AnnotationLine(it,from,to) { onEdit(it) } }
        }
    }
}

@Composable internal fun ReviewReports(records:Records,date:LocalDate,from:Long,to:Long,onApp:(String)->Unit,onRange:()->Unit,onDate:(LocalDate)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=20.dp)) {
        item { ChartTitle("近7天趋势") }
        item { TrendChart(records,date,onDate=onDate) }
        item { ChartTitle("应用构成","选择时段",onRange) }
        item { Text("${date.monthValue}月${date.dayOfMonth}日 · ${periodLabel(from,to)}",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { CompositionChart(records,from,to,onApp) }
        item { ChartTitle("使用时段") }
        item { AppDistribution(records,from,to,onApp) }
        item { ChartTitle("用途构成") }
        item { PurposeReport(records,from,to) }
        item { CoverageNote(records,from,to) }
    }
}

@Composable internal fun DetailReports(pkg:String,records:Records,selected:List<SessionRow>,from:Long,to:Long,onApp:(String)->Unit,onAnnotate:()->Unit,onEdit:(AnnotationRow)->Unit,onUpdateApp:(AppRow)->Unit,onDate:(LocalDate)->Unit,weekly:Boolean=false) {
    val range=pkg=="@range"
    val sessions=if(range) selected else selected.filter { it.pkg==pkg }
    val app=records.apps.find { it.pkg==pkg }
    var raw by rememberSaveable(pkg,from,to) { mutableStateOf(false) }
    var settings by rememberSaveable(pkg) { mutableStateOf(false) }
    var exclude by remember { mutableStateOf(false) }
    val date=Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate()
    var selectedBin by rememberSaveable(pkg,from,to) { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
        item { Text(if(weekly) "${date.monthValue}/${date.dayOfMonth} – ${date.plusDays(6).monthValue}/${date.plusDays(6).dayOfMonth} · 整周" else "${date.monthValue}月${date.dayOfMonth}日 · ${periodLabel(from,to)}",Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(range) {
            item { Summary(records,from,to) }
            item { ChartTitle("应用构成") }
            item { CompositionChart(records,from,to,onApp) }
            item { ChartTitle("应用分布") }
            item { AppDistribution(records,from,to,onApp) }
        } else if(weekly) {
            item { Row(Modifier.padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { AppIcon(pkg); Metric("整周应用用时",duration(unionDuration(sessions.map { maxOf(it.start,from) to minOf(it.end,to) })),Modifier.weight(1f)) } }
            item { ChartTitle("每日使用") }
            item { WeeklyTrend(records,date,pkg,onDate) }
            item { ChartTitle("一周时段分布") }
            item { WeekHeatmap(records,date,pkg) { a,_ -> onDate(Instant.ofEpochMilli(a).atZone(ZoneId.systemDefault()).toLocalDate()) } }
        } else {
            item {
                Row(Modifier.padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    AppIcon(pkg)
                    Metric("所选时段用时",duration(unionDuration(sessions.map { maxOf(it.start,from) to minOf(it.end,to) })),Modifier.weight(1f))
                    Metric("活跃小时",hourBoundaries(date).zipWithNext().count { (a,b) -> sessions.any { it.start<minOf(b,to) && it.end>maxOf(a,from) } }.toString(),Modifier.weight(.7f))
                }
            }
            item { AppGoalProgress(records,pkg,date) }
            item { ChartTitle("当天使用分布") }
            item { HourlyChart(records,date,pkg,selectedBin?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour }) { a,_ -> selectedBin=a } }
            selectedBin?.let { start -> item {
                val total=unionDuration(records.sessions.filter { it.pkg==pkg }.map { maxOf(it.start,start) to minOf(it.end,start+3_600_000) })
                Text("${clock(start)} – ${clock(start+3_600_000)}：${duration(total)}",Modifier.padding(horizontal=20.dp,vertical=10.dp),style=MaterialTheme.typography.bodyMedium)
            } }
            item { ChartTitle("近7天趋势") }
            item { TrendChart(records,date,pkg,onDate=onDate) }
        }
        if(!weekly) {
            item { ChartTitle("时段标注","添加",onAnnotate) }
            val notes=records.annotations.filter { it.start<to && it.end>from }
            if(notes.isEmpty()) item { Text("暂无标注",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(notes) { AnnotationLine(it,from,to) { onEdit(it) } }
        }
        item { CoverageNote(records,from,to) }
        if(!weekly) {
            item { HorizontalDivider(Modifier.padding(horizontal=20.dp,vertical=12.dp)); ListItem(headlineContent={ Text(if(raw) "收起原始会话" else "查看原始会话") },supportingContent={ Text("${sessions.size} 条记录") },trailingContent={ Icon(if(raw) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null) },modifier=Modifier.clickable { raw=!raw }) }
            if(raw) items(sessions) { s -> SessionLine(s,records,from,to) { if(range) onApp(s.pkg) } }
        }
        if(!range && app!=null) {
            item { ListItem(headlineContent={ Text("应用设置") },trailingContent={ Icon(if(settings) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null) },modifier=Modifier.clickable { settings=!settings }) }
            if(settings) {
                item { ListItem(headlineContent={ Text("从排行隐藏") },trailingContent={ Switch(app.hidden,onCheckedChange={ onUpdateApp(app.copy(hidden=it)) }) }) }
                item { ListItem(headlineContent={ Text("停止保存应用明细") },supportingContent={ Text(if(app.excluded) "只保留匿名时段" else "已有明细同时匿名化") },trailingContent={ Switch(app.excluded,onCheckedChange={ if(it) exclude=true else onUpdateApp(app.copy(excluded=false)) }) }) }
                item { Text("默认用途",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge); FlowRow(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("未分类","学习","课程事务","社交","娱乐","生活","其他").forEach { p -> FilterChip(app.purpose==p || (p=="未分类" && app.purpose.isEmpty()),onClick={ onUpdateApp(app.copy(purpose=if(p=="未分类") "" else p)) },label={ Text(p) }) } } }
                item { Text(app.pkg,Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if(exclude && app!=null) AlertDialog(onDismissRequest={ exclude=false },title={ Text("停止保存此应用明细？") },text={ Text("已有身份记录将匿名化，无法恢复。用途标注和备注会保留。") },confirmButton={ TextButton(onClick={ onUpdateApp(app.copy(excluded=true)); exclude=false }) { Text("匿名化并停止保存") } },dismissButton={ TextButton(onClick={ exclude=false }) { Text("取消") } })
}

@Composable private fun AnnotationLine(a:AnnotationRow,from:Long,to:Long,onClick:()->Unit) {
    ListItem(headlineContent={ Text(a.purpose.ifEmpty { "备注" }) },supportingContent={ Text("${clock(maxOf(a.start,from))} – ${clock(minOf(a.end,to))}${if(a.note.isNotBlank()) " · ${a.note}" else ""}",maxLines=2,overflow=TextOverflow.Ellipsis) },trailingContent={ Icon(Icons.Outlined.EditNote,null) },modifier=Modifier.clickable(onClick=onClick))
}
@Composable private fun CoverageNote(records:Records,from:Long,to:Long) {
    // Coverage explanations live behind the summary info button and collection status.
}
