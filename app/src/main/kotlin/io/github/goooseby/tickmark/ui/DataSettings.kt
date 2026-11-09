@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.goooseby.tickmark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.engine.analytics.ReportEngine
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun DataSettings(
    records:Records,bytes:Long,busy:Boolean,onExport:(UsageExportOptions,Boolean)->Unit,
    onBackup:(Boolean)->Unit,onRestore:()->Unit,onClear:()->Unit,onHistory:()->Unit,
) {
    val today=LocalDate.now()
    var period by rememberSaveable {mutableStateOf("today")}
    var first by rememberSaveable {mutableStateOf(today.toString())}
    var last by rememberSaveable {mutableStateOf(today.toString())}
    var formatName by rememberSaveable {mutableStateOf(UsageExportFormat.JSON.name)}
    var details by rememberSaveable {mutableStateOf(false)}
    var notes by rememberSaveable {mutableStateOf(false)}
    var hidden by rememberSaveable {mutableStateOf(false)}
    var showRange by rememberSaveable {mutableStateOf(false)}
    var help by rememberSaveable {mutableStateOf(false)}
    var backupOptions by rememberSaveable {mutableStateOf(false)}
    var advanced by rememberSaveable {mutableStateOf(false)}
    val bounds=remember(records.sessions,records.spans,records.summaries) {
        val start=minOf(records.summaries.minOfOrNull{it.start} ?: Long.MAX_VALUE,records.sessions.minOfOrNull{it.start} ?: Long.MAX_VALUE,records.spans.filter{it.state!="UNKNOWN"}.minOfOrNull{it.start} ?: Long.MAX_VALUE)
        val end=maxOf(records.summaries.maxOfOrNull{it.end} ?: 0,records.sessions.maxOfOrNull{it.end} ?: 0,records.spans.filter{it.state!="UNKNOWN"}.maxOfOrNull{it.end} ?: 0)
        if(start<end) Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate() to
            Instant.ofEpochMilli(end-1).atZone(ZoneId.systemDefault()).toLocalDate() else null
    }
    val range=when(period) {
        "week"->ReportEngine.weekStart(today) to today
        "all"->(bounds?.first ?: today) to today
        "custom"->LocalDate.parse(first) to LocalDate.parse(last)
        else->today to today
    }
    val format=UsageExportFormat.valueOf(formatName)
    val options=UsageExportOptions(range.first,range.second,format=format,details=details,notes=notes && format==UsageExportFormat.JSON,hidden=hidden)
    val preview=remember(records,options){UsageExport(records,options)}
    val label=if(range.first==range.second) range.first.toString() else "${range.first} — ${range.second}"
    LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            ReportSurface {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Icons.Outlined.Storage)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("本地使用记录", style = MaterialTheme.typography.titleSmall)
                        Text(bounds?.let{"${it.first} — ${it.second}"} ?: "尚无使用记录", style = MaterialTheme.typography.bodyMedium)
                        Text("${records.sessions.size} 段会话 · ${records.journal.count{it.meaningful}} 条记录 · ${String.format(Locale.getDefault(),"%.2f",bytes/1_048_576.0)} MB",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, "数据来源与导出说明") }
                }
            }
        }
        if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=8.dp))}
        item {SettingsSection("导出使用数据")}
        item {Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("today" to "今天","week" to "本周","custom" to "自选日期","all" to "全部").forEach{(key,title)->
                    FilterChip(selected=period==key,onClick={if(key=="custom") showRange=true else period=key},enabled=!busy,label={Text(title)})
                }
            }
            Row(Modifier.fillMaxWidth().clickable(enabled=!busy){showRange=true}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.DateRange,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
                Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.Edit,"修改导出日期",Modifier.size(18.dp))
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UsageExportFormat.entries.forEachIndexed{i,value->SegmentedButton(selected=format==value,onClick={formatName=value.name},enabled=!busy,
                    shape=SegmentedButtonDefaults.itemShape(i,UsageExportFormat.entries.size)){Text(value.label)}}
            }
            Text(if(format==UsageExportFormat.JSON) "每日、小时与应用统计，适合发给 AI 分析。" else if(format==UsageExportFormat.CSV) "逐段应用使用时长，适合表格处理。只保留所选日期内的部分。" else "系统保留的日、周、月、年汇总。仅导出完整落在所选日期内的统计区间，不同粒度不可相加。",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        if(format==UsageExportFormat.JSON) {
            item {ExportCheck("附带会话明细","加入每段应用使用的起止时间",details,!busy){details=it}}
            item {ExportCheck("附带复盘与备注","仅所选日期的已保存记录，不含草稿",notes,!busy){notes=it}}
        }
        item { TextButton(onClick={advanced=!advanced},enabled=!busy,modifier=Modifier.padding(horizontal=12.dp)) {
            Text(if(advanced) "收起应用范围" else "应用范围")
            Icon(if(advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)
        }}
        if(advanced) item {ExportCheck("包含排行隐藏的应用","隐私排除的应用始终不导出身份",hidden,!busy){hidden=it}}
        item {Surface(Modifier.fillMaxWidth().padding(horizontal=20.dp),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(preview.available) "${preview.appCount} 个应用 · ${if(format==UsageExportFormat.SUMMARY_CSV) "${preview.summaries.size} 条汇总" else "${preview.sessions.size} 段会话"}" else "所选日期没有可导出的数据",style=MaterialTheme.typography.labelLarge)
                if(format==UsageExportFormat.JSON && preview.summaries.isNotEmpty()) Text("另含 ${preview.summaries.size} 条系统汇总，单独标明来源",style=MaterialTheme.typography.bodySmall)
                Text(if(format==UsageExportFormat.JSON && notes) "附带 ${preview.journal.size} 条记录、${preview.annotations.size} 条标注" else "不含私人记录、设置或原始事件",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }}
        item {FlowRow(Modifier.padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={onExport(options,false)},enabled=!busy && preview.available){Icon(Icons.Outlined.Share,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("分享数据")}
            OutlinedButton(onClick={onExport(options,true)},enabled=!busy && preview.available){Text("保存到文件")}
        }}
        item {HorizontalDivider(Modifier.padding(horizontal=20.dp))}
        item {DataAction("系统历史汇总","${records.summaries.size} 条汇总 · 保留日、周、月、年原始粒度",Icons.Outlined.History,!busy,onHistory)}
        item {SettingsSection("完整备份与恢复")}
        item {DataAction("创建完整备份","所有日期的使用记录、草稿、复盘和设置",Icons.Outlined.Backup,!busy){backupOptions=true}}
        records.config["lastBackupCreatedAt"]?.toLongOrNull()?.let{time->item {
            Text("上次生成：${Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} · 请确认文件已保存或发送",
                Modifier.padding(horizontal=20.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        item {DataAction("从备份恢复","先查看备份概况，再确认替换；仅支持完整备份",Icons.Outlined.Restore,!busy,onRestore)}
        item {SettingsSection("存储与清理")}
        item {DataAction("清空本地数据","删除全部记录与设置，可选择暂停采集",Icons.Outlined.DeleteOutline,!busy,onClear)}
    }
    if(showRange) ExportDateRange(LocalDate.parse(first),LocalDate.parse(last),onDismiss={showRange=false}){a,b->first=a.toString();last=b.toString();period="custom";showRange=false}
    if(help) AlertDialog(onDismissRequest={help=false},title={Text("数据来源与导出")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("应用会补采系统仍保留的历史使用事件，并持续保存在本机。系统已清理的事件无法恢复，因此最早记录日期因设备而异；并非手机的全部历史。")
        Text("使用数据导出只包含所选日期；今天和本周截至生成时刻。JSON报告包含统计口径、时区和缺失状态，不能用于恢复。CSV为会话明细，应用并行使用时长可能重叠。")
        Text("完整备份不受上方日期筛选影响，包含所有本地数据及私人草稿。分享前请确认接收方；应用不会自动上传。")
    }},confirmButton={TextButton(onClick={help=false}){Text("知道了")}})
    if(backupOptions) AlertDialog(onDismissRequest={backupOptions=false},title={Text("创建完整备份")},text={Text("包含所有日期的使用记录、私人草稿、复盘、备注和设置。用于迁移或恢复；若只想给别人分析，请使用上方的使用数据导出。")},
        confirmButton={TextButton(onClick={backupOptions=false;onBackup(false)}){Text("分享备份")}},
        dismissButton={TextButton(onClick={backupOptions=false;onBackup(true)}){Text("保存备份")}})
}

@Composable private fun ExportCheck(title:String,summary:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    ListItem(headlineContent={Text(title)},supportingContent={Text(summary)},trailingContent={Checkbox(checked,null,enabled=enabled)},
        modifier=Modifier.toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=onChange))
}
@Composable private fun DataAction(title:String,summary:String,icon:androidx.compose.ui.graphics.vector.ImageVector,enabled:Boolean,onClick:()->Unit) {
    val colors = MaterialTheme.colorScheme
    val destructive = icon == Icons.Outlined.DeleteOutline
    ListItem(
        headlineContent = { Text(title, color = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            destructive -> colors.error
            else -> colors.onSurface
        }) },
        supportingContent = { Text(summary) },
        leadingContent = {
            if (destructive) IconTile(icon, colors.errorContainer, colors.onErrorContainer) else IconTile(icon)
        },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}
@Composable private fun ExportDateRange(first:LocalDate,last:LocalDate,onDismiss:()->Unit,onSelect:(LocalDate,LocalDate)->Unit) {
    fun utc(date:LocalDate)=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state=rememberDateRangePickerState(initialSelectedStartDateMillis=utc(first),initialSelectedEndDateMillis=utc(last),
        selectableDates=object:SelectableDates { override fun isSelectableDate(utcTimeMillis:Long)=utcTimeMillis<=utc(LocalDate.now()) })
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Scaffold(topBar={TopAppBar(title={Text("导出日期")},navigationIcon={IconButton(onClick=onDismiss){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"取消选择日期")}},
            actions={TextButton(enabled=state.selectedStartDateMillis!=null && state.selectedEndDateMillis!=null,onClick={
                onSelect(Instant.ofEpochMilli(state.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate(),Instant.ofEpochMilli(state.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate())
            }){Text("确定")}})}) {padding->DateRangePicker(state,Modifier.padding(padding),title=null,headline=null,showModeToggle=true)}
    }
}
