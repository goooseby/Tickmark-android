package com.goooseby.tickmark.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.InvalidationTracker
import androidx.work.*
import com.goooseby.tickmark.app.TickmarkApp
import java.util.concurrent.TimeUnit

val widgetPayloadKey=stringPreferencesKey("device_report_v1")
val widgetFontScaleKey=floatPreferencesKey("host_font_scale")

/** One publishing path for database commits, background capture, resizing and date changes. */
object WidgetCoordinator {
    fun observe(application:TickmarkApp) {
        application.repository.db.invalidationTracker.addObserver(object:InvalidationTracker.Observer("spans","config") {
            override fun onInvalidated(tables:Set<String>) { render(application) }
        })
    }
    fun render(context:Context) {
        if(!hasWidgets(context)) {WorkManager.getInstance(context).cancelUniqueWork("widget-periodic");return}
        WorkManager.getInstance(context).enqueueUniqueWork("widget-render",ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WidgetRenderWorker>().build())
    }
    private fun hasWidgets(context:Context):Boolean {
        val manager=AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context,TodayWidgetReceiver::class.java)).isNotEmpty() ||
            manager.getAppWidgetIds(ComponentName(context,AwayWidgetReceiver::class.java)).isNotEmpty()
    }
    fun refresh(context:Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("widget-capture",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetCaptureWorker>().build())
    }
    suspend fun updateSchedule(context:Context):Boolean {
        val manager=GlanceAppWidgetManager(context)
        val present=manager.getGlanceIds(TodayWidget::class.java).isNotEmpty() || manager.getGlanceIds(AwayWidget::class.java).isNotEmpty()
        val work=WorkManager.getInstance(context)
        if(present) work.enqueueUniquePeriodicWork("widget-periodic",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetCaptureWorker>(30,TimeUnit.MINUTES).build())
        else work.cancelUniqueWork("widget-periodic")
        return present
    }
    suspend fun publish(context:Context) {
        val application=context.applicationContext as TickmarkApp
        val payload=WidgetSnapshot.create(application.repository.snapshot(),application.repository.hasAccess()).encode()
        val manager=GlanceAppWidgetManager(context)
        for((type,widget) in listOf(TodayWidget::class.java to TodayWidget(),AwayWidget::class.java to AwayWidget())) {
            for(id in manager.getGlanceIds(type)) {
                updateAppWidgetState(context,id) {
                    it[widgetPayloadKey]=payload
                    it[widgetFontScaleKey]=context.resources.configuration.fontScale
                }
                widget.update(context,id)
            }
        }
    }
}
class WidgetRenderWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        if(WidgetCoordinator.updateSchedule(applicationContext)) WidgetCoordinator.publish(applicationContext)
        return Result.success()
    }
}
class WidgetCaptureWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        if(!WidgetCoordinator.updateSchedule(applicationContext)) return Result.success()
        val repo=(applicationContext as TickmarkApp).repository
        val disabled=!repo.hasAccess() || repo.snapshot().config["paused"]=="true"
        if(!disabled) repo.sync()
        WidgetCoordinator.publish(applicationContext)
        return if(disabled || repo.message.value==null) Result.success() else Result.retry()
    }
}
class WidgetClockReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action in setOf(Intent.ACTION_DATE_CHANGED,Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_BOOT_COMPLETED)) WidgetCoordinator.render(context)
    }
}
