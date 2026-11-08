@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.goooseby.tickmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.goooseby.tickmark.app.TickmarkApp
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.report.Records
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*

internal fun journalDate(date:LocalDate):String="${date.monthValue}月${date.dayOfMonth}日 周${listOf("一","二","三","四","五","六","日")[date.dayOfWeek.value-1]}"

@Composable internal fun JournalWeek(records:Records,anchor:LocalDate,onDay:(LocalDate)->Unit) {
    val monday=io.github.goooseby.tickmark.engine.analytics.ReportEngine.weekStart(anchor)
    val completed=records.journal.filter{it.kind=="DAILY" && !it.draft}.map{it.date}.toSet()
    val dates=(0..6).map{monday.plusDays(it.toLong())}
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(if(monday==io.github.goooseby.tickmark.engine.analytics.ReportEngine.weekStart(LocalDate.now())) "本周复盘" else "整周复盘",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
        Text("${dates.count{it.toString() in completed}} / 7",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.padding(horizontal=16.dp)) {
        dates.forEach { date ->
            val done=date.toString() in completed
            Column(Modifier.weight(1f).semantics{contentDescription="$date，${if(done) "已复盘" else "未复盘"}"}
                .clickable(enabled=date<=LocalDate.now()){onDay(date)}.padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(listOf("一","二","三","四","五","六","日")[date.dayOfWeek.value-1],style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.size(32.dp).clip(CircleShape).background(if(done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center) {
                    Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.labelLarge,color=if(date>LocalDate.now()) MaterialTheme.colorScheme.outline else if(date==LocalDate.now()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable internal fun JournalHub(records:Records,onOpen:(JournalRow)->Unit,onDaily:(LocalDate)->Unit,onNote:()->Unit,onPin:(JournalRow)->Unit,onDelete:(JournalRow)->Unit,onAction:(JournalRow)->Unit) {
    var query by rememberSaveable{mutableStateOf("")}
    var filter by rememberSaveable{mutableIntStateOf(0)}
    var completedActions by rememberSaveable{mutableStateOf(false)}
    var dateFilter by rememberSaveable{mutableStateOf<String?>(null)}
    var calendar by remember{mutableStateOf(false)}
    var create by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf<JournalRow?>(null)}
    val visible=remember(records.journal,query,filter,dateFilter,completedActions) {
        records.journal.filter { row ->
            (dateFilter==null || (if(filter==3) LocalDate.parse(row.date).plusDays(1).toString() else row.date)==dateFilter) && (query.isBlank() || row.searchable.contains(query,true) || row.date.contains(query)) &&
                when(filter){1->row.kind=="DAILY";2->row.kind=="NOTE";3->!row.draft && row.action.isNotBlank() && row.actionDone==completedActions;else->true}
        }.sortedWith(compareByDescending<JournalRow>{it.pinned}.thenByDescending{it.date}.thenByDescending{it.updatedAt})
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=100.dp)) {
            item {
                Row(Modifier.padding(start=16.dp,end=8.dp,top=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(query,{query=it},Modifier.weight(1f),placeholder={Text("搜索记录")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"清除搜索")}},singleLine=true)
                    IconButton(onClick={calendar=true}) {Icon(Icons.Outlined.CalendarMonth,"按日期筛选")}
                }
            }
            item {FlowRow(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("全部","复盘","随手记","行动").forEachIndexed { i,label -> FilterChip(filter==i,{filter=i},label={Text(label)}) }
                dateFilter?.let {date->InputChip(true,onClick={dateFilter=null},modifier=Modifier.semantics{contentDescription="清除日期筛选"},label={Text(journalDate(LocalDate.parse(date)))},trailingIcon={Icon(Icons.Outlined.Close,null,Modifier.size(16.dp))})}
            }}
            if(filter==3) item {Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!completedActions,{completedActions=false},label={Text("待完成")})
                FilterChip(completedActions,{completedActions=true},label={Text("已完成")})
            }}
            if(query.isEmpty() && dateFilter==null && filter==0) item {JournalWeek(records,LocalDate.now(),onDaily);HorizontalDivider(Modifier.padding(top=8.dp))}
            if(visible.isEmpty()) item {
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(filter==3 && query.isBlank() && dateFilter==null) (if(completedActions) "没有已完成行动" else "没有待完成行动") else if(query.isNotBlank() || filter!=0 || dateFilter!=null) "没有匹配的记录" else "还没有记录",style=MaterialTheme.typography.titleMedium)
                    Text(if(query.isNotBlank()) "试试其他关键词或日期" else if(filter==3) "复盘中写下的小行动会显示在这里。" else "可以写一篇每日复盘，或随手记下一件事。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(query.isEmpty() && filter==0 && dateFilter==null) TextButton(onClick={onDaily(LocalDate.now())}){Text("写今日复盘")}
                }
            }
            itemsIndexed(visible,key={_,row->row.id}) { index,row ->
                if(index==0 || visible[index-1].date!=row.date || visible[index-1].pinned!=row.pinned) {
                    val groupDate=LocalDate.parse(row.date).plusDays(if(filter==3) 1 else 0)
                    Text(if(row.pinned) "置顶 · ${journalDate(groupDate)}" else journalDate(groupDate),Modifier.padding(start=20.dp,end=20.dp,top=18.dp,bottom=4.dp).semantics{heading()},style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(filter==3) JournalActionRow(row,onAction,onOpen)
                else {
                    var menu by remember(row.id){mutableStateOf(false)}
                    ListItem(
                        headlineContent={Text(row.heading,maxLines=1,overflow=TextOverflow.Ellipsis)},
                        supportingContent={Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(listOfNotNull(if(row.kind=="NOTE") "随手记" else row.mood.takeIf{it.isNotBlank()},if(row.draft) "草稿" else null).joinToString(" · ").ifBlank{"已记录"},style=MaterialTheme.typography.labelMedium)
                            if(row.preview.isNotBlank()) Text(row.preview,maxLines=2,overflow=TextOverflow.Ellipsis)
                            if(!row.draft && row.action.isNotBlank()) Text(if(row.actionDone) "行动已完成" else "行动待完成",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        }},
                        leadingContent={Icon(if(row.kind=="DAILY") Icons.Outlined.EditNote else Icons.Outlined.Notes,null,tint=MaterialTheme.colorScheme.primary)},
                        trailingContent={Box {
                            IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"记录操作：${row.heading}")}
                            DropdownMenu(menu,{menu=false}) {
                                DropdownMenuItem(text={Text(if(row.pinned) "取消置顶" else "置顶")},onClick={menu=false;onPin(row)})
                                DropdownMenuItem(text={Text("删除")},onClick={menu=false;deleting=row})
                            }
                        }},modifier=Modifier.clickable{onOpen(row)})
                }
            }
        }
        ExtendedFloatingActionButton(onClick={create=true},icon={Icon(Icons.Outlined.Add,null)},text={Text("新建记录")},modifier=Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics{contentDescription="新建记录"})
    }
    if(create) ModalBottomSheet(onDismissRequest={create=false}) {
        ListItem(headlineContent={Text("写每日复盘")},supportingContent={Text("记录今天，留下明天的一个行动")},leadingContent={Icon(Icons.Outlined.EditNote,null)},modifier=Modifier.clickable{create=false;onDaily(LocalDate.now())})
        ListItem(headlineContent={Text("写随手记")},supportingContent={Text("想法、计划或一段个人记录")},leadingContent={Icon(Icons.Outlined.Notes,null)},modifier=Modifier.clickable{create=false;onNote()})
        Spacer(Modifier.height(24.dp))
    }
    if(calendar) JournalDatePicker(dateFilter?.let(LocalDate::parse) ?: LocalDate.now(),{calendar=false}) {dateFilter=it.toString();calendar=false}
    deleting?.let {row->AlertDialog(onDismissRequest={deleting=null},title={Text("删除这条记录？")},text={Text("删除后可在底部提示中撤销。")},confirmButton={TextButton(onClick={deleting=null;onDelete(row)}){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

@Composable internal fun JournalActionRow(row:JournalRow,onToggle:(JournalRow)->Unit,onOpen:(JournalRow)->Unit) {
    ListItem(headlineContent={Text(row.action,textDecoration=if(row.actionDone) TextDecoration.LineThrough else TextDecoration.None,maxLines=3,overflow=TextOverflow.Ellipsis)},
        supportingContent={Text("${journalDate(LocalDate.parse(row.date).plusDays(1))} · ${if(row.actionDone) "已完成" else "待完成"}")},
        leadingContent={Checkbox(row.actionDone,{onToggle(row)},Modifier.semantics{contentDescription="${if(row.actionDone) "取消完成" else "完成行动"}：${row.action}"})},modifier=Modifier.clickable{onOpen(row)})
}

@Composable internal fun TodayJournal(records:Records,date:LocalDate,onOpen:(LocalDate)->Unit,onAction:(JournalRow)->Unit) {
    val daily=records.journal.find{it.id=="day:$date"}
    ChartTitle("每日复盘",if(daily==null) "写复盘" else if(daily.draft) "继续填写" else "查看复盘",{onOpen(date)})
    if(daily!=null) Text(daily.preview.ifBlank{daily.mood.ifBlank{"草稿"}},Modifier.fillMaxWidth().clickable{onOpen(date)}.padding(horizontal=20.dp,vertical=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
    val action=records.journal.find{it.id=="day:${date.minusDays(1)}" && !it.draft && it.action.isNotBlank()}
    if(action!=null) {ChartTitle("今天的行动");JournalActionRow(action,onAction,{onOpen(LocalDate.parse(it.date))})}
}

@Composable internal fun JournalDatePicker(date:LocalDate,onDismiss:()->Unit,onSelect:(LocalDate)->Unit) {
    val picker=rememberDatePickerState(initialSelectedDateMillis=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),selectableDates=object:SelectableDates {
        override fun isSelectableDate(utcTimeMillis:Long)=utcTimeMillis<=LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    })
    DatePickerDialog(onDismissRequest=onDismiss,confirmButton={TextButton(onClick={picker.selectedDateMillis?.let{onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())}}){Text("确定")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}}){DatePicker(picker)}
}

private val journalSaver=Saver<JournalRow,String>(save={JournalCodec.encode(it).toString()},restore={runCatching{JournalCodec.decode(JSONObject(it))}.getOrNull()})

@Composable internal fun JournalEditor(repo:TickRepository,records:Records,initial:JournalRow,onClose:()->Unit,onDate:(LocalDate)->Unit,onDeleted:(JournalRow?)->Unit) {
    var row by rememberSaveable(initial.id,stateSaver=journalSaver){mutableStateOf(initial)}
    var loaded by rememberSaveable(initial.id){mutableStateOf(false)}
    var revision by rememberSaveable(initial.id){mutableLongStateOf(0)}
    var saving by remember{mutableStateOf(false)}
    var dirty by rememberSaveable(initial.id){mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    var showDate by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    var closed by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    val application=repo.context.applicationContext as TickmarkApp
    var pending by remember{mutableStateOf<Job?>(null)}
    val lifecycle=LocalLifecycleOwner.current
    val latest by rememberUpdatedState(row)
    val needsFlush by rememberUpdatedState(loaded && dirty && !closed)
    LaunchedEffect(initial.id) {
        if(!loaded) try {val result=repo.journalForEditing(initial);row=result.first;revision=result.second;loaded=true} catch(e:Exception) {if(e is CancellationException) throw e;error="记录读取失败，请返回后重试"}
    }
    DisposableEffect(initial.id,lifecycle) {
        fun flush() {if(needsFlush && !closed) {val value=latest;val generation=revision;application.applicationScope.launch {runCatching{repo.saveJournal(value,expectedRevision=generation)}}}}
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP) flush()}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer);pending?.cancel();flush()}
    }
    fun change(value:JournalRow) {
        row=value;dirty=true;error=null;status="正在保存…";pending?.cancel()
        pending=scope.launch {
            delay(600)
            try {repo.saveJournal(value,expectedRevision=revision);if(row==value){dirty=false;status=if(value.draft) "草稿已保存" else "已保存"}}
            catch(e:Exception){if(e is CancellationException) throw e;error=e.message ?: "保存失败，内容仍保留在编辑页";status=""}
        }
    }
    LaunchedEffect(loaded) {if(loaded && dirty && pending==null) change(row)}
    fun leave(complete:Boolean,after:()->Unit=onClose) {
        if(saving) return
        saving=true
        scope.launch {
            try {
                pending?.cancelAndJoin()
                if(loaded && (dirty || complete && row.draft)) repo.saveJournal(row,complete,revision)
                dirty=false;closed=true;after()
            } catch(e:Exception) {if(e is CancellationException) throw e;error=e.message ?: "保存失败，请重试"}
            finally{saving=false}
        }
    }
    BackHandler {leave(false)}
    Scaffold(topBar={TopAppBar(title={Text(if(row.kind=="DAILY") "每日复盘" else "随手记",maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick={leave(false)},enabled=!saving){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回并保存")}},actions={
        if(loaded && row.meaningful) IconButton(onClick={
            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,row.shareText),"分享记录"))
        }) {Icon(Icons.Outlined.Share,"分享记录")}
        if(row.createdAt>0 || loaded && row.meaningful) IconButton(onClick={deleting=true},enabled=!saving){Icon(Icons.Outlined.DeleteOutline,"删除记录")}
        TextButton(onClick={leave(true)},enabled=loaded && row.meaningful && !saving){Text(if(saving) "保存中" else "完成")}
    })}) {padding->
        if(!loaded) Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center){if(error==null) CircularProgressIndicator() else Text(error!!)}
        else LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={showDate=true},enabled=!saving,contentPadding=PaddingValues(horizontal=0.dp)) {
                    Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(journalDate(LocalDate.parse(row.date)))
                }
                Spacer(Modifier.weight(1f))
                Text(if(row.draft) "草稿" else "已记录",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            if(row.kind=="DAILY") {
                val report=records.dayReport(LocalDate.parse(row.date))
                if(report.spans.any{it.state!=io.github.goooseby.tickmark.engine.model.DeviceState.UNKNOWN}) item {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        Metric("手机使用",duration(report.used),Modifier.weight(1f));Metric("最长未使用",duration(report.longestAway?.duration ?: 0),Modifier.weight(1f))
                    }
                }
                item {Column {
                    Text("今天的状态",style=MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {JournalRow.moods.forEach {mood->FilterChip(row.mood==mood,{change(row.copy(mood=if(row.mood==mood) "" else mood))},label={Text(mood)})}}
                }}
                item {OutlinedTextField(row.positive,{if(it.length<=2000) change(row.copy(positive=it))},Modifier.fillMaxWidth(),label={Text("值得保留的一件事")},minLines=2,maxLines=8)}
                item {OutlinedTextField(row.adjustment,{if(it.length<=2000) change(row.copy(adjustment=it))},Modifier.fillMaxWidth(),label={Text("想调整的时段")},minLines=2,maxLines=8)}
                item {OutlinedTextField(row.action,{if(it.length<=1000) change(row.copy(action=it,actionDone=if(it!=row.action) false else row.actionDone))},Modifier.fillMaxWidth(),label={Text("明天的一个小行动")},minLines=2,maxLines=6)}
                if(row.action.isNotBlank()) item {Row(verticalAlignment=Alignment.CenterVertically){Checkbox(row.actionDone,{change(row.copy(actionDone=it))});Text("行动已完成",Modifier.clickable{change(row.copy(actionDone=!row.actionDone))})}}
            } else {
                item {OutlinedTextField(row.title,{if(it.length<=100) change(row.copy(title=it))},Modifier.fillMaxWidth(),label={Text("标题（可选）")},singleLine=true)}
                item {OutlinedTextField(row.body,{if(it.length<=8000) change(row.copy(body=it))},Modifier.fillMaxWidth(),label={Text("写下记录")},minLines=10,maxLines=24)}
            }
            item {Text(error ?: status.ifBlank{if(row.updatedAt>0) "上次保存 ${java.time.Instant.ofEpochMilli(row.updatedAt).atZone(ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}" else ""},style=MaterialTheme.typography.bodySmall,color=if(error!=null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
    if(showDate) JournalDatePicker(LocalDate.parse(row.date),{showDate=false}) {date->
        showDate=false
        if(date.toString()!=row.date) {
            if(row.kind=="DAILY") leave(false){onDate(date)} else change(row.copy(date=date.toString()))
        }
    }
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("删除这条记录？")},text={Text("删除后可撤销。")},confirmButton={TextButton(onClick={scope.launch{
        saving=true
        try {pending?.cancelAndJoin();val removed=repo.deleteJournal(row.id);dirty=false;closed=true;onDeleted(removed)}
        catch(e:Exception){if(e is CancellationException) throw e;error=e.message ?: "删除失败";deleting=false}
        finally{saving=false}
    }}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}
