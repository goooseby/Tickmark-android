package io.github.goooseby.tickmark

import androidx.room.Room
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.widget.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class JournalTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:TickDatabase
    private lateinit var repo:TickRepository
    @Before fun create(){db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build();repo=TickRepository(context,db,captureEnabled=false)}
    @After fun close(){db.close()}

    @Test fun draftsCompleteAndChangedActionsKeepCorrectState()=runBlocking {
        val initial=JournalRow.daily(LocalDate.now())
        assertNull(repo.saveJournal(initial))
        val draft=repo.saveJournal(initial.copy(positive="去散步",action="睡前读书"))!!
        assertTrue(draft.draft)
        val complete=repo.saveJournal(draft,true)!!
        assertFalse(complete.draft)
        repo.toggleJournalAction(initial.id)
        assertTrue(repo.dao.journal(initial.id)!!.actionDone)
        val edit=repo.saveJournal(repo.dao.journal(initial.id)!!.copy(action="明天去图书馆"))!!
        assertFalse(edit.actionDone);assertFalse(edit.draft)
        assertEquals(complete.createdAt,edit.createdAt)
        assertEquals(edit,repo.snapshot().journal.single())
        assertTrue(edit.shareText.contains("去散步"));assertTrue(edit.shareText.contains("明天去图书馆"))
    }
    @Test fun deletedEditorCannotResurrectRecordAndUndoAllowsReopening()=runBlocking {
        val initial=JournalRow.daily(LocalDate.now()).copy(positive="保留记录")
        val session=repo.journalForEditing(initial)
        repo.saveJournal(initial,expectedRevision=session.second)
        val removed=repo.deleteJournal(initial.id)!!
        try {repo.saveJournal(initial.copy(positive="迟到的自动保存"),expectedRevision=session.second);fail("stale editor")} catch(_:IllegalArgumentException) {}
        assertNull(repo.dao.journal(initial.id))
        repo.restoreJournal(removed)
        val reopened=repo.journalForEditing(initial)
        assertEquals("保留记录",reopened.first.positive)
        assertNotEquals(session.second,reopened.second)
        repo.deleteJournal(initial.id)
        val fresh=repo.journalForEditing(JournalRow.daily(LocalDate.now()))
        repo.saveJournal(fresh.first.copy(positive="重新写一篇"),true,fresh.second)
        assertEquals("重新写一篇",repo.dao.journal(initial.id)!!.positive)
    }
    @Test fun backupIncludesDraftsPinsAndActionStatesAndReadsOldReviews()=runBlocking {
        val note=JournalRow.note(LocalDate.now()).copy(title="临时想法",body="正文",pinned=true)
        repo.saveJournal(note)
        repo.saveJournal(JournalRow.daily(LocalDate.now().minusDays(1)).copy(action="读十页"),true)
        repo.toggleJournalAction("day:${LocalDate.now().minusDays(1)}")
        val transfer=DataTransfer(repo)
        val backup=transfer.backup()
        val rows=repo.dao.allJournal()
        repo.clear(true);transfer.restore(transfer.parse(backup))
        assertEquals(rows,repo.dao.allJournal())
        val old=JSONObject(backup).put("schemaVersion",1)
        old.remove("journal")
        old.getJSONArray("config").put(JSONObject().put("key","review:2026-01-02:good").put("value","旧复盘"))
        old.getJSONArray("config").put(JSONObject().put("key","review:2026-01-02:next").put("value","读书"))
        transfer.restore(transfer.parse(old.toString()))
        assertEquals("旧复盘",repo.dao.journal("day:2026-01-02")!!.positive)
        assertFalse(repo.dao.journal("day:2026-01-02")!!.draft)
        assertFalse(repo.dao.allConfig().any{it.key.startsWith("review:")})
    }
    @Test fun widgetReadsSameActionAndClearRemovesRecords()=runBlocking {
        val yesterday=LocalDate.now().minusDays(1)
        repo.saveJournal(JournalRow.daily(yesterday).copy(action="先读十页"),true)
        assertEquals("先读十页",WidgetSnapshot.create(repo.snapshot(),true).action)
        repo.toggleJournalAction("day:$yesterday")
        assertEquals("已完成 · 先读十页",WidgetSnapshot.create(repo.snapshot(),true).action)
        val session=repo.journalForEditing(JournalRow.daily(yesterday))
        repo.clear(true)
        assertTrue(repo.dao.allJournal().isEmpty())
        try {repo.saveJournal(session.first,expectedRevision=session.second);fail("cleared editor")} catch(_:IllegalArgumentException) {}
    }
    @Test fun migrationPreservesLegacyTextAndCreatesIndexedTable() {
        val name="journal-migration-${UUID.randomUUID()}.db"
        fun helper(version:Int)=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db:SupportSQLiteDatabase){db.execSQL("CREATE TABLE config (`key` TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")}
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){MIGRATION_2_3.migrate(db)}
        }).build())
        try {
            helper(2).use {h->h.writableDatabase.execSQL("INSERT INTO config VALUES ('review:2026-01-02:good','旧记录')");h.writableDatabase.execSQL("INSERT INTO config VALUES ('review:2026-01-02:next','读书')")}
            helper(3).use {h->h.readableDatabase.query("SELECT positive,action,draft,updatedAt FROM journal").use{assertTrue(it.moveToFirst());assertEquals("旧记录",it.getString(0));assertEquals("读书",it.getString(1));assertEquals(0,it.getInt(2));assertEquals(0L,it.getLong(3))}}
        } finally {context.deleteDatabase(name)}
    }
}
