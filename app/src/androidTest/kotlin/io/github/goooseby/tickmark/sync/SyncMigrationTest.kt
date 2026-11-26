package io.github.goooseby.tickmark.sync

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.MIGRATION_4_5
import io.github.goooseby.tickmark.data.MIGRATION_5_6
import io.github.goooseby.tickmark.data.TickDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncMigrationTest {
    @Test fun migrationCreatesAccountQueueMirrorAndSnapshotTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        MIGRATION_4_5.migrate(db)
        db.version = 5
        val accountColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(sync_account)").use { cursor ->
            while (cursor.moveToNext()) accountColumns += cursor.getString(1)
        }
        assertTrue(accountColumns.containsAll(listOf("accountId", "deviceId", "epoch", "cursor", "snapshotId", "snapshotEpoch", "snapshotCursor", "lastSequence")))
        listOf("sync_revisions", "sync_outbox", "sync_mirror", "sync_snapshot_stage", "sync_devices").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { cursor ->
                assertTrue(table, cursor.moveToFirst())
            }
        }
        assertEquals(5, db.version)
        db.execSQL(
            "INSERT INTO sync_account (accountId,deviceId,deviceName,epoch,fromUtcDay,status,lastSequence,lastSuccessAt,updatedAt) " +
                "VALUES ('account','phone','Phone','epoch','2026-11-25','active',0,0,1)",
        )
        MIGRATION_5_6.migrate(db)
        db.query("SELECT fromUtcDay, fromInstantMs FROM sync_account WHERE accountId='account'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("2026-11-25", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        helper.close()
    }

    @Test fun roomOpensRealVersionFourDatabaseAfterMigration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath("sync-migration-test.db")
        listOf(file, java.io.File(file.path + "-wal"), java.io.File(file.path + "-shm")).forEach { it.delete() }
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        val tables = listOf(
            "CREATE TABLE events (id TEXT NOT NULL, time INTEGER NOT NULL, kind TEXT NOT NULL, pkg TEXT NOT NULL, activity TEXT NOT NULL, instance INTEGER NOT NULL, sequence INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE INDEX index_events_time ON events(time)",
            "CREATE TABLE sessions (id TEXT NOT NULL, pkg TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, inferred INTEGER NOT NULL, ongoing INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE INDEX index_sessions_start ON sessions(start)",
            "CREATE INDEX index_sessions_end ON sessions(end)",
            "CREATE TABLE spans (start INTEGER NOT NULL, end INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(start))",
            "CREATE INDEX index_spans_start ON spans(start)",
            "CREATE TABLE annotations (id TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, purpose TEXT NOT NULL, note TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE metadata (pkg TEXT NOT NULL, name TEXT NOT NULL, hidden INTEGER NOT NULL, excluded INTEGER NOT NULL, purpose TEXT NOT NULL, automaticCategory TEXT NOT NULL DEFAULT '', categoryOverride TEXT DEFAULT NULL, PRIMARY KEY(pkg))",
            "CREATE TABLE config (key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(key))",
            "CREATE TABLE coverage (start INTEGER NOT NULL, end INTEGER NOT NULL, eventCount INTEGER NOT NULL, PRIMARY KEY(start))",
            "CREATE TABLE journal (id TEXT NOT NULL, date TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, positive TEXT NOT NULL, adjustment TEXT NOT NULL, action TEXT NOT NULL, mood TEXT NOT NULL, actionDone INTEGER NOT NULL, draft INTEGER NOT NULL, pinned INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE INDEX index_journal_date ON journal(date)",
            "CREATE TABLE system_summaries (id TEXT NOT NULL, pkg TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, foregroundMs INTEGER NOT NULL, interval TEXT NOT NULL, capturedAt INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE INDEX index_system_summaries_start ON system_summaries(start)",
        )
        tables.forEach(sqlite::execSQL)
        sqlite.execSQL("INSERT INTO sessions VALUES ('s','pkg',1,2,0,0)")
        sqlite.version = 4
        sqlite.close()
        val room = Room.databaseBuilder(context, TickDatabase::class.java, file.name).addMigrations(MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            room.openHelper.writableDatabase
            assertEquals(1, room.dao().allSessions().size)
            assertEquals(6, room.openHelper.readableDatabase.version)
        } finally {
            room.close()
            listOf(file, java.io.File(file.path + "-wal"), java.io.File(file.path + "-shm")).forEach { it.delete() }
        }
    }
}
