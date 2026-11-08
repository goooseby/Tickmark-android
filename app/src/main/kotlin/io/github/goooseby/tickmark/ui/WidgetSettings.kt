package io.github.goooseby.tickmark.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.goooseby.tickmark.R
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.widget.*

@Composable internal fun WidgetSettings(records:Records,access:Boolean) {
    val context=LocalContext.current
    val snapshot=remember(records,access){WidgetSnapshot.create(records,access)}
    var message by remember{mutableStateOf<String?>(null)}
    fun pin(away:Boolean) {
        val manager=context.getSystemService(AppWidgetManager::class.java)
        if(manager.isRequestPinAppWidgetSupported) {
            val provider=ComponentName(context,if(away) AwayWidgetReceiver::class.java else TodayWidgetReceiver::class.java)
            if(!manager.requestPinAppWidget(provider,Bundle.EMPTY,null)) message="请从桌面的组件列表添加 Tickmark"
        } else message="请在桌面长按空白处，从组件列表添加 Tickmark"
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text("今日用时",style=MaterialTheme.typography.titleMedium)}
        item {WidgetPreview(snapshot,false)}
        item {Button(onClick={pin(false)}){Text("添加今日用时组件")}}
        item {Spacer(Modifier.height(8.dp));Text("未使用时段",style=MaterialTheme.typography.titleMedium)}
        item {WidgetPreview(snapshot,true)}
        item {Button(onClick={pin(true)}){Text("添加未使用时段组件")}}
        item {Text(message ?: "添加后可在桌面长按调整大小。组件显示最近一次采集的结果，点击刷新可补采。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable private fun WidgetPreview(state:WidgetSnapshot,away:Boolean) {
    val context=LocalContext.current
    val background=Color(context.getColor(if(away) R.color.widget_away_background else R.color.widget_today_background))
    val ink=Color(context.getColor(if(away) R.color.widget_away_ink else R.color.widget_today_ink))
    val secondary=Color(context.getColor(if(away) R.color.widget_away_secondary else R.color.widget_today_secondary))
    Surface(color=background,shape=RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(away) "未使用手机" else "今日用时",style=MaterialTheme.typography.labelLarge,color=ink)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(if(away) "最长一段" else "已记录",style=MaterialTheme.typography.labelMedium,color=secondary)
                    Text(widgetDuration(if(away) state.longest else state.used),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,color=ink)
                }
                if(away) Image(WidgetArt.clock(context,state.awayBars).asImageBitmap(),"未使用时段分布",Modifier.size(92.dp))
                else Image(WidgetArt.bars(context,state.usageBars,false).asImageBitmap(),"小时使用分布",Modifier.width(124.dp).height(44.dp))
            }
            Text(if(away) "累计 ${widgetDuration(state.away)}" else if(state.goalMinutes>0) "目标 ${widgetDuration(state.goalMinutes*60_000)}" else "最长未使用 ${widgetDuration(state.longest)}",style=MaterialTheme.typography.bodySmall,color=secondary)
        }
    }
}
