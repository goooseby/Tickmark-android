package io.github.goooseby.tickmark.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.goooseby.tickmark.app.TickmarkApp
import io.github.goooseby.tickmark.data.sync.SyncRunResult

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when ((applicationContext as TickmarkApp).sync.synchronize()) {
        SyncRunResult.RETRY -> Result.retry()
        else -> Result.success()
    }
}
