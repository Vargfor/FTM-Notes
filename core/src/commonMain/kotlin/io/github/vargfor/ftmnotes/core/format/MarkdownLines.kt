package io.github.vargfor.ftmnotes.core.format

/** One line of a note body before it's rendered to our line-based Markdown dialect. */
data class MdLine(val kind: Kind, val text: String = "") {
    sealed interface Kind {
        /** Ordinary text. Text that already looks like a Markdown list item is treated as one. */
        data object Text : Kind
        data class Heading(val level: Int) : Kind
        data class ListItem(val ordered: Boolean, val depth: Int = 0) : Kind
        data object Blank : Kind
    }

    companion object {
        val Blank = MdLine(Kind.Blank)
        fun text(text: String) = MdLine(Kind.Text, text)
    }
}

/**
 * Renders [MdLine]s the way the rich-text editor itself writes Markdown, so imported notes load
 * and save without drifting (pinned by `MarkdownRoundTripTest` in `:shared`):
 * - one blank line is `\n\n`; every further blank line is a `<br>` line;
 * - a list directly after text gets a blank line before it (the editor always inserts one);
 * - ordered lists are numbered 1, 2, 3… and nested items are indented four spaces;
 * - leading and trailing blank lines are dropped.
 */
object MarkdownLines {
    private val listLike = Regex("""^\s*([-*+]|\d+[.)])\s+""")

    fun render(lines: List<MdLine>): String {
        val content = lines
            .map { if (it.text.isBlank()) MdLine.Blank else it }
            .dropWhile { it.kind == MdLine.Kind.Blank }
            .dropLastWhile { it.kind == MdLine.Kind.Blank }

        val out = mutableListOf<String>()
        var blanks = 0
        var previous: MdLine? = null
        val numbers = mutableMapOf<Int, Int>() // depth → last number in the current ordered list

        for (line in content) {
            if (line.kind == MdLine.Kind.Blank) {
                blanks++
                continue
            }
            val isList = line.isList()
            val previousIsList = previous?.isList() == true
            when {
                blanks > 0 -> {
                    out += ""
                    repeat(blanks - 1) { out += "<br>" }
                }
                isList && previous != null && !previousIsList -> out += ""
            }
            if (blanks > 0 || !isList) numbers.clear()
            blanks = 0
            out += when (val kind = line.kind) {
                is MdLine.Kind.Heading -> "#".repeat(kind.level.coerceIn(1, 6)) + " " + line.text.trim()
                is MdLine.Kind.ListItem -> {
                    numbers.keys.removeAll { it > kind.depth }
                    val marker = if (kind.ordered) {
                        val n = (numbers[kind.depth] ?: 0) + 1
                        numbers[kind.depth] = n
                        "$n."
                    } else {
                        numbers.remove(kind.depth)
                        "-"
                    }
                    "    ".repeat(kind.depth) + marker + " " + line.text.trim()
                }
                else -> line.text.trimEnd()
            }
            previous = line
        }
        return out.joinToString("\n")
    }

    private fun MdLine.isList(): Boolean =
        kind is MdLine.Kind.ListItem || (kind == MdLine.Kind.Text && listLike.containsMatchIn(text))

    /** Plain text (one line per line, nothing escaped — see CLAUDE.md) in the same canonical shape. */
    fun fromPlainText(text: String): String =
        render(text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { if (it.isBlank()) MdLine.Blank else MdLine.text(it) })
}
