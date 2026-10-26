package io.github.vargfor.ftmnotes.core.notes

import io.github.vargfor.ftmnotes.core.model.*
import kotlin.test.*

class NoteEditsTest {
    @Test fun autosaveKeepsConcurrentOrganisationChanges() {
        val original = NoteDocument(title = "Before", content = "Body")
        val current = original.copy(pinned = true, tags = listOf("Work"), color = "blue", archived = true)
        val result = mergeNoteEdits(current, original, original.copy(content = "**Edited**"))
        assertEquals("**Edited**", result.content)
        assertTrue(result.pinned)
        assertTrue(result.archived)
        assertEquals(listOf("Work"), result.tags)
        assertEquals("blue", result.color)
    }
    @Test fun checklistAndAttachmentsSurviveTitleEdit() {
        val original = NoteDocument(kind = NoteKind.Checklist, items = listOf(ChecklistItem("a", "Milk")))
        val current = original.copy(items = listOf(ChecklistItem("a", "Milk", true)), attachments = listOf(AttachmentRef("abc", "image/png")))
        val result = mergeNoteEdits(current, original, original.copy(title = "Shopping"))
        assertEquals(current.items, result.items)
        assertEquals(current.attachments, result.attachments)
        assertEquals("Shopping", result.title)
    }
}
