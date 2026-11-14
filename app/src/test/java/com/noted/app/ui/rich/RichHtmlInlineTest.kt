package com.noted.app.ui.rich

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.SuggestionSpan
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RichHtmlInlineTest {

    /** Ida y vuelta exacta y, si se pide, cómo queda el texto. */
    private fun assertRoundTrip(html: String, described: String? = null) {
        val text = RichHtml.fromHtml(html)
        if (described != null) assertEquals(described, RichDebug.describe(text))
        assertEquals(html, RichHtml.toHtml(text))
    }

    private fun text(plain: String, vararg marks: Triple<InlineMark, Int, Int>): SpannableStringBuilder {
        val text = SpannableStringBuilder(plain)
        for ((mark, start, end) in marks) text.setSpan(mark, start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        return text
    }

    @Test
    fun boldItalicNested() {
        assertRoundTrip("<div><b><i>x</i></b> y</div>", "p|{b}{i}x{/i}{/b} y")
    }

    @Test
    fun underlineStrikeSubSup() {
        assertRoundTrip(
            "<div><u>a</u><strike>b</strike><sub>c</sub><sup>d</sup></div>",
            "p|{u}a{/u}{strike}b{/strike}{sub}c{/sub}{sup}d{/sup}",
        )
    }

    @Test
    fun sTagIsKept() {
        assertRoundTrip("<div><s>x</s></div>", "p|{s}x{/s}")
    }

    @Test
    fun inlineCode() {
        assertRoundTrip("<div><code spellcheck=\"false\">x</code></div>", "p|{code}x{/code}")
    }

    @Test
    fun linkKeepsAttributes() {
        val html = "<div><a href=\"https://a.com\" class=\"noted-break-all\">a.com</a></div>"
        assertRoundTrip(html, "p|{a=https://a.com}a.com{/a}")
        val link = RichHtml.fromHtml(html).let { it.getSpans(0, it.length, LinkMark::class.java).single() }
        assertEquals(mapOf("class" to "noted-break-all"), link.attrs)
    }

    @Test
    fun linkWithoutHrefStaysWithoutHref() {
        val html = "<div><a data-note-id=\"n1\">Otra nota</a></div>"
        assertRoundTrip(html, "p|{a}Otra nota{/a}")
        val link = RichHtml.fromHtml(html).let { it.getSpans(0, it.length, LinkMark::class.java).single() }
        assertEquals(null, link.href)
    }

    @Test
    fun colorsAndHighlights() {
        assertRoundTrip(
            "<div><span class=\"noted-text-red\">r</span><span class=\"noted-highlight\">v</span>" +
                "<span class=\"noted-highlight-blue\">a</span></div>",
            "p|{color=red}r{/color}{hl=green}v{/hl}{hl=blue}a{/hl}",
        )
    }

    @Test
    fun linkOutsideAndInsideBold() {
        assertRoundTrip("<div><b><a href=\"u\">a</a></b></div>", "p|{b}{a=u}a{/a}{/b}")
        assertRoundTrip("<div><a href=\"u\"><b>a</b></a></div>", "p|{a=u}{b}a{/b}{/a}")
    }

    @Test
    fun markAcrossNewlineIsWrittenPerLine() {
        val text = text("ab\ncd", Triple(BoldMark(), 0, 5))
        assertEquals("<div><b>ab</b></div><div><b>cd</b></div>", RichHtml.toHtml(text))
        assertEquals("p|{b}ab{/b}\np|{b}cd{/b}", RichDebug.describe(text))
    }

    @Test
    fun crossingMarksNest() {
        val text = text("abcde", Triple(BoldMark(), 0, 3), Triple(ItalicMark(), 2, 5))
        assertEquals("<div><b>ab<i>c</i></b><i>de</i></div>", RichHtml.toHtml(text))
        assertEquals("p|{b}ab{i}c{/i}{/b}{i}de{/i}", RichDebug.describe(text))
        assertRoundTrip("<div><b>ab<i>c</i></b><i>de</i></div>")
    }

    @Test
    fun adjacentSameMarksMerge() {
        val text = text("abcd", Triple(BoldMark(), 0, 2), Triple(BoldMark(), 2, 4))
        assertEquals("<div><b>abcd</b></div>", RichHtml.toHtml(text))
    }

    @Test
    fun emptyMarksAreDropped() {
        assertEquals("<div>hola</div>", RichHtml.toHtml(RichHtml.fromHtml("<div><i></i>hola</div>")))
        // Una marca de largo 0 en el texto tampoco se escribe.
        assertEquals("<div>hola</div>", RichHtml.toHtml(text("hola", Triple(BoldMark(), 4, 4))))
    }

    @Test
    fun unknownInlineUnwraps() {
        val text = RichHtml.fromHtml("<div><font color=\"red\">a</font><span>b</span></div>")
        assertEquals("p|ab", RichDebug.describe(text))
        assertEquals("<div>ab</div>", RichHtml.toHtml(text))
        assertEquals(
            "<div>abcd</div>",
            RichHtml.toHtml(RichHtml.fromHtml("<div><big>a</big><small>b</small><center>c</center><span class=\"x\">d</span></div>")),
        )
    }

    @Test
    fun foreignSpansAreIgnored() {
        val text = RichHtml.fromHtml("<div>hola</div>")
        text.setSpan(UnderlineSpan(), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        text.setSpan(SuggestionSpan(Locale.ROOT, arrayOf("hola", "ola"), 0), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals("<div>hola</div>", RichHtml.toHtml(text))
        assertEquals("p|hola", RichDebug.describe(text))
    }

    @Test
    fun readMarksUseInlineFlagsAndDoNotGrowWhileReading() {
        val text = RichHtml.fromHtml("<div><b>a</b>b</div>")
        val bold = text.getSpans(0, text.length, BoldMark::class.java).single()
        assertEquals(0, text.getSpanStart(bold))
        assertEquals(1, text.getSpanEnd(bold))
        assertEquals(Spanned.SPAN_EXCLUSIVE_INCLUSIVE, text.getSpanFlags(bold))
    }

    @Test
    fun sameFormatComparesClassAndFields() {
        assertEquals(true, BoldMark().sameFormat(BoldMark()))
        assertEquals(false, BoldMark().sameFormat(ItalicMark()))
        assertEquals(false, StrikeMark().sameFormat(StrikeMark("s")))
        assertEquals(true, LinkMark("u", mapOf("class" to "c")).sameFormat(LinkMark("u", mapOf("class" to "c"))))
        assertEquals(false, LinkMark("u", emptyMap()).sameFormat(LinkMark("v", emptyMap())))
        assertEquals(false, TextColorMark("red").sameFormat(TextColorMark("blue")))
        assertEquals(true, HighlightMark(null).sameFormat(HighlightMark(null)))
        assertEquals(false, HighlightMark(null).sameFormat(HighlightMark("blue")))
    }

    /** Cada marca que existe: igual a otra igual, distinta de otra con un campo distinto o de otra clase. */
    @Test
    fun sameFormatCoversEveryMark() {
        val pairs: List<Pair<() -> RichMark, RichMark?>> = listOf(
            { BoldMark() } to null,
            { ItalicMark() } to null,
            { UnderlineMark() } to null,
            { SubscriptMark() } to null,
            { SuperscriptMark() } to null,
            { CodeMark() } to null,
            { StrikeMark("strike") } to StrikeMark("s"),
            { LinkMark("u", mapOf("class" to "c")) } to LinkMark("u", mapOf("class" to "d")),
            { LinkMark(null, emptyMap()) } to LinkMark("u", emptyMap()),
            { TextColorMark("red") } to TextColorMark("blue"),
            { HighlightMark("blue") } to HighlightMark(null),
        )
        val all = pairs.map { it.first() }
        for ((make, different) in pairs) {
            val mark = make()
            assertEquals(true, mark.sameFormat(make()))
            if (different != null) assertEquals(false, mark.sameFormat(different))
            for (other in all) if (other::class != mark::class) assertEquals(false, mark.sameFormat(other))
        }
    }

    @Test
    fun linesAndEmptyLines() {
        assertRoundTrip("<div>a</div><div><br></div><div><b>c</b></div>", "p|a\np|\np|{b}c{/b}")
        assertEquals("", RichHtml.toHtml(SpannableStringBuilder()))
    }
}
