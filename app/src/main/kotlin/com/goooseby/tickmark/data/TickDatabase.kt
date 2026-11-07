package com.goooseby.tickmark.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "events", indices = [Index("time")])
data class EventRow(@PrimaryKey val id: String, val time: Long, val kind: String, val pkg: String, val activity: String, val instance: Int, val sequence: Int)
@Entity(tableName = "sessions", indices = [Index("start"), Index("end")])
data class SessionRow(@PrimaryKey val id: String, val pkg: String, val start: Long, val end: Long, val inferred: Boolean, val ongoing: Boolean)
@Entity(tableName = "spans", indices = [Index("start")])
data class SpanRow(@PrimaryKey val start: Long, val end: Long, val state: String)
@Entity(tableName = "annotations")
data class AnnotationRow(@PrimaryKey val id: String, val start: Long, val end: Long, val purpose: String, val note: String)
@Entity(tableName = "metadata")
data class AppRow(@PrimaryKey val pkg: String, val name: String, val hidden: Boolean = false, val excluded: Boolean = false, val purpose: String = "")
@Entity(tableName = "config")
data class ConfigRow(@PrimaryKey val key: String, val value: String)
@Entity(tableName = "coverage")
data class CoverageRow(@PrimaryKey val start: Long, val end: Long, val eventCount: Int)

@Dao
interface TickDao {
    @Query("SELECT * FROM events ORDER BY time, sequence") suspend fun events(): List<EventRow>
    @Query("SELECT * FROM sessions ORDER BY start") fun sessions(): Flow<List<SessionRow>>
    @Query("SELECT * FROM spans ORDER BY start") fun spans(): Flow<List<SpanRow>>
    @Query("SELECT * FROM annotations ORDER BY start") fun annotations(): Flow<List<AnnotationRow>>
    @Query("SELECT * FROM metadata") fun apps(): Flow<List<AppRow>>
    @Query("SELECT * FROM coverage ORDER BY start") fun coverage(): Flow<List<CoverageRow>>
    @Query("SELECT * FROM config") fun config(): Flow<List<ConfigRow>>
    @Query("SELECT e.time FROM events e WHERE e.kind = 'UNLOCK' AND (SELECT p.kind FROM events p WHERE (p.time < e.time OR (p.time = e.time AND p.sequence < e.sequence)) AND p.kind IN ('LOCK','UNLOCK','STARTUP','SHUTDOWN','OBSERVE_LOCKED','OBSERVE_UNLOCKED') ORDER BY p.time DESC, p.sequence DESC LIMIT 1) IN ('LOCK','OBSERVE_LOCKED')")
    fun unlocks(): Flow<List<Long>>
    @Query("SELECT * FROM sessions ORDER BY start") suspend fun allSessions(): List<SessionRow>
    @Query("SELECT * FROM spans ORDER BY start") suspend fun allSpans(): List<SpanRow>
    @Query("SELECT * FROM annotations ORDER BY start") suspend fun allAnnotations(): List<AnnotationRow>
    @Query("SELECT * FROM metadata") suspend fun allApps(): List<AppRow>
    @Query("SELECT * FROM config") suspend fun allConfig(): List<ConfigRow>
    @Query("SELECT * FROM coverage ORDER BY start") suspend fun allCoverage(): List<CoverageRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEvents(rows: List<EventRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSessions(rows: List<SessionRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSpans(rows: List<SpanRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAnnotations(rows: List<AnnotationRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putApps(rows: List<AppRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConfig(rows: List<ConfigRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCoverage(rows: List<CoverageRow>)
    @Query("DELETE FROM events WHERE time >= :from AND time < :to") suspend fun deleteEvents(from: Long, to: Long)
    @Query("DELETE FROM events WHERE time >= :from AND time < :to AND kind NOT LIKE 'OBSERVE_%'") suspend fun deleteSystemEvents(from: Long, to: Long)
    @Query("DELETE FROM sessions") suspend fun deleteSessions()
    @Query("DELETE FROM spans") suspend fun deleteSpans()
    @Query("DELETE FROM annotations WHERE id = :id") suspend fun deleteAnnotation(id: String)
    @Query("DELETE FROM annotations") suspend fun deleteAnnotations()
    @Query("DELETE FROM metadata") suspend fun deleteApps()
    @Query("DELETE FROM config") suspend fun deleteConfig()
    @Query("DELETE FROM coverage") suspend fun deleteCoverage()
}
@Database(entities = [EventRow::class, SessionRow::class, SpanRow::class, AnnotationRow::class, AppRow::class, ConfigRow::class, CoverageRow::class], version = 1, exportSchema = false)
abstract class TickDatabase : RoomDatabase() { abstract fun dao(): TickDao }
