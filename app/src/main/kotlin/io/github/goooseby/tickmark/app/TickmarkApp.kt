package io.github.goooseby.tickmark.app

import android.app.Application
import androidx.room.Room
import androidx.work.*
import io.github.goooseby.tickmark.BuildConfig
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.sync.*
import io.github.goooseby.tickmark.worker.CaptureWorker
import io.github.goooseby.tickmark.worker.SyncWorker
import io.github.goooseby.tickmark.update.UpdateController
import io.github.goooseby.tickmark.widget.WidgetCoordinator
import java.util.concurrent.TimeUnit

class TickmarkApp : Application() {
    val repository by lazy { TickRepository(this, Room.databaseBuilder(this, TickDatabase::class.java, "tickmark.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).build()) }
    val applicationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
    val updates by lazy { UpdateController(this) }
    private val syncClient by lazy {
        val kind = if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
        SyncHttpClient(BuildConfig.SYNC_API_BASE_URL, BuildConfig.DEBUG, kind)
    }
    val sync by lazy {
        SyncCoordinator(
            context = this,
            scope = applicationScope,
            repository = repository,
            store = SyncStore(repository.db),
            tokens = SyncTokenStore(this),
            identity = SyncIdentityStore(this),
            authenticator = syncClient,
            transport = syncClient,
            configured = syncClient.configured,
        )
    }
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        WidgetCoordinator.render(this)
    }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("capture", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CaptureWorker>(1, TimeUnit.HOURS).build())
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
        sync.start()
        WidgetCoordinator.observe(this)
    }
}
