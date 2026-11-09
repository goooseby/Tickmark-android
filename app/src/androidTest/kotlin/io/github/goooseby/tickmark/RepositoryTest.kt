package io.github.goooseby.tickmark

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.goooseby.tickmark.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: TickDatabase
    private lateinit var repo: TickRepository
    @Before fun create() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,TickDatabase::class.java).build()
        repo=TickRepository(context,db)
    }
    @After fun close() { db.close() }
    @Test fun purposeReplacementSplitsAndPreservesNotes() = runBlocking {
        repo.annotate(AnnotationRow("old",100,400,"学习","原备注"),false)
        assertFalse(repo.annotate(AnnotationRow("new",200,300,"娱乐","新备注"),false))
        assertEquals(1,repo.dao.allAnnotations().size)
        assertTrue(repo.annotate(AnnotationRow("new",200,300,"娱乐","新备注"),true))
        val purposes=repo.dao.allAnnotations().filter { it.purpose.isNotBlank() }.sortedBy { it.start }
        assertEquals(listOf(100L to 200L,200L to 300L,300L to 400L),purposes.map { it.start to it.end })
        assertTrue(repo.dao.allAnnotations().any { it.purpose.isEmpty() && it.note=="原备注" && it.start==100L && it.end==400L })
        assertEquals(1,repo.dao.allAnnotations().count { it.note=="原备注" })
    }
    @Test fun backupRoundTripAndInvalidBackupDoesNotChangeDatabase() = runBlocking {
        repo.dao.putSessions(listOf(SessionRow("s","pkg",100,200,false,false)))
        repo.annotate(AnnotationRow("a",100,200,"学习","笔记"),false)
        repo.dao.putEvents(listOf(EventRow("e",100,"RESUME","pkg","A",1,0)))
        repo.dao.putSpans(listOf(SpanRow(100,200,"IN_USE")))
        val transfer=DataTransfer(repo)
        val backup=transfer.backup()
        val parsed=transfer.parse(backup)
        repo.clear(true)
        transfer.restore(parsed)
        assertEquals("笔记",repo.dao.allAnnotations().single().note)
        assertEquals(100L,repo.dao.allSessions().single().start)
        assertEquals("true",repo.dao.allConfig().find { it.key=="paused" }?.value)
        try { transfer.parse(backup.replace("\"schemaVersion\": 3","\"schemaVersion\": 99")); fail("unsupported version") } catch (_:IllegalArgumentException) { }
        assertEquals(1,repo.dao.allSessions().size)
    }
    @Test fun privacyRuleRemovesIdentityFromRawAndDerivedRecords() = runBlocking {
        repo.dao.putEvents(listOf(EventRow("a",100,"RESUME","secret","SecretActivity",5,0), EventRow("b",200,"PAUSE","secret","SecretActivity",5,1)))
        repo.updateApp(AppRow("secret","Secret",excluded=true))
        assertTrue(repo.dao.events().none { it.pkg=="secret" || it.activity.contains("Secret") })
        assertTrue(repo.dao.allSessions().none { it.pkg=="secret" })
    }
    @Test fun csvProtectsFormulaNamesAndKeepsMilliseconds() = runBlocking {
        repo.dao.putApps(listOf(AppRow("pkg","=SUM(A1)")))
        repo.dao.putSessions(listOf(SessionRow("s","pkg",100,1234,true,false)))
        val text=DataTransfer(repo).csv()
        assertTrue(text.contains("'=SUM(A1)")); assertTrue(text.contains("\"1134\"")); assertTrue(text.contains("inferred"))
    }
    @Test fun overlapReplayKeepsObservedStateAndDoesNotInventUnlocks() = runBlocking {
        repo.dao.putEvents(listOf(EventRow("snapshot",100,"OBSERVE_UNLOCKED","","",0,1),EventRow("source",110,"RESUME","pkg","A",0,0)))
        repo.dao.deleteSystemEvents(0,200)
        assertEquals(listOf("OBSERVE_UNLOCKED"),repo.dao.events().map{it.kind})
        val backup=DataTransfer(repo).backup()
        assertEquals("OBSERVE_UNLOCKED",DataTransfer(repo).parse(backup).events.single().kind)
    }
}
