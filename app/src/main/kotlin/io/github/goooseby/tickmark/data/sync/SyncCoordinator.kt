package io.github.goooseby.tickmark.data.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.goooseby.tickmark.data.TickRepository
import io.github.goooseby.tickmark.engine.sync.SyncAuthChallenge
import io.github.goooseby.tickmark.engine.sync.SyncAuthenticator
import io.github.goooseby.tickmark.engine.sync.SyncProjectionInput
import io.github.goooseby.tickmark.engine.sync.SyncProjector
import io.github.goooseby.tickmark.engine.sync.SyncPushResult
import io.github.goooseby.tickmark.engine.sync.SyncShard
import io.github.goooseby.tickmark.engine.sync.SyncTransport
import io.github.goooseby.tickmark.engine.sync.SyncUpload
import io.github.goooseby.tickmark.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SyncRunResult { IDLE, SUCCESS, RETRY, CONFLICT, AUTH_REQUIRED, CONFIGURATION_REQUIRED, REVOKED }

class SyncConfirmationRequired : Exception()

data class SyncUiState(
    val accountId: String? = null,
    val localDeviceId: String? = null,
    val deviceName: String? = null,
    val status: String? = null,
    val lastSuccessAt: Long = 0,
    val pendingCount: Int = 0,
    val lastError: String? = null,
    val devices: List<SyncDeviceRow> = emptyList(),
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: TickRepository,
    private val store: SyncStore,
    private val tokens: SyncTokenStore,
    private val identity: SyncIdentityStore,
    private val authenticator: SyncAuthenticator,
    private val transport: SyncTransport,
    private val configured: Boolean,
) {
    private val mutex = Mutex()
    private var started = false
    private var previousInput: SyncProjectionInput? = null
    private var previousAccountId: String? = null

    val state: StateFlow<SyncUiState> = store.account.flatMapLatest { account ->
        if (account == null) flowOf(SyncUiState()) else combine(
            store.pendingCount(account.accountId, account.deviceId),
            store.devices(account.accountId),
        ) { count, devices ->
            SyncUiState(
                accountId = account.accountId,
                localDeviceId = account.deviceId,
                deviceName = account.deviceName,
                status = account.status,
                lastSuccessAt = account.lastSuccessAt,
                pendingCount = count,
                lastError = account.lastError,
                devices = devices,
            )
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, SyncUiState())

    val isConfigured: Boolean get() = configured

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(store.account, repository.syncInputs) { account, _ -> account }.collectLatest { account ->
                if (account?.status == "active") {
                    delay(5_000)
                    mutex.withLock {
                        val latest = store.currentAccount()
                        if (latest?.status == "active") {
                            if (stage(latest, repository.syncInput())) scheduleImmediate()
                        }
                    }
                }
            }
        }
    }

    suspend fun requestCode(email: String): SyncAuthChallenge = withContext(Dispatchers.IO) {
        if (!configured) throw SyncException(SyncErrorCode.CONFIGURATION)
        authenticator.challenge(email, identity.installId(), defaultDeviceName())
    }

    suspend fun verifyCode(
        challengeId: String,
        code: String,
        deviceName: String,
        fromInclusive: Instant,
        historyConfirmed: Boolean,
    ): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        if (!configured) throw SyncException(SyncErrorCode.CONFIGURATION)
        require(!deviceName.isBlank() && deviceName.length <= 80)
        val deviceId = identity.installId()
        if (!historyConfirmed && repository.snapshot().let { it.sessions.isNotEmpty() || it.spans.isNotEmpty() }) {
            throw SyncConfirmationRequired()
        }
        val session = authenticator.verify(challengeId, code, deviceId, deviceName)
        require(session.deviceId == deviceId) { "服务器绑定的设备身份不一致" }
        val current = store.currentAccount()
        val sameAccount = current?.accountId == session.accountId && current.deviceId == deviceId
        val fromMs = fromInclusive.toEpochMilli()
        val account = SyncAccountRow(
            accountId = session.accountId,
            deviceId = deviceId,
            deviceName = deviceName,
            epoch = session.epoch,
            fromUtcDay = SyncProjector.utcDay(fromMs).toString(),
            throughUtcDay = null,
            status = "active",
            lastSuccessAt = if (sameAccount) current.lastSuccessAt else 0,
            updatedAt = System.currentTimeMillis(),
            fromInstantMs = fromMs,
        )
        tokens.saveRefresh(session.accountId, session.refreshToken)
        tokens.saveAccess(session.accountId, session.accessToken, session.accessTokenExpiresAt)
        store.activate(account)
        previousInput = null
        previousAccountId = session.accountId
        scheduleImmediate()
    } }

    suspend fun synchronize(): SyncRunResult = mutex.withLock { synchronizeLocked() }

    private suspend fun synchronizeLocked(): SyncRunResult {
        var refreshed = false
        var rebuilds = 0
        while (true) {
            val account = store.currentAccount() ?: return SyncRunResult.IDLE
            if (account.status == "revoked") return SyncRunResult.REVOKED
            if (!configured) {
                store.markError(account, SyncErrorCode.CONFIGURATION.name)
                return SyncRunResult.CONFIGURATION_REQUIRED
            }
            try {
                val token = accessToken(account)
                if (account.status == "rebuilding") {
                    store.prepareUpload(account, transport.serverEpoch(token))
                    previousInput = null
                    continue
                }
                stage(account, repository.syncInput())
                while (true) {
                    val pending = store.pending(account, limit = 8)
                    if (pending.isEmpty()) break
                    val shards = pending.map { row ->
                        SyncShard(
                            dayUtc = LocalDate.parse(row.utcDay),
                            revision = row.revision,
                            payloadVersion = row.payloadVersion,
                            payload = row.payloadJson?.let { SyncPayloadCodec.decode(it, LocalDate.parse(row.utcDay)) },
                        )
                    }
                    when (val result = transport.push(token, SyncUpload(account.deviceId, account.epoch, shards))) {
                        SyncPushResult.Accepted -> pending.forEach { store.acknowledge(it) }
                        is SyncPushResult.Conflict, is SyncPushResult.Stale -> {
                            pending.forEach { store.finishAttempt(it, "conflict", result.javaClass.simpleName) }
                            store.markError(account, result.javaClass.simpleName)
                            return SyncRunResult.CONFLICT
                        }
                    }
                }
                if (store.hasConflicts(account)) return SyncRunResult.CONFLICT
                store.markSuccess(account, null, 0)
                return SyncRunResult.SUCCESS
            } catch (error: SyncException) {
                when (error.code) {
                    SyncErrorCode.AUTH_REQUIRED -> {
                        tokens.clearAccess(account.accountId)
                        if (!refreshed) {
                            refreshed = true
                            continue
                        }
                        store.markError(account, SyncErrorCode.AUTH_REQUIRED.name, "reauth_required")
                        return SyncRunResult.AUTH_REQUIRED
                    }
                    SyncErrorCode.DEVICE_REVOKED, SyncErrorCode.FORBIDDEN -> {
                        store.markError(account, error.code.name, "revoked")
                        return SyncRunResult.REVOKED
                    }
                    SyncErrorCode.EPOCH_MISMATCH -> {
                        store.beginSnapshot(account)
                        rebuilds++
                        if (rebuilds > 2) {
                            store.markError(account, error.code.name)
                            return SyncRunResult.RETRY
                        }
                        refreshed = false
                    }
                    else -> {
                        store.pending(account).forEach { store.finishAttempt(it, "pending", error.code.name) }
                        store.markError(account, error.code.name)
                        return when (error.code) {
                            SyncErrorCode.NETWORK, SyncErrorCode.RATE_LIMITED, SyncErrorCode.SERVER, SyncErrorCode.PROTOCOL -> SyncRunResult.RETRY
                            SyncErrorCode.CONFIGURATION -> SyncRunResult.CONFIGURATION_REQUIRED
                            else -> SyncRunResult.RETRY
                        }
                    }
                }
            } catch (error: IllegalArgumentException) {
                store.pending(account).forEach { store.finishAttempt(it, "pending", SyncErrorCode.PROTOCOL.name) }
                store.markError(account, SyncErrorCode.PROTOCOL.name)
                return SyncRunResult.RETRY
            } catch (error: org.json.JSONException) {
                store.pending(account).forEach { store.finishAttempt(it, "pending", SyncErrorCode.PROTOCOL.name) }
                store.markError(account, SyncErrorCode.PROTOCOL.name)
                return SyncRunResult.RETRY
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                store.markError(account, SyncErrorCode.NETWORK.name)
                return SyncRunResult.RETRY
            }
        }
        error("同步循环意外退出")
    }

    suspend fun signOut(revoke: Boolean) = withContext(Dispatchers.IO) {
        val account = store.currentAccount()
        if (account != null && revoke) {
            val token = accessToken(account)
            authenticator.revoke(token, account.deviceId)
        }
        if (account != null) tokens.clear(account.accountId)
        store.clear(account?.accountId ?: return@withContext)
        previousInput = null
        previousAccountId = null
    }

    private suspend fun accessToken(account: SyncAccountRow): String {
        tokens.accessToken(account.accountId)?.let { return it }
        val refreshToken = tokens.refreshToken(account.accountId) ?: throw SyncException(SyncErrorCode.AUTH_REQUIRED)
        val pair = authenticator.refresh(refreshToken, tokens.refreshRequestId(account.accountId))
        tokens.saveRefresh(account.accountId, pair.refreshToken, UUID.randomUUID().toString())
        tokens.saveAccess(account.accountId, pair.accessToken, pair.accessTokenExpiresAt)
        return pair.accessToken
    }

    private suspend fun stage(account: SyncAccountRow, input: SyncProjectionInput): Boolean {
        if (previousAccountId != account.accountId) {
            previousAccountId = account.accountId
            previousInput = null
        }
        val from = account.fromDay()
        val through = account.throughDay()
        val cutoff = account.uploadStartMs()
        val todayUtc = SyncProjector.utcDay(input.now)
        val affected = SyncProjector.affectedDays(previousInput, input)
        val days = affected?.toMutableSet() ?: buildSet {
            addAll(store.recordedUploadDays(account))
            for (coverage in input.coverage) {
                val start = maxOf(coverage.first, cutoff)
                val end = minOf(coverage.last, input.now)
                if (start <= end) addAll(SyncProjector.utcDays(SyncProjector.utcDay(start), SyncProjector.utcDay(end)))
            }
        }.toMutableSet()
        days.add(todayUtc)
        var staged = false
        days.filter { it >= from && (through == null || it <= through) }.sorted().forEachIndexed { index, day ->
            if (index % 50 == 0) yield()
            if (store.stage(account, day, SyncProjector.project(input, day, cutoff))) staged = true
        }
        previousInput = input
        return staged
    }

    private fun defaultDeviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() }?.take(80) ?: "Android"

    private fun scheduleImmediate() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-immediate", ExistingWorkPolicy.KEEP, request)
    }
}
