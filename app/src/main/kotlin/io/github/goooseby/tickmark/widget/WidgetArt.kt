package io.github.goooseby.tickmark.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.ColorRes
import io.github.goooseby.tickmark.R

/** Only the data marks are bitmaps; labels and actions remain native accessible widget views. */
object WidgetArt {
    fun bars(context:Context,values:List<Long>,away:Boolean):Bitmap {
        val bitmap=Bitmap.createBitmap(480,112,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val color=context.getColor(if(away) R.color.widget_away_accent else R.color.widget_today_accent)
        val track=context.getColor(if(away) R.color.widget_away_track else R.color.widget_today_track)
        val step=480f/values.size.coerceAtLeast(1)
        values.forEachIndexed { i,value ->
            paint.color=track
            canvas.drawRoundRect(RectF(i*step+2,4f,(i+1)*step-3,108f),4f,4f,paint)
            if(value>0) {
                paint.color=color
                val height=(value/3_600_000f).coerceIn(0f,1f)*104
                canvas.drawRoundRect(RectF(i*step+2,108-height,(i+1)*step-3,108f),4f,4f,paint)
            }
        }
        return bitmap
    }
    fun clock(context:Context,values:List<Long>):Bitmap {
        val bitmap=Bitmap.createBitmap(360,360,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=25f;paint.strokeCap=Paint.Cap.ROUND
        paint.color=context.getColor(R.color.widget_away_track)
        val rect=RectF(29f,29f,331f,331f)
        canvas.drawArc(rect,0f,360f,false,paint)
        val step=360f/values.size.coerceAtLeast(1)
        values.forEachIndexed { i,value ->
            if(value>0) {
                paint.color=context.getColor(R.color.widget_away_accent)
                canvas.drawArc(rect,-90+i*step+1f,(step-2f)*(value/3_600_000f).coerceIn(0f,1f),false,paint)
            }
        }
        paint.style=Paint.Style.FILL;paint.color=context.getColor(R.color.widget_away_secondary)
        listOf(0f,90f,180f,270f).forEach { angle ->
            val radians=Math.toRadians((angle-90).toDouble())
            canvas.drawCircle(180+145*kotlin.math.cos(radians).toFloat(),180+145*kotlin.math.sin(radians).toFloat(),3f,paint)
        }
        return bitmap
    }
}
