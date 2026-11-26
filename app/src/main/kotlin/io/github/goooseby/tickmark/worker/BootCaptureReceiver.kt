package io.github.goooseby.tickmark.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** 开机后立即补采一次，缩短重启到首次后台补采之间的空窗。 */
class BootCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "boot-capture",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CaptureWorker>().build(),
        )
    }
}
