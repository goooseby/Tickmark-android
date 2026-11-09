package io.github.goooseby.tickmark.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.goooseby.tickmark.BuildConfig
import io.github.goooseby.tickmark.R
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.data.AppRow
import io.github.goooseby.tickmark.data.appCategory
import io.github.goooseby.tickmark.update.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun SettingsHome(records: Records, access: Boolean, onNavigate: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val paused = records.config["paused"] == "true"
    val captureStatus = when {
        !access -> "尚未授权"
        paused -> "已暂停"
        else -> "采集中"
    }
    val goal = records.config["dailyGoalMinutes"]?.toLongOrNull()?.takeIf { it > 0 }
    val appearance = when (records.config["theme"]) {
        "light" -> "浅色"
        "dark" -> "深色"
        else -> "跟随系统"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            SettingsSection("采集与数据")
            SettingsGroup {
                SettingsLink(
                    "采集状态", captureStatus, Icons.Outlined.QueryStats,
                    color = when {
                        !access -> scheme.errorContainer
                        paused -> scheme.secondaryContainer
                        else -> scheme.tertiaryContainer
                    },
                    ink = when {
                        !access -> scheme.onErrorContainer
                        paused -> scheme.onSecondaryContainer
                        else -> scheme.onTertiaryContainer
                    },
                ) { onNavigate("capture") }
                SettingsDivider()
                SettingsLink("数据管理", "导出、备份与恢复", Icons.Outlined.Storage) { onNavigate("data") }
            }
        }
        item {
            SettingsSection("使用偏好")
            SettingsGroup {
                SettingsLink("每日目标", goal?.let { "每天 ${duration(it * 60_000)}" } ?: "未设置", Icons.Outlined.Flag) { onNavigate("goals") }
                SettingsDivider()
                SettingsLink("应用管理", "分类、排行与隐私排除", Icons.Outlined.Apps) { onNavigate("apps") }
                SettingsDivider()
                SettingsLink("外观", appearance, Icons.Outlined.Palette) { onNavigate("appearance") }
                SettingsDivider()
                SettingsLink("桌面组件", "今日用时与未使用时段", Icons.Outlined.Widgets) { onNavigate("widgets") }
            }
        }
        item {
            SettingsSection("关于")
            SettingsGroup {
                SettingsLink(
                    "关于 Tickmark", "版本 ${BuildConfig.VERSION_NAME} · 项目与反馈", Icons.Outlined.Info,
                    color = scheme.primaryContainer, ink = scheme.onPrimaryContainer,
                ) { onNavigate("project") }
                SettingsDivider()
                SettingsLink("版本与更新", "检查更新与安装设置", Icons.Outlined.SystemUpdate) { onNavigate("updates") }
                SettingsDivider()
                SettingsLink("隐私说明", "本地记录与更新联网范围", Icons.Outlined.PrivacyTip) { onNavigate("privacy") }
            }
        }
    }
}

@Composable
internal fun SettingsSection(label: String) {
    Text(
        label,
        Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        Modifier.padding(start = 62.dp, end = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
    )
}

@Composable
internal fun SettingsLink(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    ink: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    external: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color, ink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = if (external) "外部链接" else null,
            modifier = Modifier.size(if (external) 17.dp else 20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
@Composable internal fun AppManager(records:Records,onApp:(AppRow)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val apps=records.apps.filter { app -> (app.name.contains(query,true) || app.pkg.contains(query,true)) && when(filter) { 1->app.hidden; 2->app.excluded; else->true } }.sortedBy { it.name }
    Column(Modifier.fillMaxSize().imePadding()) {
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),placeholder={Text("搜索应用")},leadingIcon={Icon(Icons.Outlined.Search,null)},
            trailingIcon={ if(query.isNotEmpty()) IconButton(onClick={query=""}) { Icon(Icons.Outlined.Close,"清除搜索") } },
            singleLine=true,shape=RoundedCornerShape(20.dp))
        FlowRow(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("全部","已隐藏","已排除").forEachIndexed { i,label -> FilterChip(filter==i,onClick={filter=i},label={Text(label)}) } }
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
        item {
            ReportSurface {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(app.pkg)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SettingsSection("应用分类") }
        item { CategoryPicker(app,onUpdate) }
        item { SettingsSection("每日用时目标") }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(budget,{if(it.length<=4 && it.all(Char::isDigit)) {budget=it;saved=false}},label={Text("分钟（留空关闭）")},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
            Text("用于回顾当前用时，不强制拦截应用。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={onSaveGoal(budget);saved=true},enabled=budget.isEmpty() || budget.toIntOrNull() in 1..1440) {Text(if(saved) "已保存" else "保存目标")}
        } }
        item { SettingsSection("显示与隐私") }
        item {
            SettingsGroup {
                ListItem(
                    headlineContent = { Text("从排行隐藏") },
                    supportingContent = { Text("保留记录，可随时恢复显示") },
                    leadingContent = { IconTile(Icons.Outlined.VisibilityOff) },
                    trailingContent = { Switch(app.hidden, { onUpdate(app.copy(hidden = it)) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SettingsDivider()
                ListItem(
                    headlineContent = { Text("停止保存明细") },
                    supportingContent = { Text(if (app.excluded) "只保留匿名时段" else "同时匿名化已有明细") },
                    leadingContent = { IconTile(Icons.Outlined.PrivacyTip) },
                    trailingContent = { Switch(app.excluded, { if (it) exclude = true else onUpdate(app.copy(excluded = false)) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
    if(exclude) AlertDialog(onDismissRequest={exclude=false},title={Text("停止保存此应用明细？")},text={Text("已有应用身份记录会匿名化，无法恢复。用途标注和备注保留。")},confirmButton={TextButton(onClick={onUpdate(app.copy(excluded=true));exclude=false}) {Text("匿名化并停止保存")}},dismissButton={TextButton(onClick={exclude=false}) {Text("取消")}})
}
@Composable internal fun AppearanceSettings(theme:String,onTheme:(String)->Unit) {
    val choices = listOf(
        Triple("system", "跟随系统", Icons.Outlined.BrightnessAuto),
        Triple("light", "浅色", Icons.Outlined.LightMode),
        Triple("dark", "深色", Icons.Outlined.DarkMode),
    )
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            SettingsGroup {
                Column(Modifier.selectableGroup()) {
                    choices.forEachIndexed { index, (value, label, icon) ->
                        if (index > 0) SettingsDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .selectable(selected = theme == value, role = Role.RadioButton) { onTheme(value) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconTile(icon)
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            RadioButton(selected = theme == value, onClick = null)
                        }
                    }
                }
            }
        }
    }
}
@Composable internal fun CaptureSettings(records:Records,access:Boolean,onPermission:()->Unit,onPaused:(Boolean)->Unit,onRefresh:()->Unit) {
    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)) {
        item {
            SettingsGroup {
                SettingsLink("使用情况访问权限",if(access) "已授权" else "未授权，历史仍可查看",Icons.Outlined.Security,onClick=onPermission)
                SettingsDivider()
                ListItem(
                    headlineContent = { Text("自动采集") },
                    supportingContent = { Text("打开时刷新，后台定期补采") },
                    leadingContent = { IconTile(Icons.Outlined.QueryStats) },
                    trailingContent = { Switch(records.config["paused"] != "true", { onPaused(!it) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SettingsDivider()
                SettingsLink(
                    "立即刷新记录",
                    records.config["lastSync"]?.toLongOrNull()?.let {
                        val time = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                        "上次同步 · ${time.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}"
                    } ?: "尚未同步",
                    Icons.Outlined.Refresh,
                    onClick = onRefresh,
                )
            }
        }
        item { Text("系统可能延后后台补采。若出现记录空档，可打开应用刷新。",Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsSection("记录诊断") }
        item { ListItem(headlineContent={Text("设备统计口径")},supportingContent={Text(if(records.config["screenOnly"]=="true") "设备没有安全锁屏，按屏幕可交互时间统计" else "按屏幕与锁屏事件统计")}) }
        item { ListItem(headlineContent={Text("状态空档的原因")},supportingContent={Text("首次授权、手机重启或系统清理旧事件后，部分时段可能缺少屏幕和锁屏记录。")}) }
        item { ListItem(headlineContent={Text("无法补回的历史")},supportingContent={Text("系统已不再提供的事件无法凭空恢复。打开应用、确认授权并刷新，可补采系统仍保留的范围。")}) }
    }
}
@Composable internal fun PrivacySettings() {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            SettingsGroup {
                PrivacyRow("使用记录", "只读取应用使用、屏幕和锁屏事件。记录与备注保存在本机，不上传，不读取聊天、网页、键盘或截图内容。", Icons.Outlined.Lock)
                SettingsDivider()
                PrivacyRow("网络访问", "检查更新和下载时访问 GitHub，GitHub 会收到 IP 地址等请求信息。应用使用记录不随请求发送。自动检查默认关闭。", Icons.Outlined.Language)
                SettingsDivider()
                PrivacyRow("备份与排除", "系统自动备份已关闭。手动备份可能包含完整轨迹和备注。排除某应用后只保留匿名时段，手写备注仍保留。", Icons.Outlined.Backup)
            }
        }
    }
}

@Composable
private fun PrivacyRow(title: String, summary: String, icon: ImageVector) {
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun UpdateScreen(controller:UpdateController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope(); val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller,lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {while(true) {controller.refresh();delay(1500)}} }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile(Icons.Outlined.SystemUpdate, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    Column(Modifier.weight(1f)) {
                        Text("当前版本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tickmark ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Button(onClick={scope.launch {controller.check()}},enabled=!state.checking && !state.downloading) {if(state.checking) {CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))};Text(if(state.checking) "正在检查" else "检查更新")};OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("$REPOSITORY_URL/releases")))}) {Text("发布页")} } }
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
@Composable
internal fun AboutSettings() {
    val context = LocalContext.current
    var unavailable by rememberSaveable { mutableStateOf(false) }
    fun openLink(path: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL + path)))
        } catch (_: ActivityNotFoundException) {
            unavailable = true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { AboutIdentity() }
        item {
            SettingsSection("项目与反馈")
            SettingsGroup {
                SettingsLink("源代码", "goooseby/Tickmark-android", Icons.Outlined.Code, external = true) { openLink("") }
                SettingsDivider()
                SettingsLink("反馈问题与建议", "在 GitHub 填写反馈", Icons.Outlined.Feedback, external = true) { openLink("/issues") }
                SettingsDivider()
                SettingsLink("版本记录", "浏览历史版本和改动", Icons.Outlined.History, external = true) { openLink("/releases") }
            }
        }
    }
    if (unavailable) {
        AlertDialog(
            onDismissRequest = { unavailable = false },
            title = { Text("无法打开链接") },
            text = { Text("请安装或启用浏览器后重试。") },
            confirmButton = { TextButton(onClick = { unavailable = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun AboutIdentity() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                val radius = size.minDimension * 0.58f
                val center = Offset(size.width - radius * 0.37f, size.height * 0.76f)
                drawCircle(scheme.primary.copy(alpha = 0.12f), radius, center, style = Stroke(1.dp.toPx()))
                drawCircle(scheme.primary.copy(alpha = 0.09f), radius * 0.76f, center, style = Stroke(1.dp.toPx()))
                repeat(24) { tick ->
                    val angle = tick * Math.PI / 12
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(
                        scheme.primary.copy(alpha = if (tick % 6 == 0) 0.32f else 0.17f),
                        center + direction * (radius * 0.84f),
                        center + direction * (radius * 0.94f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 108.dp).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF263E58))) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.align(Alignment.Center).requiredSize(96.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tickmark", style = MaterialTheme.typography.titleLarge)
                    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
