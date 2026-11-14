package com.noted.app.ui.rich

import android.text.SpannableStringBuilder
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RichCommandsTest {

    private fun rich(html: String, start: Int, end: Int = start) =
        RichText.fromHtml(html).withSelection(TextRange(start, end))

    private fun RichText.described() = RichDebug.describe(text)

    @Test
    fun boldOnSelectionTogglesAndKeepsSelection() {
        val value = rich("<div>abc</div>", 0, 2)
        val bold = RichCommands.toggleInline(value, InlineFormat.Bold)
        assertEquals("p|{b}ab{/b}c", bold.described())
        assertEquals(TextRange(0, 2), bold.selection)

        val back = RichCommands.toggleInline(bold, InlineFormat.Bold)
        assertEquals("p|abc", back.described())
        assertEquals(TextRange(0, 2), back.selection)
    }

    @Test
    fun boldOnPartlyBoldSelectionAppliesToAll() {
        val value = rich("<div>a<b>b</b>cd</div>", 0, 3)
        val bold = RichCommands.toggleInline(value, InlineFormat.Bold)
        assertEquals("p|{b}abc{/b}d", bold.described())

        // Sacarlo de una parte deja la negrita de afuera.
        val part = RichCommands.toggleInline(bold.withSelection(TextRange(1, 2)), InlineFormat.Bold)
        assertEquals("p|{b}a{/b}b{b}c{/b}d", part.described())
    }

    @Test
    fun strikeWritesStrikeTag() {
        val strike = RichCommands.toggleInline(rich("<div>ab</div>", 0, 2), InlineFormat.Strike)
        assertEquals("<div><strike>ab</strike></div>", RichHtml.toHtml(strike.text))

        // Un <s> leído cuenta como tachado: el botón lo saca.
        val read = rich("<div><s>ab</s></div>", 0, 2)
        assertTrue(RichCommands.formatAt(read.withSelection(TextRange(1)), InlineFormat.Strike))
        assertEquals("<div>ab</div>", RichHtml.toHtml(RichCommands.toggleInline(read, InlineFormat.Strike).text))
    }

    @Test
    fun buttonWithoutSelectionOnlySetsPending() {
        val value = rich("<div>ab</div>", 1)
        val on = RichCommands.toggleInline(value, InlineFormat.Bold)
        assertEquals("p|ab", on.described())
        assertEquals(TextRange(1), on.selection)
        assertEquals(mapOf(InlineFormat.Bold to true), on.pending)
        assertTrue(RichCommands.formatAt(on, InlineFormat.Bold))

        val off = RichCommands.toggleInline(on, InlineFormat.Bold)
        assertEquals(emptyMap<InlineFormat, Boolean>(), off.pending)
        assertFalse(RichCommands.formatAt(off, InlineFormat.Bold))
    }

    @Test
    fun inlineFormatSkipsCodeLines() {
        // appendCode escribe el <pre> en texto plano: el formato en línea ahí no se guardaría.
        val code = "<pre spellcheck=\"false\">ab</pre><div>cd</div>"
        val inside = rich(code, 0, 2)
        assertEquals("code|ab\np|cd", RichCommands.toggleInline(inside, InlineFormat.Bold).described())
        assertEquals("code|ab\np|cd", RichCommands.code(inside).described())

        val across = rich(code, 0, 5)
        assertEquals("code|ab\np|{b}cd{/b}", RichCommands.toggleInline(across, InlineFormat.Bold).described())

        val cursor = RichCommands.toggleInline(rich(code, 1), InlineFormat.Bold)
        assertEquals(emptyMap<InlineFormat, Boolean>(), cursor.pending)
        assertFalse(RichCommands.formatAt(cursor, InlineFormat.Bold))
    }

    @Test
    fun insideBoldWithoutSelectionTurnsOff() {
        val value = rich("<div>a<b>bc</b>d</div>", 2)
        assertTrue(RichCommands.formatAt(value, InlineFormat.Bold))
        val off = RichCommands.toggleInline(value, InlineFormat.Bold)
        assertEquals(false, off.pending[InlineFormat.Bold])
        assertFalse(RichCommands.formatAt(off, InlineFormat.Bold))
        assertEquals("p|a{b}bc{/b}d", off.described())

        // Al final de la negrita también está prendida (lo que se escribe la sigue).
        assertTrue(RichCommands.formatAt(value.withSelection(TextRange(3)), InlineFormat.Bold))
        assertFalse(RichCommands.formatAt(value.withSelection(TextRange(1)), InlineFormat.Bold))
    }

    @Test
    fun lineFormatReplacesOtherKind() {
        val heading = RichCommands.toggleLine(rich("<ul><li>a</li></ul>", 1), LineFormat.Heading2)
        assertEquals("h2|a", heading.described())
        assertEquals(TextRange(1), heading.selection)

        val back = RichCommands.toggleLine(heading, LineFormat.Heading2)
        assertEquals("p|a", back.described())
    }

    @Test
    fun lineFormatOverMixedSelectionSkipsEmptyLines() {
        val value = rich("<div>a</div><div><br></div><h2>b</h2>", 0, 4)
        assertEquals("p|a\np|\nh2|b", value.described())
        val bullets = RichCommands.toggleLine(value, LineFormat.Bullet)
        assertEquals("bullet0|a\np|\nbullet0|b", bullets.described())
        assertEquals(TextRange(0, 4), bullets.selection)

        // Una selección que termina al principio de un renglón no lo incluye.
        val two = RichCommands.toggleLine(rich("<div>a</div><div>b</div>", 0, 2), LineFormat.Bullet)
        assertEquals("bullet0|a\np|b", two.described())
    }

    @Test
    fun listKeepsDepth() {
        val ordered = RichCommands.toggleLine(rich("<ul><li>a<ul><li>b</li></ul></li></ul>", 3), LineFormat.Ordered)
        assertEquals("bullet0|a\nordered1|b", ordered.described())
    }

    @Test
    fun checklistStartsUnchecked() {
        val check = RichCommands.toggleLine(rich("<div>a</div>", 0), LineFormat.Check)
        assertEquals("check0|a", check.described())

        val fromBullet = RichCommands.toggleLine(rich("<ul><li>a</li></ul>", 0), LineFormat.Check)
        assertEquals("check0|a", fromBullet.described())
        assertEquals("<ul><li data-checked=\"false\">a</li></ul>", RichHtml.toHtml(fromBullet.text))
    }

    @Test
    fun quoteLinesAreOneBlockquote() {
        val quote = RichCommands.toggleLine(rich("<div>a</div><div>b</div>", 0, 3), LineFormat.Quote)
        assertEquals("quote|a\nquote|b", quote.described())
        assertEquals(1, Regex("<blockquote>").findAll(RichHtml.toHtml(quote.text)).count())
    }

    @Test
    fun quoteNextToAQuoteJoinsIt() {
        val above = RichCommands.toggleLine(rich("<blockquote>a</blockquote><div>b</div>", 3), LineFormat.Quote)
        assertEquals("quote|a\nquote|b", above.described())
        assertEquals("<blockquote>a<br>b</blockquote>", RichHtml.toHtml(above.text))

        val below = RichCommands.toggleLine(rich("<div>a</div><blockquote>b</blockquote>", 0), LineFormat.Quote)
        assertEquals("<blockquote>a<br>b</blockquote>", RichHtml.toHtml(below.text))
    }

    @Test
    fun quoteOverMixedSelectionKeepsTheOldQuoteTogether() {
        // `a` queda fuera de la selección; `b` ya era cita y `c` no.
        val value = rich("<blockquote>a<br>b</blockquote><div>c</div>", 2, 5)
        val quote = RichCommands.toggleLine(value, LineFormat.Quote)
        assertEquals("quote|a\nquote|b\nquote|c", quote.described())
        assertEquals(1, Regex("<blockquote>").findAll(RichHtml.toHtml(quote.text)).count())
    }

    @Test
    fun lineFormatSkipsDividers() {
        val value = rich("<div>a</div><hr><div>b</div>", 0, 5)
        assertEquals("bullet0|a\nhr|$DIVIDER_CHAR\nbullet0|b", RichCommands.toggleLine(value, LineFormat.Bullet).described())
    }

    @Test
    fun lineFormatIgnoresCode() {
        val value = rich("<pre spellcheck=\"false\">a</pre>", 1)
        assertEquals("code|a", RichCommands.toggleLine(value, LineFormat.Bullet).described())
        assertEquals("code|a", RichCommands.toggleLine(value, LineFormat.Heading2).described())
    }

    @Test
    fun codeMultilineMakesOneBlock() {
        val value = rich("<div><b>a</b></div><h2>b</h2>", 0, 3)
        val code = RichCommands.code(value)
        assertEquals("code|a\ncode|b\np|", code.described())
        val html = RichHtml.toHtml(code.text)
        assertEquals(1, Regex("<pre spellcheck=\"false\">").findAll(html).count())
    }

    @Test
    fun codeSkipsDividers() {
        val code = RichCommands.code(rich("<div>a</div><hr><div>b</div>", 0, 5))
        assertEquals("code|a\nhr|$DIVIDER_CHAR\ncode|b\np|", code.described())
        assertEquals(
            "<pre spellcheck=\"false\">a</pre><hr><pre spellcheck=\"false\">b</pre><div><br></div>",
            RichHtml.toHtml(code.text),
        )
    }

    @Test
    fun codeTwiceGoesBackToParagraphs() {
        val code = RichCommands.code(rich("<div>a</div><div>b</div>", 0, 3))
        assertEquals("code|a\ncode|b\np|", code.described())
        assertEquals("p|a\np|b\np|", RichCommands.code(code).described())
    }

    @Test
    fun codeOffInTheMiddleSplitsTheBlock() {
        // "b\n" termina al principio de `c`: solo entra el renglón `b`.
        val value = rich("<pre spellcheck=\"false\">a\nb\nc</pre>", 2, 4)
        val code = RichCommands.code(value)
        assertEquals("code|a\np|b\ncode|c", code.described())
        assertEquals(
            "<pre spellcheck=\"false\">a</pre><div>b</div><pre spellcheck=\"false\">c</pre>",
            RichHtml.toHtml(code.text),
        )
    }

    @Test
    fun codeOverCodeAndTextMakesOneNewBlock() {
        val code = RichCommands.code(rich("<pre spellcheck=\"false\">a</pre><div>b</div>", 0, 3))
        assertEquals("code|a\ncode|b\np|", code.described())
        assertEquals(1, Regex("<pre spellcheck=\"false\">").findAll(RichHtml.toHtml(code.text)).count())
    }

    @Test
    fun boldOverLinesSkipsEmptyLinesAndEdgeSpaces() {
        val value = rich("<div> a </div><div><br></div><div>b</div>", 0, 6)
        val bold = RichCommands.toggleInline(value, InlineFormat.Bold)
        assertEquals("p| {b}a{/b} \np|\np|{b}b{/b}", bold.described())
        assertEquals(TextRange(0, 6), bold.selection)
        // Lo que se escriba en el renglón vacío del medio no sale en negrita.
        assertFalse(RichCommands.formatAt(bold.withSelection(TextRange(4)), InlineFormat.Bold))

        // Con esa misma regla ya está toda en negrita: el segundo toque la saca.
        assertEquals("p| a \np|\np|b", RichCommands.toggleInline(bold, InlineFormat.Bold).described())
    }

    @Test
    fun codeSingleLineIsInline() {
        val code = RichCommands.code(rich("<div>abc</div>", 0, 2))
        assertEquals("p|{code}ab{/code}c", code.described())
        assertEquals("p|abc", RichCommands.code(code).described())
    }

    @Test
    fun dividerAddsBlankLineAbove() {
        val value = rich("<div>a</div><div>b</div>", 3)
        val divider = RichCommands.insertDivider(value)
        assertEquals("p|a\np|\nhr|$DIVIDER_CHAR\np|b", divider.described())
        assertEquals(TextRange(6), divider.selection)
        assertEquals("<div>a</div><div><br></div><hr><div>b</div>", RichHtml.toHtml(divider.text))
    }

    @Test
    fun dividerKeepsTheLineFormat() {
        val divider = RichCommands.insertDivider(rich("<div>a</div><h2>b</h2>", 2))
        assertEquals("p|a\np|\nhr|$DIVIDER_CHAR\nh2|b", divider.described())
    }

    @Test
    fun toggleCheckKeepsSelection() {
        val value = rich("<ul><li data-checked=\"false\">a</li><li data-checked=\"false\">b</li></ul>", 0, 1)
        val checked = RichCommands.toggleCheck(value, 2)
        assertEquals("check0|a\ncheck0x|b", checked.described())
        assertEquals(TextRange(0, 1), checked.selection)

        val unchecked = RichCommands.toggleCheck(checked, 3)
        assertEquals("check0|a\ncheck0|b", unchecked.described())
    }

    // ---- atajos con Ctrl ----

    @Test
    fun setHeadingSameLevelRemoves() {
        // Elegir otro nivel lo cambia; el mismo lo saca.
        val h1 = RichCommands.setHeading(rich("<div>ab</div>", 1), 1)
        assertEquals("h1|ab", h1.described())
        assertEquals("<h1>ab</h1>", RichHtml.toHtml(h1.text))
        assertEquals(TextRange(1), h1.selection)

        val h3 = RichCommands.setHeading(h1, 3)
        assertEquals("h3|ab", h3.described())

        assertEquals("p|ab", RichCommands.setHeading(h3, 3).described())
    }

    @Test
    fun setTextColorOnSelection() {
        val red = RichCommands.setTextColor(rich("<div>abc</div>", 0, 2), "red")
        assertEquals("<div><span class=\"noted-text-red\">ab</span>c</div>", RichHtml.toHtml(red.text))
        assertEquals(TextRange(0, 2), red.selection)

        // Otro color reemplaza al de antes; null lo saca.
        val blue = RichCommands.setTextColor(red.withSelection(TextRange(1, 3)), "blue")
        assertEquals("p|{color=red}a{/color}{color=blue}bc{/color}", blue.described())
        assertEquals("p|abc", RichCommands.setTextColor(blue.withSelection(TextRange(0, 3)), null).described())
    }

    @Test
    fun setHighlightGreenHasNoSuffix() {
        // El verde es `noted-highlight` sin sufijo.
        val green = RichCommands.setHighlight(rich("<div>abc</div>", 0, 3), "green")
        assertEquals("<div><span class=\"noted-highlight\">abc</span></div>", RichHtml.toHtml(green.text))

        val red = RichCommands.setHighlight(green.withSelection(TextRange(0, 1)), "red")
        assertEquals(
            "<div><span class=\"noted-highlight-red\">a</span><span class=\"noted-highlight\">bc</span></div>",
            RichHtml.toHtml(red.text),
        )
        assertEquals("p|abc", RichCommands.setHighlight(red.withSelection(TextRange(0, 3)), null).described())
    }

    /** Escribe [typed] en el cursor de [value] como el campo: con su `pending` y su color pendiente. */
    private fun typeInto(value: RichText, typed: String): Pair<SpannableStringBuilder, RichEditRules.Result> {
        val text = SpannableStringBuilder(value.text)
        val at = value.selection.min
        val change = RichEditRules.before(text, at, 0, typed.length)
        text.replace(at, at, typed)
        return text to RichEditRules.after(text, change, value.pending, value.pendingColor)
    }

    @Test
    fun pendingColorAppliesToTyping() {
        // Sin selección, el color queda para lo que se escriba.
        val value = RichCommands.setTextColor(rich("<div>ab</div>", 2), "red")
        assertEquals("p|ab", value.described())
        assertEquals(PendingColor("red", null, clearText = false, clearHighlight = false), value.pendingColor)

        val highlighted = RichCommands.setHighlight(value, "green")
        assertEquals(PendingColor("red", "green", clearText = false, clearHighlight = false), highlighted.pendingColor)
        val (text, _) = typeInto(highlighted, "x")
        assertEquals("p|ab{color=red}{hl=green}x{/hl}{/color}", RichDebug.describe(text))

        // "Sin color" sin selección corta desde el cursor: lo anterior queda.
        val inRed = RichText.fromHtml("<div><span class=\"noted-text-red\">ab</span></div>")
        val cleared = RichCommands.setTextColor(inRed, null)
        assertEquals(PendingColor(null, null, clearText = true, clearHighlight = false), cleared.pendingColor)
        val (plainAfter, _) = typeInto(cleared, "x")
        assertEquals("p|{color=red}ab{/color}x", RichDebug.describe(plainAfter))
    }

    @Test
    fun colorContinuesAfterEnter() {
        // Después de Enter siguen el color y el resaltado (deja sin efecto R43).
        val value = RichText.fromHtml(
            "<div><span class=\"noted-text-red\"><span class=\"noted-highlight-blue\">ab</span></span></div>",
        )
        val (text, result) = typeInto(value, "\n")
        assertEquals(PendingColor("red", "blue", clearText = false, clearHighlight = false), result.pendingColor)

        val change = RichEditRules.before(text, text.length, 0, 1)
        text.append("x")
        RichEditRules.after(text, change, result.pending, result.pendingColor)
        assertEquals(
            "p|{color=red}{hl=blue}ab{/hl}{/color}\np|{color=red}{hl=blue}x{/hl}{/color}",
            RichDebug.describe(text),
        )

        // El color pedido con el cursor también sigue.
        val pendingRed = RichCommands.setTextColor(rich("<div>ab</div>", 2), "red")
        val (_, enter) = typeInto(pendingRed, "\n")
        assertEquals(PendingColor("red", null, clearText = false, clearHighlight = false), enter.pendingColor)
    }

    @Test
    fun removeFormatClearsColorsAndInline() {
        // Saca color, resaltado y el formato en línea; el enlace queda.
        val value = rich(
            "<div><b><span class=\"noted-text-red\">ab</span></b><i><span class=\"noted-highlight\">c</span></i>" +
                "<a href=\"https://x.y\">d</a></div>",
            0, 4,
        )
        val removed = RichCommands.removeFormat(value)
        assertEquals("p|abc{a=https://x.y}d{/a}", removed.described())
        assertEquals(TextRange(0, 4), removed.selection)

        // Sin selección corta el formato alrededor desde el cursor.
        val cursor = RichCommands.removeFormat(rich("<div><b><span class=\"noted-text-red\">ab</span></b></div>", 2))
        assertEquals(false, cursor.pending[InlineFormat.Bold])
        assertEquals(PendingColor(null, null, clearText = true, clearHighlight = true), cursor.pendingColor)
    }

    @Test
    fun moveListItemSwapsWithSibling() {
        // El ítem del cursor va con sus hijos y cambia con el hermano.
        val list = "<ul><li>a</li><li>b<ul><li>c</li></ul></li><li>d</li></ul><div>e</div>"
        val value = rich(list, 3)
        assertEquals("bullet0|a\nbullet0|b\nbullet1|c\nbullet0|d\np|e", value.described())

        val up = RichCommands.moveListItem(value, up = true)
        assertEquals("bullet0|b\nbullet1|c\nbullet0|a\nbullet0|d\np|e", up.described())
        assertEquals(TextRange(1), up.selection)

        val down = RichCommands.moveListItem(value, up = false)
        assertEquals("bullet0|a\nbullet0|d\nbullet0|b\nbullet1|c\np|e", down.described())
        assertEquals(TextRange(5), down.selection)

        // Un hijo cambia con su hermano del mismo nivel, sin salir del padre.
        val nested = rich("<ul><li>a<ul><li>b</li><li>c</li></ul></li></ul>", 4)
        assertEquals("bullet0|a\nbullet1|c\nbullet1|b", RichCommands.moveListItem(nested, up = true).described())
    }

    @Test
    fun moveListItemAtEdgeDoesNothing() {
        val value = rich("<div>p</div><ul><li>a</li><li>b</li></ul><div>q</div>", 2)
        assertSame(value, RichCommands.moveListItem(value, up = true))
        val last = value.withSelection(TextRange(4))
        assertSame(last, RichCommands.moveListItem(last, up = false))
        // Fuera de una lista no hace nada; un hijo no sube sobre su padre.
        val paragraph = rich("<div>p</div><div>q</div>", 0)
        assertSame(paragraph, RichCommands.moveListItem(paragraph, up = false))
        val child = rich("<ul><li>a<ul><li>b</li></ul></li></ul>", 2)
        assertSame(child, RichCommands.moveListItem(child, up = true))
    }
}
