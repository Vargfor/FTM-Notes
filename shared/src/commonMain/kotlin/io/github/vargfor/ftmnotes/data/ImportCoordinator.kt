package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.backup.importFingerprint
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.importer.keep.*
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.training.*
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

@Serializable data class ImportDraft(val id: String, val document: FtmDocument, val sourcePath: String = "", val duplicate: Boolean = false)
@Serializable data class ImportPreview(
    val id: String, val source: String, val drafts: List<ImportDraft>, val issues: List<ReadIssue>, val skipped: Int = 0,
    val originals: List<ImportDraft>? = null, val sourceIssues: List<ReadIssue> = emptyList(),
    val questions: List<ReferenceQuestion> = emptyList(), val choices: Map<String, String> = emptyMap(),
)

/** External imports create new content; program references are resolved as one transaction. */
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
        require(Regex("[a-zA-Z0-9-]+").matches(id)); return root / (id + ".json")
    }
    suspend fun prepareFtm(text: String): ImportPreview {
        val parsed = FtmReader.read(text)
        return stage("ftm", parsed.documents.map { ImportDraft(Ids.newId(), it) }, parsed.issues)
    }
    suspend fun prepareStrong(file: PickedFile, unit: MassUnit, distanceMultiplier: Double): ImportPreview = withContext(Dispatchers.IO) {
        val parsed = StrongCsv.parse(fs.read(file.path) { readUtf8() }, unit, distanceMultiplier)
        stage("strong", parsed.documents.map { ImportDraft(Ids.newId(), it) }, parsed.issues)
    }
    suspend fun prepareKeep(files: List<PickedFile>, linksHeading: String): ImportPreview = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "Choose at least one Takeout archive" }
        val parsed = KeepTakeoutParser(KeepImportOptions(linksHeading)).parse(files.map { KeepSource.zip(fs, it.path) })
        val drafts = parsed.notes.map { note ->
            val refs = note.attachments.map { a -> attachments.put(a.open(), a.mimeType, a.name) }
            ImportDraft(Ids.newId(), note.document.copy(attachments = refs), note.sourcePath)
        }
        stage("keep", drafts, parsed.issues, parsed.trashedSkipped + parsed.emptySkipped)
    }
    suspend fun stage(source: String, drafts: List<ImportDraft>, issues: List<ReadIssue> = emptyList(), skipped: Int = 0): ImportPreview =
        stageResolved(Ids.newId(), source, drafts, issues, skipped, emptyMap())

    private suspend fun stageResolved(id: String, source: String, drafts: List<ImportDraft>, issues: List<ReadIssue>,
                                      skipped: Int, choices: Map<String, String>): ImportPreview = withContext(Dispatchers.IO) {
        val existing = documents.all().filter { !it.purged && it.deletedAt == null }.mapNotNull { runCatching { FtmFormat.decodeDocument(it.payloadJson) }.getOrNull() }
        val isTraining = drafts.any { it.document is ExerciseDocument || it.document is WorkoutTemplateDocument ||
            it.document is WorkoutSessionDocument || it.document is TrainingPlanDocument }
        val originals = if (isTraining) drafts.map { draft ->
            val doc = draft.document
            draft.copy(document = doc.withMeta(doc.id ?: draft.id, doc.createdAt, doc.updatedAt))
        } else null
        val resolved = originals?.let { TrainingBundles.resolve(it.map { it.document }, existing, choices, namespace = id) }
        val seen = existing.map { it.importFingerprint() }.toMutableSet()
        val checked = (resolved?.documents?.map { ImportDraft(it.id!!, it) } ?: drafts).map { draft ->
            val already = !seen.add(draft.document.importFingerprint())
            draft.copy(duplicate = already || isTraining && existing.any { it.id == draft.document.id })
        }
        val preview = ImportPreview(id, source, checked, issues + resolved?.issues.orEmpty(), skipped, originals, issues,
            resolved?.questions.orEmpty(), choices)
        fs.createDirectories(root)
        fs.write(sessionPath(id)) { writeUtf8(FtmFormat.compact.encodeToString(ImportPreview.serializer(), preview)) }
        preview
    }
    suspend fun resolveReference(id: String, key: String, target: String): ImportPreview {
        val old = preview(id)
        return stageResolved(id, old.source, old.originals ?: old.drafts, old.sourceIssues, old.skipped, old.choices + (key to target))
    }
    suspend fun preview(id: String): ImportPreview = withContext(Dispatchers.IO) {
        FtmFormat.writer.decodeFromString(ImportPreview.serializer(), fs.read(sessionPath(id)) { readUtf8() })
    }
    suspend fun commit(id: String, selected: Set<String>, tags: List<String>): ImportBatchEntity = mutex.withLock {
        val preview = preview(id)
        require(selected.isNotEmpty()) { "Select at least one item" }
        transactor.write {
            batches.get(id)?.let { return@write it }
            val chosen = if (preview.originals == null) preview.drafts.filter { it.id in selected } else {
                val selectedIds = preview.drafts.filter { it.id in selected }.mapNotNull { it.document.id }.toSet()
                val closure = TrainingBundles.closure(selectedIds, preview.drafts.map { it.document }).map { it.id }.toSet()
                require(preview.questions.none { it.ownerId in closure }) { "Resolve the exercise and routine choices before importing" }
                require(preview.issues.none { it.path == "$" && it.severity == ReadIssue.Severity.Error }) { "The bundle has conflicting document IDs" }
                val existing = documents.all().filter { !it.purged && it.deletedAt == null }.mapNotNull { runCatching { FtmFormat.decodeDocument(it.payloadJson) }.getOrNull() }
                val docs = preview.drafts.filter { it.document.id in closure }
                val problems = TrainingBundles.validateReferences(docs.map { it.document }, existing)
                require(problems.isEmpty()) { problems.joinToString("\n") }
                docs.filterNot { d -> existing.any { it.id == d.document.id } }
            }
            require(chosen.isNotEmpty()) { "The selected content already exists" }
            val batch = ImportBatchEntity(id, preview.source, Clock.System.now().toEpochMilliseconds(),
                chosen.size, preview.skipped + (preview.drafts.size - chosen.size).coerceAtLeast(0), "done")
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
        val candidates = documents.inBatch(id).filterNot { it.purged }
        val candidateIds = candidates.map { it.id }.toSet()
        val externalReferences = documents.all().filter { !it.purged && it.deletedAt == null && it.id !in candidateIds }.flatMap {
            runCatching { FtmFormat.decodeDocument(it.payloadJson).trainingDependencies().toList() }.getOrDefault(emptyList())
        }.toSet()
        val decoded = candidates.mapNotNull { runCatching { FtmFormat.decodeDocument(it.payloadJson) }.getOrNull() }
        val kept = TrainingBundles.closure(externalReferences.intersect(candidateIds), decoded).mapNotNull { it.id }.toSet()
        val remove = candidates.filter { it.id !in kept }
        remove.forEach { repository.moveToTrash(it.id) }
        batches.setStatus(id, "undone")
        remove.size
    }
    suspend fun exportSelection(ids: Set<String>): Path = withContext(Dispatchers.IO) {
        val all = documents.all().filterNot { it.purged }.mapNotNull { runCatching { FtmFormat.decodeDocument(it.payloadJson) }.getOrNull() }.toMutableList()
        val selected = TrainingBundles.exportClosure(ids, all)
        require(selected.none { it is WorkoutSessionDocument && it.status != SessionStatus.Finished }) { "Use a ZIP backup for a live or draft workout" }
        require(TrainingBundles.validateReferences(selected.filterNot { it is WorkoutSessionDocument && it.status != SessionStatus.Finished }, selected).isEmpty()) { "Export has unresolved dependencies" }
        fs.createDirectories(root)
        (root / "FTM-selection.ftm.json").also { fs.write(it) { writeUtf8(FtmFormat.write(selected)) } }
    }
}
