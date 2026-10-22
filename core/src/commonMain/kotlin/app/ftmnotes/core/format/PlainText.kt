package app.ftmnotes.core.format

import app.ftmnotes.core.model.FtmDocument
import app.ftmnotes.core.model.NoteDocument
import app.ftmnotes.core.model.NoteKind

/** Searchable, preview-friendly text for a document (Markdown syntax stripped). */
fun FtmDocument.plainText(): String = when (this) {
    is NoteDocument -> when (kind) {
        NoteKind.Text -> stripMarkdown(content)
        NoteKind.Checklist -> items.joinToString("\n") { it.text }
    }
}

private val blockPrefix = Regex("""^\s{0,3}(#{1,6}\s+|>\s?|[-*+]\s+\[[ xX]]\s+|[-*+]\s+|\d+[.)]\s+)""")
private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
private val strongOrStrike = Regex("""(\*\*|__|~~)(.+?)\1""")
private val emphasis = Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")
private val inlineNoise = Regex("""</?u>|`""")

internal fun stripMarkdown(markdown: String): String =
    markdown.lineSequence()
        .map { line ->
            line.replace(blockPrefix, "")
                .replace(link, "$1")
                .replace(strongOrStrike, "$2")
                .replace(emphasis) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .replace(inlineNoise, "")
        }
        .joinToString("\n")
        .trim()

/** Stable 64-bit FNV-1a hash as hex. For change detection only — not cryptographic. */
fun String.contentHash(): String {
    var hash = -0x340d631b7bdddcdbL
    for (b in encodeToByteArray()) {
        hash = (hash xor (b.toLong() and 0xff)) * 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
