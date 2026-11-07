package com.goooseby.tickmark.app

import android.app.Application
import androidx.room.Room
import androidx.work.*
import com.goooseby.tickmark.data.*
import com.goooseby.tickmark.worker.CaptureWorker
import com.goooseby.tickmark.update.UpdateController
import java.util.concurrent.TimeUnit

class TickmarkApp : Application() {
    val repository by lazy { TickRepository(this, Room.databaseBuilder(this, TickDatabase::class.java, "tickmark.db").build()) }
    val updates by lazy { UpdateController(this) }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("capture", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CaptureWorker>(1, TimeUnit.HOURS).build())
    }
}
