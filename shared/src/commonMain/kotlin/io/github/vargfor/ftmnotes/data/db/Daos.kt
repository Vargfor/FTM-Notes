package io.github.vargfor.ftmnotes.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.SkipQueryVerification
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Everything a list card needs, without the payload. [tags] is joined with [TAG_SEPARATOR]. */
data class DocumentListRow(
    val id: String,
    val type: String,
    val title: String,
    val preview: String,
    val color: String?,
    val pinned: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "checked_count") val checkedCount: Int,
    @ColumnInfo(name = "thumb_sha") val thumbSha: String?,
    val tags: String?,
)

data class TagCount(val tag: String, @ColumnInfo(name = "tag_key") val tagKey: String, val count: Int)

const val TAG_SEPARATOR = '\u001F'

private const val LIST_COLUMNS = """
    d.id, d.type, d.title, d.preview, d.color, d.pinned, d.archived, d.updated_at, d.deleted_at,
    d.item_count, d.checked_count, d.thumb_sha,
    (SELECT group_concat(t.tag, char(31)) FROM doc_tags t WHERE t.doc_id = d.id) AS tags
"""

@Dao
interface DocumentDao {

    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    /** Live (not trashed) documents of [type], pinned first; optionally only those with tag [tagKey]. */
    @Query(
        """
        SELECT $LIST_COLUMNS FROM documents d
        WHERE d.type = :type AND d.deleted_at IS NULL AND d.archived = :archived
          AND (:tagKey IS NULL OR EXISTS (SELECT 1 FROM doc_tags t WHERE t.doc_id = d.id AND t.tag_key = :tagKey))
        ORDER BY d.pinned DESC, d.updated_at DESC
        """
    )
    fun observeList(type: String, archived: Boolean, tagKey: String?): Flow<List<DocumentListRow>>

    @Query(
        """
        SELECT $LIST_COLUMNS FROM documents d
        WHERE d.type = :type AND d.deleted_at IS NOT NULL AND d.purged = 0
        ORDER BY d.deleted_at DESC
        """
    )
    fun observeTrash(type: String): Flow<List<DocumentListRow>>

    /**
     * [match] must come from `FtsQuery.build()`. Archived notes are included; trashed ones aren't.
     * Ranked by bm25 with title weighted highest, then tags, then body. Not a Flow (see FtsTable);
     * pair it with [observeChanges] to keep results live.
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT $LIST_COLUMNS FROM documents d
        JOIN documents_fts f ON f.doc_id = d.id
        WHERE documents_fts MATCH :match AND d.deleted_at IS NULL
          AND (:type IS NULL OR d.type = :type)
          AND (:tag IS NULL OR EXISTS (SELECT 1 FROM doc_tags t WHERE t.doc_id = d.id AND t.tag_key = :tag))
          AND (:color IS NULL OR d.color = :color)
          AND (:includeArchived OR d.archived = 0)
        ORDER BY bm25(documents_fts, 0.0, 10.0, 1.0, 5.0)
        LIMIT 200
        """
    )
    suspend fun search(match: String, type: String? = null, tag: String? = null, color: String? = null, includeArchived: Boolean = true): List<DocumentListRow>

    /** Emits whenever any document is written (every write advances the HLC). */
    @Query("SELECT MAX(hlc) FROM documents")
    fun observeChanges(): Flow<String?>

    @Query(
        """
        SELECT MIN(t.tag) AS tag, t.tag_key, COUNT(*) AS count FROM doc_tags t
        JOIN documents d ON d.id = t.doc_id
        WHERE d.deleted_at IS NULL
        GROUP BY t.tag_key ORDER BY t.tag_key
        """
    )
    fun observeTagCounts(): Flow<List<TagCount>>

    @Query("SELECT id FROM documents d WHERE d.deleted_at IS NULL AND EXISTS (SELECT 1 FROM doc_tags t WHERE t.doc_id = d.id AND t.tag_key = :tagKey)")
    suspend fun idsWithTag(tagKey: String): List<String>

    @Query("SELECT MAX(hlc) FROM documents")
    suspend fun maxHlc(): String?

    @Query("SELECT id FROM documents WHERE deleted_at IS NOT NULL AND purged = 0 AND deleted_at < :before")
    suspend fun expiredTrash(before: Long): List<String>

    @Query("SELECT id FROM documents WHERE deleted_at IS NOT NULL AND purged = 0")
    suspend fun trashIds(): List<String>

    @Query("SELECT * FROM documents WHERE import_batch = :batch")
    suspend fun inBatch(batch: String): List<DocumentEntity>

    @Query("SELECT payload_json FROM documents WHERE purged = 0")
    suspend fun allLivePayloads(): List<String>

    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentEntity>

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    // --- Projections (written only by DocumentRepository, inside its transaction) ---

    @Query("DELETE FROM doc_tags WHERE doc_id = :docId")
    suspend fun deleteTags(docId: String)

    @Insert
    suspend fun insertTags(tags: List<DocTagEntity>)

    @SkipQueryVerification
    @Query("DELETE FROM documents_fts WHERE doc_id = :docId")
    suspend fun deleteFts(docId: String)

    @SkipQueryVerification
    @Query("INSERT INTO documents_fts(doc_id, title, body, tags) VALUES (:docId, :title, :body, :tags)")
    suspend fun insertFts(docId: String, title: String, body: String, tags: String)

    @Query("DELETE FROM doc_tags")
    suspend fun deleteAllTags()

    @SkipQueryVerification
    @Query("DELETE FROM documents_fts")
    suspend fun deleteAllFts()
}

@Dao
interface ImportBatchDao {
    @Insert
    suspend fun insert(batch: ImportBatchEntity)

    @Query("UPDATE import_batches SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun get(id: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ImportBatchEntity>>
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE sha256 = :sha256")
    suspend fun get(sha256: String): AttachmentEntity?

    @Query("SELECT sha256 FROM attachments")
    suspend fun allShas(): List<String>

    @Query("DELETE FROM attachments WHERE sha256 IN (:shas)")
    suspend fun delete(shas: List<String>)
}

@Dao
interface DevicePrefDao {

    @Query("SELECT value FROM device_prefs WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM device_prefs")
    suspend fun all(): List<DevicePrefEntity>

    @Query("SELECT * FROM device_prefs")
    fun observeAll(): Flow<List<DevicePrefEntity>>

    @Upsert
    suspend fun put(pref: DevicePrefEntity)

    @Query("DELETE FROM device_prefs WHERE `key` = :key")
    suspend fun remove(key: String)
}

@Dao
interface DocumentRevisionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(revision: DocumentRevisionEntity)

    @Query("SELECT * FROM doc_revisions ORDER BY created_at DESC, id DESC")
    suspend fun all(): List<DocumentRevisionEntity>

    @Query("SELECT * FROM doc_revisions WHERE doc_id = :docId ORDER BY created_at DESC, id DESC")
    fun observe(docId: String): Flow<List<DocumentRevisionEntity>>

    @Query("DELETE FROM doc_revisions WHERE doc_id = :docId AND id NOT IN (SELECT id FROM doc_revisions WHERE doc_id = :docId ORDER BY created_at DESC, id DESC LIMIT :keep)")
    suspend fun trim(docId: String, keep: Int = 20)
}
