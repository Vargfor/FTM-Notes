package io.github.vargfor.ftmnotes.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    @Query(
        """
        SELECT * FROM documents
        WHERE type = :type AND deleted_at IS NULL AND archived = 0
        ORDER BY pinned DESC, updated_at DESC
        """
    )
    fun observeActive(type: String): Flow<List<DocumentEntity>>

    @Query("UPDATE documents SET deleted_at = :deletedAt, updated_at = :deletedAt, hlc = :hlc, dirty = 1 WHERE id = :id")
    suspend fun moveToTrash(id: String, deletedAt: Long, hlc: String)

    @Query("UPDATE documents SET deleted_at = NULL, hlc = :hlc, dirty = 1 WHERE id = :id")
    suspend fun restore(id: String, hlc: String)
}

@Dao
interface DevicePrefDao {

    @Query("SELECT value FROM device_prefs WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(pref: DevicePrefEntity)
}
