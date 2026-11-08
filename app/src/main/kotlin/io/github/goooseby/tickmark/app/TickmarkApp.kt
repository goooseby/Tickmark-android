package io.github.goooseby.tickmark.app

import android.app.Application
import androidx.room.Room
import androidx.work.*
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.worker.CaptureWorker
import io.github.goooseby.tickmark.update.UpdateController
import io.github.goooseby.tickmark.widget.WidgetCoordinator
import java.util.concurrent.TimeUnit

class TickmarkApp : Application() {
    val repository by lazy { TickRepository(this, Room.databaseBuilder(this, TickDatabase::class.java, "tickmark.db").addMigrations(MIGRATION_1_2).build()) }
    val updates by lazy { UpdateController(this) }
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        WidgetCoordinator.render(this)
    }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("capture", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CaptureWorker>(1, TimeUnit.HOURS).build())
        WidgetCoordinator.observe(this)
    }
}
