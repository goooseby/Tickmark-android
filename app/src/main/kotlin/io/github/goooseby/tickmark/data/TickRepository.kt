package io.github.goooseby.tickmark.data

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.os.UserManager
import androidx.room.withTransaction
import io.github.goooseby.tickmark.engine.model.*
import io.github.goooseby.tickmark.engine.session.SessionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.goooseby.tickmark.data.report.Records
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class TickRepository(val context: Context, val db: TickDatabase, private val captureEnabled: Boolean = true) {
    val dao = db.dao()
    private val classifier by lazy { AppClassifier(context) }
    private val mutex = Mutex()
    val syncing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val records: Flow<Records> = db.invalidationTracker.createFlow(
        "sessions","spans","annotations","metadata","coverage","config","events",emitInitialState=true
    ).map { snapshot() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun snapshot(): Records = withContext(Dispatchers.IO) {
        db.withTransaction {
            Records(dao.allSessions(),dao.allSpans(),dao.allAnnotations(),dao.allApps(),dao.allCoverage(),
                dao.allConfig().associate { it.key to it.value },dao.allUnlocks())
        }
    }
    fun hasAccess(): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    private fun appMetadata(packages:Collection<String>,existing:Map<String,AppRow>):List<AppRow> {
        val manager=context.packageManager
        val home=manager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        return packages.filter{it.isNotEmpty() && !it.startsWith("@")}.distinct().map { pkg ->
            val old=existing[pkg]
            val info=runCatching{manager.getApplicationInfo(pkg,0)}.getOrNull()
            val name=info?.let{manager.getApplicationLabel(it).toString()}
            val automatic=classifier.classify(pkg,info?.category ?: -1)
            (old ?: AppRow(pkg,pkg,hidden=pkg==home || pkg=="com.android.systemui" || pkg=="android")).copy(
                name=if(pkg==home) "桌面" else name ?: old?.name ?: pkg,
                automaticCategory=if(info==null && automatic==io.github.goooseby.tickmark.engine.analytics.AppCategory.UNKNOWN && !old?.automaticCategory.isNullOrEmpty()) old!!.automaticCategory else automatic.name)
        }
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!captureEnabled) return@withLock
            syncing.value = true
            try {
                val existing=dao.allApps().associateBy{it.pkg}
                val refreshed=appMetadata(existing.keys,existing).filter{it!=existing[it.pkg]}
                if(refreshed.isNotEmpty()) dao.putApps(refreshed)
                if (!hasAccess()) return@withLock
                check(context.getSystemService(UserManager::class.java).isUserUnlocked) { "解锁设备后再刷新" }
                val config = dao.allConfig().associate { it.key to it.value }
                if (config["paused"] == "true") return@withLock
                val now = System.currentTimeMillis()
                val first = config["captureStart"]?.toLongOrNull()
                    ?: LocalDate.now().minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val from = maxOf(first, (config["lastSync"]?.toLongOrNull() ?: first) - 30 * 60_000L)
                val manager = context.getSystemService(UsageStatsManager::class.java)
                val source = manager.queryEvents(from, now) ?: error("系统暂时无法提供记录，请解锁后重试")
                val rules = dao.allApps().associateBy { it.pkg }
                val rows = mutableListOf<EventRow>()
                val event = UsageEvents.Event()
                var sequence = 0
                while (source.hasNextEvent()) {
                    source.getNextEvent(event)
                    val kind = when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> EventKind.RESUME
                        UsageEvents.Event.ACTIVITY_PAUSED -> EventKind.PAUSE
                        UsageEvents.Event.ACTIVITY_STOPPED -> EventKind.STOP
                        UsageEvents.Event.SCREEN_INTERACTIVE -> EventKind.SCREEN_ON
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> EventKind.SCREEN_OFF
                        UsageEvents.Event.KEYGUARD_HIDDEN -> EventKind.UNLOCK
                        UsageEvents.Event.KEYGUARD_SHOWN -> EventKind.LOCK
                        UsageEvents.Event.DEVICE_STARTUP -> EventKind.STARTUP
                        UsageEvents.Event.DEVICE_SHUTDOWN -> EventKind.SHUTDOWN
                        else -> null
                    } ?: continue
                    if (event.timeStamp !in from until now) continue
                    val excluded = rules[event.packageName]?.excluded == true
                    val pkg = if (excluded) "@excluded" else event.packageName.orEmpty()
                    // Identity is removed before persistence; anonymous lifecycle boundaries remain.
                    val cls = if (excluded) "" else event.className.orEmpty()
                    // The public SDK does not expose Activity instance IDs; match package + class.
                    rows += EventRow("${event.timeStamp}:${sequence}", event.timeStamp, kind.name, pkg, cls, 0, sequence++)
                }
                val metadata=appMetadata(rows.map{it.pkg}.filter{it !in rules},rules)
                val observedAt=System.currentTimeMillis()
                val interactive=context.getSystemService(PowerManager::class.java).isInteractive
                val locked=context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                val observations=mutableListOf(
                    EventRow("observe:$observedAt:screen",observedAt,if(interactive) "OBSERVE_SCREEN_ON" else "OBSERVE_SCREEN_OFF","","",0,Int.MAX_VALUE-1),
                    EventRow("observe:$observedAt:keyguard",observedAt,if(locked) "OBSERVE_LOCKED" else "OBSERVE_UNLOCKED","","",0,Int.MAX_VALUE))
                val bootCount=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
                val bootKey="observedBoot:$bootCount"
                val bootTime=config[bootKey]?.toLongOrNull() ?: (observedAt-SystemClock.elapsedRealtime()).coerceAtLeast(0)
                if(bootCount>=0 && config[bootKey]==null) observations+=EventRow("observe:boot:$bootCount:$bootTime",bootTime,"OBSERVE_BOOT","","",0,-1)
                db.withTransaction {
                    dao.deleteSystemEvents(from, now)
                    dao.putEvents(rows)
                    dao.putEvents(observations)
                    dao.putApps(metadata)
                    rebuild(observedAt)
                    dao.putCoverage(listOf(CoverageRow(from, now, rows.size)))
                    dao.putConfig(listOf(ConfigRow("lastSync", now.toString()), ConfigRow("captureStart", first.toString()),ConfigRow("lastObserved",observedAt.toString()),ConfigRow("algorithmVersion","2")))
                    if(bootCount>=0) dao.putConfig(listOf(ConfigRow(bootKey,bootTime.toString())))
                }
                message.value = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message.value = "同步未完成，已保留本地记录。${e.message.orEmpty().take(100)}"
            } finally { syncing.value = false }
        }
    }
    private suspend fun rebuild(until: Long) {
        val events = dao.events().map { UsageEvent(it.time, EventKind.valueOf(it.kind), it.pkg, it.activity, it.instance) }
        val screenOnly = !context.getSystemService(KeyguardManager::class.java).isDeviceSecure && events.none { it.kind == EventKind.LOCK || it.kind == EventKind.UNLOCK }
        val result = SessionEngine.reconstruct(events, until, screenOnly)
        dao.putConfig(listOf(ConfigRow("screenOnly", screenOnly.toString())))
        dao.deleteSessions(); dao.deleteSpans()
        dao.putSessions(result.sessions.map { SessionRow("${it.packageName}:${it.start}:${it.inferred}", it.packageName, it.start, it.end, it.inferred, it.ongoing) })
        dao.putSpans(result.deviceSpans.map { SpanRow(it.start, it.end, it.state.name) })
    }
    suspend fun annotate(row: AnnotationRow, replace: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(row.end > row.start && row.note.length <= 2000 && row.purpose.length <= 40)
            val conflicts = dao.allAnnotations().filter { it.id != row.id && it.purpose.isNotEmpty() && row.purpose.isNotEmpty() && it.start < row.end && it.end > row.start }
            if (conflicts.isNotEmpty() && !replace) return@withLock false
            db.withTransaction {
                for (old in conflicts) {
                    dao.deleteAnnotation(old.id)
                    if (old.start < row.start) dao.putAnnotations(listOf(old.copy(id = UUID.randomUUID().toString(), end = row.start, note = "")))
                    if (old.end > row.end) dao.putAnnotations(listOf(old.copy(id = UUID.randomUUID().toString(), start = row.end, note = "")))
                    // Notes remain independent of purpose replacement.
                    if (old.note.isNotBlank()) dao.putAnnotations(listOf(old.copy(id = UUID.randomUUID().toString(), purpose = "")))
                }
                dao.putAnnotations(listOf(row))
            }
            true
        }
    }
    suspend fun deleteAnnotation(id: String) = withContext(Dispatchers.IO) { dao.deleteAnnotation(id) }
    suspend fun setConfig(vararg values: Pair<String,String>) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.putConfig(values.map { ConfigRow(it.first,it.second) }) }
    }
    suspend fun setPaused(paused: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.putConfig(listOf(ConfigRow("paused", paused.toString()))) }
    }
    suspend fun updateApp(app: AppRow) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.withTransaction {
                val current=dao.allApps().find{it.pkg==app.pkg}
                dao.putApps(listOf(app.copy(automaticCategory=current?.automaticCategory ?: app.automaticCategory)))
                if (app.excluded) {
                    val raw = dao.events()
                    dao.putEvents(raw.filter { it.pkg == app.pkg }.map { it.copy(pkg = "@excluded", activity = "", instance = 0) })
                    rebuild(System.currentTimeMillis())
                }
            }
        }
    }
    suspend fun clear(paused: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { db.withTransaction {
            wipe()
            dao.putConfig(listOf(ConfigRow("captureStart", System.currentTimeMillis().toString()), ConfigRow("paused", paused.toString())))
        } }
    }
    suspend fun wipe() {
        dao.deleteEvents(Long.MIN_VALUE, Long.MAX_VALUE); dao.deleteSessions(); dao.deleteSpans()
        dao.deleteAnnotations(); dao.deleteApps(); dao.deleteConfig(); dao.deleteCoverage()
    }
    suspend fun <T> exclusive(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
}
