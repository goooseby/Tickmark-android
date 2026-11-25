package io.github.goooseby.tickmark.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.sync.*
import io.github.goooseby.tickmark.engine.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncStoreTest {
    private lateinit var db: TickDatabase
    private lateinit var store: SyncStore
    private val day = LocalDate.of(2026, 11, 24)
    private val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before fun create() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickDatabase::class.java).build()
        store = SyncStore(db)
    }

    @After fun close() { db.close() }

    @Test fun immutableRevisionIsReplacedOnlyAfterLocalContentChanges() = runBlocking {
        val account = account("a1")
        store.activate(account)
        val payload = payload()
        assertTrue(store.stage(account, day, payload))
        assertFalse(store.stage(account, day, payload))
        assertEquals(1, store.pending(account).single().revision)
        assertTrue(store.stage(account, day, payload.copy(appIntervals = emptyList())))
        assertEquals(2, store.pending(account).single().revision)
    }

    @Test fun lostAcknowledgementDoesNotRemoveNewerRevision() = runBlocking {
        val account = account("a1")
        store.activate(account)
        store.stage(account, day, payload())
        val first = store.pending(account).single()
        store.stage(account, day, payload().copy(appIntervals = emptyList()))
        val second = store.pending(account).single()
        store.acknowledge(first)
        assertEquals(second.revision, store.pending(account).single().revision)
        store.acknowledge(second)
        assertTrue(store.pending(account).isEmpty())
    }

    @Test fun privacyRemovalCreatesDeletionCapableEmptyRevision() = runBlocking {
        val account = account("a1")
        store.activate(account)
        store.stage(account, day, payload())
        store.stage(account, day, payload().copy(deviceIntervals = emptyList(), appIntervals = emptyList()))
        val pending = store.pending(account).single()
        assertEquals(2, pending.revision)
        assertNull(pending.payloadJson)
        assertEquals(2, pending.revision)
    }

    @Test fun pullAppliesMirrorAndCursorAndRejectsOldSequence() = runBlocking {
        val account = account("a1")
        store.activate(account)
        val change = SyncChange(1, "remote", day, 1, payload(), false)
        val result = SyncPullResult("e1", listOf(change), emptyList(), "1", false)
        store.applyPull(account, result)
        val updated = store.currentAccount()!!
        assertEquals("1", updated.cursor)
        assertEquals(1, updated.lastSequence)
        assertEquals(1, store.mirror(account.accountId).size)
        try {
            store.applyPull(updated, result)
            fail("old sequence accepted")
        } catch (_: IllegalArgumentException) {
        }
        val deletion = SyncPullResult("e1", listOf(SyncChange(2, "remote", day, 2, null, true)), emptyList(), "2", false)
        store.applyPull(updated, deletion)
        assertTrue(store.mirror(account.accountId).single().deleted)
        try {
            store.applyPull(store.currentAccount()!!, result)
            fail("deleted shard resurrected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun snapshotStagesPagesAndSwitchesMirrorAtomically() = runBlocking {
        val account = account("a1")
        store.activate(account)
        store.applyPull(account, SyncPullResult("e1", listOf(SyncChange(1, "old", day, 1, payload(), false)), emptyList(), "1", false))
        store.beginSnapshot(account)
        val first = SyncSnapshotPage("e2", listOf(SyncChange(1, "new", day, 1, payload(), false)), emptyList(), "page-2", true)
        store.applySnapshotPage(account, first)
        assertEquals(listOf("old"), store.mirror(account.accountId).map { it.deviceId })
        val current = store.currentAccount()!!
        assertEquals("rebuilding", current.status)
        assertEquals("page-2", current.snapshotCursor)
        store.applySnapshotPage(account, SyncSnapshotPage("e2", emptyList(), emptyList(), "2", false))
        val committed = store.currentAccount()!!
        assertEquals("active", committed.status)
        assertEquals("e2", committed.epoch)
        assertEquals("2", committed.cursor)
        assertEquals(listOf("new"), store.mirror(account.accountId).map { it.deviceId })
    }

    @Test fun switchingAccountClearsRemoteStateButKeepsLocalHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = TickRepository(context, db, captureEnabled = false)
        repo.dao.putSessions(listOf(SessionRow("local", "pkg", start, start + 100, false, false)))
        val first = account("a1")
        store.activate(first)
        store.stage(first, day, payload())
        store.applyPull(first, SyncPullResult("e1", listOf(SyncChange(1, "remote", day, 1, payload(), false)), emptyList(), "1", false))
        val second = account("a2")
        store.activate(second)
        assertEquals("a2", store.currentAccount()!!.accountId)
        assertTrue(store.pending(second).isEmpty())
        assertTrue(store.mirror("a2").isEmpty())
        assertEquals(1, repo.dao.allSessions().size)
    }

    @Test fun snapshotRejectsEpochChangeAcrossPages() = runBlocking {
        val account = account("a1")
        store.activate(account)
        store.beginSnapshot(account)
        store.applySnapshotPage(account, SyncSnapshotPage("e2", emptyList(), emptyList(), "page-2", true))
        try {
            store.applySnapshotPage(account, SyncSnapshotPage("e1", emptyList(), emptyList(), "3", false))
            fail("mixed snapshot epochs accepted")
        } catch (error: SyncException) {
            assertEquals(SyncErrorCode.EPOCH_MISMATCH, error.code)
        }
        assertEquals("e2", store.currentAccount()!!.snapshotEpoch)
        assertTrue(store.mirror(account.accountId).isEmpty())
    }

    @Test fun logoutKeepsRevisionWatermarkWithoutUsagePayload() = runBlocking {
        val account = account("a1")
        store.activate(account)
        store.stage(account, day, payload())
        store.acknowledge(store.pending(account).single())
        store.clear(account.accountId)
        assertNull(store.currentAccount())
        assertEquals(1, db.syncDao().revision(account.accountId, account.deviceId, day.toString())!!.revision)
        store.activate(account)
        store.stage(account, day, payload())
        assertEquals(2, store.pending(account).single().revision)
    }

    @Test fun remoteMirrorDoesNotEnterLocalAndroidReports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = TickRepository(context, db, captureEnabled = false)
        val account = account("a1")
        store.activate(account)
        repo.dao.putSpans(listOf(SpanRow(start, start + 100, "IN_USE")))
        val remote = payload().copy(deviceIntervals = listOf(SyncDeviceInterval(start + 50, start + 150, SyncDeviceState.ACTIVE)))
        val own = payload().copy(deviceIntervals = listOf(SyncDeviceInterval(start + 200, start + 300, SyncDeviceState.ACTIVE)))
        store.applyPull(account, SyncPullResult("e1", listOf(
            SyncChange(1, "remote", day, 1, remote, false),
            SyncChange(2, account.deviceId, day, 1, own, false),
        ), listOf(SyncRemoteDevice("remote", "Tablet", null, false)), "2", false))
        val records = repo.snapshot()
        assertEquals(1, records.deviceSpans.size)
        assertEquals(start, records.deviceSpans.single().start)
        assertEquals(start + 100, records.deviceSpans.single().end)
    }

    @Test fun payloadCodecRejectsCrossDayAndOverlappingDeviceIntervals() = runBlocking {
        val account = account("a1")
        store.activate(account)
        val valid = payload()
        assertEquals(valid, SyncPayloadCodec.decode(SyncPayloadCodec.encode(valid), day))
        val crossDay = valid.copy(deviceIntervals = listOf(SyncDeviceInterval(start - 1, start + 1, SyncDeviceState.ACTIVE)))
        try {
            SyncPayloadCodec.decode(SyncPayloadCodec.encode(crossDay), day)
            fail("cross-day interval accepted")
        } catch (_: IllegalArgumentException) {
        }
        val overlap = valid.copy(deviceIntervals = listOf(
            SyncDeviceInterval(start, start + 10, SyncDeviceState.ACTIVE),
            SyncDeviceInterval(start + 5, start + 15, SyncDeviceState.ACTIVE),
        ))
        try {
            SyncPayloadCodec.decode(SyncPayloadCodec.encode(overlap), day)
            fail("overlapping intervals accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun account(id: String) = SyncAccountRow(
        accountId = id,
        deviceId = "device-${id}",
        deviceName = "Phone",
        epoch = "e1",
        fromUtcDay = day.toString(),
        throughUtcDay = null,
        updatedAt = 1,
    )

    private fun payload() = SyncDayPayload(
        utcDate = day,
        zoneId = "Asia/Shanghai",
        deviceIntervals = listOf(SyncDeviceInterval(start, start + 100, SyncDeviceState.ACTIVE)),
        appIntervals = listOf(SyncAppInterval(start, start + 100, "pkg", SyncSessionQuality.OBSERVED, "App")),
    )
}

@RunWith(AndroidJUnit4::class)
class SyncCredentialTest {
    @Test fun installIdentityAndEncryptedRefreshTokenSurviveReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = SyncIdentityStore(context)
        val tokens = SyncTokenStore(context)
        val accountId = "credential-${UUID.randomUUID()}"
        val installId = identity.installId()
        assertEquals(installId, identity.installId())
        val refresh = "refresh-${UUID.randomUUID()}"
        tokens.saveRefresh(accountId, refresh, "request-1")
        assertEquals("request-1", tokens.refreshRequestId(accountId))
        assertEquals(refresh, tokens.refreshToken(accountId))
        val stored = context.getSharedPreferences("tickmark_sync_secure", 0).getString("refresh:$accountId", null)
        assertNotNull(stored)
        assertFalse(stored!!.contains(refresh))
        tokens.saveAccess(accountId, "access", System.currentTimeMillis() + 60_000)
        assertEquals("access", tokens.accessToken(accountId))
        tokens.clear(accountId)
        assertNull(tokens.refreshToken(accountId))
        assertNull(tokens.accessToken(accountId))
    }
}
