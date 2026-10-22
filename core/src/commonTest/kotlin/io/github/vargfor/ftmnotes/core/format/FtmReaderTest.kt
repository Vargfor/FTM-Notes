package io.github.vargfor.ftmnotes.core.format

import io.github.vargfor.ftmnotes.core.model.ChecklistItem
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.NoteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class FtmReaderTest {

    @Test
    fun readsASingleBareDocument() {
        val result = FtmReader.read("""{"type": "note", "title": "Hello", "content": "**hi**"}""")
        assertEquals(emptyList(), result.errors)
        assertEquals(NoteDocument(title = "Hello", content = "**hi**"), result.documents.single())
    }

    @Test
    fun readsAnEnvelopeAndABareArray() {
        val envelope = """{"ftm": 1, "documents": [{"type": "note", "title": "A"}, {"type": "note", "title": "B"}]}"""
        val array = """[{"type": "note", "title": "A"}, {"type": "note", "title": "B"}]"""
        assertEquals(listOf("A", "B"), FtmReader.read(envelope).documents.map { it.title })
        assertEquals(listOf("A", "B"), FtmReader.read(array).documents.map { it.title })
    }

    @Test
    fun stripsCodeFencesAndSurroundingAiProse() {
        val aiAnswer = """
            Sure! Here is your note in FTM format:

            ```json
            {"type": "note", "title": "Groceries", "kind": "checklist",
             "items": [{"text": "Milk"}, {"text": "Eggs", "checked": true}]}
            ```

            Let me know if you need anything else.
        """.trimIndent()
        val result = FtmReader.read(aiAnswer)
        assertEquals(emptyList(), result.errors)
        val note = result.documents.single() as NoteDocument
        assertEquals(NoteKind.Checklist, note.kind)
        assertEquals(listOf(ChecklistItem(text = "Milk"), ChecklistItem(text = "Eggs", checked = true)), note.items)
    }

    @Test
    fun toleratesTypeAliasesEnumCaseStringBooleansAndTrailingCommas() {
        val result = FtmReader.read("""{"type": "Notes", "title": "X", "kind": "CHECKLIST", "pinned": "true",}""")
        assertEquals(emptyList(), result.errors)
        val note = result.documents.single() as NoteDocument
        assertEquals(NoteKind.Checklist, note.kind)
        assertTrue(note.pinned)
        assertTrue(result.issues.any { it.path == "$.type" && it.severity == ReadIssue.Severity.Info })
    }

    @Test
    fun warnsAboutUnknownFieldsButStillImports() {
        val result = FtmReader.read("""{"type": "note", "title": "X", "mood": "happy"}""")
        assertEquals(1, result.documents.size)
        assertEquals(listOf("$.mood"), result.issues.filter { it.severity == ReadIssue.Severity.Warning }.map { it.path })
    }

    @Test
    fun oneBadDocumentDoesNotBlockTheOthers() {
        val result = FtmReader.read("""[{"type": "note", "title": "ok"}, {"title": "no type"}, {"type": "spaceship"}]""")
        assertEquals(listOf("ok"), result.documents.map { it.title })
        assertEquals(listOf("$[1]", "$[2].type"), result.errors.map { it.path })
    }

    @Test
    fun acceptsLooseDateFormats() {
        val result = FtmReader.read(
            """[{"type": "note", "createdAt": "2026-10-22"},
                {"type": "note", "createdAt": "2026-10-22 18:30"},
                {"type": "note", "createdAt": 1761157800000}]"""
        )
        assertEquals(emptyList(), result.errors)
        assertEquals(
            listOf("2026-10-22T00:00:00Z", "2026-10-22T18:30:00Z", "2025-10-22T18:30:00Z"),
            result.documents.map { it.createdAt.toString() },
        )
    }

    @Test
    fun reportsInvalidJsonInPlainLanguage() {
        val result = FtmReader.read("I couldn't produce JSON, sorry.")
        assertTrue(result.documents.isEmpty())
        assertEquals("$", result.errors.single().path)
    }

    @Test
    fun writeThenReadRoundTripsLosslessly() {
        val docs = listOf(
            NoteDocument(
                id = "0199f0a2-0000-7000-8000-000000000001",
                title = "Ideas",
                tags = listOf("home", "diy"),
                pinned = true,
                color = "blue",
                createdAt = Instant.parse("2026-10-22T08:00:00Z"),
                content = "# Heading\n\n- one\n- **two**",
            ),
            NoteDocument(
                title = "Packing",
                kind = NoteKind.Checklist,
                items = listOf(ChecklistItem("i1", "Passport", checked = true), ChecklistItem("i2", "Charger", indent = 1)),
            ),
        )
        val result = FtmReader.read(FtmFormat.write(docs))
        assertEquals(emptyList(), result.issues)
        assertEquals(docs, result.documents)
    }
}
