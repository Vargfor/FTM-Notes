package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.format.contentHash
import io.github.vargfor.ftmnotes.core.format.plainText
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.core.model.FtmDocument
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.core.model.withMeta
import io.github.vargfor.ftmnotes.data.db.DocumentDao
import io.github.vargfor.ftmnotes.data.db.DocumentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

/** A document as stored: always has an id and timestamps. */
data class StoredDocument(
    val id: String,
    val document: FtmDocument,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The single write path for documents. Keeps the denormalised columns (title, plain text, hash)
 * and the sync bookkeeping (HLC, dirty flag) consistent with the canonical JSON payload.
 */
class DocumentRepository(
    private val dao: DocumentDao,
    private val clock: DeviceClock,
    private val now: () -> Instant = { Clock.System.now() },
) {

    fun observeActive(type: DocumentType): Flow<List<StoredDocument>> =
        dao.observeActive(type.wire).map { rows -> rows.mapNotNull { it.toStored() } }

    fun observe(id: String): Flow<StoredDocument?> = dao.observe(id).map { it?.toStored() }

    suspend fun get(id: String): StoredDocument? = dao.get(id)?.toStored()

    /**
     * Inserts or updates [document], assigning an id when it has none. Saving unchanged content
     * is a no-op, so callers can save eagerly (e.g. on every debounced keystroke).
     *
     * @return the document id
     */
    suspend fun save(document: FtmDocument): String {
        val id = document.id ?: Ids.newId()
        val existing = dao.get(id)
        val timestamp = now()
        val createdAt = document.createdAt
            ?: existing?.createdAt?.let(Instant::fromEpochMilliseconds)
            ?: timestamp

        // Hash excludes updatedAt so that re-saving identical content is detected as "no change".
        val hash = FtmFormat.encodeDocument(document.withMeta(id, createdAt, updatedAt = null)).contentHash()
        if (existing != null && existing.contentHash == hash && existing.deletedAt == null) return id

        val stored = document.withMeta(id, createdAt, updatedAt = timestamp)
        dao.upsert(
            DocumentEntity(
                id = id,
                type = stored.documentType.wire,
                title = stored.title,
                plainText = stored.plainText(),
                payloadJson = FtmFormat.encodeDocument(stored),
                schemaVersion = FtmFormat.VERSION,
                color = stored.color,
                pinned = stored.pinned,
                archived = stored.archived,
                createdAt = createdAt.toEpochMilliseconds(),
                updatedAt = timestamp.toEpochMilliseconds(),
                deletedAt = null,
                hlc = clock.now().encode(),
                baseHlc = existing?.baseHlc,
                dirty = true,
                contentHash = hash,
                remoteRef = existing?.remoteRef,
            )
        )
        return id
    }

    suspend fun moveToTrash(id: String) {
        dao.moveToTrash(id, now().toEpochMilliseconds(), clock.now().encode())
    }

    suspend fun restore(id: String) {
        dao.restore(id, clock.now().encode())
    }

    private fun DocumentEntity.toStored(): StoredDocument? {
        // A payload this app version can't read (e.g. written by a newer version) is skipped, not fatal.
        val document = runCatching { FtmFormat.decodeDocument(payloadJson) }.getOrNull() ?: return null
        return StoredDocument(
            id = id,
            document = document,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
        )
    }
}
