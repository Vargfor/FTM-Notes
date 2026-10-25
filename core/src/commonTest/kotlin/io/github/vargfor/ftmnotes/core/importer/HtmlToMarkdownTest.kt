package io.github.vargfor.ftmnotes.core.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlToMarkdownTest {

    /** How Keep marks up one line of text in `textContentHtml`. */
    private fun keepLine(vararg spans: Pair<String, String>, tag: String = "p") =
        "<$tag dir=\"ltr\" style=\"line-height:1.38;margin-top:0.0pt;margin-bottom:0.0pt;\">" +
            spans.joinToString("") { (css, text) ->
                "<span style=\"font-size:7.2pt;font-family:'Google Sans';color:#000000;background-color:transparent;" +
                    "font-weight:400;font-style:normal;font-variant:normal;text-decoration:none;vertical-align:baseline;" +
                    "white-space:pre;white-space:pre-wrap;$css\">$text</span>"
            } + "</$tag>"

    private val bold = "font-weight:700;"
    private val italic = "font-style:italic;"
    private val underline = "text-decoration:underline;"

    @Test
    fun keepStyledSpansBecomeMarkdown() {
        val html = keepLine("" to "Plain ", bold to "bold", "" to " and ", italic to "italic", "" to " and ", underline to "under", "" to ".")
        assertEquals("Plain **bold** and *italic* and <u>under</u>.", HtmlToMarkdown.convert(html))
    }

    @Test
    fun markersHugTheWordsNotTheSpaces() {
        assertEquals("**bold** after", HtmlToMarkdown.convert(keepLine(bold to "bold ", "" to "after")))
        assertEquals("before **bold**", HtmlToMarkdown.convert(keepLine("" to "before", bold to " bold")))
    }

    @Test
    fun overlappingStylesNestInTheEditorsOrder() {
        assertEquals("**a *b* c**", HtmlToMarkdown.convert("<b>a <i>b</i> c</b>"))
        assertEquals("***x***", HtmlToMarkdown.convert("<i><b>x</b></i>"))
        assertEquals("**<u>x</u>**", HtmlToMarkdown.convert("<u><b>x</b></u>"))
        assertEquals("*~~x~~*", HtmlToMarkdown.convert("<s><i>x</i></s>"))
    }

    @Test
    fun linesAndBlankLines() {
        val html = keepLine("" to "one") + keepLine("" to "two") + "<br>" + keepLine("" to "three") + "<br><br>" + keepLine("" to "four")
        assertEquals("one\ntwo\n\nthree\n\n<br>\nfour", HtmlToMarkdown.convert(html))
        assertEquals("a\nb\n\nc", HtmlToMarkdown.convert("<p>a<br>b</p><p><br></p><p>c</p>"))
        assertEquals("a\nb", HtmlToMarkdown.convert("<span style=\"white-space:pre-wrap\">a\nb</span>"))
    }

    @Test
    fun headingsDropBoldBecauseTheEditorDoes() {
        assertEquals("# Title\nBody", HtmlToMarkdown.convert(keepLine(bold to "Title", tag = "h1") + keepLine("" to "Body")))
        assertEquals("## *Sub*", HtmlToMarkdown.convert("<h2><i>Sub</i></h2>"))
    }

    @Test
    fun listsAndNestedLists() {
        assertEquals(
            "Intro\n\n- a\n    - a1\n- b\n1. one\n2. two",
            HtmlToMarkdown.convert("<p>Intro</p><ul><li>a<ul><li>a1</li></ul></li><li><p>b</p></li></ul><ol><li>one</li><li>two</li></ol>"),
        )
    }

    @Test
    fun quotesAndCodeBlocksBecomePlainLines() {
        assertEquals("quoted\nline", HtmlToMarkdown.convert("<blockquote><p>quoted</p><p>line</p></blockquote>"))
        assertEquals("val x = 1\n  indented", HtmlToMarkdown.convert("<pre><code>val x = 1\n  indented</code></pre>"))
        assertEquals("Use `npm i` here", HtmlToMarkdown.convert("<p>Use <code>npm i</code> here</p>"))
    }

    @Test
    fun linksKeepTheirTargetAndApplyOuterStyles() {
        assertEquals("See [the docs](https://x.y/a_b)", HtmlToMarkdown.convert("<p>See <a href=\"https://x.y/a_b\">the docs</a></p>"))
        assertEquals("**[t](https://u.v)**", HtmlToMarkdown.convert("<b><a href=\"https://u.v\">t</a></b>"))
        assertEquals("[a (b)](https://w.x/%28y%29%20z)", HtmlToMarkdown.convert("<a href=\"https://w.x/(y) z\">a [b]</a>"))
    }

    @Test
    fun googleDocsBoldWrapperWithNormalWeightIsNotBold() {
        assertEquals("plain **bold**", HtmlToMarkdown.convert("<b style=\"font-weight:normal\"><span>plain </span><span style=\"font-weight:700\">bold</span></b>"))
    }

    @Test
    fun entitiesAreDecodedAndNothingIsEscaped() {
        assertEquals("a < b & c * d_e", HtmlToMarkdown.convert("<p>a &lt; b &amp; c * d_e</p>"))
        assertEquals("x  y", HtmlToMarkdown.convert("<span style=\"white-space:pre-wrap\">x&nbsp; y</span>"))
    }

    @Test
    fun sourceWhitespaceBetweenBlocksIsIgnored() {
        assertEquals("a\nb", HtmlToMarkdown.convert("\n  <p>\n    a\n  </p>\n  <p>b</p>\n"))
        assertEquals("", HtmlToMarkdown.convert("<script>alert(1)</script><style>p{}</style>"))
    }
}
