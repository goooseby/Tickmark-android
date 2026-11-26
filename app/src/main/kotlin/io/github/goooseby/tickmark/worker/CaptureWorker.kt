package io.github.goooseby.tickmark.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.goooseby.tickmark.app.TickmarkApp

class CaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TickmarkApp
        val repo = app.repository
        repo.sync()
        app.sync.synchronize()
        val active = repo.hasAccess() && repo.snapshot().config["paused"] != "true"
        val succeeded = active && repo.message.value == null
        if (succeeded) repo.setConfig("lastBackgroundCapture" to System.currentTimeMillis().toString())
        return if (succeeded || !active) Result.success() else Result.retry()
    }
}
