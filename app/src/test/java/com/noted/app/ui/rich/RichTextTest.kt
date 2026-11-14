package com.noted.app.ui.rich

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.SuggestionSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RichTextTest {

    private fun rich(html: String) = RichText.fromHtml(html)

    private fun RichText.described() = RichDebug.describe(text)

    @Test
    fun splitKeepsMarksAboveAndPlainBelow() {
        val (top, bottom) = rich("<ul><li>ab</li></ul>").split(1)
        assertEquals("bullet0|a", top.described())
        assertEquals("p|b", bottom.described())
        assertEquals(TextRange(1), top.selection)
        assertEquals(TextRange(0), bottom.selection)
    }

    @Test
    fun splitWithSelectionDropsTheSelectedText() {
        val (top, bottom) = rich("<div>abcd</div>").split(1, 3)
        assertEquals("p|a", top.described())
        assertEquals("p|d", bottom.described())
    }

    @Test
    fun splitInsideBoldKeepsBoldBothSides() {
        val (top, bottom) = rich("<div>a<b>bc</b>d</div>").split(2)
        assertEquals("p|a{b}b{/b}", top.described())
        assertEquals("p|{b}c{/b}d", bottom.described())
    }

    @Test
    fun splitKeepsTheLinesBelowTheFirst() {
        val (top, bottom) = rich("<ul><li>ab</li><li>c</li></ul><h2>d</h2>").split(1)
        assertEquals("bullet0|a", top.described())
        assertEquals("p|b\nbullet0|c\nh2|d", bottom.described())
    }

    @Test
    fun splitAtLineStartCanKeepBlockBelow() {
        val (top, bottom) = rich("<div>a</div><h2>b</h2>").split(2, keepBlockBelow = true)
        // Lo de arriba termina con el salto de su renglón: el renglón vacío de abajo es un párrafo.
        assertEquals("a\n", top.plain)
        assertEquals("p|a\np|", top.described())
        assertEquals("p|a", top.dropTrailingNewline().described())
        assertEquals("h2|b", bottom.described())

        val (_, plainBelow) = rich("<div>a</div><h2>b</h2>").split(2)
        assertEquals("p|b", plainBelow.described())
    }

    @Test
    fun splitAtEndKeepsAnEmptyItemAbove() {
        val text = rich("<ul><li>a</li><li><br></li></ul>")
        val (top, bottom) = text.split(text.plain.length)
        assertEquals("bullet0|a\nbullet0|", top.described())
        assertEquals("", bottom.described())
    }

    @Test
    fun joinAddsNewlineOnlyWhenBothHaveText() {
        val joined = joinRich(rich("<div>a</div>"), rich("<h2>b</h2>"))
        assertEquals("p|a\nh2|b", joined.described())
        assertEquals(TextRange(2), joined.selection)

        val withEmptyTop = joinRich(RichText.Empty, rich("<h2>b</h2>"))
        assertEquals("h2|b", withEmptyTop.described())
        assertEquals(TextRange(0), withEmptyTop.selection)

        val withEmptyBottom = joinRich(rich("<ul><li>a</li></ul>"), RichText.Empty)
        assertEquals("bullet0|a", withEmptyBottom.described())
        assertEquals(TextRange(1), withEmptyBottom.selection)
    }

    @Test
    fun joinKeepsAnEmptyLineWithBlockMark() {
        // Una viñeta vacía no es un texto vacío; se conserva con su renglón.
        val emptyItem = rich("<ul><li><br></li></ul>")
        val below = joinRich(emptyItem, rich("<div>b</div>"))
        assertEquals("bullet0|\np|b", below.described())
        assertEquals(TextRange(1), below.selection)

        val above = joinRich(rich("<div>a</div>"), emptyItem)
        assertEquals("p|a\nbullet0|", above.described())
        assertEquals(TextRange(2), above.selection)
    }

    @Test
    fun joinDoesNotStretchTheMarkAboveOverItsEmptyLine() {
        // Arriba termina en un renglón vacío (párrafo): el título no se estira sobre él.
        val joined = joinRich(rich("<h2>a</h2><div><br></div>"), rich("<div>b</div>"))
        assertEquals("h2|a\np|\np|b", joined.described())
        assertEquals("<h2>a</h2><div><br></div><div>b</div>", joined.toHtml())
        val heading = joined.text.getSpans(0, joined.text.length, HeadingMark::class.java).single()
        assertEquals(0 to 2, joined.text.getSpanStart(heading) to joined.text.getSpanEnd(heading))
    }

    @Test
    fun joinUndoesSplit() {
        val text = rich("<ul><li>a<b>bc</b></li></ul><div>d</div>")
        val (top, bottom) = text.split(2, keepBlockBelow = true)
        val joined = joinRich(top, bottom)
        assertEquals("<ul><li>a<b>b</b></li><li><b>c</b></li></ul><div>d</div>", joined.toHtml())
        assertEquals("bullet0|a{b}b{/b}\nbullet0|{b}c{/b}\np|d", joined.described())
    }

    @Test
    fun joinedBoldDoesNotGrowOverTheNewline() {
        val joined = joinRich(rich("<div><b>a</b></div>"), rich("<div>b</div>"))
        assertEquals("p|{b}a{/b}\np|b", joined.described())
    }

    @Test
    fun joinKeepsTwoCodeBlocksApart() {
        val joined = joinRich(rich("<div>x</div><pre>a</pre>"), rich("<pre>b</pre><div>y</div>"))
        assertEquals(
            "<div>x</div><pre spellcheck=\"false\">a</pre><pre spellcheck=\"false\">b</pre><div>y</div>",
            joined.toHtml(),
        )
    }

    @Test
    fun textHasNoRenderSpans() {
        val source = SpannableStringBuilder("abc")
        val bold = BoldMark()
        source.setSpan(bold, 0, 2, INLINE_MARK_FLAGS)
        source.setSpan(object : RenderSpan {}, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(UnderlineSpan(), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        source.setSpan(SuggestionSpan(Locale.ROOT, arrayOf("abd"), 0), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(StyleSpan(Typeface.BOLD), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val text = RichText(source).text
        val spans = text.getSpans(0, text.length, Any::class.java)
        assertEquals(listOf<Any>(bold), spans.toList())
        assertEquals(0 to 2, text.getSpanStart(bold) to text.getSpanEnd(bold))
        assertEquals(INLINE_MARK_FLAGS, text.getSpanFlags(bold))
        assertEquals("abc", RichText(source).plain)
    }

    @Test
    fun dropTrailingNewlineKeepsTheLastLineMark() {
        val text = joinRich(rich("<h2>a</h2>"), rich("<div>b</div>")).split(2).first
        assertEquals("h2|a\np|", text.described())
        val dropped = text.dropTrailingNewline()
        assertEquals("h2|a", dropped.described())
        assertEquals("<h2>a</h2>", dropped.toHtml())
        // Sin salto al final queda igual.
        assertEquals("h2|a", dropped.dropTrailingNewline().described())
    }

    @Test
    fun withEmptyLineAtEndAddsAParagraph() {
        val text = rich("<ul><li><b>a</b></li></ul>").withEmptyLineAtEnd()
        assertEquals("bullet0|{b}a{/b}\np|", text.described())
        assertEquals(TextRange(2), text.selection)
        // Ya termina en un renglón vacío: no se agrega otro.
        assertEquals("bullet0|{b}a{/b}\np|", text.withEmptyLineAtEnd().described())
        assertEquals("", RichText.Empty.withEmptyLineAtEnd().plain)
    }

    @Test
    fun withSelectionAndHtml() {
        val text = rich("<div>ab</div>")
        assertEquals(TextRange(2), text.selection)
        val moved = text.withSelection(TextRange(0, 1))
        assertEquals(TextRange(0, 1), moved.selection)
        assertEquals("<div>ab</div>", moved.toHtml())
        assertTrue(RichText.Empty.plain.isEmpty())
        assertEquals("", RichText.Empty.toHtml())
    }

    @Test
    fun lineBounds() {
        val text = "ab\ncd\n"
        assertEquals(0, lineStartOf(text, 0))
        assertEquals(0, lineStartOf(text, 2))
        assertEquals(3, lineStartOf(text, 3))
        assertEquals(6, lineStartOf(text, 6))
        assertEquals(2, lineEndOf(text, 0))
        assertEquals(5, lineEndOf(text, 3))
        assertEquals(6, lineEndOf(text, 6))
    }

    @Test
    fun concatShiftsInlineMarks() {
        val ab = SpannableStringBuilder("ab").apply { setSpan(BoldMark(), 0, 2, INLINE_MARK_FLAGS) }
        val cd = SpannableStringBuilder("cd").apply { setSpan(ItalicMark(), 0, 2, INLINE_MARK_FLAGS) }
        val joined = RichText(ab).concat(RichText(cd))
        assertEquals("abcd", joined.plain)
        val bold = joined.text.getSpans(0, 4, BoldMark::class.java).single()
        val italic = joined.text.getSpans(0, 4, ItalicMark::class.java).single()
        assertEquals(0 to 2, joined.text.getSpanStart(bold) to joined.text.getSpanEnd(bold))
        assertEquals(2 to 4, joined.text.getSpanStart(italic) to joined.text.getSpanEnd(italic))
        assertEquals(TextRange(2), joined.selection)
    }
}
