package io.github.vargfor.ftmnotes.data

import androidx.room3.Room
import io.github.vargfor.ftmnotes.core.model.ChecklistItem
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.core.model.Hlc
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.NoteKind
import io.github.vargfor.ftmnotes.core.model.withOrganisation
import io.github.vargfor.ftmnotes.data.db.AppDatabase
import io.github.vargfor.ftmnotes.data.db.buildFtmDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DocumentRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>().buildFtmDatabase()
    private val dao = db.documentDao()
    private var now = Instant.parse("2026-10-22T10:00:00Z")
    private val transactor = Transactor(db)
    private val repository = newRepository()

    private fun newRepository() =
        DocumentRepository(dao, transactor, DeviceClock(dao) { now.toEpochMilliseconds() }) { now }

    @AfterTest
    fun close() = db.close()

    private suspend fun titles(archived: Boolean = false, tag: String? = null) =
        repository.observeList(DocumentType.Note, archived, tag).first().map { it.title }

    private suspend fun searchTitles(query: String) = repository.search(query).first().map { it.title }

    // ------------------------------------------------------------ basics

    @Test
    fun saveAssignsIdAndTimestampsAndRoundTripsThePayload() = runTest {
        val note = NoteDocument(title = "Packing", kind = NoteKind.Checklist, items = listOf(ChecklistItem("1", "Passport", checked = true)))
        val id = repository.save(note)

        val stored = assertNotNull(repository.get(id))
        assertEquals(note.copy(id = id, createdAt = now, updatedAt = now), stored.document)
        assertEquals(now, stored.createdAt)
    }

    @Test
    fun savingUnchangedContentIsANoOp() = runTest {
        val id = repository.save(NoteDocument(title = "A", content = "x"))
        val firstRow = assertNotNull(dao.get(id))

        now += 5.minutes
        repository.save(assertNotNull(repository.get(id)).document)

        assertEquals(firstRow, dao.get(id))
    }

    @Test
    fun editsKeepCreatedAtAndAdvanceUpdatedAtAndHlc() = runTest {
        val id = repository.save(NoteDocument(title = "A"))
        val first = assertNotNull(dao.get(id))

        now += 5.minutes
        repository.update(id) { (it as NoteDocument).copy(content = "more") }
        val second = assertNotNull(dao.get(id))

        assertEquals(first.createdAt, second.createdAt)
        assertEquals(now.toEpochMilliseconds(), second.updatedAt)
        assertTrue(Hlc.parse(second.hlc) > Hlc.parse(first.hlc))
        assertTrue(second.dirty)
        assertEquals("more", second.plainText)
    }

    // ------------------------------------------------------------ the data-loss bugs fixed in P1a.1

    @Test
    fun savingATrashedNoteDoesNotResurrectIt() = runTest {
        val id = repository.save(NoteDocument(title = "Delete me"))
        repository.moveToTrash(id)

        // A late autosave from an editor that was still open:
        repository.save(NoteDocument(id = id, title = "Delete me", content = "typed just before deleting"))

        assertNotNull(dao.get(id)?.deletedAt, "note must stay in the trash")
        assertEquals(emptyList(), titles())
    }

    @Test
    fun updateKeepsChangesMadeElsewhere() = runTest {
        val id = repository.save(NoteDocument(title = "Shared", content = "v1"))
        // The list pins the note while the editor (holding the old copy) is open…
        repository.update(id) { it.withOrganisation(pinned = true, color = "blue") }
        // …then the editor autosaves only its own fields.
        repository.update(id) { (it as NoteDocument).copy(content = "v2") }

        val doc = assertNotNull(repository.get(id)).document as NoteDocument
        assertEquals("v2", doc.content)
        assertTrue(doc.pinned)
        assertEquals("blue", doc.color)
    }

    @Test
    fun importsKeepTheirOriginalTimestamps() = runTest {
        val created = Instant.parse("2019-03-01T08:00:00Z")
        val edited = Instant.parse("2021-07-15T20:30:00Z")
        val id = repository.importNew(NoteDocument(title = "From Keep"), created, edited, batchId = "batch-1")

        val stored = assertNotNull(repository.get(id))
        assertEquals(created, stored.createdAt)
        assertEquals(edited, stored.updatedAt)
        assertEquals("batch-1", dao.get(id)?.importBatch)
    }

    @Test
    fun clockIsSeededFromTheNewestStoredHlc() = runTest {
        now = Instant.parse("2030-01-01T00:00:00Z") // a device whose clock was far ahead
        val id = repository.save(NoteDocument(title = "future"))
        val stored = Hlc.parse(assertNotNull(dao.get(id)).hlc)

        now = Instant.parse("2026-10-22T10:00:00Z") // app restarts with a correct clock
        val restarted = newRepository()
        val id2 = restarted.save(NoteDocument(title = "later edit"))
        assertTrue(Hlc.parse(assertNotNull(dao.get(id2)).hlc) > stored)
    }

    // ------------------------------------------------------------ lists, trash, purge

    @Test
    fun listIsPinnedFirstThenMostRecentAndSeparatesArchiveAndTrash() = runTest {
        repository.save(NoteDocument(title = "old"))
        now += 1.minutes
        repository.save(NoteDocument(title = "pinned", pinned = true))
        now += 1.minutes
        repository.save(NoteDocument(title = "recent"))
        repository.save(NoteDocument(title = "archived", archived = true))
        val trashed = repository.save(NoteDocument(title = "trashed"))
        repository.moveToTrash(trashed)

        assertEquals(listOf("pinned", "recent", "old"), titles())
        assertEquals(listOf("archived"), titles(archived = true))
        assertEquals(listOf("trashed"), repository.observeTrash(DocumentType.Note).first().map { it.title })

        repository.restore(trashed)
        assertEquals(4, titles().size)
    }

    @Test
    fun purgingLeavesOnlyATombstone() = runTest {
        val id = repository.save(NoteDocument(title = "Secret", content = "pin code", tags = listOf("private")))
        repository.moveToTrash(id)
        now += 31.days
        assertEquals(1, repository.purgeExpiredTrash(retention = 30.days))

        val row = assertNotNull(dao.get(id), "tombstone row is kept for sync and backups")
        assertTrue(row.purged)
        assertFalse(row.payloadJson.contains("pin code"))
        assertNull(repository.get(id))
        assertEquals(emptyList(), repository.observeTrash(DocumentType.Note).first())
        assertEquals(emptyList(), searchTitles("secret"))
        assertEquals(emptyList(), repository.observeTagCounts().first())
    }

    @Test
    fun trashNewerThanRetentionIsKept() = runTest {
        val id = repository.save(NoteDocument(title = "Recently deleted"))
        repository.moveToTrash(id)
        now += 10.days
        assertEquals(0, repository.purgeExpiredTrash(retention = 30.days))
        assertEquals(1, repository.observeTrash(DocumentType.Note).first().size)
    }

    // ------------------------------------------------------------ search

    @Test
    fun searchMatchesTitleBodyAndTagsByPrefixIgnoringAccents() = runTest {
        repository.save(NoteDocument(title = "Café ideas", content = "Try the **banana** bread"))
        repository.save(NoteDocument(title = "Gym", content = "squats", tags = listOf("Training")))
        repository.save(NoteDocument(title = "Unrelated"))

        assertEquals(listOf("Café ideas"), searchTitles("cafe"))
        assertEquals(listOf("Café ideas"), searchTitles("bana"))
        assertEquals(listOf("Gym"), searchTitles("train"))
        assertEquals(listOf("Café ideas"), searchTitles("bread café"))
        assertEquals(emptyList(), searchTitles("\" - AND"))
    }

    @Test
    fun searchFollowsEditsAndExcludesTrash() = runTest {
        val id = repository.save(NoteDocument(title = "Shopping", content = "milk"))
        repository.update(id) { (it as NoteDocument).copy(content = "oat drink") }
        assertEquals(emptyList(), searchTitles("milk"))
        assertEquals(listOf("Shopping"), searchTitles("oat"))

        repository.moveToTrash(id)
        assertEquals(emptyList(), searchTitles("oat"))
        repository.restore(id)
        assertEquals(listOf("Shopping"), searchTitles("oat"))
    }

    // ------------------------------------------------------------ tags

    @Test
    fun tagsAreCaseInsensitiveAndCanBeRenamedMergedAndDeleted() = runTest {
        repository.save(NoteDocument(title = "A", tags = listOf("Work", " work ", "Home")))
        repository.save(NoteDocument(title = "B", tags = listOf("WORK")))
        repository.save(NoteDocument(title = "C", tags = listOf("Job")))

        assertEquals(listOf("A", "B").sorted(), titles(tag = "work").sorted())
        assertEquals(mapOf("home" to 1, "job" to 1, "work" to 2), repository.observeTagCounts().first().associate { it.tagKey to it.count })

        repository.renameTag("job", "Work") // merge
        assertEquals(3, titles(tag = "Work").size)

        repository.deleteTag("work")
        assertEquals(mapOf("home" to 1), repository.observeTagCounts().first().associate { it.tagKey to it.count })
    }

    // ------------------------------------------------------------ transactions

    @Test
    fun aFailingTransactionRollsBackEverything() = runTest {
        try {
            transactor.write {
                repository.save(NoteDocument(title = "half-written", tags = listOf("x")))
                error("boom")
            }
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals(emptyList(), titles())
        assertEquals(emptyList(), searchTitles("half"))
        assertEquals(emptyList(), repository.observeTagCounts().first())
    }

    @Test
    fun applyIncomingStoresTheExactExternalState() = runTest {
        val incomingHlc = Hlc(Instant.parse("2027-01-01T00:00:00Z").toEpochMilliseconds(), 3, "other")
        repository.applyIncoming(
            IncomingDocument(
                document = NoteDocument(id = "remote-1", title = "From backup"),
                hlc = incomingHlc,
                createdAt = Instant.parse("2020-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2020-02-01T00:00:00Z"),
                deletedAt = null,
                purged = false,
            ),
            dirty = false,
        )
        val row = assertNotNull(dao.get("remote-1"))
        assertEquals(incomingHlc.encode(), row.hlc)
        assertFalse(row.dirty)
        // The local clock absorbed the incoming HLC, so the next local edit sorts after it.
        val id = repository.save(NoteDocument(title = "local"))
        assertTrue(Hlc.parse(assertNotNull(dao.get(id)).hlc) > incomingHlc)
    }
}
