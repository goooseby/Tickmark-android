package io.github.goooseby.tickmark.engine.sync

import io.github.goooseby.tickmark.engine.model.DeviceSpan
import io.github.goooseby.tickmark.engine.model.Session
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

const val SYNC_PAYLOAD_VERSION = 1
const val ANDROID_CAPTURE_PROFILE = "android.interactive-unlocked.v1"

enum class SyncDeviceState { ACTIVE, IDLE, OFFLINE, GAP }
enum class SyncSessionQuality { OBSERVED, INFERRED }

data class SyncDeviceInterval(
    val startMs: Long,
    val endMs: Long,
    val state: SyncDeviceState,
    val reason: String? = null,
)

data class SyncAppInterval(
    val startMs: Long,
    val endMs: Long,
    val appId: String,
    val quality: SyncSessionQuality,
    val displayName: String? = null,
)

data class SyncDayPayload(
    val utcDate: LocalDate,
    val zoneId: String,
    val captureProfile: String = ANDROID_CAPTURE_PROFILE,
    val deviceIntervals: List<SyncDeviceInterval> = emptyList(),
    val appIntervals: List<SyncAppInterval> = emptyList(),
)

data class SyncProjectionInput(
    val spans: List<DeviceSpan>,
    val sessions: List<Session>,
    val coverage: List<LongRange>,
    val hiddenPackages: Set<String>,
    val appNames: Map<String, String>,
    val sourceTimeZone: ZoneId,
    val now: Long,
)

data class SyncShard(
    val dayUtc: LocalDate,
    val revision: Long,
    val payloadVersion: Int = SYNC_PAYLOAD_VERSION,
    val payload: SyncDayPayload?,
)

data class SyncUpload(
    val deviceId: String,
    val epoch: String,
    val shards: List<SyncShard>,
)

sealed interface SyncPushResult {
    data object Accepted : SyncPushResult
    data class Conflict(val dayUtc: LocalDate?, val serverRevision: Long?) : SyncPushResult
    data class Stale(val dayUtc: LocalDate?) : SyncPushResult
}

data class SyncChange(
    val sequence: Long,
    val deviceId: String,
    val utcDate: LocalDate,
    val revision: Long,
    val payload: SyncDayPayload?,
    val deleted: Boolean,
)

data class SyncRemoteDevice(
    val deviceId: String,
    val name: String,
    val lastSeenAt: Long?,
    val revoked: Boolean,
)

data class SyncSnapshotPage(
    val epoch: String,
    val changes: List<SyncChange>,
    val devices: List<SyncRemoteDevice>,
    val nextCursor: String,
    val hasMore: Boolean,
)

data class SyncPullResult(
    val epoch: String,
    val changes: List<SyncChange>,
    val devices: List<SyncRemoteDevice>,
    val nextAfter: String,
    val hasMore: Boolean,
)

interface SyncTransport {
    suspend fun serverEpoch(accessToken: String): String
    suspend fun push(accessToken: String, upload: SyncUpload): SyncPushResult
    suspend fun pull(accessToken: String, epoch: String, after: String?, limit: Int, waitSeconds: Int = 0): SyncPullResult
    suspend fun snapshot(accessToken: String, cursor: String?): SyncSnapshotPage
}

data class SyncAuthChallenge(
    val challengeId: String,
    val expiresAt: Long?,
    val resendAfterSeconds: Int,
)

data class SyncAuthSession(
    val accountId: String,
    val deviceId: String,
    val epoch: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
)

data class SyncTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
)

interface SyncAuthenticator {
    suspend fun challenge(email: String, deviceId: String, deviceName: String): SyncAuthChallenge
    suspend fun verify(challengeId: String, code: String, deviceId: String, deviceName: String): SyncAuthSession
    suspend fun refresh(refreshToken: String, requestId: String): SyncTokenPair
    suspend fun revoke(accessToken: String, deviceId: String)
}
