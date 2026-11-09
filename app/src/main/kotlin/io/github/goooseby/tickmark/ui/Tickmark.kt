@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.goooseby.tickmark.ui

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import io.github.goooseby.tickmark.share.ShareFiles
import io.github.goooseby.tickmark.share.ReportImage
import io.github.goooseby.tickmark.share.ReportImageData
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.goooseby.tickmark.app.*
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.engine.model.overlap
import io.github.goooseby.tickmark.engine.model.unionDuration
import io.github.goooseby.tickmark.engine.analytics.ReportEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class ReportPage(val tab:Int,val settings:String?,val detail:String?,val date:LocalDate,val reviewWeek:Boolean,val weekly:Boolean,val from:Long,val to:Long,val filtered:Boolean) {
    val depth:Int get()=if(settings?.startsWith("app:")==true) 2 else if(settings!=null || detail!=null) 1 else 0
    val routeKey:String get()=settings?.let{"settings:$it"} ?: detail?.let{"detail:$it"} ?: "tab:$tab"
}

private val purposes = listOf("学习", "课程事务", "社交", "娱乐", "生活", "其他")
fun duration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return when { ms > 0 && seconds == 0L -> "不足1秒"; seconds < 60 -> "${seconds}秒"; seconds < 3600 -> "${seconds/60}分钟"; else -> "${seconds/3600}小时${if (seconds%3600/60 > 0) "${seconds%3600/60}分" else ""}" }
}
@Composable internal fun clock(ms: Long): String = android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(ms))
@Composable internal fun periodLabel(from:Long,to:Long):String {
    val date=Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate()
    return if(from==dayStart(date) && to==dayStart(date.plusDays(1))) "全天" else "${clock(from)} – ${if(to>=dayStart(date.plusDays(1))) "次日 " else ""}${clock(to)}"
}
internal fun appName(pkg: String, records: Records): String = if (pkg == "@excluded") "已排除的应用" else records.apps.find { it.pkg == pkg }?.name ?: pkg
internal fun dayStart(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable fun Tickmark(repo: TickRepository,entry:ReportEntry?=null,onEntryConsumed:()->Unit={}) {
    val vm: TickViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TickViewModel(repo) as T
    })
    val records by vm.records.collectAsStateWithLifecycle()
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    val error by repo.message.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updates = remember { (repo.context.applicationContext as TickmarkApp).updates }
    val lifecycle = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(repo.hasAccess()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { access = repo.hasAccess(); vm.sync(); scope.launch { updates.check(automatic=true) } } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reviewWeek by rememberSaveable { mutableStateOf(true) }
    var dateString by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(dateString)
    var rangeStart by rememberSaveable(dateString) { mutableStateOf<Long?>(null) }
    var rangeEnd by rememberSaveable(dateString) { mutableStateOf<Long?>(null) }
    var settingsRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsFromTab by rememberSaveable { mutableIntStateOf(3) }
    fun openSettings(route:String) { settingsFromTab=tab; settingsRoute=route; tab=3 }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var parentDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var weekReturnAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var weekReturnDetail by rememberSaveable { mutableStateOf<String?>(null) }
    fun back() {
        if(settingsRoute!=null) { if(settingsRoute!!.startsWith("app:")) settingsRoute="apps" else if(settingsRoute=="history") settingsRoute="data" else { settingsRoute=null; tab=settingsFromTab } }
        else if(parentDetail!=null) { detail=parentDetail; parentDetail=null }
        else if(weekReturnAnchor!=null) {
            dateString=weekReturnAnchor!!; detail=weekReturnDetail; reviewWeek=true
            rangeStart=null; rangeEnd=null; weekReturnAnchor=null; weekReturnDetail=null
        } else detail=null
    }
    LaunchedEffect(entry) {
        entry?.let {
            tab=if(it.destination=="capture") 3 else 0
            dateString=it.date.toString();rangeStart=null;rangeEnd=null
            settingsRoute=if(it.destination=="capture") "capture" else null
            detail=if(it.destination=="away") "@away" else null
            parentDetail=null;weekReturnAnchor=null;weekReturnDetail=null
            onEntryConsumed()
        }
    }
    val screenState=rememberSaveableStateHolder()
    var journalDateString by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    val journalEditor=when {detail=="@journal"->JournalRow.daily(LocalDate.parse(journalDateString));detail?.startsWith("@note:")==true->JournalRow(detail!!.removePrefix("@"),journalDateString,"NOTE");else->null}
    fun openDaily(value:LocalDate) {
        journalDateString=value.toString();detail="@journal"
    }
    fun openJournal(value:JournalRow) {
        if(value.kind=="DAILY") openDaily(LocalDate.parse(value.date))
        else {journalDateString=value.date;detail="@${value.id}"}
    }
    var showDate by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    var annotation by remember { mutableStateOf<AnnotationRow?>(null) }
    var preview by remember { mutableStateOf<DataTransfer.Restore?>(null) }
    var clear by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val transfer = remember { DataTransfer(repo) }
    val paused = records.config["paused"] == "true"
    var preparingShare by remember{mutableStateOf(false)}
    fun task(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; snack.showSnackbar(e.message ?: "操作失败，原记录已保留") } } }
    fun deletedJournal(row:JournalRow?) {
        if(row!=null) task {
            if(snack.showSnackbar("记录已删除",actionLabel="撤销",duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) repo.restoreJournal(row)
        }
    }
    fun shareReport(week:Boolean) {
        if(preparingShare) return
        preparingShare=true
        val snapshot=records;val anchor=date
        task {try {
            val data=withContext(Dispatchers.Default){ReportImageData.create(snapshot,anchor,week)}
            val bitmap=withContext(Dispatchers.Default){ReportImage.render(context,data)}
            val uri=try {withContext(Dispatchers.IO){ShareFiles.image(context,data.filename,bitmap)}} finally {bitmap.recycle()}
            ShareFiles.launch(context,ShareFiles.intent(uri,"image/png","${data.title} · 使用${if(week) "周报" else "日报"}","Tickmark · ${data.title}\n${ShareFiles.PROJECT_URL}"))
        } finally {preparingShare=false}}
    }
    var pendingExport by rememberSaveable{mutableStateOf<String?>(null)}
    fun saveGenerated(target:android.net.Uri?) {
        val source=pendingExport;pendingExport=null
        if(target==null || source==null) {preparingShare=false;return}
        preparingShare=true
        task {try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(android.net.Uri.parse(source))?.use{input->
                    context.contentResolver.openOutputStream(target)?.use{output->input.copyTo(output)} ?: error("无法写入文件")
                } ?: error("导出文件已失效，请重新生成")
            }
            snack.showSnackbar("文件已保存")
        } finally {preparingShare=false}}
    }
    val exportJson=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"),::saveGenerated)
    val exportCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"),::saveGenerated)
    fun prepareExport(options:UsageExportOptions?,save:Boolean) {
        if(preparingShare || pendingExport!=null) return
        preparingShare=true
        task {try {
            val name=options?.filename ?: "Tickmark-backup-${LocalDate.now()}.json"
            val uri=withContext(Dispatchers.IO) {
                val text=if(options==null) transfer.backup() else UsageExport(repo.snapshot(),options).text()
                ShareFiles.text(context,name,text)
            }
            if(options==null) repo.setConfig("lastBackupCreatedAt" to System.currentTimeMillis().toString())
            if(save) {
                pendingExport=uri.toString()
                if(options!=null && options.format!=UsageExportFormat.JSON) exportCsv.launch(name) else exportJson.launch(name)
            } else ShareFiles.launch(context,ShareFiles.intent(uri,options?.format?.mime ?: "application/json",if(options==null) "Tickmark 完整备份" else "Tickmark 使用数据"))
        } finally {preparingShare=false}}
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {preparingShare=true;task {try { preview = withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 50 * 1024 * 1024) { "备份超过 50 MB，请使用较小文件" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            } ?: error("无法读取文件")
            transfer.parse(text)
        } } finally {preparingShare=false}}}
    }
    BackHandler(settingsRoute!=null || detail != null || rangeStart != null || tab != 0) {
        when { settingsRoute!=null || detail != null || weekReturnAnchor!=null -> back(); rangeStart != null -> { rangeStart = null; rangeEnd = null }; else -> tab = 0 }
    }
    val dark = when(records.config["theme"]) { "light"->false; "dark"->true; else->isSystemInDarkTheme() }
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    TickmarkTheme(dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if(journalEditor!=null) {
            key(journalEditor.id) {JournalEditor(repo,records,journalEditor,onClose={back()},onDate=::openDaily,onDeleted={removed->back();deletedJournal(removed)})}
        } else {
        val weekly = tab == 1 && reviewWeek && rangeStart == null
        val weekBounds = ReportEngine.weekBoundaries(date,ZoneId.systemDefault())
        val from = rangeStart ?: if(weekly) weekBounds.first() else dayStart(date)
        val to = minOf(rangeEnd ?: if(weekly) weekBounds.last() else dayStart(date.plusDays(1)), System.currentTimeMillis())
            val title = when { settingsRoute!=null -> when { settingsRoute!!.startsWith("app:")->records.apps.find{it.pkg==settingsRoute!!.removePrefix("app:")}?.name ?: "应用设置"; else->mapOf("apps" to "应用管理","goals" to "每日目标","appearance" to "外观","capture" to "采集状态","data" to "数据管理","history" to "系统历史汇总","updates" to "版本与更新","privacy" to "隐私说明","project" to "关于","widgets" to "桌面组件")[settingsRoute] ?: "设置" }; detail=="@away"->"未使用时段"; detail=="@journal"->"每日复盘"; detail == "@range" -> "时段复盘"; detail != null -> appName(detail!!, records); tab == 0 -> if (date == LocalDate.now()) "今日" else "每日概览"; tab == 1 -> "回顾"; tab == 2 -> "记录"; else -> "设置" }
        Scaffold(
            topBar = { TopAppBar(title = { Crossfade(title,animationSpec=tween(180),label="page-title") {Text(it,maxLines=1,overflow=TextOverflow.Ellipsis)} }, navigationIcon = {
                if (settingsRoute!=null || detail != null) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            }, actions = {
                if(tab in 0..1 && detail==null) {
                    IconButton(onClick={shareReport(weekly)},enabled=!preparingShare) {
                        if(preparingShare) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp) else Icon(Icons.Outlined.Share,"分享${if(weekly) "每周" else "每日"}概览")
                    }
                }
                if (tab in 0..1 && detail == null) IconButton(onClick = { vm.sync() }, enabled = !syncing) { Icon(Icons.Outlined.Refresh, "刷新") }
            }) },
            bottomBar = { if (settingsRoute==null && detail == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf("今日", "回顾", "记录", "设置").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; weekReturnAnchor=null; weekReturnDetail=null },
                        icon = { Icon(when(i) { 0 -> Icons.Outlined.Today; 1 -> Icons.Outlined.BarChart; 2 -> Icons.Outlined.EditNote; else -> Icons.Outlined.Settings }, null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            } }, snackbarHost = { SnackbarHost(snack) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (syncing && journalEditor==null && tab in 0..1) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null && tab!=2 && journalEditor==null) TextButton(onClick = { vm.sync() }, modifier = Modifier.fillMaxWidth()) { Text(error!!) }
                if (!access && tab in 0..1 && journalEditor==null) {
                    if(records.sessions.isEmpty()) PermissionNotice { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    else TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },modifier=Modifier.fillMaxWidth()) { Text("采集已暂停 · 恢复访问权限") }
                }
                if (paused && tab in 0..1 && journalEditor==null) TextButton(onClick = { task { repo.setPaused(false); vm.sync() } }, modifier = Modifier.fillMaxWidth()) { Text("采集已暂停 · 点此继续") }
                AnimatedContent(
                    targetState=ReportPage(tab,settingsRoute,detail,date,reviewWeek,weekly,from,to,rangeStart!=null),
                    contentKey={it.routeKey},modifier=Modifier.weight(1f).fillMaxWidth().pageEntry(Unit),
                    transitionSpec={
                        val direction=if(targetState.depth<initialState.depth || targetState.depth==0 && targetState.tab<initialState.tab) -1 else 1
                        (fadeIn(tween(210,delayMillis=70))+slideInHorizontally(tween(280)){direction*it/24}) togetherWith
                            (fadeOut(tween(100))+slideOutHorizontally(tween(180)){-direction*it/40}) using SizeTransform(clip=true)
                    },label="page-navigation"
                ) {page ->
                Box(Modifier.fillMaxSize().destinationInput(page.tab==tab && page.settings==settingsRoute && page.detail==detail)) {
                when {
                    page.settings!=null -> when(val route=page.settings) {
                        "apps" -> screenState.SaveableStateProvider("settings-apps") {
                            AppManager(records) { settingsRoute="app:${it.pkg}" }
                        }
                        "goals" -> GoalSettings(records) { value->task { repo.setConfig("dailyGoalMinutes" to value) } }
                        "appearance" -> AppearanceSettings(records.config["theme"] ?: "system") { value->task { repo.setConfig("theme" to value) } }
                        "capture" -> CaptureSettings(records,access,onPermission={ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },onPaused={ task {repo.setPaused(it);if(!it) vm.sync()} },onRefresh={vm.sync()})
                        "privacy" -> PrivacySettings()
                        "updates" -> UpdateScreen(updates)
                        "project" -> AboutSettings()
                        "widgets" -> WidgetSettings(records,access)
                        "history" -> HistorySummaryScreen(records)
                        "data" -> screenState.SaveableStateProvider("settings-data") {
                            val bytes by produceState(0L,records) { value=withContext(Dispatchers.IO) { val db=context.getDatabasePath("tickmark.db");db.length()+java.io.File(db.path+"-wal").length() } }
                            DataSettings(records,bytes,busy=preparingShare || pendingExport!=null,
                                onExport=::prepareExport,onBackup={save->prepareExport(null,save)},
                                onRestore={importJson.launch(arrayOf("application/json","text/plain","application/octet-stream"))},
                                onClear={clear=true},onHistory={settingsRoute="history"})
                        }
                        else -> records.apps.find{it.pkg==route.removePrefix("app:")}?.let { app->AppPreferences(app,records.config,onUpdate={task{repo.updateApp(it)}},onSaveGoal={value->task{repo.setConfig("appGoal:${app.pkg}" to value)}}) }
                    }
                    page.detail=="@away" -> AwayPage(records,page.from,page.to) { a,b -> annotation=AnnotationRow(UUID.randomUUID().toString(),a,b,"","") }
                    page.detail != null -> DetailReports(page.detail, records, records.sessions.filter{it.start<page.to && it.end>page.from}, page.from, page.to, onApp = { parentDetail = detail; detail = it }, onAnnotate = { annotation = AnnotationRow(UUID.randomUUID().toString(), from, to, "", "") }, onEdit = { annotation = it }, onUpdateApp = { task { repo.updateApp(it) } }, onDate = { if(weekly) { weekReturnAnchor=dateString; weekReturnDetail=detail }; dateString = it.toString(); reviewWeek = false; rangeStart = null; rangeEnd = null }, weekly = page.weekly)
                    page.tab == 3 -> screenState.SaveableStateProvider("settings-home") {
                        SettingsHome(records,access,::openSettings)
                    }
                    page.tab == 2 -> screenState.SaveableStateProvider("journal-hub") {JournalHub(records,onOpen=::openJournal,onDaily=::openDaily,onNote={openJournal(JournalRow.note(LocalDate.now()))},onPin={row->task{repo.pinJournal(row.id)}},onDelete={row->task{deletedJournal(repo.deleteJournal(row.id))}},onAction={row->task{repo.toggleJournalAction(row.id)}})}
                    else -> Column(Modifier.fillMaxSize()) {
                        if(page.tab==1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp)) {
                            listOf("日","周").forEachIndexed { index,label -> SegmentedButton(selected=page.reviewWeek==(index==1),onClick={ reviewWeek=index==1; rangeStart=null; rangeEnd=null },shape=SegmentedButtonDefaults.itemShape(index,2)) { Text(label) } }
                        }
                        DateRow(page.date, onPrevious = { dateString = page.date.minusDays(if(page.tab==1 && page.reviewWeek) 7 else 1).toString() }, onNext = { dateString = page.date.plusDays(if(page.tab==1 && page.reviewWeek) 7 else 1).coerceAtMost(LocalDate.now()).toString() }, onChoose = { showDate = true }, onToday = { dateString = LocalDate.now().toString() }, weekly=page.tab==1 && page.reviewWeek)
                        Box(Modifier.weight(1f).fillMaxWidth().pageEntry(listOf(page.date,page.reviewWeek))) {
                        if (page.tab == 0) TodayReports(records, page.date, page.from, page.to, page.filtered,
                            onHour = { hour -> val z = ZoneId.systemDefault(); rangeStart = date.atTime(hour,0).atZone(z).toInstant().toEpochMilli(); rangeEnd = date.atTime(hour,0).plusHours(1).atZone(z).toInstant().toEpochMilli() },
                            onClearRange = { rangeStart = null; rangeEnd = null }, onRange = { showRange = true }, onDetail = { detail = "@range" },
                            onApp = { s -> detail = s.pkg }, onEdit = { annotation = it }, onGoal={openSettings("goals")},onJournal=::openDaily,onAway={detail="@away"},onJournalAction={row->task{repo.toggleJournalAction(row.id)}})
                        else if(page.reviewWeek) WeeklyReports(records,page.date,onJournal=::openDaily,onApp={ detail=it },onDay={ weekReturnAnchor=dateString; weekReturnDetail=null; dateString=it.toString(); reviewWeek=false; rangeStart=null; rangeEnd=null },onRange={ a,b -> weekReturnAnchor=dateString; weekReturnDetail=null; dateString=Instant.ofEpochMilli(a).atZone(ZoneId.systemDefault()).toLocalDate().toString(); reviewWeek=false; rangeStart=a; rangeEnd=b; detail="@range" })
                        else ReviewReports(records, page.date, page.from, page.to, onApp = { detail = it }, onRange = { showRange = true }, onDate = { dateString = it.toString(); rangeStart = null; rangeEnd = null })
                        }
                    }
                }
                }
                }
            }
        }
        if (showDate) {
            val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() })
            DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { dateString = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; showDate = false }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } }) { DatePicker(state) }
        }
        if (showRange) RangeDialog(date, from, to, onDismiss = { showRange = false }) { a,b -> rangeStart = a; rangeEnd = b; showRange = false; detail = "@range" }
        annotation?.let { row -> AnnotationDialog(row, onDismiss = { annotation = null }, onDelete = { task { repo.deleteAnnotation(row.id); annotation = null } }, onSave = { new,replace -> repo.annotate(new, replace) }, onDone = { annotation = null }) }
        preview?.let { data -> AlertDialog(onDismissRequest={if(!preparingShare) preview=null},title={Text("恢复完整备份")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("${data.sessions.size} 段会话 · ${data.journal.size} 条记录\n${data.summaries.size} 条系统汇总 · ${data.annotations.size} 条标注\n时区：${data.zone}")
            Text("当前所有记录与设置将被替换。恢复后暂停采集，请先备份当前数据。")
            if(preparingShare) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(enabled=!preparingShare,onClick={preparingShare=true;task {try {transfer.restore(data);preview=null;snack.showSnackbar("已恢复，采集已暂停")} finally {preparingShare=false}}}){Text("替换并恢复")}},
            dismissButton={TextButton(enabled=!preparingShare,onClick={preview=null}){Text("取消")}}) }

        if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空全部记录？") }, text = { Text("会话、标注、记录和设置将被删除。重新采集只记录清空之后的事件，旧记录不会回填。") }, confirmButton = { TextButton(onClick = { task { repo.clear(false); clear = false; vm.sync() } }) { Text("清空并重新采集") } }, dismissButton = { Column { TextButton(onClick = { task { repo.clear(true); clear = false } }) { Text("清空并暂停") }; TextButton(onClick = { clear = false }) { Text("取消") } } })
        }
        }
    }
}

@Composable private fun PermissionNotice(onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Outlined.Security, MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("允许读取应用使用记录", style = MaterialTheme.typography.titleSmall)
                Text("用于统计应用使用、息屏与锁屏时段。记录保存在本机。", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onClick) { Text("前往授权") }
            }
        }
    }
}
@Composable private fun DateRow(date: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, onChoose: () -> Unit, onToday: () -> Unit,weekly:Boolean=false) {
    val week=ReportEngine.weekStart(date)
    val current=if(weekly) week==ReportEngine.weekStart(LocalDate.now()) else date==LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, if(weekly) "上一周" else "前一天") }
        FilledTonalButton(
            onClick = onChoose,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            AnimatedContent(targetState=if(weekly) "${week.monthValue}/${week.dayOfMonth} – ${week.plusDays(6).monthValue}/${week.plusDays(6).dayOfMonth}" else date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.SIMPLIFIED_CHINESE)),transitionSpec={fadeIn(tween(180))+slideInVertically(tween(220)){it/3} togetherWith fadeOut(tween(120))+slideOutVertically(tween(180)){-it/3}},label="report-date") {
                Text(it, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        IconButton(onClick = onNext, enabled = !current && date < LocalDate.now()) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, if(weekly) "下一周" else "后一天") }
        if (!current) TextButton(onClick = onToday) { Text(if(weekly) "本周" else "今天") }
    }
}
@Composable internal fun Summary(records: Records, from: Long, to: Long) {
    val report=remember(records.spans,from,to){records.deviceReport(from,to)}
    val rounds = records.deviceSpans.filter { it.state == io.github.goooseby.tickmark.engine.model.DeviceState.IN_USE && it.start < to && it.end > from }
    val screenOnly = records.config["screenOnly"] == "true"
    val countLabel = if (screenOnly) "亮屏次数" else "解锁次数"
    val count = if (screenOnly) rounds.count { it.start >= from } else records.unlocks.count { it >= from && it < to }
    val longest = duration(rounds.maxOfOrNull { overlap(it.start, it.end, from, to) } ?: 0)
    val wideText = LocalDensity.current.fontScale >= 1.3f
    ReportSurface {
        if (wideText) {
            Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Metric("手机使用", duration(report.used), Modifier.weight(1f), prominent = true)
                    StatisticsInfoButton("手机使用统计说明",deviceStatisticsInfo(records,from,to))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(countLabel, count.toString(), Modifier.weight(1f))
                    Metric("最长一轮", longest, Modifier.weight(1f))
                }
            }
        } else {
            Row(
                Modifier.padding(start = 14.dp, end = 3.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Metric("手机使用", duration(report.used), Modifier.weight(1.6f), prominent = true)
                Metric(countLabel, count.toString(), Modifier.weight(0.8f))
                Metric("最长一轮", longest, Modifier.weight(1.2f))
                StatisticsInfoButton("手机使用统计说明",deviceStatisticsInfo(records,from,to))
            }
        }
    }
}

@Composable internal fun ReportSurface(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

@Composable internal fun Metric(label: String, value: String, modifier: Modifier, prominent: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
@Composable internal fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,pkg) { value=withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(80,80).asImageBitmap() }.getOrNull() } }
    Crossfade(bitmap,animationSpec=tween(180),modifier=Modifier.size(36.dp),label="app-icon") {icon ->
        if(icon!=null) Image(icon,null,Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        else Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.Apps,null,Modifier.size(22.dp))}
    }
}
@Composable internal fun SessionLine(s: SessionRow, records: Records, from: Long, to: Long, onClick: () -> Unit) {
    val purpose = records.annotations.firstOrNull { it.purpose.isNotBlank() && it.start <= maxOf(s.start,from) && it.end >= minOf(s.end,to) }?.purpose .orEmpty()
    ListItem(headlineContent={ Text(appName(s.pkg,records),maxLines=1,overflow=TextOverflow.Ellipsis) }, supportingContent={ Text("${clock(maxOf(s.start,from))} – ${clock(minOf(s.end,to))}${if(s.ongoing) " · 进行中" else ""}${if(s.inferred) " · 推算" else ""}${if(purpose.isNotBlank()) " · $purpose" else ""}",style=MaterialTheme.typography.bodySmall) }, leadingContent={ AppIcon(s.pkg) }, trailingContent={ Text(duration(overlap(s.start,s.end,from,to)),style=MaterialTheme.typography.labelLarge) }, modifier=Modifier.clickable(onClick=onClick))
}
@Composable private fun EmptyState(title:String, body:String) { Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleSmall); Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun RangeDialog(date:LocalDate, from:Long, to:Long, onDismiss:()->Unit, onSave:(Long,Long)->Unit) {
    val zone=ZoneId.systemDefault()
    fun minute(ms:Long):Int { val t=Instant.ofEpochMilli(ms).atZone(zone); return if(t.toLocalDate()>date) 1440 else t.hour*60+t.minute }
    var start by remember { mutableStateOf("%02d:%02d".format(minute(from).coerceIn(0,1439)/60,minute(from).coerceIn(0,1439)%60)) }
    var end by remember { mutableStateOf(if(to>from) "%02d:%02d".format(minute(to).coerceIn(0,1440)/60,minute(to).coerceIn(0,1440)%60) else "24:00") }
    fun parse(text:String):Int? { val p=text.split(":"); if(p.size!=2) return null; val h=p[0].toIntOrNull() ?: return null; val m=p[1].toIntOrNull() ?: return null; return if(h in 0..23 && m in 0..59 || h==24 && m==0) h*60+m else null }
    val a=parse(start); val b=parse(end)
    val valid=a!=null && b!=null && b>a
    AlertDialog(onDismissRequest=onDismiss,title={ Text("选择时段") },text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { Text(date.toString()); OutlinedTextField(start,{ start=it },label={ Text("开始时间（00:00）") },singleLine=true); OutlinedTextField(end,{ end=it },label={ Text("结束时间（可填 24:00）") },singleLine=true); if(!valid) Text("请填写同一天内有效的起止时间",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) } },confirmButton={ TextButton(onClick={ onSave(date.atStartOfDay().plusMinutes(a!!.toLong()).atZone(zone).toInstant().toEpochMilli(),date.atStartOfDay().plusMinutes(b!!.toLong()).atZone(zone).toInstant().toEpochMilli()) },enabled=valid) { Text("查看时段") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}
@Composable private fun AnnotationDialog(row:AnnotationRow, onDismiss:()->Unit, onDelete:()->Unit, onSave:suspend (AnnotationRow,Boolean)->Boolean, onDone:()->Unit) {
    var purpose by remember(row.id) { mutableStateOf(row.purpose) }
    var note by remember(row.id) { mutableStateOf(row.note) }
    var conflict by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    fun save(replace:Boolean) { scope.launch { saving=true; try { if(onSave(row.copy(purpose=purpose.trim(),note=note.trim()),replace)) onDone() else conflict=true } catch(e:Exception) { error=e.message ?: "保存失败" } finally { saving=false } } }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("时段标注") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("${clock(row.start)} – ${clock(row.end)}",style=MaterialTheme.typography.bodyMedium); FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { purposes.forEach { p -> FilterChip(purpose==p,onClick={ purpose=if(purpose==p) "" else p },label={ Text(p) }) } }; OutlinedTextField(purpose,{ if(it.length<=40) purpose=it },label={ Text("用途（可自定义或留空）") },singleLine=true); OutlinedTextField(note,{ if(it.length<=2000) note=it },label={ Text("备注") },minLines=2,maxLines=5); error?.let { Text(it,color=MaterialTheme.colorScheme.error) } } },confirmButton={ TextButton(onClick={ save(false) },enabled=!saving && (purpose.isNotBlank() || note.isNotBlank())) { Text("保存") } },dismissButton={ Row { if(row.purpose.isNotBlank() || row.note.isNotBlank()) TextButton(onClick=onDelete,enabled=!saving) { Text("删除") }; TextButton(onClick=onDismiss) { Text("取消") } } })
    if(conflict) AlertDialog(onDismissRequest={ conflict=false },title={ Text("用途时间重叠") },text={ Text("此时段已有用途标注。替换只修改重叠部分，原备注保留。") },confirmButton={ TextButton(onClick={ conflict=false; save(true) }) { Text("替换重叠部分") } },dismissButton={ TextButton(onClick={ conflict=false }) { Text("返回调整") } })
}
