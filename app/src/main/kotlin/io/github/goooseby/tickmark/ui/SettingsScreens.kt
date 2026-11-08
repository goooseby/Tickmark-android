package io.github.goooseby.tickmark.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.goooseby.tickmark.BuildConfig
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.data.AppRow
import io.github.goooseby.tickmark.data.appCategory
import io.github.goooseby.tickmark.update.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun SettingsHome(records:Records,access:Boolean,onNavigate:(String)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=20.dp)) {
        item { SettingsSection("使用与复盘") }
        item { SettingsLink("每日目标",records.config["dailyGoalMinutes"]?.toIntOrNull()?.takeIf { it>0 }?.let { "每天 ${duration(it*60_000L)}" } ?: "设置自己合适的用时目标",Icons.Outlined.Flag) { onNavigate("goals") } }
        item { SettingsLink("应用管理","应用分类、排行显示与隐私排除",Icons.Outlined.Apps) { onNavigate("apps") } }
        item { SettingsLink("外观",when(records.config["theme"]) { "light"->"浅色"; "dark"->"深色"; else->"跟随系统" },Icons.Outlined.Palette) { onNavigate("appearance") } }
        item { SettingsLink("桌面组件","今日用时与未使用时段",Icons.Outlined.Widgets) { onNavigate("widgets") } }
        item { SettingsSection("采集与数据") }
        item { SettingsLink("采集状态",when { !access->"尚未授权"; records.config["paused"]=="true"->"已暂停"; else->"采集中" },Icons.Outlined.QueryStats) { onNavigate("capture") } }
        item { SettingsLink("数据管理","备份、恢复与导出",Icons.Outlined.Storage) { onNavigate("data") } }
        item { SettingsSection("关于") }
        item { SettingsLink("版本与更新","Tickmark ${BuildConfig.VERSION_NAME}",Icons.Outlined.SystemUpdate) { onNavigate("updates") } }
        item { SettingsLink("隐私说明","本地记录与更新联网范围",Icons.Outlined.PrivacyTip) { onNavigate("privacy") } }
        item { SettingsLink("项目与反馈","GitHub · goooseby/Tickmark-android",Icons.Outlined.Code) { onNavigate("project") } }
    }
}
@Composable internal fun SettingsSection(label:String) { Text(label,Modifier.padding(start=20.dp,top=20.dp,bottom=6.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary) }
@Composable internal fun SettingsLink(title:String,subtitle:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit) {
    ListItem(headlineContent={ Text(title) },supportingContent={ Text(subtitle,maxLines=2,overflow=TextOverflow.Ellipsis) },leadingContent={ Icon(icon,null) },trailingContent={ Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null) },modifier=Modifier.clickable(onClick=onClick))
}
@Composable internal fun AppManager(records:Records,onApp:(AppRow)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val apps=records.apps.filter { app -> (app.name.contains(query,true) || app.pkg.contains(query,true)) && when(filter) { 1->app.hidden; 2->app.excluded; else->true } }.sortedBy { it.name }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),placeholder={Text("搜索应用")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true)
        Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("全部","已隐藏","已排除").forEachIndexed { i,label -> FilterChip(filter==i,onClick={filter=i},label={Text(label)}) } }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)) {
            if(apps.isEmpty()) item { EmptyReport(if(query.isNotBlank() || filter!=0) "没有匹配的应用" else "采集到应用记录后会显示在这里") }
            items(apps,key={it.pkg}) { app -> ListItem(headlineContent={Text(app.name,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Text(listOfNotNull(app.appCategory.title,if(app.hidden) "排行隐藏" else null,if(app.excluded) "已排除明细" else null).joinToString(" · "))},leadingContent={AppIcon(app.pkg)},modifier=Modifier.clickable {onApp(app)}) }
        }
    }
}
@Composable internal fun AppPreferences(app:AppRow,config:Map<String,String>,onUpdate:(AppRow)->Unit,onSaveGoal:(String)->Unit) {
    var exclude by remember { mutableStateOf(false) }
    var budget by rememberSaveable(app.pkg,config["appGoal:${app.pkg}"]) { mutableStateOf(config["appGoal:${app.pkg}"].orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
        item { Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { AppIcon(app.pkg); Text(app.name,style=MaterialTheme.typography.titleMedium) } }
        item { SettingsSection("应用分类") }
        item { CategoryPicker(app,onUpdate) }
        item { SettingsSection("每日用时目标") }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(budget,{if(it.length<=4 && it.all(Char::isDigit)) {budget=it;saved=false}},label={Text("分钟（留空关闭）")},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
            Text("用于回顾当前用时，不强制拦截应用。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={onSaveGoal(budget);saved=true},enabled=budget.isEmpty() || budget.toIntOrNull() in 1..1440) {Text(if(saved) "已保存" else "保存目标")}
        } }
        item { SettingsSection("显示与隐私") }
        item { ListItem(headlineContent={Text("从排行隐藏")},supportingContent={Text("保留记录，可随时恢复显示")},trailingContent={Switch(app.hidden,{onUpdate(app.copy(hidden=it))})}) }
        item { ListItem(headlineContent={Text("停止保存明细")},supportingContent={Text(if(app.excluded) "只保留匿名时段" else "同时匿名化已有明细")},trailingContent={Switch(app.excluded,{if(it) exclude=true else onUpdate(app.copy(excluded=false))})}) }
        item { Text(app.pkg,Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if(exclude) AlertDialog(onDismissRequest={exclude=false},title={Text("停止保存此应用明细？")},text={Text("已有应用身份记录会匿名化，无法恢复。用途标注和备注保留。")},confirmButton={TextButton(onClick={onUpdate(app.copy(excluded=true));exclude=false}) {Text("匿名化并停止保存")}},dismissButton={TextButton(onClick={exclude=false}) {Text("取消")}})
}
@Composable internal fun AppearanceSettings(theme:String,onTheme:(String)->Unit) {
    Column { listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach { (value,label)->ListItem(headlineContent={Text(label)},leadingContent={RadioButton(selected=theme==value,onClick=null)},modifier=Modifier.clickable {onTheme(value)}) } }
}
@Composable internal fun CaptureSettings(records:Records,access:Boolean,onPermission:()->Unit,onPaused:(Boolean)->Unit,onRefresh:()->Unit) {
    LazyColumn {
        item { SettingsLink("使用情况访问权限",if(access) "已授权" else "未授权，历史仍可查看",Icons.Outlined.Security,onPermission) }
        item { ListItem(headlineContent={Text("自动采集")},supportingContent={Text("打开时刷新，后台约每小时补采")},trailingContent={Switch(records.config["paused"]!="true",{onPaused(!it)})}) }
        item { ListItem(headlineContent={Text("最后成功同步")},supportingContent={Text(records.config["lastSync"]?.toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss")) } ?: "尚未同步")}) }
        item { TextButton(onClick=onRefresh,Modifier.padding(horizontal=12.dp)) {Icon(Icons.Outlined.Refresh,null);Text("立即刷新记录")} }
        item { Text("系统可能延后后台任务。近期未打开或厂商限制后台时，记录可能不完整。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsSection("记录诊断") }
        item { ListItem(headlineContent={Text("设备统计口径")},supportingContent={Text(if(records.config["screenOnly"]=="true") "设备没有安全锁屏，按屏幕可交互时间统计" else "按屏幕与锁屏事件统计")}) }
        item { ListItem(headlineContent={Text("状态空档的原因")},supportingContent={Text("首次可查询事件前没有初始状态；系统重启或事件保留边界也可能缺少屏幕记录。启动事件晚于亮屏的情况已修复，不再清空新启动周期的状态。")}) }
        item { ListItem(headlineContent={Text("后续如何接续")},supportingContent={Text("每次同步保存当下屏幕和锁屏状态。已记录的状态可向后接续；不会把当前状态填回没有证据的历史。")}) }
        item { ListItem(headlineContent={Text("无法补回的历史")},supportingContent={Text("系统已不再提供的事件无法凭空恢复。打开应用、确认授权并刷新，可补采系统仍保留的范围。")}) }
    }
}
@Composable internal fun PrivacySettings() {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("使用记录",style=MaterialTheme.typography.titleSmall); Text("只读取应用使用、屏幕和锁屏事件。记录与备注保存在本机，不上传，不读取聊天、网页、键盘或截图内容。") }
        item { Text("网络访问",style=MaterialTheme.typography.titleSmall); Text("检查更新时访问 GitHub 的版本信息，下载时访问 GitHub 的安装包。GitHub 会收到网络请求所需的信息，例如 IP 地址。应用使用记录不随请求发送。自动检查默认关闭。") }
        item { Text("备份与排除",style=MaterialTheme.typography.titleSmall); Text("系统自动备份已关闭。导出的备份可能包含完整轨迹和备注，请自行保管。排除某应用后只保留匿名时段；手写备注仍保留。") }
    }
}
@Composable internal fun UpdateScreen(controller:UpdateController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope(); val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller,lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {while(true) {controller.refresh();delay(1500)}} }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("Tickmark ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleLarge);Text("当前版本",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Button(onClick={scope.launch {controller.check()}},enabled=!state.checking && !state.downloading) {if(state.checking) {CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))};Text(if(state.checking) "正在检查" else "检查更新")};OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("$REPOSITORY_URL/releases")))}) {Text("发布页")} } }
        item { state.message?.let {Text(it,style=MaterialTheme.typography.bodyMedium)};if(state.lastCheck>0) Text("上次检查：${Instant.ofEpochMilli(state.lastCheck).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        state.release?.let { release ->
            item { HorizontalDivider();Text(release.title,Modifier.padding(top=14.dp),style=MaterialTheme.typography.titleMedium);Text(release.notes.ifBlank {"此版本未提供更新说明"},style=MaterialTheme.typography.bodyMedium) }
            item {
                if(state.downloading) { if(state.progress!=null) LinearProgressIndicator(progress={state.progress!!/100f},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick={scope.launch {controller.cancel()}}) {Text("取消下载${state.progress?.let {" · $it%"} ?: ""}")} }
                else if(state.ready) Button(onClick={scope.launch {controller.install()}}) {Text("安装更新")}
                else if(release.apk!=null && release.digest!=null) Button(onClick={scope.launch {controller.download()}}) {Text("下载安装包 · ${"%.1f".format(release.bytes/1_048_576.0)} MB")}
                else Text("此版本暂不支持应用内下载，请打开发布页查看。",style=MaterialTheme.typography.bodyMedium)
            }
        }
        item { HorizontalDivider();ListItem(headlineContent={Text("自动检查更新")},supportingContent={Text("打开应用时检查，每天最多一次，不自动下载")},trailingContent={Switch(state.autoCheck,controller::setAuto)},colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surface)) }
        item { Text("安装由系统确认，更新会保留本地记录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun ProjectSettings() {
    val context=LocalContext.current
    Column {
        SettingsLink("源代码","goooseby/Tickmark-android",Icons.Outlined.Code) {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(REPOSITORY_URL)))}
        SettingsLink("反馈问题与建议","在 GitHub 中主动填写，不自动附加使用记录",Icons.Outlined.Feedback) {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("$REPOSITORY_URL/issues")))}
        SettingsLink("版本记录","查看每个版本的改动",Icons.Outlined.History) {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("$REPOSITORY_URL/releases")))}
    }
}
