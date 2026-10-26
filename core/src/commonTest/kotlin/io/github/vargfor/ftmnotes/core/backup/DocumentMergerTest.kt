package io.github.vargfor.ftmnotes.core.backup

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.model.*
import kotlin.test.*

class DocumentMergerTest {
    private fun record(note: NoteDocument, clock: Long) = BackupRecord("a", "note", FtmFormat.encodeDocument(note.copy(id = "a")), 1, clock, Hlc(clock, 0, "test").encode())
    @Test fun differentTextIsKeptAsASeparateCopy() {
        val local = record(NoteDocument(title = "Local", content = "Our words"), 3)
        val incoming = record(NoteDocument(title = "Other", content = "Their words"), 2)
        val result = DocumentMerger.merge(local, incoming)
        assertEquals(local, result.record)
        assertEquals("Their words", result.conflictCopy!!.content)
        assertNull(result.conflictCopy.id)
    }
    @Test fun checklistUsesItemClocksAndKeepsDeletions() {
        val early = Hlc(1, 0, "a").encode()
        val late = Hlc(4, 0, "a").encode()
        val a = NoteDocument(kind = NoteKind.Checklist, items = listOf(ChecklistItem("x", "Milk", modifiedHlc = early), ChecklistItem("y", "Eggs", modifiedHlc = late, deleted = true)))
        val b = NoteDocument(kind = NoteKind.Checklist, items = listOf(ChecklistItem("x", "Milk", checked = true, modifiedHlc = late), ChecklistItem("y", "Eggs", modifiedHlc = early), ChecklistItem("z", "Bread", modifiedHlc = early)))
        val merged = FtmFormat.decodeDocument(DocumentMerger.merge(record(a, 5), record(b, 3)).record.payloadJson) as NoteDocument
        assertTrue(merged.items.first { it.id == "x" }.checked)
        assertTrue(merged.items.first { it.id == "y" }.deleted)
        assertEquals(3, merged.items.size)
        assertEquals(DocumentMerger.merge(record(a, 5), record(b, 3)).record.payloadJson,
            DocumentMerger.merge(record(b, 3), record(a, 5)).record.payloadJson)
    }
    @Test fun removingAnItemCreatesAStampedTombstone() {
        val note = NoteDocument(kind = NoteKind.Checklist, items = listOf(ChecklistItem("x", "Milk"), ChecklistItem("y", "Eggs")))
        val result = stampChecklistEdits(note, note.copy(items = note.items.drop(1)), "new")
        assertTrue(result.items.first { it.id == "x" }.deleted)
        assertEquals("new", result.items.first { it.id == "x" }.modifiedHlc)
    }
    @Test fun tombstoneWinsAgainstAnOlderBackup() {
        val old = record(NoteDocument(content = "old"), 1)
        val deleted = old.copy(hlc = Hlc(5, 0, "a").encode(), deletedAt = 5, purged = true, payloadJson = """{"type":"note","id":"a"}""")
        assertTrue(DocumentMerger.merge(deleted, old).record.purged)
    }
}
