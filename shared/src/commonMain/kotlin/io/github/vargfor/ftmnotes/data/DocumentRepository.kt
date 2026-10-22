package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.format.contentHash
import io.github.vargfor.ftmnotes.core.format.listProjection
import io.github.vargfor.ftmnotes.core.format.plainText
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.core.model.FtmDocument
import io.github.vargfor.ftmnotes.core.model.Hlc
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.core.model.withMeta
import io.github.vargfor.ftmnotes.core.model.withOrganisation
import io.github.vargfor.ftmnotes.core.search.FtsQuery
import io.github.vargfor.ftmnotes.data.db.DocTagEntity
import io.github.vargfor.ftmnotes.data.db.DocumentDao
import io.github.vargfor.ftmnotes.data.db.DocumentEntity
import io.github.vargfor.ftmnotes.data.db.DocumentListRow
import io.github.vargfor.ftmnotes.data.db.TagCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A document as stored: always has an id and timestamps. */
data class StoredDocument(
    val id: String,
    val document: FtmDocument,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val hlc: String,
)

/** A document arriving with its own history (backup restore now, sync later); stored as-is. */
data class IncomingDocument(
    val document: FtmDocument,
    val hlc: Hlc,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val purged: Boolean,
)

/**
 * The only write path for documents. Every write runs in one transaction that also maintains the
 * denormalised columns, the tag projection and the full-text index, plus sync bookkeeping
 * (HLC, dirty flag). Writes that wouldn't change the content are skipped.
 *
 * Write paths, by intent:
 * - [save] — a user edit of a whole document (new or existing).
 * - [update] — read-modify-write of the *current* stored version, for edits that touch only some
 *   fields (pin from the list, editor autosave) so concurrent edits elsewhere aren't overwritten.
 * - [importNew] — a new document from an import, keeping its original timestamps.
 * - [applyIncoming] — an exact external state (restore/sync), keeping its HLC and timestamps.
 *
 * Trash is not part of the payload: only [moveToTrash]/[restore] change it, so an autosave can
 * never resurrect a deleted note.
 */
class DocumentRepository(
    private val dao: DocumentDao,
    private val transactor: Transactor,
    private val clock: DeviceClock,
    private val now: () -> Instant = { Clock.System.now() },
) {

    // ---------------------------------------------------------------- reads

    fun observeList(type: DocumentType, archived: Boolean, tag: String? = null): Flow<List<DocumentListRow>> =
        dao.observeList(type.wire, archived, tag?.let(::tagKey))

    fun observeTrash(type: DocumentType): Flow<List<DocumentListRow>> = dao.observeTrash(type.wire)

    /** Live full-text search: re-runs whenever any document changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun search(query: String): Flow<List<DocumentListRow>> {
        val match = FtsQuery.build(query) ?: return flowOf(emptyList())
        return dao.observeChanges().mapLatest { dao.search(match) }
    }

    fun observeTagCounts(): Flow<List<TagCount>> = dao.observeTagCounts()

    fun observe(id: String): Flow<StoredDocument?> = dao.observe(id).map { it?.toStored() }

    suspend fun get(id: String): StoredDocument? = dao.get(id)?.toStored()

    // ---------------------------------------------------------------- writes

    /** Saves a whole document (assigning an id if it has none). @return the id. */
    suspend fun save(document: FtmDocument): String = transactor.write {
        val id = document.id ?: Ids.newId()
        val existing = dao.get(id)
        if (existing?.purged == true) return@write id
        val createdAt = document.createdAt ?: existing?.createdAt?.let(Instant::fromEpochMilliseconds) ?: now()
        writeIfChanged(id, document, existing, createdAt, dirty = true)
        id
    }

    /**
     * Applies [transform] to the currently stored version of [id].
     * @return true if something changed (false also when the document doesn't exist or is purged).
     */
    suspend fun update(id: String, transform: (FtmDocument) -> FtmDocument): Boolean = transactor.write {
        val existing = dao.get(id)?.takeUnless { it.purged } ?: return@write false
        val current = existing.decode() ?: return@write false
        writeIfChanged(id, transform(current), existing, Instant.fromEpochMilliseconds(existing.createdAt), dirty = true)
    }

    /** Inserts an imported document as new, keeping its source timestamps. @return the new id. */
    suspend fun importNew(document: FtmDocument, createdAt: Instant?, updatedAt: Instant?, batchId: String): String =
        transactor.write {
            val id = document.id?.takeIf { dao.get(it) == null } ?: Ids.newId()
            val created = createdAt ?: document.createdAt ?: now()
            val updated = updatedAt ?: document.updatedAt ?: created
            writeRow(id, normalised(document), existing = null, created, updated, clock.now(), dirty = true, importBatch = batchId)
            id
        }

    /** Stores [incoming] exactly as given (the caller has already decided it should win). */
    suspend fun applyIncoming(incoming: IncomingDocument, dirty: Boolean) {
        val id = requireNotNull(incoming.document.id) { "Incoming documents need an id" }
        transactor.write {
            clock.receive(incoming.hlc)
            val existing = dao.get(id)
            writeRow(
                id, normalised(incoming.document), existing, incoming.createdAt, incoming.updatedAt, incoming.hlc, dirty,
                deletedAt = incoming.deletedAt?.toEpochMilliseconds(),
                purged = incoming.purged,
            )
        }
    }

    suspend fun moveToTrash(id: String) = setTrashed(id, trashed = true)

    suspend fun restore(id: String) = setTrashed(id, trashed = false)

    /** Ends trash retention now: the content is erased and only a tombstone remains. */
    suspend fun deleteForever(id: String) = transactor.write { purgeRow(id) }

    /** Purges everything that has been in the trash longer than [retention]. @return how many. */
    suspend fun purgeExpiredTrash(retention: Duration): Int = transactor.write {
        val ids = dao.expiredTrash((now() - retention).toEpochMilliseconds())
        ids.forEach { purgeRow(it) }
        ids.size
    }

    suspend fun emptyTrash(): Int = transactor.write {
        val ids = dao.trashIds()
        ids.forEach { purgeRow(it) }
        ids.size
    }

    /** Renames (or merges, if [to] already exists) a tag across all documents. */
    suspend fun renameTag(from: String, to: String) {
        val target = to.trim().takeIf { it.isNotEmpty() } ?: return
        dao.idsWithTag(tagKey(from)).forEach { id ->
            update(id) { doc -> doc.withOrganisation(tags = doc.tags.map { if (tagKey(it) == tagKey(from)) target else it }) }
        }
    }

    suspend fun deleteTag(tag: String) {
        dao.idsWithTag(tagKey(tag)).forEach { id ->
            update(id) { doc -> doc.withOrganisation(tags = doc.tags.filterNot { tagKey(it) == tagKey(tag) }) }
        }
    }

    // ---------------------------------------------------------------- internals

    /** @return true if a write happened. */
    private suspend fun writeIfChanged(
        id: String,
        document: FtmDocument,
        existing: DocumentEntity?,
        createdAt: Instant,
        dirty: Boolean,
    ): Boolean {
        val doc = normalised(document)
        if (existing != null && existing.contentHash == hashOf(doc, id, createdAt)) return false
        writeRow(id, doc, existing, createdAt, now(), clock.now(), dirty)
        return true
    }

    private suspend fun writeRow(
        id: String,
        document: FtmDocument,
        existing: DocumentEntity?,
        createdAt: Instant,
        updatedAt: Instant,
        hlc: Hlc,
        dirty: Boolean,
        deletedAt: Long? = existing?.deletedAt,
        purged: Boolean = existing?.purged ?: false,
        importBatch: String? = existing?.importBatch,
    ) {
        val stored = document.withMeta(id, createdAt, updatedAt)
        val plain = stored.plainText()
        val projection = stored.listProjection()
        dao.upsert(
            DocumentEntity(
                id = id,
                type = stored.documentType.wire,
                title = stored.title,
                plainText = plain,
                payloadJson = FtmFormat.encodeDocument(stored),
                schemaVersion = FtmFormat.VERSION,
                color = stored.color,
                pinned = stored.pinned,
                archived = stored.archived,
                createdAt = createdAt.toEpochMilliseconds(),
                updatedAt = updatedAt.toEpochMilliseconds(),
                deletedAt = deletedAt,
                purged = purged,
                hlc = hlc.encode(),
                baseHlc = existing?.baseHlc,
                dirty = dirty,
                contentHash = hashOf(document, id, createdAt),
                remoteRef = existing?.remoteRef,
                importBatch = importBatch,
                preview = projection.preview,
                itemCount = projection.itemCount,
                checkedCount = projection.checkedCount,
                thumbSha = projection.thumbSha,
            )
        )
        writeProjections(id, stored, plain, existing, purged)
    }

    private suspend fun writeProjections(id: String, doc: FtmDocument, plain: String, existing: DocumentEntity?, purged: Boolean) {
        dao.deleteTags(id)
        if (purged) {
            dao.deleteFts(id)
            return
        }
        dao.insertTags(doc.tags.map { DocTagEntity(id, it, tagKey(it)) })

        val tagsText = doc.tags.joinToString(" ")
        val previousTags = existing?.decode()?.tags?.joinToString(" ")
        val ftsUnchanged = existing != null && !existing.purged &&
            existing.title == doc.title && existing.plainText == plain && previousTags == tagsText
        if (!ftsUnchanged) {
            dao.deleteFts(id)
            dao.insertFts(id, doc.title, plain, tagsText)
        }
    }

    private suspend fun setTrashed(id: String, trashed: Boolean) = transactor.write {
        val existing = dao.get(id)?.takeUnless { it.purged } ?: return@write
        if ((existing.deletedAt != null) == trashed) return@write
        dao.upsert(
            existing.copy(
                deletedAt = if (trashed) now().toEpochMilliseconds() else null,
                hlc = clock.now().encode(),
                dirty = true,
            )
        )
    }

    private suspend fun purgeRow(id: String) {
        val existing = dao.get(id)?.takeUnless { it.purged } ?: return
        val tombstone = """{"type":"${existing.type}","id":"$id"}"""
        dao.upsert(
            existing.copy(
                title = "", plainText = "", payloadJson = tombstone, preview = "",
                itemCount = 0, checkedCount = 0, thumbSha = null, color = null, pinned = false,
                deletedAt = existing.deletedAt ?: now().toEpochMilliseconds(),
                purged = true,
                hlc = clock.now().encode(),
                dirty = true,
                contentHash = tombstone.contentHash(),
            )
        )
        dao.deleteTags(id)
        dao.deleteFts(id)
    }

    /** Trims tags, drops blanks and case-insensitive duplicates (first spelling wins). */
    private fun normalised(document: FtmDocument): FtmDocument {
        val tags = document.tags.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .distinctBy(::tagKey)
        return if (tags == document.tags) document else document.withOrganisation(tags = tags)
    }

    // Excludes updatedAt so that re-saving identical content is detected as "no change".
    private fun hashOf(document: FtmDocument, id: String, createdAt: Instant): String =
        FtmFormat.encodeDocument(document.withMeta(id, createdAt, updatedAt = null)).contentHash()

    private fun DocumentEntity.decode(): FtmDocument? =
        // A payload this app version can't read (e.g. from a newer version) is skipped, not fatal.
        runCatching { FtmFormat.decodeDocument(payloadJson) }.getOrNull()

    private fun DocumentEntity.toStored(): StoredDocument? {
        if (purged) return null
        val document = decode() ?: return null
        return StoredDocument(
            id = id,
            document = document,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            deletedAt = deletedAt?.let(Instant::fromEpochMilliseconds),
            hlc = hlc,
        )
    }

    companion object {
        fun tagKey(tag: String): String = tag.trim().lowercase()
    }
}
