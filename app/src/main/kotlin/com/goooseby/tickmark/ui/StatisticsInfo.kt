package com.goooseby.tickmark.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goooseby.tickmark.data.report.Records
import com.goooseby.tickmark.engine.model.overlap
import com.goooseby.tickmark.engine.model.unionDuration
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun StatisticsInfoButton(title:String,body:String,modifier:Modifier=Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick={open=true},modifier=modifier) {Icon(Icons.Outlined.Info,contentDescription=title)}
    if(open) AlertDialog(onDismissRequest={open=false},title={Text(title)},text={Text(body)},confirmButton={TextButton(onClick={open=false}) {Text("知道了")}})
}
internal fun deviceStatisticsInfo(records:Records,from:Long,to:Long):String {
    val estimate=unionDuration(records.sessions.filter {it.inferred}.map {maxOf(it.start,from) to minOf(it.end,to)})
    val unknown=records.deviceReport(from,to).unknown
    val first=records.spans.minOfOrNull {it.start}
    return buildString {
        append(if(records.config["screenOnly"]=="true") "这台设备按屏幕亮起且可操作的时间统计。" else "手机使用统计屏幕可操作且已解锁的时间。")
        append("\n解锁或亮屏次数只统计记录到的状态变化，最长一轮指连续使用手机的时间。")
        if(estimate>0) append("\n\n有 ${duration(estimate)}应用记录缺少对应的屏幕状态，暂未计入手机使用总量；应用图表将其单独标记。")
        if(unknown>0) append("\n这段日期存在设备状态空档。可在设置的“采集状态”查看原因和最新同步结果。")
        if(first!=null && first>from) append("\n${Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}之前没有可还原的设备状态。")
    }
}
internal const val APP_COMPOSITION_INFO="占比按每个应用的前台用时计算。分屏等同时使用的应用会分别计时，因此合计可能超过手机使用总量。缺少屏幕状态的应用片段会标为推算。桌面、隐藏和隐私排除应用不进入此构成。"
