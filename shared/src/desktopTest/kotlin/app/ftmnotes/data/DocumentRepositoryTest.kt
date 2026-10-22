package app.ftmnotes.data

import androidx.room3.Room
import app.ftmnotes.core.model.ChecklistItem
import app.ftmnotes.core.model.DocumentType
import app.ftmnotes.core.model.Hlc
import app.ftmnotes.core.model.NoteDocument
import app.ftmnotes.core.model.NoteKind
import app.ftmnotes.data.db.AppDatabase
import app.ftmnotes.data.db.buildFtmDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DocumentRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>().buildFtmDatabase()
    private var now = Instant.parse("2026-10-22T10:00:00Z")
    private val repository = DocumentRepository(db.documentDao(), DeviceClock(db.devicePrefDao())) { now }

    @AfterTest
    fun close() = db.close()

    @Test
    fun saveAssignsIdAndTimestampsAndRoundTripsThePayload() = runTest {
        val note = NoteDocument(
            title = "Packing",
            kind = NoteKind.Checklist,
            items = listOf(ChecklistItem("1", "Passport", checked = true)),
        )
        val id = repository.save(note)

        val stored = assertNotNull(repository.get(id))
        assertEquals(note.copy(id = id, createdAt = now, updatedAt = now), stored.document)
        assertEquals(now, stored.createdAt)
    }

    @Test
    fun savingUnchangedContentIsANoOp() = runTest {
        val id = repository.save(NoteDocument(title = "A", content = "x"))
        val firstRow = assertNotNull(db.documentDao().get(id))

        now += 5.minutes
        val reloaded = assertNotNull(repository.get(id)).document
        repository.save(reloaded)

        assertEquals(firstRow, db.documentDao().get(id))
    }

    @Test
    fun editsKeepCreatedAtAndAdvanceUpdatedAtAndHlc() = runTest {
        val id = repository.save(NoteDocument(title = "A"))
        val first = assertNotNull(db.documentDao().get(id))

        now += 5.minutes
        repository.save((assertNotNull(repository.get(id)).document as NoteDocument).copy(content = "more"))
        val second = assertNotNull(db.documentDao().get(id))

        assertEquals(first.createdAt, second.createdAt)
        assertEquals(now.toEpochMilliseconds(), second.updatedAt)
        assertTrue(Hlc.parse(second.hlc) > Hlc.parse(first.hlc))
        assertTrue(second.dirty)
        assertEquals("more", second.plainText)
    }

    @Test
    fun activeListIsPinnedFirstThenMostRecentAndExcludesTrash() = runTest {
        repository.save(NoteDocument(title = "old"))
        now += 1.minutes
        repository.save(NoteDocument(title = "pinned", pinned = true))
        now += 1.minutes
        repository.save(NoteDocument(title = "recent"))
        now += 1.minutes
        val trashed = repository.save(NoteDocument(title = "trashed"))
        repository.moveToTrash(trashed)

        val titles = repository.observeActive(DocumentType.Note).first().map { it.document.title }
        assertEquals(listOf("pinned", "recent", "old"), titles)
        assertNotNull(db.documentDao().get(trashed)?.deletedAt)

        repository.restore(trashed)
        assertNull(db.documentDao().get(trashed)?.deletedAt)
        assertEquals(4, repository.observeActive(DocumentType.Note).first().size)
    }
}
