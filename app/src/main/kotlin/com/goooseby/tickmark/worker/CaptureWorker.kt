package com.goooseby.tickmark.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goooseby.tickmark.app.TickmarkApp

class CaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as TickmarkApp).repository
        repo.sync()
        return if (repo.message.value == null) Result.success() else Result.retry()
    }
}
