package io.github.vargfor.ftmnotes.core.importer.keep

import io.github.vargfor.ftmnotes.core.backup.ZipWriter
import io.github.vargfor.ftmnotes.core.format.ReadIssue
import io.github.vargfor.ftmnotes.core.model.ChecklistItem
import io.github.vargfor.ftmnotes.core.model.NoteKind
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class KeepTakeoutParserTest {

    private val fs = FakeFileSystem()
    private val modified = Instant.parse("2026-10-22T12:00:00Z")

    /** Writes a real zip (as Takeout would) and opens it as a source. */
    private fun zip(name: String, files: Map<String, Any>): KeepSource {
        val path = "/$name".toPath()
        ZipWriter(fs.sink(path).buffer()).use { zip ->
            files.forEach { (entry, content) ->
                val bytes = when (content) {
                    is ByteArray -> content
                    else -> content.toString().encodeToByteArray()
                }
                zip.add(entry, bytes, modified)
            }
        }
        return KeepSource.zip(fs, path)
    }

    private fun parse(vararg sources: KeepSource) = KeepTakeoutParser().parse(sources.toList())

    private fun parseOne(json: String): KeepParseResult = parse(zip("takeout.zip", mapOf("Takeout/Keep/note.json" to json)))

    /** A Keep note file with sensible defaults; [fields] are raw JSON members that override them. */
    private fun keepJson(vararg fields: Pair<String, String>): String {
        val defaults = linkedMapOf(
            "color" to "\"DEFAULT\"",
            "isTrashed" to "false",
            "isPinned" to "false",
            "isArchived" to "false",
            "title" to "\"Note\"",
            "userEditedTimestampUsec" to "1700000000123456",
            "createdTimestampUsec" to "1600000000000000",
        )
        fields.forEach { (key, value) -> defaults[key] = value }
        return defaults.entries.joinToString(",\n", "{\n", "\n}") { (key, value) -> "  \"$key\": $value" }
    }

    @Test
    fun aTextNoteWithEveryField() {
        val result = parseOne(
            keepJson(
                "title" to "\"  Weekend plans \"",
                "textContent" to "\"Hike\\nBake bread\"",
                "color" to "\"CERULEAN\"",
                "isPinned" to "true",
                "labels" to "[{\"name\": \"Home\"}, {\"name\": \"home \"}, {\"name\": \"Fun\"}]",
            )
        )
        assertEquals(emptyList(), result.issues)
        val note = result.notes.single()
        val doc = note.document
        assertEquals("Weekend plans", doc.title)
        assertEquals(NoteKind.Text, doc.kind)
        assertEquals("Hike\nBake bread", doc.content)
        assertEquals("cerulean", doc.color)
        assertTrue(doc.pinned)
        assertEquals(false, doc.archived)
        assertEquals(listOf("Home", "Fun"), doc.tags)
        assertEquals(Instant.parse("2020-09-13T12:26:40Z"), note.createdAt)
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_123), note.updatedAt)
        assertEquals("takeout.zip: Takeout/Keep/note.json", note.sourcePath)
    }

    @Test
    fun formattingComesFromTheHtmlWhenItMatchesThePlainText() {
        val html = "<p dir=\\\"ltr\\\"><span style=\\\"font-weight:700;white-space:pre-wrap;\\\">Bold</span><span> rest</span></p><br><p><span>Next</span></p>"
        val doc = parseOne(keepJson("textContent" to "\"Bold rest\\n\\nNext\"", "textContentHtml" to "\"$html\"")).notes.single().document
        assertEquals("**Bold** rest\n\nNext", doc.content)
    }

    @Test
    fun htmlThatDisagreesWithThePlainTextIsNotTrusted() {
        val result = parseOne(keepJson("textContent" to "\"The real text\"", "textContentHtml" to "\"<p>Something else</p>\""))
        assertEquals("The real text", result.notes.single().document.content)
        assertEquals(ReadIssue.Severity.Info, result.issues.single().severity)
    }

    @Test
    fun plainTextIsKeptAsIsWithoutEscaping() {
        val doc = parseOne(keepJson("textContent" to "\"2 * 3 = 6, snake_case, <3\"")).notes.single().document
        assertEquals("2 * 3 = 6, snake_case, <3", doc.content)
    }

    @Test
    fun aChecklist() {
        val doc = parseOne(
            keepJson(
                "listContent" to "[{\"text\": \"Milk\", \"isChecked\": false}, {\"text\": \"  \", \"isChecked\": false}, {\"text\": \"Eggs\", \"isChecked\": true}]",
                "isArchived" to "true",
            )
        ).notes.single().document
        assertEquals(NoteKind.Checklist, doc.kind)
        assertEquals(listOf(ChecklistItem(text = "Milk"), ChecklistItem(text = "Eggs", checked = true)), doc.items)
        assertTrue(doc.archived)
    }

    @Test
    fun webLinksNotAlreadyInTheTextAreAddedAtTheEnd() {
        val doc = parseOne(
            keepJson(
                "textContent" to "\"Read https://a.example/post later\"",
                "annotations" to """[
                    {"description": "", "source": "WEBLINK", "title": "A post", "url": "https://a.example/post"},
                    {"description": "", "source": "WEBLINK", "title": "Recipe [video]", "url": "https://b.example/r"},
                    {"description": "", "source": "WEBLINK", "title": "", "url": "https://c.example/"}
                ]""",
            )
        ).notes.single().document
        assertEquals(
            "Read https://a.example/post later\n\n**Links**\n[Recipe (video)](https://b.example/r)\n[https://c.example/](https://c.example/)",
            doc.content,
        )
    }

    @Test
    fun checklistLinksBecomeItems() {
        val doc = parseOne(
            keepJson(
                "listContent" to "[{\"text\": \"Buy\", \"isChecked\": false}]",
                "annotations" to "[{\"source\": \"WEBLINK\", \"title\": \"Shop\", \"url\": \"https://shop.example\"}]",
            )
        ).notes.single().document
        assertEquals(listOf("Buy", "Shop – https://shop.example"), doc.items.map { it.text })
    }

    @Test
    fun trashedAndEmptyNotesAreSkippedAndCounted() {
        val result = parse(
            zip(
                "takeout.zip",
                mapOf(
                    "Takeout/Keep/a.json" to keepJson("isTrashed" to "true", "textContent" to "\"gone\""),
                    "Takeout/Keep/b.json" to keepJson("title" to "\"\"", "textContent" to "\"\""),
                    "Takeout/Keep/c.json" to keepJson("textContent" to "\"kept\""),
                )
            )
        )
        assertEquals(listOf("kept"), result.notes.map { it.document.content })
        assertEquals(1, result.trashedSkipped)
        assertEquals(1, result.emptySkipped)
    }

    @Test
    fun notesAreFoundByTheirKeysWhateverTheFolderIsCalled() {
        val result = parse(
            zip(
                "export.zip",
                mapOf(
                    "Takeout/Notizen/einkauf.json" to keepJson("textContent" to "\"Brot\""), // localised folder
                    "loose.json" to keepJson("textContent" to "\"re-zipped\""),
                    "Takeout/Keep/Labels.txt" to "Home\nFun",
                    "Takeout/Fit/settings.json" to "{\"units\": \"metric\"}", // another product's JSON
                    "__MACOSX/Takeout/._note.json" to "junk",
                )
            )
        )
        assertEquals(setOf("Brot", "re-zipped"), result.notes.map { it.document.content }.toSet())
        assertEquals(emptyList(), result.issues)
    }

    @Test
    fun invalidJsonIsReportedAndTheRestStillImports() {
        val result = parse(
            zip("t.zip", mapOf("Takeout/Keep/broken.json" to "{\"title\": ", "Takeout/Keep/ok.json" to keepJson("textContent" to "\"fine\"")))
        )
        assertEquals(1, result.notes.size)
        assertEquals("t.zip: Takeout/Keep/broken.json", result.issues.single().path)
        assertEquals(ReadIssue.Severity.Warning, result.issues.single().severity)
    }

    @Test
    fun unknownColoursAreReported() {
        val result = parseOne(keepJson("textContent" to "\"x\"", "color" to "\"SPARKLY\""))
        assertEquals(null, result.notes.single().document.color)
        assertEquals(ReadIssue.Severity.Warning, result.issues.single().severity)
    }

    @Test
    fun attachmentsAreFoundAcrossZipsAndJpegMatchesJpg() {
        val photo = byteArrayOf(1, 2, 3, 4)
        val audio = byteArrayOf(9, 9)
        val first = zip(
            "takeout-001.zip",
            mapOf(
                "Takeout/Keep/trip.json" to keepJson(
                    "textContent" to "\"Photos\"",
                    "attachments" to """[
                        {"filePath": "1a2b.jpeg", "mimetype": "image/jpeg"},
                        {"filePath": "rec.3gp", "mimetype": "audio/3gp"},
                        {"filePath": "missing.png", "mimetype": "image/png"}
                    ]""",
                ),
            )
        )
        val second = zip("takeout-002.zip", mapOf("Takeout/Keep/1a2b.jpg" to photo, "Takeout/Keep/rec.3gp" to audio))

        val result = parse(first, second)
        val note = result.notes.single()
        assertEquals(listOf("1a2b.jpg" to "image/jpeg", "rec.3gp" to "audio/3gp"), note.attachments.map { it.name to it.mimeType })
        assertContentEquals(photo, note.attachments.first().open().buffer().use { it.readByteArray() })
        assertTrue(result.issues.single().message.contains("missing.png"))
    }

    @Test
    fun anAttachmentOnlyNoteIsNotEmpty() {
        val result = parse(
            zip(
                "t.zip",
                mapOf(
                    "Takeout/Keep/pic.json" to keepJson("title" to "\"\"", "textContent" to "\"\"", "attachments" to "[{\"filePath\": \"p.png\", \"mimetype\": \"image/png\"}]"),
                    "Takeout/Keep/p.png" to byteArrayOf(7),
                )
            )
        )
        assertEquals(1, result.notes.size)
        assertEquals(0, result.emptySkipped)
    }

    @Test
    fun anUnzippedFolderWorksToo() {
        fs.createDirectories("/export/Takeout/Keep".toPath())
        fs.write("/export/Takeout/Keep/a.json".toPath()) { writeUtf8(keepJson("textContent" to "\"from a folder\"")) }
        val result = KeepTakeoutParser().parse(listOf(KeepSource(fs, "/export".toPath())))
        assertEquals("Takeout/Keep/a.json", result.notes.single().sourcePath)
    }
}
