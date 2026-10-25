package io.github.vargfor.ftmnotes.core.format

import io.github.vargfor.ftmnotes.core.format.MdLine.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownLinesTest {

    private fun render(vararg lines: MdLine) = MarkdownLines.render(lines.toList())
    private fun item(text: String, ordered: Boolean = false, depth: Int = 0) = MdLine(Kind.ListItem(ordered, depth), text)

    @Test
    fun blankLinesUseTheEditorsEncoding() {
        assertEquals("a\nb", render(MdLine.text("a"), MdLine.text("b")))
        assertEquals("a\n\nb", render(MdLine.text("a"), MdLine.Blank, MdLine.text("b")))
        assertEquals("a\n\n<br>\n<br>\nb", render(MdLine.text("a"), MdLine.Blank, MdLine.Blank, MdLine.Blank, MdLine.text("b")))
    }

    @Test
    fun leadingAndTrailingBlankLinesAreDropped() {
        assertEquals("a", render(MdLine.Blank, MdLine.text("  "), MdLine.text("a"), MdLine.Blank))
        assertEquals("", render(MdLine.Blank))
    }

    @Test
    fun listsDirectlyAfterTextGetABlankLineLikeTheEditorWrites() {
        assertEquals("Intro\n\n- a\n- b\nOutro", render(MdLine.text("Intro"), item("a"), item("b"), MdLine.text("Outro")))
        assertEquals("Intro\n\n- a", render(MdLine.text("Intro"), MdLine.Blank, item("a")))
        // Plain text that already looks like a list counts too.
        assertEquals("Shopping\n\n- milk\n- eggs", MarkdownLines.fromPlainText("Shopping\n- milk\n- eggs"))
    }

    @Test
    fun orderedListsAreNumberedAndNestedItemsIndented() {
        assertEquals(
            "1. one\n    - sub\n    - sub2\n2. two\n3. three",
            render(item("one", true), item("sub", depth = 1), item("sub2", depth = 1), item("two", true), item("three", true)),
        )
        assertEquals("1. a\n\n1. b", render(item("a", true), MdLine.Blank, item("b", true)))
    }

    @Test
    fun headingsAreClampedAndTrimmed() {
        assertEquals("# Title\n###### Deep", render(MdLine(Kind.Heading(1), " Title "), MdLine(Kind.Heading(9), "Deep")))
    }

    @Test
    fun plainTextIsNeverEscaped() {
        assertEquals("2 * 3 and snake_case and <3", MarkdownLines.fromPlainText("2 * 3 and snake_case and <3"))
        assertEquals("a\n\nb", MarkdownLines.fromPlainText("a\r\n\r\nb\r\n"))
    }
}
