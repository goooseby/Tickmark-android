package com.goooseby.tickmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goooseby.tickmark.app.Records
import com.goooseby.tickmark.engine.analytics.*
import com.goooseby.tickmark.engine.model.Session
import com.goooseby.tickmark.engine.model.overlap
import com.goooseby.tickmark.engine.model.unionDuration
import java.time.LocalDate

@Composable internal fun GoalSettings(records:Records,onSave:(String)->Unit) {
    var value by rememberSaveable(records.config["dailyGoalMinutes"]) { mutableStateOf(records.config["dailyGoalMinutes"].orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("每日手机用时",style=MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(value,{if(it.length<=4 && it.all(Char::isDigit)) {value=it;saved=false}},label={Text("每天最多多少分钟（留空关闭）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth()) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(60,90,120,180).forEach { minutes->SuggestionChip(onClick={value=minutes.toString();saved=false},label={Text("${minutes}分钟")}) } } }
        item { Text("选择一个适合自己的目标。首页会显示已用时间，不强制拦截，也不会因为某一天超时重置你的进度。",style=MaterialTheme.typography.bodyMedium) }
        item { Button(onClick={onSave(value);saved=true},enabled=value.isEmpty() || value.toIntOrNull()?.let{it in 1..1440}==true) {Text(if(saved) "已保存" else "保存目标")} }
        item { Text("单应用目标可在“应用管理”中设置。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun GoalProgress(records:Records,date:LocalDate,onEdit:()->Unit) {
    val goal=records.config["dailyGoalMinutes"]?.toLongOrNull()?.takeIf {it>0} ?: return
    val start=dayStart(date);val end=minOf(dayStart(date.plusDays(1)),System.currentTimeMillis())
    val used=records.spans.filter{it.state=="IN_USE"}.sumOf {overlap(it.start,it.end,start,end)}
    Column(Modifier.padding(horizontal=20.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("每日目标",style=MaterialTheme.typography.labelLarge);TextButton(onClick=onEdit,contentPadding=PaddingValues(horizontal=8.dp)) {Text("${duration(used)} / ${duration(goal*60_000)}")} }
        LinearProgressIndicator(progress={(used.toFloat()/(goal*60_000)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=if(used>goal*60_000) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,drawStopIndicator={})
    }
}
@Composable internal fun DailyReflection(records:Records,date:LocalDate,onSave:(String,String,String)->Unit) {
    val prefix="review:$date:"
    var good by rememberSaveable(date.toString()) {mutableStateOf(records.config[prefix+"good"].orEmpty())}
    var change by rememberSaveable(date.toString()) {mutableStateOf(records.config[prefix+"change"].orEmpty())}
    var next by rememberSaveable(date.toString()) {mutableStateOf(records.config[prefix+"next"].orEmpty())}
    var saved by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Text("${date.monthValue}月${date.dayOfMonth}日",style=MaterialTheme.typography.titleMedium)}
        item {OutlinedTextField(good,{if(it.length<=1000){good=it;saved=false}},label={Text("今天值得保留的一件事")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)}
        item {OutlinedTextField(change,{if(it.length<=1000){change=it;saved=false}},label={Text("哪一段用时想调整")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)}
        item {OutlinedTextField(next,{if(it.length<=1000){next=it;saved=false}},label={Text("明天的一个小行动")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)}
        item {Button(onClick={onSave(good.trim(),change.trim(),next.trim());saved=true}) {Text(if(saved) "已保存" else "保存复盘")}}
    }
}
@Composable internal fun AppGoalProgress(records:Records,pkg:String,date:LocalDate) {
    val goal=records.config["appGoal:$pkg"]?.toLongOrNull()?.takeIf{it>0} ?: return
    val from=dayStart(date);val to=minOf(dayStart(date.plusDays(1)),System.currentTimeMillis())
    val used=unionDuration(records.sessions.filter{it.pkg==pkg && !it.inferred}.map{maxOf(it.start,from) to minOf(it.end,to)})
    Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("当天目标 ${duration(used)} / ${duration(goal*60_000)}",style=MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress={(used.toFloat()/(goal*60_000)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),drawStopIndicator={})
    }
}
@Composable internal fun PurposeReport(records:Records,from:Long,to:Long) {
    val totals=remember(records.sessions,records.spans,records.annotations,records.apps,from,to) {
        PurposeEngine.totals(records.sessions.map {Session(it.pkg,it.start,it.end,it.inferred,it.ongoing)},records.spans.filter{it.state=="IN_USE"}.map{it.start to it.end},records.annotations.map{PurposeRange(it.start,it.end,it.purpose)},records.apps.associate{it.pkg to it.purpose},from,to)
    }
    if(totals.isEmpty()) {EmptyReport("暂无可分类的手机用时");return}
    Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        totals.forEach { (purpose,amount)->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(purpose,style=MaterialTheme.typography.bodyMedium);Text(duration(amount),style=MaterialTheme.typography.bodyMedium)}
            LinearProgressIndicator(progress={amount.toFloat()/totals.sumOf{it.second}},modifier=Modifier.fillMaxWidth().height(6.dp),color=reportColor(purpose),drawStopIndicator={})
        }
        StatisticsInfoButton("用途统计说明","用途优先使用你的时段标注，再使用应用的默认用途。只分配已确认的手机使用时间；多个不同用途的应用同时活跃时计入“并发用途”，不会重复累加。未标注的记录保留为未分类。")
    }
}
@Composable internal fun DataSettings(bytes:Long,lastBackup:String?,onBackup:()->Unit,onExport:()->Unit,onRestore:()->Unit,onClear:()->Unit) {
    LazyColumn(contentPadding=PaddingValues(bottom=20.dp)) {
        item {ListItem(headlineContent={Text("本地数据")},supportingContent={Text("${"%.2f".format(bytes/1_048_576.0)} MB · 历史记录保存在此设备")})}
        item {SettingsLink("保存 JSON 备份",lastBackup ?: "保存使用记录、目标与复盘",Icons.Outlined.Save,onBackup)}
        item {SettingsLink("导出 CSV 会话表","用于自己的表格分析",Icons.Outlined.TableChart,onExport)}
        item {SettingsLink("从备份恢复","先预览，再替换当前数据",Icons.Outlined.Restore,onRestore)}
        item {SettingsLink("清空数据","选择重新采集或暂停",Icons.Outlined.DeleteOutline,onClear)}
        item {Text("备份含应用使用记录与个人备注，请妥善保管。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
