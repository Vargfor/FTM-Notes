package io.github.vargfor.ftmnotes.feature.notes

import com.mohamedrejeb.richeditor.model.RichTextState
import io.github.vargfor.ftmnotes.core.format.MarkdownLines
import io.github.vargfor.ftmnotes.core.importer.HtmlToMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how compose-rich-editor round-trips our stored Markdown dialect. The editor only re-saves
 * when its output differs from the baseline taken right after loading, so what matters is:
 * 1. output is stable after one pass (never drifts on repeated load/save), and
 * 2. everyday text survives unchanged.
 */
class MarkdownRoundTripTest {

    private fun roundTrip(markdown: String) = RichTextState().setMarkdown(markdown).toMarkdown()

    @Test
    fun everydayTextSurvivesUnchanged() {
        listOf(
            "Plain line",
            "**bold** and *italic* and ~~strike~~ and <u>under</u>",
            "# Heading\nText under",
            "1. first\n2. second",
            "A [link](https://example.com) here",
            "Use `code` inline",
            "Line one\nLine two",
            "Para\n\nAfter blank",
            "a*b*c, snake_case_name, #hashtag, 1984. A year, C:\\Users\\path, **not closed",
            "[x] done and [ ] todo, 5 - 3, a < b > c & d",
        ).forEach { assertEquals(it, roundTrip(it), "should survive unchanged") }
    }

    @Test
    fun outputIsStableAfterOnePass() {
        listOf(
            "## Sub\n- one\n- two\n  - nested",
            "Intro\n- a\n- b\nOutro",
            "Para\n<br>\nAfter blank",
            "> quoted",
            "see https://example.com/a_b_c",
            "2 * 3 = 6",
        ).forEach { input ->
            val first = roundTrip(input)
            assertEquals(first, roundTrip(first), "not stable: $input")
        }
    }

    /** Importers write the editor's own canonical form, so an imported note never changes on first edit. */
    @Test
    fun importerOutputSurvivesTheEditorUnchanged() {
        val fromHtml = listOf(
            "<p><b>Bold</b> plain <i>italic</i> <u>under</u> <s>strike</s> <code>code</code></p>",
            "<b>a <i>b</i> c</b><br><i><b>x</b></i> <u><b>y</b></u> <s><i>z</i></s>",
            "<h1><b>Title</b></h1><p>Body</p><h2>Sub</h2><p>more</p>",
            "<p>one</p><p>two</p><br><p>three</p><br><br><p>four</p>",
            "<p>Intro</p><ul><li>a<ul><li>a1</li></ul></li><li>b</li></ul><ol><li>one</li><li>two</li></ol><p>Outro</p>",
            "<p>See <a href=\"https://x.y/a_b\">the docs</a> and <b><a href=\"https://u.v\">this</a></b></p>",
            "<blockquote>quoted</blockquote><pre>code block\nline two</pre>",
        ).map(HtmlToMarkdown::convert)
        val fromPlainText = listOf(
            "Shopping\n- milk\n- eggs\nDone",
            "Para\n\n\n\nAfter three blank lines",
            "snake_case_name, 5 - 3, a < b, a*b*c", // (a lone " * " is a known quirk, see below)
        ).map(MarkdownLines::fromPlainText)
        val keepLinks = "Text\n\n**Links**\n[A post](https://a.example/post)\n[https://c.example/](https://c.example/)"

        (fromHtml + fromPlainText + keepLinks).forEach { assertEquals(it, roundTrip(it), "importer output changed in the editor") }
    }

    /** Known library quirks — if one of these starts failing, upstream changed behaviour. */
    @Test
    fun knownQuirks() {
        assertEquals("quoted", roundTrip("> quoted")) // blockquotes are not supported: importers flatten them first
        assertEquals("2 *3 = 6", roundTrip("2 * 3 = 6")) // space after a lone '*' is dropped
        assertEquals("2 \\ *3", roundTrip("2 \\* 3")) // escapes get mangled, so importers must not escape
        assertEquals("Intro\n\n- a\n- b\nOutro", roundTrip("Intro\n- a\n- b\nOutro")) // blank line before lists
        assertEquals(
            "see [https://example.com](https://example.com)",
            roundTrip("see https://example.com"),
        ) // bare URLs become explicit links
    }
}
