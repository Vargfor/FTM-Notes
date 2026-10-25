package io.github.vargfor.ftmnotes.core.importer.keep

import io.github.vargfor.ftmnotes.core.format.MarkdownLines
import io.github.vargfor.ftmnotes.core.format.ReadIssue
import io.github.vargfor.ftmnotes.core.format.stripMarkdown
import io.github.vargfor.ftmnotes.core.importer.HtmlToMarkdown
import io.github.vargfor.ftmnotes.core.model.ChecklistItem
import io.github.vargfor.ftmnotes.core.model.NoteColor
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.NoteKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.openZip
import kotlin.time.Instant

/** A folder or zip to read from. Several sources are read as one export (Takeout splits big ones). */
class KeepSource(val fileSystem: FileSystem, val root: Path = "/".toPath(), val label: String = "") {
    companion object {
        /** Opens [zip] (any size; ZIP64 is fine) for reading. */
        fun zip(fileSystem: FileSystem, zip: Path): KeepSource = KeepSource(fileSystem.openZip(zip), label = zip.name)
    }
}

/** A file a note refers to, found in one of the sources. Not read until imported. */
class KeepAttachment(val name: String, val mimeType: String, private val source: KeepSource, private val path: Path) {
    fun open(): Source = source.fileSystem.source(path)
}

/** One note ready to import: [document] has no id and no attachment refs yet (they need the bytes hashed). */
data class KeepNote(
    /** Where it came from, for the preview and for issues. */
    val sourcePath: String,
    val document: NoteDocument,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val attachments: List<KeepAttachment>,
)

data class KeepParseResult(
    val notes: List<KeepNote>,
    /** Notes that were in Keep's trash: not imported. */
    val trashedSkipped: Int,
    /** Notes with no title, text, items or attachments. */
    val emptySkipped: Int,
    val issues: List<ReadIssue>,
)

/** User-facing words the parser writes into notes; the UI passes them localised. */
data class KeepImportOptions(val linksHeading: String = "Links")

/**
 * Reads a Google Keep Takeout export (one or more zips, or an unzipped folder).
 *
 * A `.json` file counts as a note when it has Keep's keys, wherever it sits. Attachments are looked
 * up by file name across **all** sources, also trying `.jpg` for `.jpeg` and back (Takeout lists one
 * and stores the other). Text uses `textContentHtml` when present (keeps bold, headings, …) and falls
 * back to `textContent` if the HTML can't be trusted. Trashed notes are skipped and counted.
 */
class KeepTakeoutParser(private val options: KeepImportOptions = KeepImportOptions()) {

    fun parse(sources: List<KeepSource>): KeepParseResult {
        val issues = mutableListOf<ReadIssue>()
        val files = sources.flatMap { source -> source.regularFiles().map { source to it } }
        val filesByName = files.groupBy { (_, path) -> path.name.lowercase() }
        var trashed = 0
        var empty = 0
        val notes = mutableListOf<KeepNote>()

        for ((source, path) in files.filter { (_, path) -> path.name.endsWith(".json", ignoreCase = true) }) {
            val where = source.describe(path)
            val obj = try {
                KeepJson.json.parseToJsonElement(source.fileSystem.read(path) { readUtf8() }) as? JsonObject
            } catch (e: SerializationException) {
                issues += warning(where, "This file isn't valid JSON, so it was skipped")
                null
            } catch (e: IOException) {
                issues += warning(where, "This file couldn't be read: ${e.message}")
                null
            }
            if (obj == null || !KeepJson.looksLikeKeepNote(obj)) continue

            val keep = try {
                KeepJson.json.decodeFromJsonElement(KeepNoteJson.serializer(), obj)
            } catch (e: SerializationException) {
                issues += warning(where, "This note couldn't be read: ${e.message?.lineSequence()?.firstOrNull()}")
                continue
            }
            if (keep.isTrashed) {
                trashed++
                continue
            }
            val note = convert(keep, where, filesByName, issues)
            if (note == null) empty++ else notes += note
        }
        return KeepParseResult(notes, trashed, empty, issues)
    }

    private fun convert(
        keep: KeepNoteJson,
        where: String,
        filesByName: Map<String, List<Pair<KeepSource, Path>>>,
        issues: MutableList<ReadIssue>,
    ): KeepNote? {
        val attachments = keep.attachments.mapNotNull { ref ->
            resolve(ref, filesByName) ?: null.also {
                issues += warning(where, "Attachment \"${ref.filePath}\" wasn't in the selected files. Select every part of the export.")
            }
        }
        val links = keep.annotations
            .filter { it.source.equals("WEBLINK", ignoreCase = true) && !it.url.isNullOrBlank() }
            .distinctBy { it.url }

        val isChecklist = !keep.listContent.isNullOrEmpty()
        val document = if (isChecklist) {
            val items = keep.listContent.orEmpty()
                .filter { it.text.isNotBlank() }
                .map { ChecklistItem(text = it.text.trimEnd(), checked = it.isChecked) }
            val linkItems = links.newIn(items.joinToString("\n") { it.text }).map { ChecklistItem(text = it.asItemText()) }
            NoteDocument(kind = NoteKind.Checklist, items = items + linkItems)
        } else {
            val body = textBody(keep, where, issues)
            val newLinks = links.newIn(keep.textContent.orEmpty() + keep.textContentHtml.orEmpty())
            NoteDocument(kind = NoteKind.Text, content = withLinks(body, newLinks))
        }.copy(
            title = keep.title.trim(),
            tags = keep.labels.map { it.name.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
            color = keep.color?.let { colorOf(it, where, issues) },
            pinned = keep.isPinned,
            archived = keep.isArchived,
        )

        val isEmpty = document.title.isEmpty() && document.content.isBlank() && document.items.isEmpty() && attachments.isEmpty()
        if (isEmpty) return null
        return KeepNote(
            sourcePath = where,
            document = document,
            createdAt = keep.createdTimestampUsec.toInstant(),
            updatedAt = keep.userEditedTimestampUsec.toInstant(),
            attachments = attachments,
        )
    }

    /** Formatted text from the HTML when it says the same as the plain text; otherwise the plain text. */
    private fun textBody(keep: KeepNoteJson, where: String, issues: MutableList<ReadIssue>): String {
        val plain = keep.textContent?.let(MarkdownLines::fromPlainText).orEmpty()
        val html = keep.textContentHtml?.takeIf { it.isNotBlank() } ?: return plain
        val formatted = runCatching { HtmlToMarkdown.convert(html) }.getOrNull()
        if (formatted == null || (keep.textContent != null && !sameWords(formatted, plain))) {
            issues += ReadIssue(ReadIssue.Severity.Info, where, "The formatting couldn't be read reliably, so this note was imported as plain text")
            return plain
        }
        return formatted
    }

    private fun sameWords(a: String, b: String): Boolean =
        stripMarkdown(a).filterNot(Char::isWhitespace) == stripMarkdown(b).filterNot(Char::isWhitespace)

    private fun withLinks(body: String, links: List<KeepAnnotationJson>): String {
        if (links.isEmpty()) return body
        val section = (listOf("**${options.linksHeading}**") + links.map { it.asMarkdown() }).joinToString("\n")
        return if (body.isBlank()) section else "$body\n\n$section"
    }

    // Keep also lists URLs that are already in the text; only the others need adding.
    private fun List<KeepAnnotationJson>.newIn(text: String) = filter { link -> link.url!!.trimEnd('/') !in text }

    private fun KeepAnnotationJson.label(): String =
        title?.trim()?.takeIf { it.isNotEmpty() }?.replace('[', '(')?.replace(']', ')') ?: url!!

    private fun KeepAnnotationJson.asMarkdown() =
        "[${label()}](${url!!.replace(" ", "%20").replace("(", "%28").replace(")", "%29")})"

    private fun KeepAnnotationJson.asItemText() = if (label() == url) url else "${label()} – $url"

    private fun colorOf(raw: String, where: String, issues: MutableList<ReadIssue>): String? =
        when (val parsed = NoteColor.parse(raw)) {
            is NoteColor.Parsed.Color -> parsed.color.wire
            NoteColor.Parsed.Default -> null
            NoteColor.Parsed.Unknown -> null.also { issues += warning(where, "Unknown colour \"$raw\"; imported without a colour") }
        }

    private fun resolve(ref: KeepAttachmentJson, filesByName: Map<String, List<Pair<KeepSource, Path>>>): KeepAttachment? {
        val name = ref.filePath.substringAfterLast('/').substringAfterLast('\\')
        if (name.isEmpty()) return null
        val candidates = listOf(name) + when {
            name.endsWith(".jpeg", ignoreCase = true) -> listOf(name.dropLast(5) + ".jpg")
            name.endsWith(".jpg", ignoreCase = true) -> listOf(name.dropLast(4) + ".jpeg")
            else -> emptyList()
        }
        val (source, path) = candidates.firstNotNullOfOrNull { filesByName[it.lowercase()]?.firstOrNull() } ?: return null
        return KeepAttachment(path.name, ref.mimetype?.takeIf { it.isNotBlank() } ?: mimeTypeOf(path.name), source, path)
    }

    private fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "3gp" -> "audio/3gpp"
        "m4a", "mp4" -> "audio/mp4"
        "amr" -> "audio/amr"
        else -> "application/octet-stream"
    }

    private fun KeepSource.regularFiles(): List<Path> =
        fileSystem.listRecursively(root)
            .filter { !it.name.startsWith("._") && "__MACOSX" !in it.segments } // macOS zip litter
            .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true }
            .sortedBy { it.toString() }
            .toList()

    private fun KeepSource.describe(path: Path): String {
        val relative = path.relativeTo(root).toString().replace('\\', '/')
        return if (label.isEmpty()) relative else "$label: $relative"
    }

    private fun Long?.toInstant(): Instant? = this?.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it / 1_000) }

    private fun warning(path: String, message: String) = ReadIssue(ReadIssue.Severity.Warning, path, message)
}
