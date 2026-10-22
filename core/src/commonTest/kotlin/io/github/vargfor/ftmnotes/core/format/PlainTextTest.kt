package io.github.vargfor.ftmnotes.core.format

import io.github.vargfor.ftmnotes.core.model.ChecklistItem
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.NoteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlainTextTest {

    @Test
    fun stripsCommonMarkdownSyntax() {
        val markdown = "# Title\n\nSome **bold**, *italic*, ~~gone~~ and <u>under</u> text.\n- item\n1. first\n> quote\n[link](https://x.y)"
        assertEquals(
            "Title\n\nSome bold, italic, gone and under text.\nitem\nfirst\nquote\nlink",
            NoteDocument(content = markdown).plainText(),
        )
    }

    @Test
    fun keepsSnakeCaseAndArithmeticIntact() {
        assertEquals("use snake_case and 2 * 3", NoteDocument(content = "use snake_case and 2 * 3").plainText())
    }

    @Test
    fun checklistPlainTextIsOneItemPerLine() {
        val note = NoteDocument(kind = NoteKind.Checklist, items = listOf(ChecklistItem(text = "Milk"), ChecklistItem(text = "Eggs")))
        assertEquals("Milk\nEggs", note.plainText())
    }

    @Test
    fun contentHashIsStableAndSensitive() {
        assertEquals("abc".contentHash(), "abc".contentHash())
        assertNotEquals("abc".contentHash(), "abd".contentHash())
        assertEquals(16, "".contentHash().length)
    }
}
