package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.backup.importFingerprint
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.importer.keep.*
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okio.*
import kotlin.time.Clock

@Serializable
data class ImportDraft(val id: String, val document: FtmDocument, val sourcePath: String = "", val duplicate: Boolean = false)
@Serializable
data class ImportPreview(val id: String, val source: String, val drafts: List<ImportDraft>, val issues: List<ReadIssue>, val skipped: Int = 0)

/** All importers converge here; external input never writes existing documents or settings. */
class ImportCoordinator(
    private val bridge: PlatformBridge, private val attachments: AttachmentStore,
    private val repository: DocumentRepository, private val documents: DocumentDao,
    private val batches: ImportBatchDao, private val transactor: Transactor,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    private val mutex = Mutex()
    val history get() = batches.observeAll()
    private val root get() = requireNotNull(bridge.cacheRoot) { "Import staging is unavailable" } / "imports"
    private fun sessionPath(id: String): Path {
        require(Regex("[a-zA-Z0-9-]+").matches(id))
        return root / "$id.json"
    }

    suspend fun prepareFtm(text: String): ImportPreview {
        val parsed = FtmReader.read(text)
        return stage("ftm", parsed.documents.map { ImportDraft(Ids.newId(), it) }, parsed.issues)
    }

    suspend fun prepareKeep(files: List<PickedFile>, linksHeading: String): ImportPreview = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "Choose at least one Takeout archive" }
        val sources = files.map { file -> KeepSource.zip(fs, file.path) }
        val parsed = KeepTakeoutParser(KeepImportOptions(linksHeading)).parse(sources)
        val drafts = parsed.notes.map { note ->
            val refs = note.attachments.map { attachment -> attachments.put(attachment.open(), attachment.mimeType, attachment.name) }
            ImportDraft(Ids.newId(), note.document.copy(attachments = refs), note.sourcePath)
        }
        stage("keep", drafts, parsed.issues, parsed.trashedSkipped + parsed.emptySkipped)
    }

    suspend fun stage(source: String, drafts: List<ImportDraft>, issues: List<ReadIssue> = emptyList(), skipped: Int = 0): ImportPreview = withContext(Dispatchers.IO) {
        val seen = documents.allLivePayloads().mapNotNull { runCatching { FtmFormat.decodeDocument(it).importFingerprint() }.getOrNull() }.toMutableSet()
        val checked = drafts.map { draft -> draft.copy(duplicate = !seen.add(draft.document.importFingerprint())) }
        val preview = ImportPreview(Ids.newId(), source, checked, issues, skipped)
        fs.createDirectories(root)
        fs.write(sessionPath(preview.id)) { writeUtf8(FtmFormat.compact.encodeToString(ImportPreview.serializer(), preview)) }
        preview
    }

    suspend fun preview(id: String): ImportPreview = withContext(Dispatchers.IO) {
        FtmFormat.writer.decodeFromString(ImportPreview.serializer(), fs.read(sessionPath(id)) { readUtf8() })
    }

    suspend fun commit(id: String, selected: Set<String>, tags: List<String>): ImportBatchEntity = mutex.withLock {
        val preview = preview(id)
        val chosen = preview.drafts.filter { it.id in selected }
        require(chosen.isNotEmpty()) { "Select at least one item" }
        transactor.write {
            batches.get(id)?.let { return@write it }
            val batch = ImportBatchEntity(id, preview.source, Clock.System.now().toEpochMilliseconds(),
                chosen.size, preview.skipped + preview.drafts.size - chosen.size, "done")
            batches.insert(batch)
            chosen.forEach { draft ->
                require(draft.document !is SettingsDocument) { "Settings are not content imports" }
                repository.importNew(draft.document.withOrganisation(tags = draft.document.tags + tags), null, null, id)
            }
            batch
        }
    }

    suspend fun undo(id: String): Int = transactor.write {
        val batch = batches.get(id) ?: return@write 0
        if (batch.status == "undone") return@write 0
        val docs = documents.inBatch(id).filterNot { it.purged }
        docs.forEach { repository.moveToTrash(it.id) }
        batches.setStatus(id, "undone")
        docs.size
    }
}
