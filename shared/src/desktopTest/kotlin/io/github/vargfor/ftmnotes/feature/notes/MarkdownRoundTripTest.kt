package io.github.vargfor.ftmnotes.feature.notes

import com.mohamedrejeb.richeditor.model.RichTextState
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
