package io.github.goooseby.tickmark.data.sync

import androidx.room.withTransaction
import io.github.goooseby.tickmark.data.TickDatabase
import io.github.goooseby.tickmark.engine.sync.SyncDayPayload
import io.github.goooseby.tickmark.engine.sync.SyncPullResult
import io.github.goooseby.tickmark.engine.sync.SyncRemoteDevice
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.util.UUID

class SyncStore(private val db: TickDatabase) {
    private val dao = db.syncDao()

    val account = dao.account().distinctUntilChanged()

    suspend fun currentAccount(): SyncAccountRow? = dao.currentAccount()

    fun pendingCount(accountId: String, deviceId: String) = dao.pendingCount(accountId, deviceId)

    fun devices(accountId: String) = dao.devices(accountId)

    suspend fun activate(account: SyncAccountRow) = db.withTransaction {
        val previous = dao.currentAccount()
        if (previous != null && previous.accountId != account.accountId) clear(previous.accountId)
        if (previous != null && previous.accountId == account.accountId && previous.epoch != account.epoch) {
            dao.deleteOutbox(account.accountId)
            dao.deleteMirror(account.accountId)
        }
        dao.putAccount(account)
        dao.putDevice(SyncDeviceRow(account.accountId, account.deviceId, account.deviceName, null, false))
    }

    suspend fun stage(account: SyncAccountRow, day: LocalDate, payload: SyncDayPayload): Boolean = db.withTransaction {
        val current = dao.account(account.accountId) ?: return@withTransaction false
        if (current.status != "active" || current.deviceId != account.deviceId) return@withTransaction false
        val dayText = day.toString()
        val revision = dao.revision(account.accountId, account.deviceId, dayText)
        val outbox = dao.outbox(account.accountId, account.deviceId, dayText)
        val mirror = dao.mirror(account.accountId, account.deviceId, dayText)
        if (payload.deviceIntervals.isEmpty() && payload.appIntervals.isEmpty() && revision == null && outbox == null && (mirror == null || mirror.deleted)) {
            return@withTransaction false
        }
        val encoded = if (payload.deviceIntervals.isEmpty() && payload.appIntervals.isEmpty() && (revision != null || outbox != null || mirror?.deleted == false)) null else SyncPayloadCodec.encode(payload)
        if (outbox != null && outbox.revision == (revision?.revision ?: 0) && outbox.payloadJson == encoded ||
            outbox == null && mirror?.sequence == 0L && mirror.payloadJson == encoded) {
            return@withTransaction false
        }
        val nextRevision = (revision?.revision ?: 0L) + 1
        require(nextRevision > 0) { "同步修订号已耗尽" }
        val now = System.currentTimeMillis()
        dao.putRevision(SyncRevisionRow(account.accountId, account.deviceId, dayText, nextRevision))
        dao.putOutbox(
            SyncOutboxRow(
                accountId = account.accountId,
                deviceId = account.deviceId,
                utcDay = dayText,
                revision = nextRevision,
                payloadVersion = payload.schemaVersion(),
                payloadJson = encoded,
                status = "pending",
                attempts = 0,
                lastError = null,
                createdAt = outbox?.createdAt ?: now,
                updatedAt = now,
            )
        )
        true
    }

    suspend fun pending(account: SyncAccountRow, limit: Int = 25): List<SyncOutboxRow> =
        dao.pending(account.accountId, account.deviceId, limit)

    suspend fun hasConflicts(account: SyncAccountRow): Boolean =
        dao.conflictCount(account.accountId, account.deviceId) > 0

    suspend fun acknowledge(row: SyncOutboxRow) = db.withTransaction {
        val known = dao.mirror(row.accountId, row.deviceId, row.utcDay)
        if (known == null || known.revision <= row.revision) {
            dao.putMirror(SyncMirrorRow(row.accountId, row.deviceId, row.utcDay, row.revision, row.payloadVersion,
                row.payloadJson, row.payloadJson == null, 0))
        }
        dao.acknowledge(row.accountId, row.deviceId, row.utcDay, row.revision)
    }

    suspend fun prepareUpload(account: SyncAccountRow, epoch: String) = db.withTransaction {
        if (account.epoch != epoch || account.status == "rebuilding") {
            dao.deleteOutbox(account.accountId)
            dao.deleteMirror(account.accountId)
            dao.deleteSnapshot(account.accountId)
        }
        dao.putAccount(account.copy(epoch = epoch, status = "active", cursor = null, snapshotId = null,
            snapshotEpoch = null, snapshotCursor = null, lastSequence = 0))
    }

    suspend fun finishAttempt(row: SyncOutboxRow, status: String, error: String?) {
        dao.finishAttempt(row.accountId, row.deviceId, row.utcDay, row.revision, status, error, System.currentTimeMillis())
    }

    suspend fun markError(account: SyncAccountRow, error: String, status: String = account.status) {
        dao.updateAccountStatus(account.accountId, status, error, System.currentTimeMillis())
    }

    suspend fun markSuccess(account: SyncAccountRow, cursor: String?, sequence: Long) {
        val current = dao.currentAccount() ?: return
        val nextCursor = cursor ?: current.cursor
        if (nextCursor != null) {
            dao.updateCursor(account.accountId, nextCursor, sequence, System.currentTimeMillis(), System.currentTimeMillis())
        } else {
            val now = System.currentTimeMillis()
            dao.touchSuccess(account.accountId, now, now)
        }
    }

    suspend fun beginSnapshot(account: SyncAccountRow): String = db.withTransaction {
        val current = dao.account(account.accountId) ?: error("同步账号不存在")
        val snapshotId = current.snapshotId ?: UUID.randomUUID().toString()
        dao.beginSnapshot(account.accountId, snapshotId, System.currentTimeMillis())
        dao.deleteSnapshot(account.accountId)
        snapshotId
    }

    suspend fun applySnapshotPage(account: SyncAccountRow, page: io.github.goooseby.tickmark.engine.sync.SyncSnapshotPage) = db.withTransaction {
        val current = dao.account(account.accountId) ?: error("同步账号不存在")
        val snapshotId = current.snapshotId ?: error("同步快照未开始")
        require(current.status == "rebuilding" && page.nextCursor.isNotBlank()) { "同步快照状态无效" }
        if (current.snapshotEpoch != null && current.snapshotEpoch != page.epoch) throw SyncException(SyncErrorCode.EPOCH_MISMATCH)
        if (current.snapshotEpoch == null) dao.setSnapshotEpoch(account.accountId, snapshotId, page.epoch, System.currentTimeMillis())
        require(!page.hasMore || page.nextCursor != current.snapshotCursor) { "同步快照分页未推进" }
        page.changes.forEach { change ->
            val day = change.utcDate.toString()
            dao.putSnapshot(
                SyncSnapshotRow(
                    accountId = account.accountId,
                    deviceId = change.deviceId,
                    utcDay = day,
                    revision = change.revision,
                    payloadVersion = change.payload?.schemaVersion() ?: 1,
                    payloadJson = change.payload?.let(SyncPayloadCodec::encode),
                    deleted = change.deleted,
                    sequence = change.sequence,
                )
            )
        }
        page.devices.forEach { dao.putDevice(it.row(account.accountId)) }
        if (page.hasMore) {
            dao.updateSnapshotCursor(account.accountId, snapshotId, page.nextCursor, System.currentTimeMillis())
        } else {
            dao.deleteMirror(account.accountId)
            dao.replaceMirrorFromSnapshot(account.accountId)
            dao.deleteSnapshot(account.accountId)
            dao.commitSnapshot(
                account.accountId,
                snapshotId,
                page.epoch,
                page.nextCursor,
                page.changes.maxOfOrNull { it.sequence } ?: 0,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun applyPull(account: SyncAccountRow, result: SyncPullResult) = db.withTransaction {
        val current = dao.account(account.accountId) ?: return@withTransaction
        require(current.epoch == result.epoch) { "同步世代已变化" }
        require(current.cursor == account.cursor) { "同步游标已变化" }
        var sequence = current.lastSequence
        result.changes.forEach { change ->
            require(change.sequence > sequence) { "同步变更顺序无效" }
            sequence = change.sequence
            val day = change.utcDate.toString()
            val existing = dao.mirror(account.accountId, change.deviceId, day)
            if (existing == null || existing.sequence < change.sequence) {
                dao.putMirror(
                    SyncMirrorRow(
                        accountId = account.accountId,
                        deviceId = change.deviceId,
                        utcDay = day,
                        revision = change.revision,
                        payloadVersion = change.payload?.schemaVersion() ?: 1,
                        payloadJson = change.payload?.let(SyncPayloadCodec::encode),
                        deleted = change.deleted,
                        sequence = change.sequence,
                    )
                )
            }
        }
        result.devices.forEach { device -> dao.putDevice(device.row(account.accountId)) }
        require(result.nextAfter.isNotBlank()) { "同步游标无效" }
        require(!result.hasMore || result.nextAfter != account.cursor) { "同步分页未推进" }
        dao.updateCursor(account.accountId, result.nextAfter, sequence, System.currentTimeMillis(), System.currentTimeMillis())
    }

    suspend fun mirror(accountId: String): List<SyncMirrorRow> = dao.mirror(accountId)

    suspend fun clear(accountId: String) = db.withTransaction {
        dao.deleteOutbox(accountId)
        dao.deleteMirror(accountId)
        dao.deleteSnapshot(accountId)
        dao.deleteDevices(accountId)
        dao.deleteAccount(accountId)
    }

    private fun SyncRemoteDevice.row(accountId: String) = SyncDeviceRow(accountId, deviceId, name, lastSeenAt, revoked)

    private fun SyncDayPayload.schemaVersion(): Int = io.github.goooseby.tickmark.engine.sync.SYNC_PAYLOAD_VERSION
}
