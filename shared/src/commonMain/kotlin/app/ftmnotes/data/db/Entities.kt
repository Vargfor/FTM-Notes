package app.ftmnotes.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One row per FTM document. The canonical content is [payloadJson]; the other columns are
 * denormalised for listing, search and sync. Timestamps are epoch milliseconds (UTC).
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["type", "deleted_at", "updated_at"]),
        Index(value = ["dirty"]),
    ],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    @ColumnInfo(name = "plain_text") val plainText: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    val color: String?,
    val pinned: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Non-null = in the trash; also the sync tombstone marker. */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    /** Hybrid logical clock of the last local or merged change. */
    val hlc: String,
    /** HLC of the version last confirmed with the cloud (null = never synced). */
    @ColumnInfo(name = "base_hlc") val baseHlc: String?,
    /** True when there are local changes not yet uploaded. */
    val dirty: Boolean,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "remote_ref") val remoteRef: String?,
)

/** Small key/value store for values that belong to this device only (never synced). */
@Entity(tableName = "device_prefs")
data class DevicePrefEntity(
    @PrimaryKey val key: String,
    val value: String,
)
