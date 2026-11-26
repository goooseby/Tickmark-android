package io.github.goooseby.tickmark.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.data.sync.*
import io.github.goooseby.tickmark.engine.sync.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.net.URL

@RunWith(AndroidJUnit4::class)
class SyncCoordinatorTest {
    private lateinit var db: TickDatabase
    private lateinit var repository: TickRepository
    private lateinit var store: SyncStore
    private lateinit var tokens: SyncTokenStore
    private lateinit var scope: CoroutineScope
    private lateinit var transport: FakeTransport
    private lateinit var authenticator: FakeAuthenticator
    private lateinit var coordinator: SyncCoordinator
    private val day = LocalDate.now(ZoneOffset.UTC)
    private val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before fun create() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickDatabase::class.java).build()
        repository = TickRepository(context, db, captureEnabled = false)
        store = SyncStore(db)
        tokens = SyncTokenStore(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        transport = FakeTransport()
        authenticator = FakeAuthenticator()
        coordinator = SyncCoordinator(context, scope, repository, store, tokens, SyncIdentityStore(context), authenticator, transport, true)
        runBlocking {
            repository.dao.putApps(listOf(AppRow("pkg", "App")))
            repository.dao.putSessions(listOf(SessionRow("session", "pkg", start, start + 1_000, false, false)))
            repository.dao.putSpans(listOf(SpanRow(start, start + 1_000, "IN_USE")))
            repository.dao.putCoverage(listOf(CoverageRow(start, start + 1_000, 1)))
        }
    }

    @After fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        tokens.clear("account-1")
        db.close()
    }

    @Test fun responseLossRetriesSameRevisionAndPayloadWithoutDuplication() = runBlocking {
        activate()
        transport.loseNextResponse = true
        assertEquals(SyncRunResult.RETRY, coordinator.synchronize())
        assertEquals(1, store.pending(store.currentAccount()!!).single().attempts)
        assertEquals(SyncRunResult.SUCCESS, coordinator.synchronize())
        assertEquals(2, transport.uploads.size)
        assertEquals(transport.uploads[0].shards, transport.uploads[1].shards)
        assertEquals(1, transport.uploads[1].shards.single().revision)
        assertTrue(store.pending(store.currentAccount()!!).isEmpty())
        assertTrue(store.currentAccount()!!.lastSuccessAt > 0)
    }

    @Test fun publishesOwnIntervalsToRunningServer() = runBlocking {
        val registry = InstrumentationRegistry.getInstrumentation()
        val endpoint = InstrumentationRegistry.getArguments().getString("tickmarkFixture")
        org.junit.Assume.assumeTrue(endpoint in setOf("http://10.0.2.2:17812", "http://127.0.0.1:17812"))
        val client = SyncHttpClient(endpoint!!, allowLocalHttp = true)
        val real = SyncCoordinator(registry.targetContext, scope, repository, store, tokens,
            SyncIdentityStore(registry.targetContext), client, client, true)
        val challenge = real.requestCode("sync-test@example.com")
        val code = withContext(Dispatchers.IO) { URL("$endpoint/__fixture/code").readText().trim() }
        real.verifyCode(challenge.challengeId, code, "同步验收手机", Instant.ofEpochMilli(start), historyConfirmed = true)
        val active = store.currentAccount()!!
        try {
            assertEquals(SyncRunResult.SUCCESS, real.synchronize())
            assertTrue(store.pending(active).isEmpty())
            assertTrue(store.currentAccount()!!.lastSuccessAt > 0)
        } finally {
            tokens.clear(active.accountId)
        }
    }

    @Test fun sameVersionConflictStopsAutomaticRetriesAndKeepsEvidence() = runBlocking {
        activate()
        transport.conflict = true
        assertEquals(SyncRunResult.CONFLICT, coordinator.synchronize())
        val pushes = transport.uploads.size
        assertEquals(SyncRunResult.CONFLICT, coordinator.synchronize())
        assertEquals(pushes, transport.uploads.size)
        val outbox = db.syncDao().outbox("account-1", "device-1", day.toString())!!
        assertEquals("conflict", outbox.status)
        assertNotNull(outbox.payloadJson)
    }

    @Test fun clearingLocalHistoryPublishesDeletionTombstone() = runBlocking {
        activate()
        assertEquals(SyncRunResult.SUCCESS, coordinator.synchronize())
        repository.clear(true)
        assertEquals(SyncRunResult.SUCCESS, coordinator.synchronize())
        val shard = transport.uploads.last().shards.single()
        assertEquals(2, shard.revision)
        assertNull(shard.payload)
    }

    @Test fun persistedStartClipsTheFirstUtcShardBeforeUploading() = runBlocking {
        activate()
        val account = store.currentAccount()!!
        val cutoff = start + 500
        store.activate(account.copy(fromInstantMs = cutoff))

        assertEquals(SyncRunResult.SUCCESS, coordinator.synchronize())
        val payload = transport.uploads.single().shards.single().payload!!
        assertEquals(cutoff, payload.appIntervals.single().startMs)
        assertEquals(cutoff, payload.deviceIntervals.single().startMs)
        assertEquals(start + 1_000, payload.appIntervals.single().endMs)
    }

    @Test fun expiredAccessTokenRefreshesOnceAndContinues() = runBlocking {
        activate()
        transport.authFailureOnce = true
        assertEquals(SyncRunResult.SUCCESS, coordinator.synchronize())
        assertEquals(1, authenticator.refreshes)
        assertEquals("refresh-2", tokens.refreshToken("account-1"))
        assertNotEquals("request-1", tokens.refreshRequestId("account-1"))
        assertEquals("access-2", tokens.accessToken("account-1"))
    }

    @Test fun previousBuildRebuildingStateMigratesToUploadOnly() = runBlocking {
        val account = SyncAccountRow(
            accountId = "account-1",
            deviceId = "device-1",
            deviceName = "Phone",
            epoch = "e1",
            fromUtcDay = day.toString(),
            throughUtcDay = null,
            status = "rebuilding",
            updatedAt = 1,
        )
        store.activate(account)
        tokens.saveRefresh(account.accountId, "refresh-1", "request-1")
        tokens.saveAccess(account.accountId, "access-1", System.currentTimeMillis() + 60_000)
        assertEquals(SyncRunResult.SUCCESS, coordinator.synchronize())
        assertEquals(1, transport.epochChecks)
        assertEquals("active", store.currentAccount()!!.status)
        assertEquals("e2", transport.uploads.single().epoch)
    }

    @Test fun epochMismatchRestagesOwnUploads() = runBlocking {
        activate()
        transport.epochMismatchOnce = true
        assertEquals(SyncRunResult.SUCCESS, coordinator.synchronize())
        val account = store.currentAccount()!!
        assertEquals("e2", account.epoch)
        assertNull(account.cursor)
        assertEquals(1, transport.epochChecks)
        assertEquals("e2", transport.uploads.last().epoch)
    }

    private fun activate() {
        val account = SyncAccountRow(
            accountId = "account-1",
            deviceId = "device-1",
            deviceName = "Phone",
            epoch = "e1",
            fromUtcDay = day.toString(),
            throughUtcDay = null,
            cursor = "0",
            updatedAt = 1,
        )
        runBlocking { store.activate(account) }
        tokens.saveRefresh(account.accountId, "refresh-1", "request-1")
        tokens.saveAccess(account.accountId, "access-1", System.currentTimeMillis() + 60_000)
    }

    private class FakeTransport : SyncTransport {
        val uploads = mutableListOf<SyncUpload>()
        var epochChecks = 0
        var loseNextResponse = false
        var conflict = false
        var authFailureOnce = false
        var epochMismatchOnce = false

        override suspend fun push(accessToken: String, upload: SyncUpload): SyncPushResult {
            uploads += upload
            if (epochMismatchOnce) {
                epochMismatchOnce = false
                throw SyncException(SyncErrorCode.EPOCH_MISMATCH)
            }
            if (authFailureOnce) {
                authFailureOnce = false
                throw SyncException(SyncErrorCode.AUTH_REQUIRED)
            }
            if (conflict) return SyncPushResult.Conflict(upload.shards.first().dayUtc, 99)
            if (loseNextResponse) {
                loseNextResponse = false
                throw SyncException(SyncErrorCode.NETWORK)
            }
            return SyncPushResult.Accepted
        }

        override suspend fun serverEpoch(accessToken: String): String {
            epochChecks++
            return "e2"
        }

        override suspend fun pull(accessToken: String, epoch: String, after: String?, limit: Int, waitSeconds: Int): SyncPullResult =
            error("Android does not pull account reports")

        override suspend fun snapshot(accessToken: String, cursor: String?): SyncSnapshotPage =
            error("Android does not pull account snapshots")
    }

    private class FakeAuthenticator : SyncAuthenticator {
        var refreshes = 0
        override suspend fun challenge(email: String, deviceId: String, deviceName: String) = error("unused")
        override suspend fun verify(challengeId: String, code: String, deviceId: String, deviceName: String) = error("unused")
        override suspend fun refresh(refreshToken: String, requestId: String): SyncTokenPair {
            refreshes++
            return SyncTokenPair("access-2", "refresh-2", System.currentTimeMillis() + 60_000)
        }
        override suspend fun revoke(accessToken: String, deviceId: String) = Unit
    }
}
