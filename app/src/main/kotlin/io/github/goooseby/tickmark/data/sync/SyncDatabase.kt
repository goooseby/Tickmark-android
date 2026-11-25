package io.github.goooseby.tickmark.data.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(tableName = "sync_account")
data class SyncAccountRow(
    @PrimaryKey val accountId: String,
    val deviceId: String,
    val deviceName: String,
    val epoch: String,
    val fromUtcDay: String,
    val throughUtcDay: String?,
    val status: String = "active",
    val cursor: String? = null,
    val snapshotId: String? = null,
    val snapshotEpoch: String? = null,
    val snapshotCursor: String? = null,
    val lastSequence: Long = 0,
    val lastSuccessAt: Long = 0,
    val lastError: String? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_revisions",
    primaryKeys = ["accountId", "deviceId", "utcDay"],
    indices = [Index("accountId", "deviceId")],
)
data class SyncRevisionRow(
    val accountId: String,
    val deviceId: String,
    val utcDay: String,
    val revision: Long,
)

@Entity(
    tableName = "sync_outbox",
    primaryKeys = ["accountId", "deviceId", "utcDay"],
    indices = [Index("accountId", "deviceId", "status")],
)
data class SyncOutboxRow(
    val accountId: String,
    val deviceId: String,
    val utcDay: String,
    val revision: Long,
    val payloadVersion: Int,
    val payloadJson: String?,
    val status: String = "pending",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_mirror",
    primaryKeys = ["accountId", "deviceId", "utcDay"],
    indices = [Index("accountId"), Index("accountId", "deviceId")],
)
data class SyncMirrorRow(
    val accountId: String,
    val deviceId: String,
    val utcDay: String,
    val revision: Long,
    val payloadVersion: Int,
    val payloadJson: String?,
    val deleted: Boolean,
    val sequence: Long,
)

@Entity(
    tableName = "sync_snapshot_stage",
    primaryKeys = ["accountId", "deviceId", "utcDay"],
    indices = [Index("accountId")],
)
data class SyncSnapshotRow(
    val accountId: String,
    val deviceId: String,
    val utcDay: String,
    val revision: Long,
    val payloadVersion: Int,
    val payloadJson: String?,
    val deleted: Boolean,
    val sequence: Long,
)

@Entity(
    tableName = "sync_devices",
    primaryKeys = ["accountId", "deviceId"],
    indices = [Index("accountId")],
)
data class SyncDeviceRow(
    val accountId: String,
    val deviceId: String,
    val name: String,
    val lastSeenAt: Long?,
    val revoked: Boolean,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_account LIMIT 1")
    fun account(): Flow<SyncAccountRow?>

    @Query("SELECT * FROM sync_account LIMIT 1")
    suspend fun currentAccount(): SyncAccountRow?

    @Query("SELECT * FROM sync_account WHERE accountId=:accountId")
    suspend fun account(accountId: String): SyncAccountRow?

    @Upsert
    suspend fun putAccount(row: SyncAccountRow)

    @Query("UPDATE sync_account SET status=:status,lastError=:error,updatedAt=:updatedAt WHERE accountId=:accountId")
    suspend fun updateAccountStatus(accountId: String, status: String, error: String?, updatedAt: Long)

    @Query("UPDATE sync_account SET cursor=:cursor,lastSequence=:sequence,lastSuccessAt=:successAt,lastError=NULL,updatedAt=:updatedAt WHERE accountId=:accountId")
    suspend fun updateCursor(accountId: String, cursor: String, sequence: Long, successAt: Long, updatedAt: Long)

    @Query("UPDATE sync_account SET status='rebuilding',snapshotId=:snapshotId,snapshotEpoch=NULL,snapshotCursor=NULL,lastError=NULL,updatedAt=:updatedAt WHERE accountId=:accountId")
    suspend fun beginSnapshot(accountId: String, snapshotId: String, updatedAt: Long)

    @Query("UPDATE sync_account SET snapshotEpoch=:epoch,updatedAt=:updatedAt WHERE accountId=:accountId AND snapshotId=:snapshotId AND snapshotEpoch IS NULL")
    suspend fun setSnapshotEpoch(accountId: String, snapshotId: String, epoch: String, updatedAt: Long)

    @Query("UPDATE sync_account SET snapshotCursor=:cursor,lastError=NULL,updatedAt=:updatedAt WHERE accountId=:accountId AND snapshotId=:snapshotId")
    suspend fun updateSnapshotCursor(accountId: String, snapshotId: String, cursor: String, updatedAt: Long)

    @Query("UPDATE sync_account SET epoch=:epoch,cursor=:cursor,lastSequence=:sequence,status='active',snapshotId=NULL,snapshotEpoch=NULL,snapshotCursor=NULL,lastError=NULL,updatedAt=:updatedAt WHERE accountId=:accountId AND snapshotId=:snapshotId")
    suspend fun commitSnapshot(accountId: String, snapshotId: String, epoch: String, cursor: String, sequence: Long, updatedAt: Long)

    @Query("UPDATE sync_account SET lastSuccessAt=:successAt,lastError=NULL,updatedAt=:updatedAt WHERE accountId=:accountId")
    suspend fun touchSuccess(accountId: String, successAt: Long, updatedAt: Long)

    @Query("UPDATE sync_account SET deviceName=:name,updatedAt=:updatedAt WHERE accountId=:accountId AND deviceId=:deviceId")
    suspend fun renameDevice(accountId: String, deviceId: String, name: String, updatedAt: Long)

    @Query("SELECT * FROM sync_revisions WHERE accountId=:accountId AND deviceId=:deviceId AND utcDay=:day")
    suspend fun revision(accountId: String, deviceId: String, day: String): SyncRevisionRow?

    @Query("SELECT * FROM sync_revisions WHERE accountId=:accountId AND deviceId=:deviceId ORDER BY utcDay")
    suspend fun localRevisions(accountId: String, deviceId: String): List<SyncRevisionRow>

    @Upsert
    suspend fun putRevision(row: SyncRevisionRow)

    @Query("SELECT * FROM sync_outbox WHERE accountId=:accountId AND deviceId=:deviceId AND status='pending' ORDER BY utcDay LIMIT :limit")
    suspend fun pending(accountId: String, deviceId: String, limit: Int): List<SyncOutboxRow>

    @Query("SELECT * FROM sync_outbox WHERE accountId=:accountId AND deviceId=:deviceId AND utcDay=:day")
    suspend fun outbox(accountId: String, deviceId: String, day: String): SyncOutboxRow?

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE accountId=:accountId AND deviceId=:deviceId AND status IN ('pending','conflict')")
    fun pendingCount(accountId: String, deviceId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE accountId=:accountId AND deviceId=:deviceId AND status='conflict'")
    suspend fun conflictCount(accountId: String, deviceId: String): Int

    @Upsert
    suspend fun putOutbox(row: SyncOutboxRow)

    @Query("DELETE FROM sync_outbox WHERE accountId=:accountId AND deviceId=:deviceId AND utcDay=:day AND revision=:revision")
    suspend fun acknowledge(accountId: String, deviceId: String, day: String, revision: Long)

    @Query("UPDATE sync_outbox SET status=:status,attempts=attempts+1,lastError=:error,updatedAt=:updatedAt WHERE accountId=:accountId AND deviceId=:deviceId AND utcDay=:day AND revision=:revision")
    suspend fun finishAttempt(accountId: String, deviceId: String, day: String, revision: Long, status: String, error: String?, updatedAt: Long)

    @Query("SELECT * FROM sync_mirror WHERE accountId=:accountId ORDER BY deviceId,utcDay")
    suspend fun mirror(accountId: String): List<SyncMirrorRow>

    @Query("SELECT * FROM sync_mirror WHERE accountId=:accountId AND deviceId=:deviceId AND utcDay=:day")
    suspend fun mirror(accountId: String, deviceId: String, day: String): SyncMirrorRow?

    @Upsert
    suspend fun putMirror(row: SyncMirrorRow)

    @Query("DELETE FROM sync_mirror WHERE accountId=:accountId AND deviceId=:deviceId AND utcDay=:day")
    suspend fun deleteMirror(accountId: String, deviceId: String, day: String)

    @Query("DELETE FROM sync_snapshot_stage WHERE accountId=:accountId")
    suspend fun deleteSnapshot(accountId: String)

    @Upsert
    suspend fun putSnapshot(row: SyncSnapshotRow)

    @Query("INSERT INTO sync_mirror (accountId,deviceId,utcDay,revision,payloadVersion,payloadJson,deleted,sequence) SELECT accountId,deviceId,utcDay,revision,payloadVersion,payloadJson,deleted,sequence FROM sync_snapshot_stage WHERE accountId=:accountId")
    suspend fun replaceMirrorFromSnapshot(accountId: String)

    @Query("SELECT * FROM sync_devices WHERE accountId=:accountId ORDER BY deviceId")
    fun devices(accountId: String): Flow<List<SyncDeviceRow>>

    @Query("SELECT * FROM sync_devices WHERE accountId=:accountId ORDER BY deviceId")
    suspend fun allDevices(accountId: String): List<SyncDeviceRow>

    @Upsert
    suspend fun putDevice(row: SyncDeviceRow)

    @Query("DELETE FROM sync_outbox WHERE accountId=:accountId")
    suspend fun deleteOutbox(accountId: String)

    @Query("DELETE FROM sync_mirror WHERE accountId=:accountId")
    suspend fun deleteMirror(accountId: String)

    @Query("DELETE FROM sync_devices WHERE accountId=:accountId")
    suspend fun deleteDevices(accountId: String)

    @Query("DELETE FROM sync_account WHERE accountId=:accountId")
    suspend fun deleteAccount(accountId: String)
}

fun SyncAccountRow.fromDay(): LocalDate = LocalDate.parse(fromUtcDay)
fun SyncAccountRow.throughDay(): LocalDate? = throughUtcDay?.let(LocalDate::parse)
