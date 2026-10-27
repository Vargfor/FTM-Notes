package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.backup.*
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.platform.PlatformBridge
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okio.*
import kotlin.time.Clock
import kotlin.time.Instant

data class RestoreSummary(val documents: Int, val conflicts: Int, val revisions: Int)
private fun DocumentEntity.backupRecord() = BackupRecord(id, type, payloadJson, createdAt, updatedAt, hlc, deletedAt, purged)

class BackupService(
    private val bridge: PlatformBridge, private val attachments: AttachmentStore,
    private val repository: DocumentRepository, private val documents: DocumentDao,
    private val revisions: DocumentRevisionDao, private val prefs: DevicePrefDao,
    private val settings: SettingsRepository, private val transactor: Transactor,
    private val clock: DeviceClock, private val fs: FileSystem = FileSystem.SYSTEM,
    private val training: TrainingDao? = null,
) {
    private val mutex = Mutex()
    val status get() = prefs.observeAll()
    private val root get() = requireNotNull(bridge.cacheRoot) { "Backup storage unavailable" } / "backups"

    suspend fun configure(folder: String) {
        settings.set(Flags.backupFolder, folder)
        settings.set(Flags.backupEnabled, true)
        bridge.backupsChanged(true)
    }

    suspend fun enable(enabled: Boolean) {
        require(!enabled || settings.snapshot.value[Flags.backupFolder].isNotBlank()) { "Choose a backup folder first" }
        settings.set(Flags.backupEnabled, enabled)
        bridge.backupsChanged(enabled)
    }

    suspend fun create(): Path = mutex.withLock { createUnlocked() }

    private suspend fun createUnlocked(): Path = withContext(Dispatchers.IO) {
        val manifest = transactor.write {
            val records = documents.all().map { it.backupRecord() }
            val history = revisions.all().map { BackupRevision(it.id, it.docId, it.payloadJson, it.hlc, it.createdAt, it.deletedAt, it.purged) }
            val refs = (records.filterNot { it.purged }.map { it.payloadJson } + history.filterNot { it.purged }.map { it.payloadJson })
                .flatMap { FtmFormat.decodeDocument(it).attachmentRefs() }.distinctBy { it.sha256 }
            val blobs = refs.map { ref ->
                val meta = requireNotNull(attachmentsMetadata(ref.sha256)) { "Missing attachment: ${ref.name ?: ref.sha256}" }
                BackupAttachment(meta.sha256, meta.mime, meta.size)
            }
            val keys = Flags.all.filter { it.scope == FlagScope.Device && !it.id.startsWith("backup.") }.map { it.id }.toSet()
            val local = prefs.all().filter { it.key in keys || it.key.startsWith("training.plan.") }.associate { it.key to Json.parseToJsonElement(it.value) }
            val live = training?.active()?.let { TrainingRecovery(it.sessionId, it.restEnd, it.restToken, it.alertedToken, it.exerciseId) }
            BackupManifest(createdAt = Clock.System.now().toEpochMilliseconds(), records = records, attachments = blobs, revisions = history, preferences = local, training = live)
        }
        fs.createDirectories(root)
        val path = root / "FTM-Notes-${manifest.createdAt}-${Ids.newId().takeLast(8)}.zip"
        try {
            BackupArchive.write(fs, path, manifest, attachments::source)
            path
        } catch (e: Exception) { fs.delete(path, mustExist = false); throw e }
    }

    private suspend fun attachmentsMetadata(sha: String) =
        // Metadata is read through the store so database and blob validation stay together.
        attachments.metadata(sha)

    suspend fun backupToFolder() = mutex.withLock {
        try {
            val folder = settings.snapshot.value[Flags.backupFolder]
            require(folder.isNotBlank()) { "Choose a backup folder first" }
            val archive = createUnlocked()
            try {
                bridge.writeBackup(folder, archive, archive.name)
                val now = Clock.System.now().toEpochMilliseconds()
                prefs.put(DevicePrefEntity("backup.last_success", JsonPrimitive(now).toString()))
                prefs.remove("backup.last_error")
                val keep = settings.snapshot.value[Flags.backupRetention]
                bridge.listBackups(folder).filter { it.name.startsWith("FTM-Notes-") && it.name.endsWith(".zip") }
                    .sortedByDescending { it.name }.drop(keep).forEach { bridge.deleteBackup(it.reference) }
            } finally { fs.delete(archive, mustExist = false) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            prefs.put(DevicePrefEntity("backup.last_error", JsonPrimitive(e.message ?: "Backup failed").toString()))
            throw e
        }
    }

    suspend fun restore(archive: Path, conflictSuffix: String): RestoreSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Validate every record and every blob before the first database write.
            val manifest = BackupArchive.read(fs, archive)
            for (blob in manifest.attachments) {
                if (!attachments.verify(blob.sha256)) {
                    val stored = attachments.put(BackupArchive.attachmentSource(fs, archive, blob.sha256), blob.mime)
                    require(stored.sha256 == blob.sha256) { "Backup changed while restoring" }
                }
            }
            transactor.write {
                var copies = 0
                var displaced = 0
                val retained = training?.active()
                val retainedDocument = retained?.let { repository.get(it.sessionId)?.document as? WorkoutSessionDocument }
                val fingerprints = documents.allLivePayloads().mapNotNull { runCatching { FtmFormat.decodeDocument(it).importFingerprint() }.getOrNull() }.toMutableSet()
                for (original in manifest.records) {
                    val incomingSession = if (!original.purged && original.type == DocumentType.WorkoutSession.wire)
                        FtmFormat.decodeDocument(original.payloadJson) as WorkoutSessionDocument else null
                    // A restore cannot replace an ongoing workout. Preserve a different recovered copy as a draft.
                    if (original.id == retained?.sessionId) {
                        if (incomingSession != null && !original.purged && original.deletedAt == null &&
                            incomingSession.importFingerprint() != retainedDocument?.importFingerprint()) {
                            val draft = incomingSession.copy(id = Ids.newId(), status = SessionStatus.Draft, endedAt = null)
                            if (fingerprints.add(draft.importFingerprint())) { repository.save(draft); copies++ }
                        }
                        continue
                    }
                    val recoverActive = retained == null && manifest.training?.sessionId == original.id && incomingSession?.status == SessionStatus.InProgress && original.deletedAt == null
                    val incoming = if (incomingSession?.status == SessionStatus.InProgress && !recoverActive)
                        original.copy(payloadJson = FtmFormat.encodeDocument(incomingSession.copy(status = SessionStatus.Draft))) else original
                    val current = documents.get(incoming.id)?.backupRecord()
                    val merged = DocumentMerger.merge(current, incoming, conflictSuffix)
                    clock.receive(Hlc.parse(incoming.hlc))
                    val record = if (merged.record.payloadJson != incoming.payloadJson && merged.record.payloadJson != current?.payloadJson) {
                        val at = Clock.System.now().toEpochMilliseconds()
                        val stamp = clock.now().encode()
                        val doc = FtmFormat.decodeDocument(merged.record.payloadJson).withMeta(incoming.id,
                            Instant.fromEpochMilliseconds(merged.record.createdAt), Instant.fromEpochMilliseconds(at))
                        merged.record.copy(payloadJson = FtmFormat.encodeDocument(doc), hlc = stamp, updatedAt = at)
                    } else merged.record
                    if (record != current) repository.applyBackupRecord(record)
                    merged.conflictCopy?.let { if (fingerprints.add(it.importFingerprint())) { repository.save(it.copy(id = null)); copies++ } }
                    merged.revision?.let { loser ->
                        revisions.insert(DocumentRevisionEntity("${loser.id}/${loser.hlc}/${loser.payloadJson.contentHash()}", loser.id,
                            loser.payloadJson, loser.hlc, Clock.System.now().toEpochMilliseconds(), loser.deletedAt, loser.purged))
                        revisions.trim(loser.id)
                        displaced++
                    }
                }
                manifest.revisions.forEach {
                    revisions.insert(DocumentRevisionEntity(it.id, it.docId, it.payloadJson, it.hlc, it.createdAt, it.deletedAt, it.purged))
                }
                (manifest.records.map { it.id } + manifest.revisions.map { it.docId }).distinct().forEach { revisions.trim(it) }
                val deviceKeys = Flags.all.filter { it.scope == FlagScope.Device && !it.id.startsWith("backup.") }.map { it.id }.toSet()
                manifest.preferences.filterKeys { it in deviceKeys || it.startsWith("training.plan.") }.forEach { (key, value) ->
                    if (prefs.get(key) == null) prefs.put(DevicePrefEntity(key, value.toString()))
                }
                if (retained == null && training != null) {
                    val recovery = manifest.training
                    val restored = recovery?.let { repository.get(it.sessionId) }
                    if (restored?.deletedAt == null && (restored?.document as? WorkoutSessionDocument)?.status == SessionStatus.InProgress) {
                        training.putActive(ActiveWorkoutEntity(sessionId = recovery!!.sessionId, restEnd = recovery.restEnd,
                            restToken = recovery.restToken, alertedToken = recovery.alertedToken, exerciseId = recovery.exerciseId))
                    }
                }
                RestoreSummary(manifest.records.size, copies, displaced)
            }.also { bridge.workoutChanged(training?.active()?.sessionId) }
        }
    }

    suspend fun exportFtm(): Path = withContext(Dispatchers.IO) {
        val all = documents.all().filterNot { it.purged }.map { FtmFormat.decodeDocument(it.payloadJson) }.toMutableList()
        val liveIds = documents.all().filter { !it.purged && it.deletedAt == null && it.type != DocumentType.Settings.wire }.map { it.id }.toSet()
        val roots = all.filter { it.id in liveIds && (it !is WorkoutSessionDocument || it.status == SessionStatus.Finished) }.mapNotNull { it.id }.toSet()
        val docs = TrainingBundles.exportClosure(roots, all)
        require(TrainingBundles.validateReferences(docs, docs).isEmpty()) { "Export has unresolved Training dependencies" }
        fs.createDirectories(root)
        (root / "FTM-Notes.ftm.json").also { path -> fs.write(path) { writeUtf8(FtmFormat.write(docs)) } }
    }
}
