package com.goooseby.tickmark.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import com.goooseby.tickmark.R

/** Public day/night providers let the launcher resolve colors, independent of app appearance. */
class WidgetPalette(context:Context,away:Boolean) {
    private val day=context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    })
    private val night=context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    })
    private fun color(id:Int)=ColorProvider(day=Color(day.getColor(id)),night=Color(night.getColor(id)))
    val background=color(if(away) R.color.widget_away_background else R.color.widget_today_background)
    val ink=color(if(away) R.color.widget_away_ink else R.color.widget_today_ink)
    val secondary=color(if(away) R.color.widget_away_secondary else R.color.widget_today_secondary)
    val accent=color(if(away) R.color.widget_away_accent else R.color.widget_today_accent)
    val track=color(if(away) R.color.widget_away_track else R.color.widget_today_track)
}
