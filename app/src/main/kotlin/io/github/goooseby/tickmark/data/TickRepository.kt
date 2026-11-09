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
import java.time.Instant
import kotlinx.coroutines.ensureActive
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class TickRepository(val context: Context, val db: TickDatabase, private val captureEnabled: Boolean = true) {
    val dao = db.dao()
    private val classifier by lazy { AppClassifier(context) }
    private val mutex = Mutex()
    private val journalRevisions=mutableMapOf<String,Long>()
    val syncing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val records: Flow<Records> = db.invalidationTracker.createFlow(
        "sessions","spans","annotations","metadata","coverage","config","events","journal","system_summaries",emitInitialState=true
    ).map { snapshot() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun snapshot(): Records = withContext(Dispatchers.IO) {
        db.withTransaction {
            Records(dao.allSessions(),dao.allSpans(),dao.allAnnotations(),dao.allApps(),dao.allCoverage(),
                dao.allConfig().associate { it.key to it.value },dao.allUnlocks(),dao.allJournal(),dao.allSummaries())
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
                val existingStart=config["captureStart"]?.toLongOrNull()
                // Earlier versions recorded an exact clear time, while the default was local midnight.
                val legacyFloor=existingStart?.takeIf {
                    config["historyImported"]==null && Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()!=java.time.LocalTime.MIDNIGHT
                }
                val floor=config["captureFloor"]?.toLongOrNull() ?: legacyFloor ?: 0L
                val importHistory=config["historyImported"]!="true"
                val now = System.currentTimeMillis()
                val from=if(importHistory) floor else maxOf(floor,(config["lastSync"]?.toLongOrNull() ?: now)-30*60_000L)
                val windows=if(importHistory) HistoryImport.windows(now,floor) else listOf(from to now)
                val manager=context.getSystemService(UsageStatsManager::class.java)
                val rules=dao.allApps().associateBy{it.pkg}
                val rows=mutableListOf<EventRow>()
                var sequence=0
                for((start,end) in windows) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val source=manager.queryEvents(start,end) ?: error("系统暂时无法提供记录，请解锁后重试")
                    val event=UsageEvents.Event()
                    while(source.hasNextEvent()) {
                        source.getNextEvent(event)
                        val kind=when(event.eventType) {
                            UsageEvents.Event.ACTIVITY_RESUMED->EventKind.RESUME
                            UsageEvents.Event.ACTIVITY_PAUSED->EventKind.PAUSE
                            UsageEvents.Event.ACTIVITY_STOPPED->EventKind.STOP
                            UsageEvents.Event.SCREEN_INTERACTIVE->EventKind.SCREEN_ON
                            UsageEvents.Event.SCREEN_NON_INTERACTIVE->EventKind.SCREEN_OFF
                            UsageEvents.Event.KEYGUARD_HIDDEN->EventKind.UNLOCK
                            UsageEvents.Event.KEYGUARD_SHOWN->EventKind.LOCK
                            UsageEvents.Event.DEVICE_STARTUP->EventKind.STARTUP
                            UsageEvents.Event.DEVICE_SHUTDOWN->EventKind.SHUTDOWN
                            else->null
                        } ?: continue
                        if(event.timeStamp !in start until end) continue
                        val excluded=rules[event.packageName]?.excluded==true
                        val pkg=if(excluded) "@excluded" else event.packageName.orEmpty()
                        val cls=if(excluded) "" else event.className.orEmpty()
                        rows+=EventRow("${event.timeStamp}:${sequence}",event.timeStamp,kind.name,pkg,cls,0,sequence++)
                    }
                    kotlinx.coroutines.yield()
                }
                // A one-time broader import must not erase locally preserved older events.
                val saved=if(importHistory) dao.events() else emptyList()
                val imported=if(importHistory) HistoryImport.additions(saved,rows) else rows
                val first=if(floor>0) floor else minOf(existingStart ?: now,rows.minOfOrNull{it.time} ?: (now-1).coerceAtLeast(0))
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
                if(bootCount>=0 && config[bootKey]==null && bootTime>=floor) observations+=EventRow("observe:boot:$bootCount:$bootTime",bootTime,"OBSERVE_BOOT","","",0,-1)
                db.withTransaction {
                    if(!importHistory) dao.deleteSystemEvents(from, now)
                    dao.putEvents(imported)
                    dao.putEvents(observations)
                    dao.putApps(metadata)
                    rebuild(observedAt)
                    dao.putCoverage(listOf(CoverageRow(if(importHistory) first else from, now, rows.size)))
                    dao.putConfig(listOf(ConfigRow("lastSync", now.toString()), ConfigRow("captureStart", first.toString()),ConfigRow("lastObserved",observedAt.toString()),ConfigRow("algorithmVersion","2"),ConfigRow("historyImported","true"),ConfigRow("captureFloor",floor.toString())))
                    if(bootCount>=0) dao.putConfig(listOf(ConfigRow(bootKey,bootTime.toString())))
                }
                // Aggregates have their own provenance and may outlive event-level history.
                try {
                    val lastSummary=config["lastSummarySync"]?.toLongOrNull() ?: 0
                    if(now-lastSummary>=86_400_000L || config["historySummaryImported"]!="true") {
                        val summaries=SummaryCodec.query(manager,floor,now,rules.values.filter{it.excluded}.map{it.pkg}.toSet())
                        val summaryApps=appMetadata(summaries.map{it.pkg}.filter{it !in rules},rules)
                        db.withTransaction {
                            dao.putSummaries(summaries);dao.putApps(summaryApps)
                            dao.putConfig(listOf(ConfigRow("lastSummarySync",now.toString()),ConfigRow("historySummaryImported","true")))
                        }
                    }
                    message.value=null
                } catch(e:Exception) {
                    if(e is kotlinx.coroutines.CancellationException) throw e
                    message.value="使用记录已保存，系统历史汇总暂未更新，请稍后刷新。"
                }
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
    suspend fun journalForEditing(initial:JournalRow):Pair<JournalRow,Long> = exclusive {
        (dao.journal(initial.id) ?: initial) to (journalRevisions[initial.id] ?: 0L)
    }
    suspend fun saveJournal(row:JournalRow,complete:Boolean=false,expectedRevision:Long?=null):JournalRow? = exclusive {
        JournalRow.validate(row)
        require(expectedRevision==null || expectedRevision==(journalRevisions[row.id] ?: 0L)){"记录已被删除或恢复，请返回后重新打开"}
        val old=dao.journal(row.id)
        if(!row.meaningful && old==null) return@exclusive null
        require(!complete || row.meaningful){"请先填写记录内容"}
        val now=System.currentTimeMillis()
        val saved=row.copy(draft=if(complete) false else old?.draft ?: row.draft,
            actionDone=if(old!=null && old.action!=row.action) false else row.actionDone,
            createdAt=old?.createdAt?.takeIf{it>0} ?: row.createdAt.takeIf{it>0} ?: now,updatedAt=now)
        dao.putJournal(listOf(saved));saved
    }
    suspend fun deleteJournal(id:String):JournalRow? = exclusive {
        val old=dao.journal(id);journalRevisions[id]=(journalRevisions[id] ?: 0L)+1;dao.deleteJournal(id);old
    }
    suspend fun restoreJournal(row:JournalRow)=exclusive {JournalRow.validate(row);journalRevisions[row.id]=(journalRevisions[row.id] ?: 0L)+1;dao.putJournal(listOf(row))}
    suspend fun toggleJournalAction(id:String)=exclusive {
        dao.journal(id)?.takeIf{it.action.isNotBlank()}?.let {dao.putJournal(listOf(it.copy(actionDone=!it.actionDone,updatedAt=System.currentTimeMillis())))}
    }
    suspend fun pinJournal(id:String)=exclusive {
        dao.journal(id)?.let {dao.putJournal(listOf(it.copy(pinned=!it.pinned)))}
    }
    suspend fun updateApp(app: AppRow) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.withTransaction {
                val current=dao.allApps().find{it.pkg==app.pkg}
                dao.putApps(listOf(app.copy(automaticCategory=current?.automaticCategory ?: app.automaticCategory)))
                if (app.excluded) {
                    dao.deleteAppSummaries(app.pkg)
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
            val start=System.currentTimeMillis().toString()
            dao.putConfig(listOf(ConfigRow("captureStart",start),ConfigRow("captureFloor",start),ConfigRow("historyImported","true"),ConfigRow("paused",paused.toString())))
        } }
    }
    suspend fun wipe() {
        dao.allJournal().forEach{journalRevisions[it.id]=(journalRevisions[it.id] ?: 0L)+1}
        dao.deleteEvents(Long.MIN_VALUE, Long.MAX_VALUE); dao.deleteSessions(); dao.deleteSpans()
        dao.deleteAnnotations(); dao.deleteApps(); dao.deleteConfig(); dao.deleteCoverage();dao.deleteJournal();dao.deleteSummaries()
    }
    suspend fun <T> exclusive(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
}
