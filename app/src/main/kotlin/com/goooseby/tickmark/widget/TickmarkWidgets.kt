package com.goooseby.tickmark.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.goooseby.tickmark.R
import com.goooseby.tickmark.app.TickmarkApp
import com.goooseby.tickmark.app.reportIntent
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val sizes=setOf(DpSize(140.dp,150.dp),DpSize(280.dp,150.dp),DpSize(280.dp,230.dp))

abstract class TickmarkWidget(private val away:Boolean):GlanceAppWidget() {
    override val sizeMode=SizeMode.Responsive(sizes)
    override suspend fun provideGlance(context:Context,id:GlanceId) {
        val repo=(context.applicationContext as TickmarkApp).repository
        val fallback=WidgetSnapshot.create(repo.snapshot(),repo.hasAccess())
        val largeFont=context.resources.configuration.fontScale>1.2f
        val palette=WidgetPalette(context,away)
        provideContent {
            val state=currentState<Preferences>()
            val snapshot=WidgetSnapshot.decode(state[widgetPayloadKey]) ?: fallback
            WidgetContent(snapshot,away,(state[widgetFontScaleKey]?.let{it>1.2f} ?: largeFont),palette)
        }
    }
}
class TodayWidget:TickmarkWidget(false)
class AwayWidget:TickmarkWidget(true)

class TodayWidgetReceiver:GlanceAppWidgetReceiver() {
    override val glanceAppWidget:GlanceAppWidget=TodayWidget()
    override fun onUpdate(context:Context,manager:android.appwidget.AppWidgetManager,ids:IntArray) {
        super.onUpdate(context,manager,ids);WidgetCoordinator.refresh(context)
    }
    override fun onDeleted(context:Context,ids:IntArray) {super.onDeleted(context,ids);WidgetCoordinator.render(context)}
}
class AwayWidgetReceiver:GlanceAppWidgetReceiver() {
    override val glanceAppWidget:GlanceAppWidget=AwayWidget()
    override fun onUpdate(context:Context,manager:android.appwidget.AppWidgetManager,ids:IntArray) {
        super.onUpdate(context,manager,ids);WidgetCoordinator.refresh(context)
    }
    override fun onDeleted(context:Context,ids:IntArray) {super.onDeleted(context,ids);WidgetCoordinator.render(context)}
}
class RefreshWidgetAction:ActionCallback {
    override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters) {WidgetCoordinator.refresh(context)}
}

@Composable internal fun WidgetContent(state:WidgetSnapshot,away:Boolean,largeFont:Boolean,palette:WidgetPalette) {
    val context=LocalContext.current
    val size=LocalSize.current
    val wide=size.width>=280.dp
    val tall=size.height>=230.dp
    val background=palette.background
    val ink=palette.ink
    val secondary=palette.secondary
    val accent=palette.accent
    val date=runCatching{LocalDate.parse(state.date)}.getOrDefault(LocalDate.now())
    val intent=reportIntent(context,if(!state.access) "capture" else if(away) "away" else "today",date)
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(background).cornerRadius(26.dp)
        .clickable(actionStartActivity(intent)).padding(16.dp)) {
        Row(GlanceModifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            if(!largeFont || wide) {
                Image(ImageProvider(if(away) R.drawable.ic_widget_away else R.drawable.ic_widget_today),null,
                    GlanceModifier.size(17.dp),colorFilter=ColorFilter.tint(accent))
                Spacer(GlanceModifier.width(7.dp))
            }
            Text(if(away) "未使用手机" else if(date==LocalDate.now()) "今日用时" else "${date.monthValue}/${date.dayOfMonth} 用时",style=TextStyle(color=ink,fontSize=13.sp,fontWeight=FontWeight.Medium),maxLines=1)
        }
        Spacer(GlanceModifier.height(if(wide) 10.dp else 12.dp))
        if(!state.hasEvidence) {
            Text(if(!state.access) "允许读取记录" else "暂无记录",style=TextStyle(color=ink,fontSize=21.sp,fontWeight=FontWeight.Bold),maxLines=2)
            Spacer(GlanceModifier.defaultWeight())
            WidgetFooter(state,secondary,largeFont)
        } else if(wide && largeFont) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(),verticalAlignment=Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(if(away) "最长一段" else "已用",style=TextStyle(color=secondary,fontSize=11.sp))
                    DurationNumber(if(away) state.longest else state.used,ink,wide=false)
                }
                Spacer(GlanceModifier.width(16.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(if(away) "累计" else "最长未使用",style=TextStyle(color=secondary,fontSize=11.sp))
                    DurationNumber(if(away) state.away else state.longest,ink,wide=false)
                }
            }
            WidgetFooter(state,secondary,largeFont)
        } else if(wide) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(),verticalAlignment=Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(if(away) "最长一段" else if(state.goalMinutes>0) "已用 / 目标${widgetDuration(state.goalMinutes*60_000)}" else "已记录",style=TextStyle(color=secondary,fontSize=11.sp),maxLines=1)
                    Spacer(GlanceModifier.height(4.dp))
                    DurationNumber(if(away) state.longest else state.used,ink,wide=true)
                    Spacer(GlanceModifier.height(6.dp))
                    if(away) Text(if(state.longest>0) timeRange(context,state.longestStart,state.longestEnd) else "暂无未使用时段",style=TextStyle(color=secondary,fontSize=12.sp),maxLines=1)
                    else if(state.goalMinutes>0) LinearProgressIndicator(progress=(state.used.toFloat()/(state.goalMinutes*60_000)).coerceIn(0f,1f),modifier=GlanceModifier.fillMaxWidth().height(4.dp),color=accent,backgroundColor=palette.track)
                    else Text("最长未使用 ${widgetDuration(state.longest)}",style=TextStyle(color=secondary,fontSize=12.sp),maxLines=1)
                }
                Spacer(GlanceModifier.width(18.dp))
                if(away) Box(GlanceModifier.size(106.dp),contentAlignment=Alignment.Center) {
                    Image(ImageProvider(WidgetArt.clock(context,state.awayBars)),"全天未使用时段分布",GlanceModifier.fillMaxSize())
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("累计",style=TextStyle(color=secondary,fontSize=11.sp))
                        Text(widgetDuration(state.away),style=TextStyle(color=ink,fontSize=14.sp,fontWeight=FontWeight.Medium),maxLines=1)
                    }
                } else Column(GlanceModifier.width(124.dp)) {
                    Image(ImageProvider(WidgetArt.bars(context,state.usageBars,false)),"每小时使用时长",GlanceModifier.fillMaxWidth().height(48.dp))
                    Row(GlanceModifier.fillMaxWidth()) {
                        Text("00",style=TextStyle(color=secondary,fontSize=10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("12",style=TextStyle(color=secondary,fontSize=10.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text("24",style=TextStyle(color=secondary,fontSize=10.sp))
                    }
                }
            }
            if(tall) {
                Spacer(GlanceModifier.height(12.dp))
                Text(if(away) "最近一段  ${widgetDuration(state.latest)} · ${timeRange(context,state.latestStart,state.latestEnd)}"
                    else state.action.ifBlank{"最长未使用 ${widgetDuration(state.longest)} · ${timeRange(context,state.longestStart,state.longestEnd)}"},
                    style=TextStyle(color=ink,fontSize=13.sp),maxLines=2)
            }
            WidgetFooter(state,secondary,largeFont)
        } else {
            DurationNumber(if(away) state.longest else state.used,ink,wide=false)
            Spacer(GlanceModifier.height(3.dp))
            Text(if(away) "最长一段" else if(state.goalMinutes>0) "目标 ${widgetDuration(state.goalMinutes*60_000)}" else "最长未使用 ${widgetDuration(state.longest)}",style=TextStyle(color=secondary,fontSize=11.sp),maxLines=1)
            Spacer(GlanceModifier.defaultWeight())
            if(!largeFont) Image(ImageProvider(WidgetArt.bars(context,if(away) state.awayBars else state.usageBars,away)),if(away) "全天未使用时段分布" else "每小时使用时长",GlanceModifier.fillMaxWidth().height(26.dp))
            WidgetFooter(state,secondary,largeFont)
        }
    }
}

@Composable private fun DurationNumber(ms:Long,ink:ColorProvider,wide:Boolean) {
    val minutes=(ms/60_000).coerceAtLeast(0)
    Row(verticalAlignment=Alignment.Bottom) {
        if(minutes>=60) {
            Text((minutes/60).toString(),style=TextStyle(color=ink,fontSize=(if(wide) 36 else 30).sp,fontWeight=FontWeight.Bold),maxLines=1)
            Text("时 ",style=TextStyle(color=ink,fontSize=12.sp))
            Text("%02d".format(minutes%60),style=TextStyle(color=ink,fontSize=(if(wide) 36 else 30).sp,fontWeight=FontWeight.Bold),maxLines=1)
            Text("分",style=TextStyle(color=ink,fontSize=12.sp))
        } else {
            Text(if(ms>0 && minutes==0L) (ms/1000).coerceAtLeast(1).toString() else minutes.toString(),style=TextStyle(color=ink,fontSize=(if(wide) 38 else 34).sp,fontWeight=FontWeight.Bold),maxLines=1)
            Text(if(ms>0 && minutes==0L) "秒" else "分钟",style=TextStyle(color=ink,fontSize=12.sp))
        }
    }
}
@Composable private fun WidgetFooter(state:WidgetSnapshot,secondary:ColorProvider,largeFont:Boolean) {
    Row(GlanceModifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        val context=LocalContext.current
        val stamp=if(state.updated>0) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.updated)) else "尚未更新"
        Text(if(!state.access) "恢复授权" else if(state.paused) "采集暂停" else if(state.date==LocalDate.now().toString()) "$stamp 更新" else "${LocalDate.parse(state.date).monthValue}/${LocalDate.parse(state.date).dayOfMonth} · $stamp",style=TextStyle(color=secondary,fontSize=11.sp),modifier=GlanceModifier.defaultWeight(),maxLines=1)
        if(!largeFont || LocalSize.current.width>=280.dp) Box(GlanceModifier.size(40.dp).clickable(actionRunCallback<RefreshWidgetAction>()),contentAlignment=Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_widget_refresh),"刷新记录",GlanceModifier.size(16.dp),colorFilter=ColorFilter.tint(secondary))
        }
    }
}
internal fun widgetDuration(ms:Long):String {
    val minutes=ms/60_000
    return when {ms in 1..59_999->"${(ms/1000).coerceAtLeast(1)}秒";minutes<60->"${minutes}分";else->"${minutes/60}时${if(minutes%60>0) "${minutes%60}分" else ""}"}
}
private fun timeRange(context:Context,start:Long,end:Long):String {
    if(end<=start) return ""
    val format=android.text.format.DateFormat.getTimeFormat(context)
    return "${format.format(java.util.Date(start))}–${format.format(java.util.Date(end))}"
}
