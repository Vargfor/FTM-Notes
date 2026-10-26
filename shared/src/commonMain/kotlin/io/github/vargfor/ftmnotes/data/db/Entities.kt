package io.github.vargfor.ftmnotes.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One row per FTM document. The canonical content is [payloadJson]; every other column is
 * derived from it (or is sync/trash bookkeeping) and is maintained by `DocumentRepository`.
 * Timestamps are epoch milliseconds (UTC).
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["type", "deleted_at", "archived", "pinned", "updated_at"]),
        Index(value = ["dirty"]),
        Index(value = ["import_batch"]),
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
    /** Non-null = in the trash (or purged, see [purged]). */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    /** Trash retention expired: payload stripped, row kept only as a tombstone for sync/backup. */
    val purged: Boolean,
    /** Hybrid logical clock of the last local or merged change. */
    val hlc: String,
    /** HLC of the version last confirmed with the cloud (null = never synced). */
    @ColumnInfo(name = "base_hlc") val baseHlc: String?,
    /** True when there are local changes not yet uploaded. */
    val dirty: Boolean,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "remote_ref") val remoteRef: String?,
    /** Set when the document arrived through an import, so the import can be undone. */
    @ColumnInfo(name = "import_batch") val importBatch: String?,
    // List projection (see core `listProjection()`): lets the notes list skip decoding payloads.
    val preview: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "checked_count") val checkedCount: Int,
    @ColumnInfo(name = "thumb_sha") val thumbSha: String?,
)

/** Tag projection for filtering; [tagKey] is the lowercase form used for case-insensitive matching. */
@Entity(
    tableName = "doc_tags",
    primaryKeys = ["doc_id", "tag_key"],
    indices = [Index(value = ["tag_key"])],
)
data class DocTagEntity(
    @ColumnInfo(name = "doc_id") val docId: String,
    val tag: String,
    @ColumnInfo(name = "tag_key") val tagKey: String,
)

/** One row per import, so imports can be listed and undone. */
@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val imported: Int,
    val skipped: Int,
    /** "done" or "undone". */
    val status: String,
)

/** Metadata for a stored attachment file (`attachments/<sha256>` in app storage). */
@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val sha256: String,
    val mime: String,
    val size: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Small key/value store for values that belong to this device only (never synced). */
@Entity(tableName = "device_prefs")
data class DevicePrefEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Displaced restore/sync versions; kept separately so the canonical document remains singular. */
@Entity(tableName = "doc_revisions", indices = [Index(value = ["doc_id"])])
data class DocumentRevisionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val hlc: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val purged: Boolean,
)
