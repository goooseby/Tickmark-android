package io.github.goooseby.tickmark.ui

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
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.data.appCategory
import io.github.goooseby.tickmark.engine.analytics.*
import io.github.goooseby.tickmark.engine.model.Session
import io.github.goooseby.tickmark.engine.model.overlap
import io.github.goooseby.tickmark.engine.model.unionDuration
import java.time.LocalDate

@Composable internal fun GoalSettings(records:Records,onSave:(String)->Unit) {
    var value by rememberSaveable(records.config["dailyGoalMinutes"]) { mutableStateOf(records.config["dailyGoalMinutes"].orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("每日手机用时",style=MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(value,{if(it.length<=4 && it.all(Char::isDigit)) {value=it;saved=false}},label={Text("每天最多多少分钟（留空关闭）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth()) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(60,90,120,180).forEach { minutes->SuggestionChip(onClick={value=minutes.toString();saved=false},label={Text("${minutes}分钟")}) } } }
        item { Text("目标用于首页进度，不拦截应用。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick={onSave(value);saved=true},enabled=value.isEmpty() || value.toIntOrNull()?.let{it in 1..1440}==true) {Text(if(saved) "已保存" else "保存目标")} }
        item { Text("单应用目标可在“应用管理”中设置。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun GoalProgress(records:Records,date:LocalDate,onEdit:()->Unit) {
    val goal=records.config["dailyGoalMinutes"]?.toLongOrNull()?.takeIf {it>0} ?: return
    val start=dayStart(date);val end=minOf(dayStart(date.plusDays(1)),System.currentTimeMillis())
    val used=records.dayReport(date).used
    Column(Modifier.padding(horizontal=20.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("每日目标",style=MaterialTheme.typography.labelLarge);TextButton(onClick=onEdit,contentPadding=PaddingValues(horizontal=8.dp)) {Text("${duration(used)} / ${duration(goal*60_000)}")} }
        LinearProgressIndicator(progress={(used.toFloat()/(goal*60_000)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=if(used>goal*60_000) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,drawStopIndicator={})
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
@Composable internal fun CategoryReport(records:Records,from:Long,to:Long) {
    val totals=remember(records.sessions,records.apps,from,to) {
        CategoryEngine.totals(visibleSessions(records).map {Session(it.pkg,it.start,it.end,it.inferred,it.ongoing)},records.apps.associate{it.pkg to it.appCategory},from,to)
    }
    if(totals.isEmpty()) {EmptyReport("暂无可分类的手机用时");return}
    val reveal by chartReveal(listOf(from,totals))
    Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        totals.forEach { (category,amount)->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(category.title,style=MaterialTheme.typography.bodyMedium);Text(duration(amount),style=MaterialTheme.typography.bodyMedium)}
            LinearProgressIndicator(progress={amount.toFloat()/totals.sumOf{it.second}*reveal},modifier=Modifier.fillMaxWidth().height(6.dp),color=reportColor(category.name),drawStopIndicator={})
        }
        StatisticsInfoButton("应用分类统计说明","按应用所属类别汇总前台用时，包含应用图表中的推算片段；时段标注不改变应用类别。分屏应用分别计时，合计可能超过手机使用时长。隐藏和隐私排除的应用不参与，无法识别的应用保留未分类。")
    }
}
