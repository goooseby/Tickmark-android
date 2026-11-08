package io.github.goooseby.tickmark.share

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import io.github.goooseby.tickmark.R
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.engine.analytics.ReportEngine
import io.github.goooseby.tickmark.engine.model.DeviceState
import io.github.goooseby.tickmark.engine.model.Session
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

data class ShareSlice(val name:String,val duration:Long)
data class ReportImageData(
    val date:LocalDate,val weekly:Boolean,val generatedAt:Long,val zone:ZoneId,
    val used:Long,val away:Long,val longestAway:Long,val unlocks:Int,val screenOnly:Boolean,
    val recordedDays:Int,val elapsedDays:Int,val appCount:Int,
    val bars:List<Long?>,val barLabels:List<String>,val slices:List<ShareSlice>,val unknown:Long,
) {
    val title:String get()=if(weekly) "${date.year} · ${date.monthValue}/${date.dayOfMonth} – ${date.plusDays(6).monthValue}/${date.plusDays(6).dayOfMonth}" else date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE",java.util.Locale.SIMPLIFIED_CHINESE))
    val filename:String get()="Tickmark-${if(weekly) "week" else "day"}-$date.png"
    companion object {
        fun create(records:Records,date:LocalDate,weekly:Boolean,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):ReportImageData {
            val start=if(weekly) ReportEngine.weekStart(date) else date
            val from=start.atStartOfDay(zone).toInstant().toEpochMilli()
            val end=start.plusDays(if(weekly) 7 else 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val until=minOf(end,now).coerceAtLeast(from)
            val report=records.deviceReport(from,end,now)
            val boundaries=if(weekly) ReportEngine.weekBoundaries(start,zone) else {
                val a=start.atStartOfDay(zone);val b=start.plusDays(1).atStartOfDay(zone)
                generateSequence(a){it.plusHours(1)}.takeWhile{it<b}.map{it.toInstant().toEpochMilli()}.toList()+b.toInstant().toEpochMilli()
            }
            val buckets=ReportEngine.buckets(report.spans.filter{it.state==DeviceState.IN_USE}.map{it.start to it.end},boundaries)
            val bars=buckets.map { bucket ->
                if(bucket.start>=until || report.spans.none{it.state!=DeviceState.UNKNOWN && it.start<minOf(bucket.end,until) && it.end>bucket.start}) null else bucket.duration
            }
            val names=records.apps.associateBy{it.pkg}
            val sessions=records.sessions.filter{it.pkg!="@excluded" && names[it.pkg]?.let{a->a.hidden || a.excluded}!=true}
            val ranking=ReportEngine.composition(sessions.map{Session(it.pkg,it.start,it.end,it.inferred,it.ongoing)},from,until)
            val slices=ranking.take(4).map{ShareSlice(names[it.first]?.name ?: it.first,it.second)}.toMutableList()
            val other=ranking.drop(4).sumOf{it.second};if(other>0) slices+=ShareSlice("其他应用",other)
            val days=ReportEngine.weekBoundaries(start,zone).zipWithNext()
            val elapsed=if(weekly) days.count{it.first<until} else if(from<until) 1 else 0
            val recorded=if(weekly) days.count{(a,b)->report.spans.any{it.state!=DeviceState.UNKNOWN && it.start<minOf(b,until) && it.end>a}} else if(report.spans.any{it.state!=DeviceState.UNKNOWN}) 1 else 0
            val screenOnly=records.config["screenOnly"]=="true"
            val unlocks=if(screenOnly) records.deviceSpans.count{it.state==DeviceState.IN_USE && it.start>=from && it.start<until} else records.unlocks.count{it>=from && it<until}
            return ReportImageData(start,weekly,now,zone,report.used,report.notUsed,report.longestAway?.duration ?: 0,unlocks,screenOnly,recorded,elapsed,ranking.size,bars,
                if(weekly) (0..6).map{"周${listOf("一","二","三","四","五","六","日")[it]}"} else boundaries.dropLast(1).map{Instant.ofEpochMilli(it).atZone(zone).hour.toString().padStart(2,'0')},slices,report.unknown)
        }
    }
}

/** A fixed 3:4 report, independent of screen size, scroll position and animation phase. */
object ReportImage {
    const val WIDTH=1080
    const val HEIGHT=1440
    private val palette=intArrayOf(0xFF4378C5.toInt(),0xFF3E9487.toInt(),0xFFD59A36.toInt(),0xFF9670BC.toInt(),0xFF8191A2.toInt())
    private const val INK=0xFF172438.toInt()
    private const val MUTED=0xFF64738A.toInt()
    private const val BLUE=0xFF315DA8.toInt()
    private const val GRID=0xFFE3E9F0.toInt()

    private fun time(ms:Long):String {
        val minutes=(ms.coerceAtLeast(0)/60_000)
        return if(ms==0L) "0分钟" else if(minutes==0L) "不足1分钟" else if(minutes<60) "${minutes}分钟" else "${minutes/60}小时${if(minutes%60>0) "${minutes%60}分" else ""}"
    }

    fun render(context:Context,data:ReportImageData):Bitmap {
        val image=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG)
        fun text(value:String,x:Float,y:Float,size:Float,color:Int=INK,bold:Boolean=false,width:Float?=null,align:Paint.Align=Paint.Align.LEFT) {
            paint.color=color;paint.textSize=size;paint.typeface=if(bold) Typeface.create("sans-serif",Typeface.BOLD) else Typeface.create("sans-serif",Typeface.NORMAL);paint.textAlign=align;paint.style=Paint.Style.FILL
            val label=if(width==null) value else TextUtils.ellipsize(value,paint,width,TextUtils.TruncateAt.END).toString()
            canvas.drawText(label,x,y,paint)
        }
        fun line(y:Float){paint.color=GRID;paint.strokeWidth=2f;canvas.drawLine(64f,y,1016f,y,paint)}
        fun metric(label:String,value:String,x:Float){text(label,x,337f,25f,MUTED);text(value,x,387f,36f,INK,true,294f)}
        text("Tickmark · ${if(data.weekly) "每周使用" else "每日使用"}",64f,76f,28f,MUTED)
        val periodEnd=data.date.plusDays(if(data.weekly) 7 else 1).atStartOfDay(data.zone).toInstant().toEpochMilli()
        if(data.generatedAt<periodEnd) text("截至 ${Instant.ofEpochMilli(data.generatedAt).atZone(data.zone).format(DateTimeFormatter.ofPattern("HH:mm"))}",1016f,76f,24f,MUTED,align=Paint.Align.RIGHT)
        text(data.title,64f,143f,48f,INK,true,950f)
        text("手机使用",64f,207f,28f,MUTED)
        val total=if(data.recordedDays==0) "暂无记录" else time(data.used)
        paint.textSize=88f;paint.typeface=Typeface.create("sans-serif",Typeface.BOLD)
        val totalSize=minOf(88f,88f*620f/paint.measureText(total).coerceAtLeast(1f))
        text(total,64f,292f,totalSize,BLUE,true)
        text("累计未使用",745f,218f,25f,MUTED)
        text(if(data.recordedDays==0) "—" else time(data.away),745f,273f,36f,INK,true,270f)
        metric(if(data.screenOnly) "亮屏次数" else "解锁次数",if(data.recordedDays==0 && data.unlocks==0) "—" else "${data.unlocks} 次",64f)
        metric("最长未使用",if(data.recordedDays==0) "—" else time(data.longestAway),398f)
        metric(if(data.weekly) "有记录日期" else "使用应用",if(data.weekly) "${data.recordedDays} / ${data.elapsedDays} 天" else "${data.appCount} 个",732f)
        line(424f)
        text(if(data.weekly) "每日使用" else "每小时使用",64f,482f,34f,INK,true)
        text(if(data.weekly) "按天汇总" else "分钟",1016f,482f,24f,MUTED,align=Paint.Align.RIGHT)
        val maxValue=if(data.weekly) maxOf(3_600_000L,((data.bars.filterNotNull().maxOrNull() ?: 0)+3_599_999)/3_600_000*3_600_000) else 3_600_000L
        val left=132f;val right=1016f;val top=530f;val bottom=735f
        repeat(3){i->val y=top+(bottom-top)*i/2;paint.color=GRID;paint.strokeWidth=2f;canvas.drawLine(left,y,right,y,paint);text(if(data.weekly) time(maxValue*(2-i)/2) else listOf("60","30","0")[i],116f,y+8f,if(data.weekly) 20f else 24f,MUTED,align=Paint.Align.RIGHT)}
        val step=(right-left)/data.bars.size.coerceAtLeast(1)
        data.bars.forEachIndexed { i,value->
            val x=left+i*step+step*.17f
            if(value==null) text("—",left+(i+.5f)*step,bottom-8f,if(data.weekly) 24f else 16f,MUTED,align=Paint.Align.CENTER)
            else if(value>0) {val height=(value.toFloat()/maxValue).coerceIn(0f,1f)*(bottom-top);paint.color=BLUE;canvas.drawRoundRect(x,bottom-height,x+step*.66f,bottom,5f,5f,paint)}
            if(data.weekly || data.barLabels[i] in listOf("00","06","12","18")) text(data.barLabels[i],left+(i+.5f)*step,779f,24f,MUTED,align=Paint.Align.CENTER)
        }
        if(!data.weekly) text("24",right,779f,24f,MUTED,align=Paint.Align.RIGHT)
        if(data.bars.any{it==null}) text("— 记录不足或尚未开始",64f,825f,23f,MUTED)
        text("应用前台构成",64f,895f,34f,INK,true)
        val rect=RectF(129f,966f,399f,1236f)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=40f;paint.color=GRID;canvas.drawOval(rect,paint)
        val appTotal=data.slices.sumOf{it.duration}
        var angle=-90f
        data.slices.forEachIndexed { i,slice->
            val sweep=if(appTotal>0) slice.duration.toFloat()/appTotal*360f else 0f
            paint.color=palette[i];canvas.drawArc(rect,angle+1f,(sweep-2f).coerceAtLeast(0f),false,paint);angle+=sweep
        }
        paint.style=Paint.Style.FILL
        text("应用前台",264f,1085f,24f,MUTED,align=Paint.Align.CENTER)
        text(if(appTotal>0) time(appTotal) else "暂无记录",264f,1132f,29f,INK,true,220f,Paint.Align.CENTER)
        if(data.slices.isEmpty()) text("没有可展示的应用记录",485f,1104f,28f,MUTED)
        data.slices.forEachIndexed { i,slice->
            val y=986f+i*57f;paint.color=palette[i];canvas.drawCircle(485f,y-9f,7f,paint)
            text(slice.name,508f,y,27f,INK,width=310f)
            text(time(slice.duration),1016f,y,25f,MUTED,width=175f,align=Paint.Align.RIGHT)
        }
        text(if(data.unknown>0) "有记录空档 · 手机使用仅汇总已确认时长" else "手机使用按设备状态统计 · 应用前台含推算及并行使用",64f,1293f,23f,MUTED,width=952f)
        paint.color=0xFFF0F4FA.toInt();canvas.drawRect(0f,1320f,1080f,1440f,paint)
        ContextCompat.getDrawable(context,R.mipmap.ic_launcher)?.let{it.setBounds(64,1348,128,1412);it.draw(canvas)}
        text("Tickmark",151f,1378f,32f,BLUE,true)
        text("github.com/goooseby/Tickmark-android",151f,1410f,23f,MUTED)
        return image
    }
}
