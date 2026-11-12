package com.jot.app.ui.rich

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.text.style.SuggestionSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.text.TextRange
import com.jot.app.ui.rich.RichDebug.describe
import com.jot.app.ui.rich.RichDebug.edit
import com.jot.app.ui.rich.RichDebug.editResult
import com.jot.app.ui.rich.RichDebug.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RichEditRulesTest {

    private class Line(val text: String, val marks: List<BlockMark>)

    private fun l(text: String, vararg marks: BlockMark) = Line(text, marks.toList())

    /** Un texto renglón por renglón, con sus marcas de bloque. */
    private fun doc(vararg lines: Line): SpannableStringBuilder {
        val text = SpannableStringBuilder(lines.joinToString("\n") { it.text })
        var start = 0
        for (line in lines) {
            val end = (start + line.text.length).let { if (it < text.length) it + 1 else it }
            for (mark in line.marks) text.setSpan(mark, start, end, BLOCK_MARK_FLAGS)
            start = end
        }
        return text
    }

    private fun html(fragment: String) = SpannableStringBuilder(RichHtml.fromHtml(fragment))

    private fun bullet(depth: Int) = ListMark(ListKind.Bullet, depth)

    private fun groups(text: Spanned) = text.getSpans(0, text.length, QuoteMark::class.java).map { it.group } +
        text.getSpans(0, text.length, CodeBlockMark::class.java).map { it.group }

    private val bold = mapOf(InlineFormat.Bold to true)

    // ---- Enter ----

    @Test
    fun enterContinuesBulletAndCheck() {
        val check = doc(l("tarea", ListMark(ListKind.Check, 0, checked = true)))
        assertEquals(6, type(check, 5, "\n"))
        assertEquals("check0x|tarea\ncheck0|", describe(check))

        val list = doc(l("a", bullet(1)))
        type(list, 1, "\n")
        assertEquals("bullet1|a\nbullet1|", describe(list))

        // El ítem nuevo no lleva el `start` del primero.
        val ordered = doc(l("a", ListMark(ListKind.Ordered, 0, start = 3)))
        type(ordered, 1, "\n")
        assertEquals("ordered0@3|a\nordered0|", describe(ordered))
    }

    @Test
    fun enterAtStartOfCheckedItemMovesTheCheckDown() {
        // El ítem de arriba queda vacío y sin tildar; el de abajo hereda la tilde.
        val text = doc(l("tarea", ListMark(ListKind.Check, 0, checked = true)))
        type(text, 0, "\n")
        assertEquals("check0|\ncheck0x|tarea", describe(text))
    }

    @Test
    fun enterOnEmptyItemOutdentsThenExits() {
        val text = doc(l("x"), l("", bullet(1)))
        assertEquals(2, type(text, 2, "\n"))
        assertEquals("p|x\nbullet0|", describe(text))
        assertEquals(2, type(text, 2, "\n"))
        assertEquals("p|x\np|", describe(text))
    }

    @Test
    fun enterInQuote() {
        val text = doc(l("a", QuoteMark(newBlockGroup())))
        type(text, 1, "\n")
        assertEquals("quote|a\nquote|", describe(text))
        assertEquals(1, groups(text).toSet().size)
    }

    @Test
    fun enterOnEmptyQuoteLineExits() {
        val group = newBlockGroup()
        val text = doc(l("a", QuoteMark(group)), l("", QuoteMark(group)))
        type(text, 2, "\n")
        assertEquals("quote|a\nquote|\np|", describe(text))
    }

    @Test
    fun enterOnEmptyQuoteLineInTheMiddleSplitsTheQuote() {
        val group = newBlockGroup()
        val text = doc(l("a", QuoteMark(group)), l("", QuoteMark(group)), l("b", QuoteMark(group)))
        type(text, 2, "\n")
        assertEquals("quote|a\nquote|\np|\nquote|b", describe(text))
        assertEquals("<blockquote>a<br><br></blockquote><div><br></div><blockquote>b</blockquote>", RichHtml.toHtml(text))
        // Lo de abajo es otra cita: si se borra el párrafo, no se pegan solas.
        val marks = text.getSpans(0, text.length, QuoteMark::class.java).sortedBy { text.getSpanStart(it) }
        assertTrue(marks.first().group != marks.last().group)
    }

    @Test
    fun enterInCodeNeverExits() {
        val text = doc(l("a", CodeBlockMark(newBlockGroup(), "kotlin")))
        var cursor = 1
        repeat(3) { cursor = type(text, cursor, "\n") }
        assertEquals("code:kotlin|a\ncode:kotlin|\ncode:kotlin|\ncode:kotlin|", describe(text))
        assertEquals(1, groups(text).toSet().size)
    }

    @Test
    fun enterAfterHeadingIsParagraph() {
        val text = doc(l("Tit", HeadingMark(2)))
        val cursor = type(text, 3, "\n")
        type(text, cursor, "x")
        assertEquals("h2|Tit\np|x", describe(text))
    }

    @Test
    fun enterInsideBoldWordSplitsBold() {
        val text = html("<div><b>boton</b></div>")
        assertEquals(3, type(text, 2, "\n"))
        assertEquals("p|{b}bo{/b}\np|{b}ton{/b}", describe(text))
    }

    @Test
    fun typingAtLineStartTakesNextFormat() {
        val text = html("<div><b>boton</b></div>")
        type(text, 2, "\n")
        type(text, 3, "x")
        assertEquals("p|{b}bo{/b}\np|{b}xton{/b}", describe(text))
        assertEquals("<div><b>bo</b></div><div><b>xton</b></div>", RichHtml.toHtml(text))
    }

    @Test
    fun typingInsideAndAfterFormatFollowsTheCharBefore() {
        val text = html("<div><b>ab</b>c</div>")
        type(text, 1, "x")
        type(text, 3, "y")
        assertEquals("p|{b}axby{/b}c", describe(text))
    }

    @Test
    fun pendingBoldAppliesToTyped() {
        val text = html("<div>ab</div>")
        type(text, 1, "x", bold)
        assertEquals("p|a{b}x{/b}b", describe(text))

        val off = html("<div><b>ab</b></div>")
        type(off, 1, "x", mapOf(InlineFormat.Bold to false))
        assertEquals("p|{b}a{/b}x{b}b{/b}", describe(off))
    }

    @Test
    fun enterAtEndOfBoldKeepsBold() {
        // El formato del cursor pasa al pending del renglón nuevo.
        val text = html("<div><b>ab</b></div>")
        val enter = editResult(text, 2, 2, "\n")
        assertEquals(mapOf(InlineFormat.Bold to true), enter.pending)
        type(text, enter.cursor!!, "x", enter.pending)
        assertEquals("p|{b}ab{/b}\np|{b}x{/b}", describe(text))

        // En el medio no hace falta: lo de abajo ya tiene su formato.
        assertEquals(emptyMap<InlineFormat, Boolean>(), editResult(html("<div><b>ab</b>c</div>"), 2, 2, "\n").pending)
    }

    @Test
    fun pendingSurvivesEnter() {
        val text = SpannableStringBuilder("")
        val enter = editResult(text, 0, 0, "\n", bold)
        assertEquals(bold, enter.pending)
        type(text, enter.cursor!!, "x", enter.pending)
        assertEquals("p|\np|{b}x{/b}", describe(text))
    }

    @Test
    fun enterInsideLinkGoesAfterTheLink() {
        // R41, como hoy: el enlace no se parte.
        val text = html("<div><a href=\"u\">abcd</a>z</div>")
        assertEquals(5, type(text, 2, "\n"))
        assertEquals("p|{a=u}abcd{/a}\np|z", describe(text))
    }

    @Test
    fun backspaceBelowDividerRemovesTheDivider() {
        // El texto no se pega al separador; queda con las marcas de su renglón.
        val text = html("<div>a</div><hr><h2>b</h2>")
        assertEquals(2, edit(text, 3, 4, ""))
        assertEquals("<div>a</div><h2>b</h2>", RichHtml.toHtml(text))
    }

    @Test
    fun forwardDeleteAboveDividerRemovesTheDivider() {
        val text = html("<div>a</div><hr><div>b</div>")
        assertEquals(1, edit(text, 1, 2, "", forwardDelete = true))
        assertEquals("<div>a</div><div>b</div>", RichHtml.toHtml(text))
    }

    @Test
    fun backspaceAfterDividerCharRemovesTheDivider() {
        val text = html("<div>a</div><hr><div>b</div>")
        assertEquals(2, edit(text, 2, 3, ""))
        assertEquals("<div>a</div><div><br></div><div>b</div>", RichHtml.toHtml(text))
    }

    @Test
    fun typingOverSelectedDividerIsAParagraph() {
        val text = html("<div>a</div><hr><div>b</div>")
        assertEquals(3, edit(text, 2, 3, "x"))
        assertEquals("<div>a</div><div>x</div><div>b</div>", RichHtml.toHtml(text))
    }

    @Test
    fun joiningTwoParagraphs() {
        val text = doc(l("a"), l("b"))
        assertEquals(1, edit(text, 1, 2, ""))
        assertEquals("p|ab", describe(text))
    }

    @Test
    fun forwardDeleteJoinsInsteadOfOutdenting() {
        // Con Supr se unen los renglones; R9 es solo de Backspace.
        val text = doc(l("a"), l("b", bullet(0)))
        assertEquals(1, edit(text, 1, 2, "", forwardDelete = true))
        assertEquals("p|ab", describe(text))
    }

    @Test
    fun typingOnDividerGoesBelow() {
        val after = html("<div>a</div><hr><div>b</div>")
        assertEquals(5, type(after, 3, "x"))
        assertEquals("<div>a</div><hr><div>x</div><div>b</div>", RichHtml.toHtml(after))

        val before = html("<div>a</div><hr><div>b</div>")
        assertEquals(5, type(before, 2, "x"))
        assertEquals("<div>a</div><hr><div>x</div><div>b</div>", RichHtml.toHtml(before))

        // Enter en el separador: un párrafo vacío debajo.
        val enter = html("<div>a</div><hr>")
        assertEquals(4, type(enter, 3, "\n"))
        assertEquals("<div>a</div><hr><div><br></div>", RichHtml.toHtml(enter))
    }

    // ---- Backspace ----

    @Test
    fun backspaceAtItemStart() {
        val text = doc(l("a", bullet(0)), l("b", bullet(1)))
        assertEquals(2, edit(text, 1, 2, ""))
        assertEquals("bullet0|a\nbullet0|b", describe(text))
        assertEquals(2, edit(text, 1, 2, ""))
        assertEquals("bullet0|a\np|b", describe(text))
        // Ya es párrafo: el siguiente une los renglones.
        assertEquals(1, edit(text, 1, 2, ""))
        assertEquals("bullet0|ab", describe(text))
    }

    @Test
    fun backspaceAtFirstLineQuote() {
        val quote = doc(l("a", QuoteMark(newBlockGroup())))
        assertEquals(0, RichEditRules.backspaceAtLineStart(quote, 0))
        assertEquals("p|a", describe(quote))

        val item = doc(l("a", bullet(1)))
        assertEquals(0, RichEditRules.backspaceAtLineStart(item, 0))
        assertEquals("bullet0|a", describe(item))
        assertEquals(0, RichEditRules.backspaceAtLineStart(item, 0))
        assertEquals("p|a", describe(item))

        // No aplica: un párrafo, o el cursor fuera del principio.
        assertNull(RichEditRules.backspaceAtLineStart(item, 0))
        assertNull(RichEditRules.backspaceAtLineStart(doc(l("a", bullet(0))), 1))
    }

    @Test
    fun backspaceInsideQuoteJoinsLines() {
        // Solo el primer renglón de la cita sale; en el medio se unen.
        val group = newBlockGroup()
        val text = doc(l("a", QuoteMark(group)), l("b", QuoteMark(group)))
        assertNull(RichEditRules.backspaceAtLineStart(text, 2))
        edit(text, 1, 2, "")
        assertEquals("quote|ab", describe(text))
    }

    @Test
    fun backspaceAtQuoteStartAfterParagraphLeavesQuote() {
        val group = newBlockGroup()
        val text = doc(l("x"), l("a", QuoteMark(group)), l("b", QuoteMark(group)))
        assertEquals(2, edit(text, 1, 2, ""))
        assertEquals("p|x\np|a\nquote|b", describe(text))
    }

    @Test
    fun joinKeepsUpperLineKind() {
        val item = doc(l("a5", bullet(0)), l("E1"))
        assertEquals(2, edit(item, 2, 3, ""))
        assertEquals("bullet0|a5E1", describe(item))

        val heading = doc(l("E2"), l("Tit", HeadingMark(2)))
        edit(heading, 2, 3, "")
        assertEquals("p|E2Tit", describe(heading))

        // Borrar un tramo de varios renglones también deja la marca del de arriba.
        val range = doc(l("ab", HeadingMark(2)), l("c", bullet(0)), l("de", QuoteMark(newBlockGroup())), l("f"))
        edit(range, 1, 6, "")
        assertEquals("h2|ae\np|f", describe(range))
    }

    // ---- Tab y flecha abajo ----

    @Test
    fun tabIndentsOnlyLists() {
        val text = doc(l("a", bullet(0)), l("b", ListMark(ListKind.Check, 0, checked = true)), l("c"))
        assertTrue(RichEditRules.indent(text, TextRange(0, 3), outdent = false))
        assertEquals("bullet1|a\ncheck1x|b\np|c", describe(text))
        assertTrue(RichEditRules.indent(text, TextRange(0, 3), outdent = true))
        assertTrue(RichEditRules.indent(text, TextRange(0, 3), outdent = true))
        assertEquals("bullet0|a\ncheck0x|b\np|c", describe(text))

        assertFalse(RichEditRules.indent(text, TextRange(5), outdent = false))
        assertEquals("bullet0|a\ncheck0x|b\np|c", describe(text))
    }

    @Test
    fun arrowDownLeavesTrailingCode() {
        val code = doc(l("a"), l("b", CodeBlockMark(newBlockGroup(), null)))
        assertNull(RichEditRules.arrowDownOutOfBlock(code, 0))
        assertEquals(4, RichEditRules.arrowDownOutOfBlock(code, 3))
        assertEquals("p|a\ncode|b\np|", describe(code))

        val quote = doc(l("a", QuoteMark(newBlockGroup())))
        assertEquals(2, RichEditRules.arrowDownOutOfBlock(quote, 1))
        assertEquals("quote|a\np|", describe(quote))

        assertNull(RichEditRules.arrowDownOutOfBlock(doc(l("a"), l("b")), 3))
    }

    // ---- teclado ----

    @Test
    fun imeCommitWithNewlineActsAsEnter() {
        val text = doc(l("lech", bullet(0)))
        assertEquals(6, edit(text, 0, 4, "leche\n"))
        assertEquals("bullet0|leche\nbullet0|", describe(text))
    }

    @Test
    fun imeCommitKeepsFormatOfReplacedWord() {
        val text = html("<div>a <b>lech</b></div>")
        edit(text, 2, 6, "leche")
        assertEquals("p|a {b}leche{/b}", describe(text))
    }

    @Test
    fun pasteWithSeveralLinesEntersAsParagraphs() {
        val text = doc(l("a", bullet(0)))
        type(text, 1, "x\ny\n")
        assertEquals("bullet0|ax\np|y\np|", describe(text))
    }

    @Test
    fun pasteInsideCodeStaysInCode() {
        val group = newBlockGroup()
        val text = doc(l("xy", CodeBlockMark(group, "kotlin")), l("z"))
        type(text, 1, "a\nb\nc")
        assertEquals("code:kotlin|xa\ncode:kotlin|b\ncode:kotlin|cy\np|z", describe(text))
        assertEquals(listOf(group), text.getSpans(0, text.length, CodeBlockMark::class.java).map { it.group }.distinct())
    }

    // ---- enlaces ----

    @Test
    fun typingRightAfterLinkIsNotLink() {
        val text = html("<div><a href=\"u\">ab</a></div>")
        type(text, 2, "x")
        assertEquals("p|{a=u}ab{/a}x", describe(text))
    }

    @Test
    fun typingAtLineStartBeforeLinkIsNotLink() {
        val text = html("<div><a href=\"u\">ab</a></div>")
        type(text, 0, "x")
        assertEquals("p|x{a=u}ab{/a}", describe(text))

        // El resto del formato del que sigue sí lo toma.
        val bold = html("<div><b><a href=\"u\">ab</a></b></div>")
        type(bold, 0, "x")
        assertEquals("p|{b}x{a=u}ab{/a}{/b}", describe(bold))
    }

    @Test
    fun typingInsideLinkStaysLink() {
        val text = html("<div><a href=\"u\">ab</a></div>")
        type(text, 1, "x")
        assertEquals("p|{a=u}axb{/a}", describe(text))

        // Reemplazar una palabra del enlace (autocorrección) sigue en el enlace.
        val word = html("<div><a href=\"u\">ab</a></div>")
        edit(word, 0, 2, "cd")
        assertEquals("p|{a=u}cd{/a}", describe(word))
    }

    // ---- código (formato en línea) ----

    @Test
    fun typingInCodeTakesNoInlineFormat() {
        val text = doc(l("a", CodeBlockMark(newBlockGroup(), null)))
        type(text, 1, "x", bold)
        assertEquals("code|ax", describe(text))
    }

    @Test
    fun replacingWholeLastItemKeepsBullet() {
        val text = doc(l("a"), l("lech", bullet(0)))
        edit(text, 2, 6, "pan")
        assertEquals("p|a\nbullet0|pan", describe(text))
    }

    @Test
    fun deletingOnlyCharOfLastItemKeepsBullet() {
        val text = doc(l("a"), l("x", bullet(0)))
        assertEquals(2, edit(text, 2, 3, ""))
        assertEquals("p|a\nbullet0|", describe(text))
    }

    @Test
    fun typingInNewEmptyItemKeepsMark() {
        val text = doc(l("a", bullet(0)))
        val cursor = type(text, 1, "\n")
        type(text, cursor, "b")
        assertEquals("bullet0|a\nbullet0|b", describe(text))
    }

    @Test
    fun foreignStylesInTypedTextAreRemoved() {
        val text = SpannableStringBuilder("ab")
        val suggestion = SuggestionSpan(Locale.US, arrayOf("xz"), 0)
        val typed = SpannableString("xy").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(suggestion, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val composing = UnderlineSpan()
        text.setSpan(composing, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        val change = RichEditRules.before(text, 1, 0, 2)
        text.replace(1, 1, typed)
        RichEditRules.after(text, change, emptyMap())
        assertEquals(0, text.getSpans(0, text.length, StyleSpan::class.java).size)
        // Lo de la composición queda: es del teclado y no se guarda.
        assertEquals(listOf(composing), text.getSpans(0, text.length, UnderlineSpan::class.java).toList())
        // Los SuggestionSpan también.
        assertEquals(1, text.getSpans(0, text.length, SuggestionSpan::class.java).size)
    }

    @Test
    fun rulesDoNotRetriggerThemselves() {
        // Como en el campo: un TextWatcher llama a las reglas; sus propios cambios no las disparan.
        val code = watched(doc(l("a", CodeBlockMark(newBlockGroup(), null))))
        RichEditRules.arrowDownOutOfBlock(code, 1)
        assertEquals("code|a\np|", describe(code))

        val item = watched(doc(l("x"), l("", bullet(1))))
        item.insert(2, "\n")
        assertEquals("p|x\nbullet0|", describe(item))

        val divider = watched(html("<div>a</div><hr>"))
        divider.insert(3, "x")
        assertEquals("<div>a</div><hr><div>x</div>", RichHtml.toHtml(divider))
    }

    // ---- el resto de las teclas ----

    @Test
    fun enterAtQuoteStartInsertsLineBefore() {
        // Un párrafo vacío antes del bloque; el cursor sigue en el bloque.
        val group = newBlockGroup()
        val quote = watched(doc(l("x"), l("ab", QuoteMark(group)), l("cd", QuoteMark(group))))
        assertEquals(3, RichEditRules.enterAtBlockStart(quote, 2))
        assertEquals("p|x\np|\nquote|ab\nquote|cd", describe(quote))
        assertEquals("<div>x</div><div><br></div><blockquote>ab<br>cd</blockquote>", RichHtml.toHtml(quote))

        val code = watched(doc(l("ab", CodeBlockMark(newBlockGroup(), null))))
        assertEquals(1, RichEditRules.enterAtBlockStart(code, 0))
        assertEquals("p|\ncode|ab", describe(code))

        // No aplica: en el medio del bloque, fuera del principio, en un renglón vacío o fuera de un bloque.
        assertNull(RichEditRules.enterAtBlockStart(quote, 6))
        assertNull(RichEditRules.enterAtBlockStart(quote, 4))
        assertNull(RichEditRules.enterAtBlockStart(doc(l("", QuoteMark(newBlockGroup()))), 0))
        assertNull(RichEditRules.enterAtBlockStart(doc(l("a")), 0))
    }

    @Test
    fun backspaceAtCodeStartUnwraps() {
        // Todo el bloque pasa a párrafos con el mismo texto.
        val group = newBlockGroup()
        val text = doc(l("x"), l("a", CodeBlockMark(group, null)), l("b", CodeBlockMark(group, null)))
        assertNull(RichEditRules.backspaceAtCodeStart(text, 4))
        assertNull(RichEditRules.backspaceAtCodeStart(text, 3))
        assertEquals(2, RichEditRules.backspaceAtCodeStart(text, 2))
        assertEquals("p|x\np|a\np|b", describe(text))
        assertNull(RichEditRules.backspaceAtCodeStart(text, 2))

        // Un código dentro de un ítem sale del código, no de la lista.
        val item = doc(l("a", bullet(0), CodeBlockMark(newBlockGroup(), null)))
        assertEquals(0, RichEditRules.backspaceAtCodeStart(item, 0))
        assertEquals("bullet0|a", describe(item))
    }

    @Test
    fun backspaceAfterDividerRemovesIt() {
        // Se borra el separador; el renglón queda como estaba.
        val text = watched(html("<div>a</div><hr><div>b</div>"))
        assertEquals("p|a\nhr|$DIVIDER_CHAR\np|b", describe(text))
        assertNull(RichEditRules.backspaceAfterDivider(text, 5))
        assertEquals(2, RichEditRules.backspaceAfterDivider(text, 4))
        assertEquals("p|a\np|b", describe(text))

        // También con un renglón vacío abajo (sin la regla, el vacío se iba y el separador quedaba).
        val empty = watched(html("<hr><div><br></div><div>c</div>"))
        assertEquals("hr|$DIVIDER_CHAR\np|\np|c", describe(empty))
        assertEquals(0, RichEditRules.backspaceAfterDivider(empty, 2))
        assertEquals("p|\np|c", describe(empty))

        assertNull(RichEditRules.backspaceAfterDivider(doc(l("a"), l("b")), 2))
    }

    @Test
    fun escapeLeavesQuote() {
        // Un párrafo vacío después del bloque, con el cursor ahí, desde cualquier renglón.
        val group = newBlockGroup()
        val quote = watched(doc(l("a", QuoteMark(group)), l("b", QuoteMark(group)), l("c")))
        assertEquals(4, RichEditRules.escapeOutOfBlock(quote, 1))
        assertEquals("quote|a\nquote|b\np|\np|c", describe(quote))
        assertEquals("<blockquote>a<br>b</blockquote><div><br></div><div>c</div>", RichHtml.toHtml(quote))

        val code = watched(doc(l("x"), l("a", CodeBlockMark(newBlockGroup(), null))))
        assertEquals(4, RichEditRules.escapeOutOfBlock(code, 3))
        assertEquals("p|x\ncode|a\np|", describe(code))
    }

    @Test
    fun escapeOutsideBlockDoesNothing() {
        val text = doc(l("a"), l("b", bullet(0)))
        assertNull(RichEditRules.escapeOutOfBlock(text, 1))
        assertNull(RichEditRules.escapeOutOfBlock(text, 3))
        assertEquals("p|a\nbullet0|b", describe(text))
    }

    @Test
    fun arrowUpAtFirstCodeAddsLine() {
        // Flecha arriba en un código o una cita que empieza el texto.
        val code = watched(doc(l("a", CodeBlockMark(newBlockGroup(), null)), l("b")))
        assertEquals(0, RichEditRules.arrowUpOutOfBlock(code, 1))
        assertEquals("p|\ncode|a\np|b", describe(code))
        assertNull(RichEditRules.arrowUpOutOfBlock(code, 0))

        val quote = watched(doc(l("a", QuoteMark(newBlockGroup()))))
        assertEquals(0, RichEditRules.arrowUpOutOfBlock(quote, 0))
        assertEquals("p|\nquote|a", describe(quote))

        assertNull(RichEditRules.arrowUpOutOfBlock(doc(l("a"), l("b", QuoteMark(newBlockGroup()))), 3))
    }

    @Test
    fun arrowRightLeavesInlineCode() {
        // Al final de un código en línea que termina el renglón, un espacio afuera.
        val text = watched(html("<div>a <code>x</code></div><div>b</div>"))
        assertEquals(4, RichEditRules.arrowRightOutOfCode(text, 3))
        assertEquals("p|a {code}x{/code} \np|b", describe(text))
        // Ya no termina en código: no aplica más.
        assertNull(RichEditRules.arrowRightOutOfCode(text, 4))

        // No aplica: en el medio del código o con texto después.
        val middle = html("<div><code>xy</code></div>")
        assertNull(RichEditRules.arrowRightOutOfCode(middle, 1))
        assertNull(RichEditRules.arrowRightOutOfCode(html("<div><code>x</code>y</div>"), 1))
    }

    @Test
    fun tabOutsideListInsertsEmSpace() {
        // Fuera de una lista, Tab escribe un espacio eme.
        val text = watched(html("<div><b>ab</b></div>"))
        assertEquals(2, RichEditRules.tabOutsideList(text, TextRange(1), outdent = false))
        assertEquals("p|{b}a b{/b}", describe(text))

        // En una lista no: eso es la sangría.
        assertNull(RichEditRules.tabOutsideList(doc(l("a", bullet(0))), TextRange(1), outdent = false))
    }

    @Test
    fun shiftTabRemovesEmSpace() {
        val text = watched(doc(l("a b")))
        assertNull(RichEditRules.tabOutsideList(text, TextRange(1), outdent = true))
        assertEquals(1, RichEditRules.tabOutsideList(text, TextRange(2), outdent = true))
        assertEquals("p|ab", describe(text))
        assertNull(RichEditRules.tabOutsideList(text, TextRange(1), outdent = true))
    }

    private fun watched(text: SpannableStringBuilder): SpannableStringBuilder {
        text.setSpan(
            object : TextWatcher {
                var change: RichEditRules.Change? = null

                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                    change = RichEditRules.before(s as Spanned, start, count, after)
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable) {
                    RichEditRules.after(s, change!!, emptyMap())
                }
            },
            0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return text
    }
}
