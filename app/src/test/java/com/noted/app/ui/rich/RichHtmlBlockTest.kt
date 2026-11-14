package com.jot.app.ui.rich

import android.text.SpannableStringBuilder
import android.text.Spanned
import com.jot.app.data.html.NoteMarkdown
import com.jot.app.data.html.TestBodies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RichHtmlBlockTest {

    /** Ida y vuelta exacta y cómo queda el texto. */
    private fun assertRoundTrip(html: String, described: String) {
        val text = RichHtml.fromHtml(html)
        assertEquals(described, RichDebug.describe(text))
        assertEquals(html, RichHtml.toHtml(text))
    }

    /** Solo se lee: cómo queda el texto y el HTML que sale. */
    private fun assertReads(html: String, described: String, written: String) {
        val text = RichHtml.fromHtml(html)
        assertEquals(described, RichDebug.describe(text))
        assertEquals(written, RichHtml.toHtml(text))
    }

    private inline fun <reified T : BlockMark> Spanned.blockMarks(): List<T> =
        getSpans(0, length, T::class.java).sortedBy { getSpanStart(it) }

    @Test
    fun paragraphsAndEmptyLines() {
        assertRoundTrip("<div>a</div><div><br></div><div><b>c</b></div>", "p|a\np|\np|{b}c{/b}")
        // Un solo renglón vacío sin marca es el texto vacío, que se escribe vacío (como NoteMarkdown).
        assertReads("<div><br></div>", "", "")
    }

    @Test
    fun headingsOneToSix() {
        assertRoundTrip(
            "<h1>a</h1><h2>b</h2><h3>c</h3><h4>d</h4><h5>e</h5><h6>f</h6><h3><br></h3>",
            "h1|a\nh2|b\nh3|c\nh4|d\nh5|e\nh6|f\nh3|",
        )
        assertRoundTrip("<h2><i>x</i> y</h2>", "h2|{i}x{/i} y")
    }

    @Test
    fun nestedBullets() {
        assertRoundTrip(
            "<ul><li>a</li><ul><li>b</li><ul><li>c</li></ul></ul><li>d</li></ul>",
            "bullet0|a\nbullet1|b\nbullet2|c\nbullet0|d",
        )
        assertRoundTrip("<ul><li><br></li></ul>", "bullet0|")
        // Un ítem que salta niveles deja listas sin ítems en el medio, como ListBuilder.
        assertRoundTrip("<ul><li>a</li><ul><ul><li>b</li></ul></ul></ul>", "bullet0|a\nbullet2|b")
    }

    @Test
    fun listInsideLiIsOneLevelDeeper() {
        assertReads(
            "<ul><li>a<ul><li>b</li></ul></li></ul>",
            "bullet0|a\nbullet1|b",
            "<ul><li>a</li><ul><li>b</li></ul></ul>",
        )
    }

    @Test
    fun orderedWithStart() {
        assertRoundTrip("<ol start=\"3\"><li>a</li><li>b</li></ol>", "ordered0@3|a\nordered0|b")
        assertRoundTrip("<ol><li>a</li><li>b</li></ol>", "ordered0|a\nordered0|b")
        assertRoundTrip(
            "<ol start=\"3\"><li>a</li><ol><li>b</li></ol><li>c</li></ol>",
            "ordered0@3|a\nordered1|b\nordered0|c",
        )
        val start = RichHtml.fromHtml("<ol start=\"3\"><li>a</li><li>b</li></ol>").blockMarks<ListMark>().map { it.start }
        assertEquals(listOf(3, null), start)
    }

    @Test
    fun checklistMixedWithBullets() {
        assertRoundTrip(
            "<ul><li>a</li><li data-checked=\"false\">b</li><li data-checked=\"true\">c</li></ul>",
            "bullet0|a\ncheck0|b\ncheck0x|c",
        )
        // Viñeta y número del mismo nivel no van en la misma lista.
        assertRoundTrip("<ul><li>a</li></ul><ol><li>b</li></ol>", "bullet0|a\nordered0|b")
    }

    @Test
    fun headingInsideItem() {
        assertRoundTrip("<ul><li><h2>x</h2></li></ul>", "bullet0+h2|x")
        assertRoundTrip("<ul><li data-checked=\"true\"><h3><br></h3></li></ul>", "check0x+h3|")
    }

    @Test
    fun quoteLinesWithBr() {
        assertRoundTrip("<blockquote>a<br>b</blockquote>", "quote|a\nquote|b")
        val text = RichHtml.fromHtml("<blockquote>a<br>b</blockquote>")
        assertEquals(1, text.blockMarks<QuoteMark>().map { it.group }.distinct().size)
        // El último renglón vacío lleva un <br> más.
        assertRoundTrip("<blockquote>a<br><br></blockquote>", "quote|a\nquote|")
        assertRoundTrip("<blockquote><br></blockquote>", "quote|")
        assertRoundTrip("<blockquote>a<br><br>b</blockquote>", "quote|a\nquote|\nquote|b")
        // Dos citas seguidas no se juntan.
        assertRoundTrip("<blockquote>a</blockquote><blockquote>b</blockquote>", "quote|a\nquote|b")
    }

    @Test
    fun twoCodeBlocksStaySeparate() {
        val html = "<pre spellcheck=\"false\" data-language=\"kotlin\">a\nb</pre><pre spellcheck=\"false\">c</pre>"
        assertRoundTrip(html, "code:kotlin|a\ncode:kotlin|b\ncode|c")
        assertRoundTrip(
            "<pre spellcheck=\"false\">a</pre><pre spellcheck=\"false\">b</pre>",
            "code|a\ncode|b",
        )
        val groups = RichHtml.fromHtml(html).blockMarks<CodeBlockMark>().map { it.group }
        assertEquals(groups[0], groups[1])
        assertNotEquals(groups[1], groups[2])
        // El grupo sale de un contador global: otra lectura no repite grupos.
        val again = RichHtml.fromHtml(html).blockMarks<CodeBlockMark>().map { it.group }
        assertEquals(emptySet<Long>(), groups.toSet() intersect again.toSet())
    }

    @Test
    fun codeBlockEdgeEmptyLines() {
        assertRoundTrip("<pre spellcheck=\"false\"><br>a\n\n</pre>", "code|\ncode|a\ncode|")
        assertRoundTrip("<pre spellcheck=\"false\"><br></pre>", "code|")
        // Adentro del código los espacios no cambian.
        assertRoundTrip("<pre spellcheck=\"false\">  a  b </pre>", "code|  a  b ")
    }

    @Test
    fun divider() {
        assertRoundTrip("<div>a</div><hr><div>b</div>", "p|a\nhr|$DIVIDER_CHAR\np|b")
        val text = RichHtml.fromHtml("<hr>")
        assertEquals(DIVIDER_CHAR.toString(), text.toString())
        assertEquals(1, text.blockMarks<DividerMark>().size)
    }

    @Test
    fun nestedDivsUnwrap() {
        assertReads(
            "<div><div>a</div><div><b>b</b></div></div>",
            "p|a\np|{b}b{/b}",
            "<div>a</div><div><b>b</b></div>",
        )
        assertReads("<div>x<h2>y</h2></div>", "p|x\nh2|y", "<div>x</div><h2>y</h2>")
    }

    @Test
    fun looseLiAndText() {
        assertReads(
            "<li>a</li>suelto<div>b</div>",
            "bullet0|a\np|suelto\np|b",
            "<ul><li>a</li></ul><div>suelto</div><div>b</div>",
        )
    }

    @Test
    fun blankTextNodesAreIgnored() {
        assertReads(
            "<div>a</div>\n  <ul>\n    <li>b</li>\n  </ul>\n<blockquote>c</blockquote>\n",
            "p|a\nbullet0|b\nquote|c",
            "<div>a</div><ul><li>b</li></ul><blockquote>c</blockquote>",
        )
    }

    @Test
    fun brInsideLineSplitsIt() {
        assertReads("<div>a<br>b</div>", "p|a\np|b", "<div>a</div><div>b</div>")
        assertReads("<h2>a<br>b</h2>", "h2|a\nh2|b", "<h2>a</h2><h2>b</h2>")
        assertReads(
            "<ol start=\"3\"><li>a<br>b</li></ol>",
            "ordered0@3|a\nordered0|b",
            "<ol start=\"3\"><li>a</li><li>b</li></ol>",
        )
        // El <br> del final no abre otro renglón.
        assertReads("<div>a<br></div>", "p|a", "<div>a</div>")
    }

    @Test
    fun spacesUseNbspAtEdges() {
        assertEquals("<div>a &nbsp;b</div>", RichHtml.toHtml(SpannableStringBuilder("a  b")))
        assertEquals("<div>&nbsp; x</div>", RichHtml.toHtml(SpannableStringBuilder("  x")))
        assertEquals("<div>x&nbsp;</div>", RichHtml.toHtml(SpannableStringBuilder("x ")))
        assertEquals("<div>&nbsp; &nbsp;</div>", RichHtml.toHtml(SpannableStringBuilder("   ")))
        for (plain in listOf("a  b", "  x", "x ", "   ")) {
            val html = RichHtml.toHtml(SpannableStringBuilder(plain))
            assertEquals(plain, RichHtml.fromHtml(html).toString())
        }
        // También con marcas en el medio: la regla mira el renglón entero.
        val text = SpannableStringBuilder("a  b")
        text.setSpan(BoldMark(), 1, 2, INLINE_MARK_FLAGS)
        assertEquals("<div>a<b> </b>&nbsp;b</div>", RichHtml.toHtml(text))
    }

    @Test
    fun blankLineNeverCollapses() {
        val text = SpannableStringBuilder("a\n\nb")
        text.setSpan(BoldMark(), 2, 2, INLINE_MARK_FLAGS)
        assertEquals("<div>a</div><div><br></div><div>b</div>", RichHtml.toHtml(text))
    }

    @Test
    fun blockMarksAreParagraphSpans() {
        val text = RichHtml.fromHtml("<h2>a</h2><ul><li>b</li><li><br></li></ul>")
        assertEquals("a\nb\n", text.toString())
        val heading = text.blockMarks<HeadingMark>().single()
        assertEquals(0, text.getSpanStart(heading))
        assertEquals(2, text.getSpanEnd(heading))
        assertEquals(Spanned.SPAN_PARAGRAPH, text.getSpanFlags(heading))
        val items = text.blockMarks<ListMark>()
        assertEquals(listOf(2 to 4, 4 to 4), items.map { text.getSpanStart(it) to text.getSpanEnd(it) })
    }

    @Test
    fun sameFormatComparesBlockFields() {
        assertEquals(true, HeadingMark(2).sameFormat(HeadingMark(2)))
        assertEquals(false, HeadingMark(2).sameFormat(HeadingMark(3)))
        assertEquals(true, ListMark(ListKind.Ordered, 1, start = 3).sameFormat(ListMark(ListKind.Ordered, 1, start = 3)))
        assertEquals(false, ListMark(ListKind.Bullet, 0).sameFormat(ListMark(ListKind.Check, 0)))
        assertEquals(false, ListMark(ListKind.Bullet, 0).sameFormat(ListMark(ListKind.Bullet, 1)))
        assertEquals(false, ListMark(ListKind.Check, 0).sameFormat(ListMark(ListKind.Check, 0, checked = true)))
        assertEquals(false, ListMark(ListKind.Ordered, 0).sameFormat(ListMark(ListKind.Ordered, 0, start = 3)))
        assertEquals(true, QuoteMark(1).sameFormat(QuoteMark(1)))
        assertEquals(false, QuoteMark(1).sameFormat(QuoteMark(2)))
        assertEquals(true, CodeBlockMark(1, "kotlin").sameFormat(CodeBlockMark(1, "kotlin")))
        assertEquals(false, CodeBlockMark(1, "kotlin").sameFormat(CodeBlockMark(1, null)))
        assertEquals(false, CodeBlockMark(1, null).sameFormat(CodeBlockMark(2, null)))
        assertEquals(true, DividerMark().sameFormat(DividerMark()))
        assertEquals(false, DividerMark().sameFormat(BoldMark()))
    }

    @Test
    fun canonicalBodyRoundTrip() {
        val html = NoteMarkdown.toHtml(TestBodies.canonicalMarkdown, emptyList())
        // El cuerpo no tiene espacios dobles: no hace falta pasar nada a U+00A0 en el esperado.
        assertEquals(false, html.contains("  "))
        assertEquals(html, RichHtml.toHtml(RichHtml.fromHtml(html)))
    }
}
