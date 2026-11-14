package com.jot.app.ui.rich

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Qué botones de la barra se ven apretados. */
@RunWith(RobolectricTestRunner::class)
class RichActiveFormatsTest {

    private fun active(html: String, start: Int, end: Int = start) =
        RichCommands.activeFormats(RichText.fromHtml(html).withSelection(TextRange(start, end)))

    @Test
    fun plainTextMarksNothing() {
        assertEquals(emptySet<BarFormat>(), active("<div>abc</div>", 1))
    }

    @Test
    fun cursorInsideInlineFormats() {
        assertEquals(setOf(BarFormat.Bold), active("<div>a<b>bc</b>d</div>", 2))
        assertEquals(setOf(BarFormat.Italic), active("<div>a<i>bc</i>d</div>", 2))
        assertEquals(setOf(BarFormat.Strike), active("<div>a<strike>bc</strike>d</div>", 2))
        assertEquals(setOf(BarFormat.Strike), active("<div>a<s>bc</s>d</div>", 2))
        assertEquals(setOf(BarFormat.Code), active("<div>a<code spellcheck=\"false\">bc</code>d</div>", 2))
        assertEquals(setOf(BarFormat.Bold, BarFormat.Italic), active("<div><b><i>ab</i></b></div>", 1))
    }

    @Test
    fun cursorRightAfterBoldFollowsIt() {
        // Lo que se escribe ahí sale en negrita: el botón está prendido.
        assertEquals(setOf(BarFormat.Bold), active("<div>a<b>bc</b>d</div>", 3))
        assertEquals(emptySet<BarFormat>(), active("<div>a<b>bc</b>d</div>", 1))
    }

    @Test
    fun lineFormatsOnTheCursorLine() {
        assertEquals(setOf(BarFormat.Heading), active("<h2>ab</h2>", 1))
        assertEquals(setOf(BarFormat.Heading), active("<h1>ab</h1>", 1))
        assertEquals(setOf(BarFormat.Bullet), active("<ul><li>ab</li></ul>", 1))
        assertEquals(setOf(BarFormat.Ordered), active("<ol><li>ab</li></ol>", 1))
        assertEquals(setOf(BarFormat.Check), active("<ul><li data-checked=\"false\">ab</li></ul>", 1))
        assertEquals(setOf(BarFormat.Check), active("<ul><li data-checked=\"true\">ab</li></ul>", 1))
        assertEquals(setOf(BarFormat.Quote), active("<blockquote>ab</blockquote>", 1))
        // Solo el renglón del cursor.
        assertEquals(emptySet<BarFormat>(), active("<h2>ab</h2><div>cd</div>", 4))
    }

    @Test
    fun headingAndBoldTogether() {
        assertEquals(setOf(BarFormat.Heading, BarFormat.Bold), active("<h2><b>ab</b></h2>", 1))
    }

    @Test
    fun pendingWithoutSelectionCounts() {
        val value = RichText.fromHtml("<div>ab</div>").withSelection(TextRange(1))
        val on = RichCommands.toggleInline(value, InlineFormat.Bold)
        assertEquals(setOf(BarFormat.Bold), RichCommands.activeFormats(on))
        // Otro toque lo apaga.
        assertEquals(emptySet<BarFormat>(), RichCommands.activeFormats(RichCommands.toggleInline(on, InlineFormat.Bold)))

        // Dentro de negrita, tocar B la apaga para lo que se escriba.
        val inside = RichText.fromHtml("<div><b>ab</b></div>").withSelection(TextRange(1))
        assertEquals(emptySet<BarFormat>(), RichCommands.activeFormats(RichCommands.toggleInline(inside, InlineFormat.Bold)))
    }

    @Test
    fun selectionNeedsTheWholeSelection() {
        assertEquals(setOf(BarFormat.Bold), active("<div>a<b>bc</b>d</div>", 1, 3))
        assertEquals(emptySet<BarFormat>(), active("<div>a<b>bc</b>d</div>", 0, 3))
        assertEquals(emptySet<BarFormat>(), active("<div>a<b>bc</b>d</div>", 1, 4))
        // Varios renglones: un tramo por renglón, sin los bordes en blanco (como el botón).
        assertEquals(setOf(BarFormat.Italic), active("<div><i>ab</i></div><div><i>cd</i></div>", 0, 5))
        assertEquals(emptySet<BarFormat>(), active("<div><i>ab</i></div><div>cd</div>", 0, 5))
    }

    @Test
    fun pendingDoesNotCountWithSelection() {
        val value = RichText("ab", TextRange(0, 2), mapOf(InlineFormat.Bold to true))
        assertEquals(emptySet<BarFormat>(), RichCommands.activeFormats(value))
    }

    @Test
    fun lineSelectionNeedsEveryLine() {
        assertEquals(setOf(BarFormat.Bullet), active("<ul><li>a</li><li>b</li></ul>", 0, 3))
        assertEquals(emptySet<BarFormat>(), active("<ul><li>a</li></ul><div>b</div>", 0, 3))
        // Los renglones vacíos no cuentan, como en el botón.
        assertEquals(setOf(BarFormat.Heading), active("<h2>a</h2><div><br></div><h2>b</h2>", 0, 4))
    }

    @Test
    fun codeBlockNeverMarks() {
        val code = "<pre spellcheck=\"false\">ab</pre><div>cd</div>"
        assertEquals(emptySet<BarFormat>(), active(code, 1))
        assertEquals(emptySet<BarFormat>(), active(code, 0, 2))
        // Aunque se haya pedido un formato para lo que se escriba.
        val value = RichText(RichText.fromHtml(code).text, TextRange(1), mapOf(InlineFormat.Bold to true))
        assertEquals(emptySet<BarFormat>(), RichCommands.activeFormats(value))
    }

    @Test
    fun dividerAloneMarksNothing() {
        val divider = RichCommands.insertDivider(RichText.fromHtml("<div>a</div>").withSelection(TextRange(0)))
        assertEquals(emptySet<BarFormat>(), RichCommands.activeFormats(divider.withSelection(TextRange(1, 2))))
    }
}
