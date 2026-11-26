@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.goooseby.tickmark.ui

import android.os.Build
import android.os.SystemClock
import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.goooseby.tickmark.data.sync.SyncConfirmationRequired
import io.github.goooseby.tickmark.data.sync.SyncCoordinator
import io.github.goooseby.tickmark.data.sync.SyncErrorCode
import io.github.goooseby.tickmark.data.sync.SyncException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun SyncSettings(coordinator: SyncCoordinator, configured: Boolean) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    when {
        !configured -> UnavailableSync()
        state.accountId == null || state.status == "reauth_required" -> SyncLogin(coordinator, reauth = state.accountId != null)
        else -> SyncAccount(coordinator, state)
    }
}

@Composable
private fun UnavailableSync() {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            SettingsGroup {
                ListItem(
                    headlineContent = { Text("跨设备同步") },
                    supportingContent = { Text("同步服务尚未开放") },
                    leadingContent = { IconTile(Icons.Outlined.CloudSync) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item {
            Text(
                "本机记录、报告和备份不受影响。",
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncLogin(coordinator: SyncCoordinator, reauth: Boolean) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(Build.MODEL?.takeIf { it.isNotBlank() }?.take(80) ?: "Android") }
    var challenge by rememberSaveable { mutableStateOf<String?>(null) }
    var challengeEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var resendAt by rememberSaveable { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var code by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var submittedStartDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var sending by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    val busy = sending || signingIn
    var error by remember { mutableStateOf<String?>(null) }
    var invalidEmail by remember { mutableStateOf(false) }
    val normalizedEmail = email.trim().lowercase()
    val hasChallenge = challenge != null && challengeEmail == normalizedEmail
    val isEmailValid = normalizedEmail.length <= 254 && Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()
    val resendSeconds = ((resendAt - elapsed + 999) / 1_000).coerceAtLeast(0)
    val focus = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme
    val fieldShape = RoundedCornerShape(14.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = scheme.surfaceContainerLowest,
        unfocusedContainerColor = scheme.surfaceContainerLowest,
        errorContainerColor = scheme.surfaceContainerLowest,
    )
    val continueLogin: () -> Unit = {
        when {
            !isEmailValid -> {
                invalidEmail = true
                emailFocus.requestFocus()
            }
            !hasChallenge -> error = "请先获取验证码"
            code.length != 6 -> {
                error = "请输入 6 位验证码"
                codeFocus.requestFocus()
            }
            else -> {
                focus.clearFocus()
                consent = true
            }
        }
    }
    LaunchedEffect(resendAt) {
        elapsed = SystemClock.elapsedRealtime()
        while (elapsed < resendAt) {
            delay(minOf(1_000L, resendAt - elapsed))
            elapsed = SystemClock.elapsedRealtime()
        }
    }

    val requestCode: () -> Unit = {
        if (!isEmailValid) {
            invalidEmail = true
            emailFocus.requestFocus()
        } else {
            focus.clearFocus()
            sending = true
            error = null
            scope.launch {
                try {
                    val result = coordinator.requestCode(normalizedEmail)
                    challenge = result.challengeId
                    challengeEmail = normalizedEmail
                    code = ""
                    elapsed = SystemClock.elapsedRealtime()
                    resendAt = elapsed + result.resendAfterSeconds * 1_000L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    (failure as? SyncException)?.retryAfterSeconds?.let { seconds ->
                        elapsed = SystemClock.elapsedRealtime()
                        resendAt = elapsed + seconds * 1_000L
                    }
                    error = syncErrorMessage(failure)
                } finally {
                    sending = false
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                    ) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                    }
                    Text(
                        if (reauth) "重新登录" else "邮箱登录",
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    )
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        if (it.trim().lowercase() != normalizedEmail) {
                            challenge = null
                            challengeEmail = null
                            code = ""
                            submittedStartDay = null
                        }
                        email = it
                        error = null
                        invalidEmail = false
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(emailFocus),
                    label = { Text("受邀邮箱") },
                    isError = invalidEmail,
                    supportingText = if (invalidEmail) ({ Text("请输入有效的邮箱地址") }) else null,
                    singleLine = true,
                    readOnly = busy,
                    shape = fieldShape,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )
                val codeField: @Composable (Modifier) -> Unit = { modifier ->
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            if (it.length <= 6 && it.all { digit -> digit in '0'..'9' }) {
                                code = it
                                error = null
                            }
                        },
                        modifier = modifier.focusRequester(codeFocus),
                        label = { Text("验证码") },
                        placeholder = { Text("6 位数字") },
                        singleLine = true,
                        readOnly = busy,
                        shape = fieldShape,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (!busy) continueLogin()
                        }),
                    )
                }
                val sendButton: @Composable (Modifier) -> Unit = { modifier ->
                    FilledTonalButton(
                        onClick = requestCode,
                        modifier = modifier.heightIn(min = 48.dp),
                        enabled = !busy && resendSeconds == 0L,
                        shape = fieldShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            disabledContainerColor = scheme.surfaceContainerHigh,
                            disabledContentColor = scheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(when {
                            sending -> "发送中"
                            resendSeconds >= 3_600 -> "${(resendSeconds + 3_599) / 3_600} 小时后重发"
                            resendSeconds > 60 -> "${(resendSeconds + 59) / 60} 分钟后重发"
                            resendSeconds > 0 -> "${resendSeconds} 秒后重发"
                            challenge != null -> "重新发送"
                            else -> "获取验证码"
                        })
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 230.dp || LocalDensity.current.fontScale > 1.25f) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            codeField(Modifier.fillMaxWidth())
                            sendButton(Modifier.padding(top = 4.dp))
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            codeField(Modifier.weight(1f))
                            sendButton(Modifier.width(128.dp).padding(bottom = 4.dp))
                        }
                    }
                }
                val message = error ?: if (challenge != null) "请查收邮件，验证码 5 分钟内有效。" else null
                if (message != null) {
                    Text(
                        message,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = continueLogin,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp),
                    shape = fieldShape,
                ) {
                    Text(if (signingIn) "登录中" else "登录")
                }
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    Text(
                        "同账号的电脑可查看本机用时",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (consent) {
        SyncUploadRangeDialog(
            deviceName = deviceName,
            onDeviceNameChange = { if (it.length <= 80) deviceName = it },
            onDismiss = {
                submittedStartDay = null
                consent = false
            },
            canConfirm = !busy && hasChallenge && code.length == 6 && deviceName.isNotBlank(),
            initialDay = submittedStartDay,
            onConfirm = { startDate ->
                submittedStartDay = startDate.toEpochDay()
                val cutoff = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
                consent = false
                signingIn = true
                error = null
                val challengeId = checkNotNull(challenge)
                scope.launch {
                    try {
                        coordinator.verifyCode(challengeId, code, deviceName.trim(), cutoff, historyConfirmed = true)
                        challenge = null
                        code = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (failure is SyncException && failure.code == SyncErrorCode.EXPIRED_CODE) {
                            challenge = null
                            code = ""
                        }
                        error = syncErrorMessage(failure)
                    } finally {
                        signingIn = false
                    }
                }
            },
        )
    }
}

@Composable
internal fun SyncUploadRangeDialog(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    canConfirm: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    initialDay: Long? = null,
) {
    val localZone = ZoneId.systemDefault()
    val today = LocalDate.now(localZone)
    var selectedDay by rememberSaveable { mutableStateOf(initialDay) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    val fromDate = selectedDay?.let(LocalDate::ofEpochDay)?.coerceAtMost(today) ?: today
    if (datePicker) {
        UploadStartDatePicker(fromDate, today, onDismiss = { datePicker = false }) { date ->
            selectedDay = date.toEpochDay().takeUnless { date == today }
            datePicker = false
        }
    } else {
        SyncUploadConsent(
            deviceName = deviceName,
            onDeviceNameChange = onDeviceNameChange,
            fromDate = fromDate,
            today = today,
            onChangeDate = { datePicker = true },
            onResetDate = { selectedDay = null },
            onDismiss = onDismiss,
            canConfirm = canConfirm,
            onConfirm = { onConfirm(fromDate) },
        )
    }
}

@Composable
private fun SyncUploadConsent(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    fromDate: LocalDate,
    today: LocalDate,
    onChangeDate: () -> Unit,
    onResetDate: () -> Unit,
    onDismiss: () -> Unit,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
) {
    val horizontalInset = if (LocalConfiguration.current.screenWidthDp <= 360) 12.dp else 0.dp
    val dateLabel = if (fromDate == today) "今天（${fromDate.monthValue}月${fromDate.dayOfMonth}日）"
        else fromDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = horizontalInset),
        title = { Text("确认上传范围") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("设备名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("上传开始日期", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(
                        onClick = onChangeDate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp))
                            Text(dateLabel, Modifier.weight(1f))
                            Text("更改")
                        }
                    }
                    Text("从这天零点起持续同步本机记录。", style = MaterialTheme.typography.bodySmall)
                    if (fromDate != today) {
                        TextButton(onClick = onResetDate) { Text("改为从今天起") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = canConfirm) { Text("开启同步") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun UploadStartDatePicker(
    date: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    // Material's date picker represents a calendar date as UTC midnight.
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = remember(today) {
            val latest = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
                override fun isSelectableYear(year: Int) = year <= today.year
            }
        },
    )
    val selected = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val confirm: @Composable () -> Unit = {
        TextButton(onClick = { selected?.let(onSelect) }, enabled = selected != null && selected <= today) {
            Text("使用此日期")
        }
    }
    val cancel: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text("取消") } }
    val picker: @Composable () -> Unit = {
        DatePicker(pickerState, title = {
            Text("选择上传开始日期", Modifier.padding(start = 24.dp, top = 16.dp), style = MaterialTheme.typography.labelLarge)
        })
    }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    if (screenWidth < 360) {
        // DatePickerDialog itself requires a 360 dp width on Android.
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val dayCellSize = ((screenWidth - 40) / 7).coerceIn(40, 48).dp
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides dayCellSize) {
                        picker()
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        cancel()
                        confirm()
                    }
                }
            }
        }
    } else {
        DatePickerDialog(onDismissRequest = onDismiss, confirmButton = confirm, dismissButton = cancel, content = { picker() })
    }
}

@Composable
private fun SyncAccount(coordinator: SyncCoordinator, state: io.github.goooseby.tickmark.data.sync.SyncUiState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signOut by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf(false) }
    val revoked = state.status == "revoked"
    val status = when {
        revoked -> "设备访问已撤销"
        state.status == "rebuilding" -> "正在恢复云端记录"
        state.status == "reauth_required" -> "需要重新登录"
        else -> "自动同步已开启"
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            SettingsSection("同步状态")
            SettingsGroup {
                ListItem(
                    headlineContent = { Text(state.deviceName ?: "此设备") },
                    supportingContent = { Text(status) },
                    leadingContent = { IconTile(Icons.Outlined.CloudSync) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SettingsDivider()
                ListItem(
                    headlineContent = { Text("最近成功") },
                    supportingContent = {
                        Text(state.lastSuccessAt.takeIf { it > 0 }?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                        } ?: "尚未完成")
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SettingsDivider()
                ListItem(
                    headlineContent = { Text("待上传") },
                    supportingContent = { Text(if (state.pendingCount == 0) "没有待上传分片" else "${state.pendingCount} 个日期分片待处理") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try { coordinator.synchronize() } catch (failure: Exception) { error = syncErrorMessage(failure) } finally { busy = false }
                        }
                    },
                    enabled = !busy && !revoked,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (busy) "正在同步" else "立即同步")
                }
                OutlinedButton(onClick = { signOut = true }, enabled = !busy) { Text("退出登录") }
                if (!revoked) OutlinedButton(onClick = { revoke = true }, enabled = !busy) { Text("撤销此设备") }
            }
        }
        state.lastError?.let { code ->
            item { Text(syncErrorLabel(code), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        error?.let { message ->
            item { Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (signOut) {
        AlertDialog(
            onDismissRequest = { signOut = false },
            title = { Text("退出登录？") },
            text = { Text("退出后停止上传。本机记录和云端已有记录都会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    signOut = false
                    busy = true
                    scope.launch {
                        try { coordinator.signOut(false) } catch (failure: Exception) { error = syncErrorMessage(failure) } finally { busy = false }
                    }
                }, enabled = !busy) { Text("退出登录") }
            },
            dismissButton = { TextButton(onClick = { signOut = false }, enabled = !busy) { Text("取消") } },
        )
    }
    if (revoke) {
        AlertDialog(
            onDismissRequest = { revoke = false },
            title = { Text("撤销此设备？") },
            text = { Text("撤销后服务端将停止此设备的同步访问，本机记录仍保留。") },
            confirmButton = {
                TextButton(onClick = {
                    revoke = false
                    busy = true
                    scope.launch {
                        try { coordinator.signOut(true) } catch (failure: Exception) { error = syncErrorMessage(failure) } finally { busy = false }
                    }
                }, enabled = !busy) { Text("撤销并退出") }
            },
            dismissButton = { TextButton(onClick = { revoke = false }, enabled = !busy) { Text("取消") } },
        )
    }
}

private fun syncErrorMessage(error: Throwable): String = when (error) {
    is SyncConfirmationRequired -> "请确认要上传的本机历史范围"
    is SyncException -> when (error.code) {
        SyncErrorCode.CONFIGURATION -> "同步服务尚未配置"
        SyncErrorCode.NETWORK -> "网络连接失败，请稍后重试"
        SyncErrorCode.AUTH_REQUIRED -> "登录已失效，请重新验证邮箱"
        SyncErrorCode.INVALID_CODE -> "验证码不正确"
        SyncErrorCode.EXPIRED_CODE -> "验证码已失效，请重新获取"
        SyncErrorCode.DEVICE_REVOKED, SyncErrorCode.FORBIDDEN -> "此设备的同步访问已失效"
        SyncErrorCode.REVISION_CONFLICT, SyncErrorCode.STALE_REVISION -> "同步版本冲突，本地证据已保留"
        SyncErrorCode.EPOCH_MISMATCH -> "正在恢复同步状态，请稍后重试"
        SyncErrorCode.RATE_LIMITED -> "操作过于频繁，请稍后重试"
        SyncErrorCode.PROTOCOL, SyncErrorCode.SERVER -> "同步服务暂时不可用"
        SyncErrorCode.VALIDATION -> "请检查输入内容"
    }
    is IllegalArgumentException -> "请检查输入内容"
    is org.json.JSONException -> "同步服务暂时不可用"
    else -> "操作未完成，请稍后重试"
}

private fun syncErrorLabel(code: String): String = when (code) {
    "AUTH_REQUIRED" -> "登录已失效，请重新验证邮箱"
    "DEVICE_REVOKED", "FORBIDDEN" -> "此设备的同步访问已撤销"
    "REVISION_CONFLICT", "STALE_REVISION", "Conflict", "Stale" -> "同步版本冲突，本地证据已保留"
    "EPOCH_MISMATCH" -> "正在恢复同步状态"
    "NETWORK" -> "网络连接失败，本机记录已保留"
    "RATE_LIMITED" -> "同步请求过于频繁，请稍后重试"
    "CONFIGURATION" -> "同步服务尚未配置"
    else -> "同步暂未完成，本机记录已保留"
}
