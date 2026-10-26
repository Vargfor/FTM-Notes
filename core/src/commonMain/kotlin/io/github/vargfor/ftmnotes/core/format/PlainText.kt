package io.github.vargfor.ftmnotes.core.format

import io.github.vargfor.ftmnotes.core.model.FtmDocument
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.NoteKind
import io.github.vargfor.ftmnotes.core.model.SettingsDocument
import io.github.vargfor.ftmnotes.core.model.RecipeDocument
import io.github.vargfor.ftmnotes.core.model.ShoppingListDocument

/** Searchable, preview-friendly text for a document (Markdown syntax stripped). */
fun FtmDocument.plainText(): String = when (this) {
    is RecipeDocument -> (listOf(description, notes, sourceUrl.orEmpty(), sourceName.orEmpty()) +
        ingredientGroups.flatMap { group -> listOf(group.name.orEmpty()) + group.items.map { it.raw + " " + it.name + " " + it.note } } + steps.map { it.text }).joinToString("\n")
    is ShoppingListDocument -> items.filterNot { it.deleted }.joinToString("\n") { it.text }
    is NoteDocument -> when (kind) {
        NoteKind.Text -> stripMarkdown(content)
        NoteKind.Checklist -> items.filterNot { it.deleted }.joinToString("\n") { it.text }
    }
    is SettingsDocument -> ""
}

/**
 * Denormalised data the notes list needs, computed once per save so the list never has to decode
 * payloads. Checklist previews list unchecked items first, one per line, prefixed with
 * [UNCHECKED_MARK] or [CHECKED_MARK].
 */
data class ListProjection(
    val preview: String,
    val itemCount: Int,
    val checkedCount: Int,
    val thumbSha: String?,
)

const val UNCHECKED_MARK = '☐'
const val CHECKED_MARK = '☑'
private const val PREVIEW_CHARS = 400
private const val PREVIEW_ITEMS = 8

fun FtmDocument.listProjection(): ListProjection = when (this) {
    is RecipeDocument -> ListProjection((description.ifBlank { ingredientGroups.flatMap { it.items }.joinToString(", ") { it.name.ifBlank { it.raw } } }).take(PREVIEW_CHARS), 0, 0, images.firstOrNull { it.isImage }?.sha256)
    is ShoppingListDocument -> {
        val active = items.filterNot { it.deleted }
        ListProjection((active.filterNot { it.checked } + active.filter { it.checked }).take(PREVIEW_ITEMS).joinToString("\n") {
            "${if (it.checked) CHECKED_MARK else UNCHECKED_MARK} ${it.text}"
        }, active.size, active.count { it.checked }, null)
    }
    is NoteDocument -> {
        val thumb = attachments.firstOrNull { it.isImage }?.sha256
        when (kind) {
            NoteKind.Text -> ListProjection(plainText().take(PREVIEW_CHARS), 0, 0, thumb)
            NoteKind.Checklist -> {
                val active = items.filterNot { it.deleted }
                val ordered = active.filter { !it.checked } + active.filter { it.checked }
                val preview = ordered.take(PREVIEW_ITEMS).joinToString("\n") { item ->
                    "${if (item.checked) CHECKED_MARK else UNCHECKED_MARK} ${item.text.take(120)}"
                }
                ListProjection(preview, active.size, active.count { it.checked }, thumb)
            }
        }
    }
    is SettingsDocument -> ListProjection("", 0, 0, null)
}

private val blockPrefix = Regex("""^\s{0,3}(#{1,6}\s+|>\s?|[-*+]\s+\[[ xX]]\s+|[-*+]\s+|\d+[.)]\s+)""")
private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
private val strongOrStrike = Regex("""(\*\*|__|~~)(.+?)\1""")
private val emphasis = Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")
private val inlineNoise = Regex("""</?u>|<br\s*/?>|`""")
private val escape = Regex("""\\([\\`*_{}\[\]()#+\-.!~<>])""")
private val entities = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ")
private val entity = Regex("""&(amp|lt|gt|quot|#39|nbsp);""")

internal fun stripMarkdown(markdown: String): String =
    markdown.lineSequence()
        .map { line ->
            protectEscapes(line)
                .replace(blockPrefix, "")
                .replace(link, "$1")
                .replace(strongOrStrike, "$2")
                .replace(emphasis) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .replace(inlineNoise, "")
                .replace(entity) { entities.getValue(it.value) }
                .let(::restoreEscapes)
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

// Escaped characters are swapped for private-use code points so the formatting regexes can't
// treat them as syntax, then swapped back as plain characters.
private const val PROTECTED_BASE = 0xE000

private fun protectEscapes(line: String): String =
    line.replace(escape) { (PROTECTED_BASE + it.groupValues[1][0].code).toChar().toString() }

private fun restoreEscapes(line: String): String = buildString(line.length) {
    for (c in line) append(if (c.code in PROTECTED_BASE until PROTECTED_BASE + 0x80) (c.code - PROTECTED_BASE).toChar() else c)
}

/** Stable 64-bit FNV-1a hash as hex. For change detection only — not cryptographic. */
fun String.contentHash(): String {
    var hash = -0x340d631b7bdddcdbL
    for (b in encodeToByteArray()) {
        hash = (hash xor (b.toLong() and 0xff)) * 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
