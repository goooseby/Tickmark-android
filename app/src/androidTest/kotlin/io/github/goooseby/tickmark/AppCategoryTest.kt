package io.github.goooseby.tickmark

import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.engine.analytics.AppCategory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AppCategoryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun packageRulesAndSystemMetadataClassifyPlatformsWithoutGuessingNames() {
        val classifier=AppClassifier(context)
        assertEquals(AppCategory.MEDIA,classifier.classify("tv.danmaku.bili",ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals(AppCategory.GAME,classifier.classify("unknown.game",ApplicationInfo.CATEGORY_GAME))
        assertEquals(AppCategory.UNKNOWN,classifier.classify("unknown.tv.danmaku.bili"))
        val app=AppRow("tv.danmaku.bili","哔哩哔哩",automaticCategory="MEDIA",categoryOverride="EDUCATION")
        assertEquals(AppCategory.EDUCATION,app.appCategory)
        assertEquals(AppCategory.MEDIA,app.copy(categoryOverride=null).appCategory)
        assertEquals(AppCategory.UNKNOWN,app.copy(categoryOverride="UNKNOWN").appCategory)
    }
    @Test fun backupPreservesAutomaticAndManualCategoryAndReadsOldBackups()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build()
        try {
            val repo=TickRepository(context,db,captureEnabled=false)
            val app=AppRow("video","Video",purpose="旧用途",automaticCategory="MEDIA",categoryOverride="OTHER")
            db.dao().putApps(listOf(app))
            val transfer=DataTransfer(repo)
            val json=transfer.backup()
            transfer.restore(transfer.parse(json))
            assertEquals(app,db.dao().allApps().single())
            val legacy=JSONObject(json)
            legacy.getJSONArray("apps").getJSONObject(0).apply {remove("automaticCategory");remove("categoryOverride")}
            val restored=transfer.parse(legacy.toString()).apps.single()
            assertEquals("旧用途",restored.purpose)
            assertNull(restored.categoryOverride)
            assertEquals(AppCategory.UNKNOWN,restored.appCategory)
            val bad=JSONObject(json)
            bad.getJSONArray("apps").getJSONObject(0).put("categoryOverride","invalid")
            try {transfer.parse(bad.toString());fail("invalid category")} catch(_:IllegalArgumentException) {}
        } finally {db.close()}
    }
    @Test fun migrationAddsCategoryFieldsWithoutRewritingOldMetadata() {
        val name="category-migration-${UUID.randomUUID()}.db"
        fun helper(version:Int)=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object:SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db:SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE metadata (pkg TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, hidden INTEGER NOT NULL, excluded INTEGER NOT NULL, purpose TEXT NOT NULL)")
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) {MIGRATION_1_2.migrate(db)}
            }).build())
        try {
            helper(1).use {it.writableDatabase.execSQL("INSERT INTO metadata VALUES ('video','Video',1,0,'旧用途')")}
            helper(2).use {db->db.readableDatabase.query("SELECT * FROM metadata").use {
                assertTrue(it.moveToFirst())
                assertEquals("旧用途",it.getString(it.getColumnIndexOrThrow("purpose")))
                assertEquals(1,it.getInt(it.getColumnIndexOrThrow("hidden")))
                assertEquals("",it.getString(it.getColumnIndexOrThrow("automaticCategory")))
                assertTrue(it.isNull(it.getColumnIndexOrThrow("categoryOverride")))
            }}
        } finally {context.deleteDatabase(name)}
    }
    @Test fun pausedCaptureStillClassifiesAndNeverOverwritesUserChoice()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build()
        try {
            val repo=TickRepository(context,db)
            db.dao().putConfig(listOf(ConfigRow("paused","true")))
            db.dao().putApps(listOf(AppRow("tv.danmaku.bili","哔哩哔哩",categoryOverride="OTHER")))
            repo.sync()
            val classified=db.dao().allApps().single()
            assertEquals("MEDIA",classified.automaticCategory)
            assertEquals(AppCategory.OTHER,classified.appCategory)
            repo.updateApp(classified.copy(categoryOverride=null))
            repo.sync()
            assertEquals(AppCategory.MEDIA,db.dao().allApps().single().appCategory)
            repo.updateApp(db.dao().allApps().single().copy(categoryOverride="UNKNOWN"))
            repo.sync()
            assertEquals(AppCategory.UNKNOWN,db.dao().allApps().single().appCategory)
        } finally {db.close()}
    }
}
