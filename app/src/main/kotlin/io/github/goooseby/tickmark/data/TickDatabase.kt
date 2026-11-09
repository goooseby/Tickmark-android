package io.github.goooseby.tickmark.data

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
data class AppRow(@PrimaryKey val pkg: String, val name: String, val hidden: Boolean = false, val excluded: Boolean = false, val purpose: String = "",
    @ColumnInfo(defaultValue = "''") val automaticCategory:String = "",
    @ColumnInfo(defaultValue = "NULL") val categoryOverride:String? = null)
@Entity(tableName = "config")
data class ConfigRow(@PrimaryKey val key: String, val value: String)
@Entity(tableName = "coverage")
data class CoverageRow(@PrimaryKey val start: Long, val end: Long, val eventCount: Int)

@Dao
interface TickDao {
    @Query("SELECT * FROM system_summaries ORDER BY start") suspend fun allSummaries():List<SystemUsageSummary>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSummaries(rows:List<SystemUsageSummary>)
    @Query("DELETE FROM system_summaries") suspend fun deleteSummaries()
    @Query("DELETE FROM system_summaries WHERE pkg=:pkg") suspend fun deleteAppSummaries(pkg:String)
    @Query("SELECT * FROM events ORDER BY time, sequence") suspend fun events(): List<EventRow>
    @Query("SELECT * FROM sessions ORDER BY start") fun sessions(): Flow<List<SessionRow>>
    @Query("SELECT * FROM spans ORDER BY start") fun spans(): Flow<List<SpanRow>>
    @Query("SELECT * FROM annotations ORDER BY start") fun annotations(): Flow<List<AnnotationRow>>
    @Query("SELECT * FROM metadata") fun apps(): Flow<List<AppRow>>
    @Query("SELECT * FROM coverage ORDER BY start") fun coverage(): Flow<List<CoverageRow>>
    @Query("SELECT * FROM config") fun config(): Flow<List<ConfigRow>>
    @Query("SELECT e.time FROM events e WHERE e.kind = 'UNLOCK' AND (SELECT p.kind FROM events p WHERE (p.time < e.time OR (p.time = e.time AND p.sequence < e.sequence)) AND p.kind IN ('LOCK','UNLOCK','STARTUP','SHUTDOWN','OBSERVE_LOCKED','OBSERVE_UNLOCKED') ORDER BY p.time DESC, p.sequence DESC LIMIT 1) IN ('LOCK','OBSERVE_LOCKED')")
    fun unlocks(): Flow<List<Long>>
    @Query("SELECT e.time FROM events e WHERE e.kind = 'UNLOCK' AND (SELECT p.kind FROM events p WHERE (p.time < e.time OR (p.time = e.time AND p.sequence < e.sequence)) AND p.kind IN ('LOCK','UNLOCK','STARTUP','SHUTDOWN','OBSERVE_BOOT','OBSERVE_LOCKED','OBSERVE_UNLOCKED') ORDER BY p.time DESC, p.sequence DESC LIMIT 1) IN ('LOCK','OBSERVE_LOCKED')")
    suspend fun allUnlocks(): List<Long>
    @Query("SELECT * FROM sessions ORDER BY start") suspend fun allSessions(): List<SessionRow>
    @Query("SELECT * FROM spans ORDER BY start") suspend fun allSpans(): List<SpanRow>
    @Query("SELECT * FROM annotations ORDER BY start") suspend fun allAnnotations(): List<AnnotationRow>
    @Query("SELECT * FROM metadata") suspend fun allApps(): List<AppRow>
    @Query("SELECT * FROM config") suspend fun allConfig(): List<ConfigRow>
    @Query("SELECT * FROM coverage ORDER BY start") suspend fun allCoverage(): List<CoverageRow>
    @Query("SELECT * FROM journal ORDER BY date DESC, updatedAt DESC") suspend fun allJournal():List<JournalRow>
    @Query("SELECT * FROM journal WHERE id=:id") suspend fun journal(id:String):JournalRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putJournal(rows:List<JournalRow>)
    @Query("DELETE FROM journal WHERE id=:id") suspend fun deleteJournal(id:String)
    @Query("DELETE FROM journal") suspend fun deleteJournal()
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
val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE metadata ADD COLUMN automaticCategory TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE metadata ADD COLUMN categoryOverride TEXT DEFAULT NULL")
    }
}
val MIGRATION_2_3=object:androidx.room.migration.Migration(2,3) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(JOURNAL_TABLE_SQL)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_date ON journal(date)")
        val config=mutableMapOf<String,String>()
        db.query("SELECT `key`,value FROM config WHERE `key` LIKE 'review:%'").use {while(it.moveToNext()) config[it.getString(0)]=it.getString(1)}
        JournalRow.legacy(config).forEach{db.insertJournal(it)}
        config.keys.filter{Regex("^review:\\d{4}-\\d{2}-\\d{2}:(good|change|next)$").matches(it)}.forEach {db.execSQL("DELETE FROM config WHERE `key`=?",arrayOf(it))}
    }
}
val MIGRATION_3_4=object:androidx.room.migration.Migration(3,4) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(SUMMARY_TABLE_SQL)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_system_summaries_start ON system_summaries(start)")
    }
}
@Database(entities = [EventRow::class, SessionRow::class, SpanRow::class, AnnotationRow::class, AppRow::class, ConfigRow::class, CoverageRow::class,JournalRow::class,SystemUsageSummary::class], version = 4, exportSchema = false)
abstract class TickDatabase : RoomDatabase() { abstract fun dao(): TickDao }
