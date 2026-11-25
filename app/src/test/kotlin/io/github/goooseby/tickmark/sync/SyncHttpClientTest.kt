package io.github.goooseby.tickmark.sync

import io.github.goooseby.tickmark.data.sync.SyncException
import io.github.goooseby.tickmark.data.sync.SyncHttpClient
import io.github.goooseby.tickmark.engine.sync.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class SyncHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SyncHttpClient
    private val day = LocalDate.of(2026, 11, 24)
    private val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
        client = SyncHttpClient(server.url("/").toString().trimEnd('/'), allowLocalHttp = true)
    }

    @After fun stopServer() { server.shutdown() }

    @Test fun pushUsesSharedShardContractAndDeletionPayload() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val payload = SyncDayPayload(
            day,
            "Asia/Shanghai",
            deviceIntervals = listOf(SyncDeviceInterval(start, start + 100, SyncDeviceState.ACTIVE)),
            appIntervals = listOf(SyncAppInterval(start, start + 100, "pkg", SyncSessionQuality.OBSERVED, "App")),
        )
        val result = client.push("token", SyncUpload("device-1", "e1", listOf(
            SyncShard(day, 7, 1, payload),
            SyncShard(day.plusDays(1), 3, 1, null),
        )))
        assertEquals(SyncPushResult.Accepted, result)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals("/v1/devices/device-1/shards", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        val json = JSONObject(request.body.readUtf8())
        assertEquals("e1", json.getString("epoch"))
        val shards = json.getJSONArray("shards")
        assertEquals(2, shards.length())
        assertEquals("2026-11-24", shards.getJSONObject(0).getString("dayUtc"))
        assertEquals(7, shards.getJSONObject(0).getInt("revision"))
        assertEquals(1, shards.getJSONObject(0).getInt("payloadVersion"))
        assertEquals("android.interactive-unlocked.v1", shards.getJSONObject(0).getJSONObject("payload").getString("captureProfile"))
        assertTrue(shards.getJSONObject(1).isNull("payload"))
    }

    @Test fun conflictResponseCarriesMachineReadableDetails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"REVISION_CONFLICT","dayUtc":"2026-11-24","serverRevision":9}"""))
        val result = client.push("token", SyncUpload("device-1", "e1", listOf(SyncShard(day, 7, 1, null))))
        assertEquals(SyncPushResult.Conflict(day, 9), result)
    }

    @Test fun pullParsesOrderedChangesAndCursor() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"epoch":"e1","changes":[{"seq":5,"deviceId":"remote","dayUtc":"2026-11-24","revision":2,"payloadVersion":1,"payload":{"zoneId":"UTC","captureProfile":"windows.v1","deviceIntervals":[],"appIntervals":[]}}],"devices":[{"id":"remote","name":"Desktop","revoked":false}],"nextAfter":"5","hasMore":false}"""))
        val result = client.pull("token", "e1", "4", 200, 0)
        assertEquals("5", result.nextAfter)
        assertEquals(5, result.changes.single().sequence)
        assertEquals("remote", result.devices.single().deviceId)
        assertEquals("Desktop", result.devices.single().name)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.path!!.contains("after=4"))
    }

    @Test fun snapshotAllowsMissingSequenceAndParsesDeletion() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"epoch":"e2","shards":[{"deviceId":"remote","dayUtc":"2026-11-24","revision":4,"payloadVersion":1,"payload":null,"deleted":true}],"nextCursor":"cursor-1","hasMore":false}"""))
        val page = client.snapshot("token", null)
        assertEquals("e2", page.epoch)
        assertEquals("cursor-1", page.nextCursor)
        assertTrue(page.changes.single().deleted)
        assertNull(page.changes.single().payload)
    }

    @Test fun authChallengeAndVerifyUseDeviceBindingContract() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"challengeId":"challenge-1","expiresAt":2000000000000,"resendAfterSeconds":60}"""))
        val challenge = client.challenge("User@Example.com", "device-1", "Phone")
        assertEquals("challenge-1", challenge.challengeId)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/auth/challenges", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("user@example.com", body.getString("email"))
        assertEquals("device-1", body.getString("deviceId"))
        assertEquals("android", body.getString("platform"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accountId":"account-1","device":{"id":"device-1"},"epoch":"e1","tokens":{"accessToken":"a","refreshToken":"r","expiresIn":900}}"""))
        val session = client.verify("challenge-1", "123456", "device-1", "Phone")
        assertEquals("account-1", session.accountId)
        assertEquals("device-1", session.deviceId)
        assertEquals("e1", session.epoch)
        assertEquals("a", session.accessToken)
    }

    @Test fun revokeTargetsOnlyBoundDevice() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.revoke("token", "device-1")
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/devices/device-1/revoke", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
    }

    @Test fun refreshUsesStableIdempotencyKeyAndProductionRejectsCleartext() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"a","refreshToken":"r","expiresIn":900}"""))
        val tokens = client.refresh("old", "request-1")
        assertEquals("request-1", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Idempotency-Key"))
        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        val invalidClient = SyncHttpClient(server.url("/").toString().trimEnd('/'))
        assertFalse(invalidClient.configured)
    }

}
