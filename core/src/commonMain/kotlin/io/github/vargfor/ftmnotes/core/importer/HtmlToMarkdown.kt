package io.github.vargfor.ftmnotes.core.importer

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import io.github.vargfor.ftmnotes.core.format.MarkdownLines
import io.github.vargfor.ftmnotes.core.format.MdLine

/**
 * Converts rich-text HTML (Keep's `textContentHtml`, later recipe pages) to our line-based
 * Markdown dialect.
 *
 * - Formatting comes from tags (`<b>`, `<i>`, `<u>`, `<s>`, `<code>`, `<a>`) and from inline
 *   styles (`font-weight:700`, `font-style:italic`, `text-decoration:underline|line-through`),
 *   which is how Keep and Google Docs mark it up.
 * - Blocks become lines; `<h1>`–`<h6>` headings, `<ul>`/`<ol>` lists (nested ones indented).
 * - Blockquotes and code blocks become plain lines: the editor can't show them and would drop them.
 * - Nothing is escaped (see CLAUDE.md): the editor mangles backslash escapes.
 */
object HtmlToMarkdown {

    fun convert(html: String): String = MarkdownLines.render(lines(html))

    fun lines(html: String): List<MdLine> {
        val builder = LineBuilder()
        Ksoup.parseBodyFragment(html).body().childNodes().forEach { builder.visit(it, Context()) }
        builder.flush()
        return builder.lines
    }

    private data class Context(
        val style: InlineStyle = InlineStyle(),
        val preserveWhitespace: Boolean = false,
        val inHeading: Boolean = false,
        val listStack: List<Boolean> = emptyList(), // true = ordered, innermost last
    )

    private data class InlineStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val underline: Boolean = false,
        val code: Boolean = false,
        val href: String? = null,
    )

    private data class Run(val text: String, val style: InlineStyle)

    /** `&nbsp;`: editors use it to keep runs of spaces; stored as a plain space. */
    private const val NBSP = ' '

    private val blockTags = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside", "nav",
        "blockquote", "pre", "tr", "dt", "dd", "figure", "figcaption", "address", "hr",
    )
    private val headingTags = (1..6).associateBy { "h$it" }
    private val skippedTags = setOf("script", "style", "head", "title", "img", "svg", "button", "input", "select", "textarea")

    private class LineBuilder {
        val lines = mutableListOf<MdLine>()
        private val runs = mutableListOf<Run>()
        private var kind: MdLine.Kind = MdLine.Kind.Text

        fun visit(node: Node, context: Context) {
            when (node) {
                is TextNode -> text(node.getWholeText(), context)
                is Element -> element(node, context)
                else -> Unit // comments, doctype
            }
        }

        private fun element(element: Element, parent: Context) {
            val tag = element.normalName()
            if (tag in skippedTags) return
            if (tag == "br") {
                lineBreak()
                return
            }
            val context = parent.copy(style = styleOf(element, parent))
                .let { if (tag == "pre" || isPreWhitespace(element)) it.copy(preserveWhitespace = true) else it }
            when {
                tag in headingTags -> block(MdLine.Kind.Heading(headingTags.getValue(tag)), element, context.copy(inHeading = true))
                tag == "ul" || tag == "ol" -> {
                    flush()
                    val nested = context.copy(listStack = context.listStack + (tag == "ol"))
                    element.childNodes().forEach { visit(it, nested) }
                    flush()
                }
                tag == "li" -> {
                    val ordered = context.listStack.lastOrNull() ?: false
                    val depth = (context.listStack.size - 1).coerceAtLeast(0)
                    block(MdLine.Kind.ListItem(ordered, depth), element, context)
                }
                tag in blockTags -> block(MdLine.Kind.Text, element, context)
                tag == "td" || tag == "th" -> {
                    if (runs.isNotEmpty()) runs += Run(" ", context.style)
                    element.childNodes().forEach { visit(it, context) }
                }
                else -> element.childNodes().forEach { visit(it, context) } // inline: span, b, a, font, …
            }
        }

        private fun block(kind: MdLine.Kind, element: Element, context: Context) {
            // `<li><p>text</p></li>` is still a list item.
            val listItem = this.kind.takeIf { it is MdLine.Kind.ListItem && runs.none { run -> run.text.isNotBlank() } }
            flush()
            this.kind = if (kind == MdLine.Kind.Text && listItem != null) listItem else kind
            element.childNodes().forEach { visit(it, context) }
            flush()
        }

        private fun text(raw: String, context: Context) {
            val style = if (context.inHeading) context.style.copy(bold = false) else context.style
            if (context.preserveWhitespace) {
                raw.replace("\r\n", "\n").split('\n').forEachIndexed { index, part ->
                    if (index > 0) lineBreak()
                    if (part.isNotEmpty()) runs += Run(part.replace(NBSP, ' '), style)
                }
            } else {
                val collapsed = raw.replace(Regex("[ \\t\\r\\n\\f]+"), " ").replace(NBSP, ' ')
                // Whitespace at the start of a line is source formatting, not content.
                val text = if (runs.none { it.text.isNotBlank() }) collapsed.trimStart() else collapsed
                if (text.isNotEmpty()) runs += Run(text, style)
            }
        }

        private fun lineBreak() {
            if (runs.any { it.text.isNotBlank() }) flush() else {
                runs.clear()
                lines += MdLine.Blank
            }
        }

        fun flush() {
            val text = renderInline(runs)
            runs.clear()
            if (text.isNotBlank()) lines += MdLine(kind, text)
            kind = MdLine.Kind.Text
        }
    }

    private fun styleOf(element: Element, parent: Context): InlineStyle {
        var style = parent.style
        when (element.normalName()) {
            "b", "strong" -> style = style.copy(bold = true)
            "i", "em", "cite", "var" -> style = style.copy(italic = true)
            "u", "ins" -> style = style.copy(underline = true)
            "s", "strike", "del" -> style = style.copy(strike = true)
            "code", "kbd", "samp", "tt" -> if (!parent.preserveWhitespace) style = style.copy(code = true)
            "a" -> element.attr("href").trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.let { style = style.copy(href = it) }
        }
        val css = element.attr("style").lowercase()
        if (css.isNotEmpty()) {
            cssValue(css, "font-weight")?.let { weight ->
                val numeric = weight.toIntOrNull()
                style = when {
                    weight == "bold" || weight == "bolder" || (numeric != null && numeric >= 600) -> style.copy(bold = true)
                    weight == "normal" || weight == "lighter" || (numeric != null && numeric < 600) -> style.copy(bold = false)
                    else -> style
                }
            }
            cssValue(css, "font-style")?.let { style = style.copy(italic = it == "italic" || it == "oblique") }
            (cssValue(css, "text-decoration") ?: cssValue(css, "text-decoration-line"))?.let { decoration ->
                if ("underline" in decoration) style = style.copy(underline = true)
                if ("line-through" in decoration) style = style.copy(strike = true)
            }
        }
        return style
    }

    private fun isPreWhitespace(element: Element): Boolean =
        cssValue(element.attr("style").lowercase(), "white-space")?.startsWith("pre") == true

    /** Last declaration wins, as in CSS (Keep writes `white-space:pre;white-space:pre-wrap;`). */
    private fun cssValue(css: String, property: String): String? =
        css.split(';').mapNotNull { declaration ->
            val (name, value) = declaration.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            value.trim().takeIf { name.trim() == property }
        }.lastOrNull()

    // ---------------------------------------------------------------- inline rendering

    private enum class Marker(val open: String, val close: String) {
        // Outermost first: the order the editor writes nested formatting in.
        Bold("**", "**"), Italic("*", "*"), Strike("~~", "~~"), Underline("<u>", "</u>");
    }

    private fun InlineStyle.markers(): List<Marker> = buildList {
        if (bold) add(Marker.Bold)
        if (italic) add(Marker.Italic)
        // The editor can't hold strike and underline on the same text; underline wins there.
        if (strike && !underline) add(Marker.Strike)
        if (underline) add(Marker.Underline)
    }

    /** Groups links and code into atomic tokens, then emits properly nested, space-hugging markers. */
    private fun renderInline(runs: List<Run>): String {
        val tokens = mutableListOf<Run>()
        for (run in runs) {
            val last = tokens.lastOrNull()
            val groupKey = run.style.href ?: if (run.style.code) "`code`" else null
            if (last != null && groupKey != null && last.style.href == run.style.href && last.style.code == run.style.code) {
                tokens[tokens.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                tokens += run
            }
        }

        val out = StringBuilder()
        val open = mutableListOf<Marker>()
        var pendingSpace = ""
        // Closes markers (innermost first) until everything still open is wanted.
        fun closeAllBut(wanted: List<Marker>) {
            val keep = open.indices.firstOrNull { open[it] !in wanted } ?: open.size
            while (open.size > keep) out.append(open.removeAt(open.lastIndex).close)
        }

        for (token in tokens) {
            val core = token.text.trim()
            val wanted = token.style.markers()
            if (core.isEmpty()) {
                // A space outside a style ends it; a space inside one (`<b>a <i>b</i></b>`) doesn't.
                closeAllBut(wanted)
                pendingSpace += token.text
                continue
            }
            val leadLength = token.text.length - token.text.trimStart().length
            val lead = token.text.substring(0, leadLength)
            val trail = token.text.substring(leadLength + core.length)

            closeAllBut(wanted)
            out.append(pendingSpace).append(lead)
            wanted.filter { it !in open }.forEach { out.append(it.open); open += it }

            out.append(
                when {
                    token.style.href != null -> "[" + core.replace('[', '(').replace(']', ')') + "](" + token.style.href.urlSafe() + ")"
                    token.style.code -> "`" + core.replace("`", "'") + "`"
                    else -> core
                }
            )
            pendingSpace = trail
        }
        while (open.isNotEmpty()) out.append(open.removeAt(open.lastIndex).close)
        return out.toString().trimEnd()
    }

    private fun String.urlSafe(): String = replace(" ", "%20").replace("(", "%28").replace(")", "%29")
}
