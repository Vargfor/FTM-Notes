package io.github.vargfor.ftmnotes.data

import androidx.room3.Room
import io.github.vargfor.ftmnotes.core.backup.*
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.di.AppScope
import io.github.vargfor.ftmnotes.platform.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Instant

private class TestFiles(root: Path) : PlatformBridge {
    override val filesRoot = root / "files"
    override val cacheRoot = root / "cache"
    val saved = linkedMapOf<String, ByteArray>()
    var failWrites = false
    override suspend fun writeBackup(folder: String, file: Path, name: String) {
        if (failWrites) throw java.io.IOException("Provider offline")
        saved[name] = FileSystem.SYSTEM.read(file) { readByteArray() }
    }
    override suspend fun listBackups(folder: String) = saved.keys.map { BackupFile(it, it, 0) }
    override suspend fun deleteBackup(reference: String) { saved.remove(reference) }
}

class BackupImportTest {
    private val fs = FileSystem.SYSTEM
    private val root = Files.createTempDirectory("ftm-backup-test").toString().toPath()
    private val bridge = TestFiles(root)
    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>().buildFtmDatabase()
    private val scope = AppScope()
    private val tx = Transactor(db)
    private val clock = DeviceClock(db.documentDao())
    private val repo = DocumentRepository(db.documentDao(), tx, clock)
    private val settings = SettingsRepository(repo, db.devicePrefDao(), clock, scope)
    private val blobs = AttachmentStore(bridge, db.attachmentDao())
    private val backups = BackupService(bridge, blobs, repo, db.documentDao(), db.revisionDao(), db.devicePrefDao(), settings, tx, clock)
    private val imports = ImportCoordinator(bridge, blobs, repo, db.documentDao(), db.importBatchDao(), tx)

    @AfterTest fun close() { scope.cancel(); db.close(); fs.deleteRecursively(root) }

    @Test fun backupWipeRestorePreservesContentPhotosTrashAndSettings() = runTest {
        settings.awaitLoaded()
        val photo = blobs.put(Buffer().writeUtf8("photo fixture"), "image/png", "photo.png")
        val noteId = repo.save(NoteDocument(title = "Photo", content = "**Keep me**", tags = listOf("Home"), attachments = listOf(photo)))
        val trash = repo.save(NoteDocument(title = "Trashed")); repo.moveToTrash(trash)
        settings.set(Flags.theme, ThemeMode.Dark)
        settings.set(Flags.checkedSection, false)
        val original = repo.get(noteId)!!
        val archive = backups.create()
        tx.write {
            db.documentDao().deleteAllTags(); db.documentDao().deleteAllFts(); db.documentDao().deleteAll()
            db.devicePrefDao().all().forEach { db.devicePrefDao().remove(it.key) }
        }
        fs.delete(blobs.path(photo.sha256))
        db.attachmentDao().delete(listOf(photo.sha256))
        val restored = backups.restore(archive, " (restored)")
        assertEquals(3, restored.documents)
        assertEquals(original.document, repo.get(noteId)!!.document)
        assertEquals(original.hlc, repo.get(noteId)!!.hlc)
        assertNotNull(repo.get(trash)!!.deletedAt)
        assertTrue(blobs.verify(photo.sha256))
        assertEquals("\"dark\"", db.devicePrefDao().get(Flags.theme.id))
        assertEquals(false, (repo.get(SettingsDocument.ID)!!.document as SettingsDocument).values[Flags.checkedSection.id]!!.v.toString().toBoolean())
        assertEquals(listOf("Photo"), repo.search("Keep").first().map { it.title })
    }

    @Test fun oldBackupCannotResurrectAPurgedNote() = runTest {
        settings.awaitLoaded()
        val id = repo.save(NoteDocument(title = "Gone", content = "Old"))
        val archive = backups.create()
        repo.moveToTrash(id); repo.deleteForever(id)
        backups.restore(archive, " (restored)")
        assertNull(repo.get(id))
        assertTrue(db.documentDao().get(id)!!.purged)
        assertTrue(repo.search("Gone").first().isEmpty())
    }

    @Test fun repeatedRestoreDoesNotMultiplyConflictCopies() = runTest {
        settings.awaitLoaded()
        val id = repo.save(NoteDocument(title = "Draft", content = "Old words"))
        val archive = backups.create()
        repo.update(id) { (it as NoteDocument).copy(content = "New words") }
        assertEquals(1, backups.restore(archive, " (restored)").conflicts)
        assertEquals(0, backups.restore(archive, " (restored)").conflicts)
        val notes = db.documentDao().all().filter { it.type == "note" && it.deletedAt == null }
        assertEquals(2, notes.size)
        assertEquals("New words", (repo.get(id)!!.document as NoteDocument).content)
    }

    @Test fun corruptAttachmentIsRejectedBeforeAnyDocumentChanges() = runTest {
        settings.awaitLoaded()
        val ref = blobs.put(Buffer().writeUtf8("correct"), "image/png")
        val id = repo.save(NoteDocument(title = "Before", attachments = listOf(ref)))
        val good = backups.create()
        val manifest = BackupArchive.read(fs, good)
        val bad = root / "corrupt.zip"
        fs.sink(bad).buffer().use { sink ->
            ZipWriter(sink).use { zip ->
                val at = Instant.fromEpochMilliseconds(manifest.createdAt)
                zip.add("manifest.json", FtmFormat.writer.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray(), at)
                zip.add("documents.ftm.json", FtmFormat.write(listOf(repo.get(id)!!.document)).encodeToByteArray(), at)
                zip.add("attachments/${ref.sha256}", "corrupt".encodeToByteArray(), at)
            }
        }
        val before = db.documentDao().all()
        assertFailsWith<IllegalArgumentException> { backups.restore(bad, " (restored)") }
        assertEquals(before, db.documentDao().all())
    }

    @Test fun importDuplicatesAreFlaggedAndCommitIsIdempotentAndUndoUsesTrash() = runTest {
        val note = NoteDocument(title = "Same", content = "Text")
        repo.save(note)
        val preview = imports.prepareFtm(FtmFormat.write(listOf(note, NoteDocument(title = "New", content = "Other"))))
        assertTrue(preview.drafts.first().duplicate)
        val chosen = preview.drafts.filterNot { it.duplicate }.map { it.id }.toSet()
        val batch = imports.commit(preview.id, chosen, listOf("Imported"))
        assertEquals(batch, imports.commit(preview.id, chosen, emptyList()))
        assertEquals(2, db.documentDao().all().size)
        assertEquals(1, imports.undo(batch.id))
        assertEquals(0, imports.undo(batch.id))
        val imported = db.documentDao().inBatch(batch.id).single()
        assertNotNull(imported.deletedAt)
        repo.restore(imported.id)
        assertEquals(listOf("Imported"), repo.get(imported.id)!!.document.tags)
    }

    @Test fun failingImportRollsBackDocumentsAndBatch() = runTest {
        var calls = 0
        val failingClock = DeviceClock(db.documentDao()) { if (++calls == 2) error("Injected failure"); 100 }
        val failingRepo = DocumentRepository(db.documentDao(), tx, failingClock)
        val coordinator = ImportCoordinator(bridge, blobs, failingRepo, db.documentDao(), db.importBatchDao(), tx)
        val preview = coordinator.prepareFtm(FtmFormat.write(listOf(NoteDocument(title = "One"), NoteDocument(title = "Two"))))
        assertFailsWith<IllegalStateException> { coordinator.commit(preview.id, preview.drafts.map { it.id }.toSet(), emptyList()) }
        assertTrue(db.documentDao().all().isEmpty())
        assertNull(db.importBatchDao().get(preview.id))
    }

    @Test fun failedBackupNeverPrunesSuccessfulArchives() = runTest {
        settings.awaitLoaded()
        settings.set(Flags.backupFolder, "chosen")
        settings.observe(Flags.backupFolder).first { it == "chosen" }
        repo.save(NoteDocument(title = "Backup"))
        repeat(9) { backups.backupToFolder() }
        assertEquals(7, bridge.saved.size)
        val before = bridge.saved.keys.toSet()
        bridge.failWrites = true
        assertFailsWith<java.io.IOException> { backups.backupToFolder() }
        assertEquals(before, bridge.saved.keys.toSet())
        assertNotNull(db.devicePrefDao().get("backup.last_error"))
        assertNotNull(db.devicePrefDao().get("backup.last_success"))
    }

    @Test fun listEditsKeepItemClocksAndRecordDeletionWithoutShowingItInSearch() = runTest {
        val id = repo.save(NoteDocument(kind = NoteKind.Checklist, items = listOf(ChecklistItem("a", "Milk"), ChecklistItem("b", "Eggs"))))
        val first = repo.get(id)!!.document as NoteDocument
        repo.update(id) { (it as NoteDocument).copy(pinned = true) }
        assertEquals(first.items, (repo.get(id)!!.document as NoteDocument).items)
        repo.update(id) { (it as NoteDocument).copy(items = it.items.filterNot { item -> item.id == "a" }) }
        val edited = repo.get(id)!!.document as NoteDocument
        assertTrue(edited.items.first { it.id == "a" }.deleted)
        assertTrue(edited.items.first { it.id == "a" }.modifiedHlc!! > first.items.first { it.id == "a" }.modifiedHlc!!)
        assertTrue(repo.search("Milk").first().isEmpty())
        assertEquals(1, db.documentDao().observeList("note", false, null).first().single().itemCount)
    }
}
