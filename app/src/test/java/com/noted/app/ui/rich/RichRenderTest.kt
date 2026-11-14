package com.noted.app.ui.rich

import android.graphics.Typeface
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichRenderTest {

    private val style = RichStyle(
        primary = 0xFF6750A4.toInt(),
        onSurfaceVariant = 0xFF49454F.toInt(),
        surfaceVariant = 0xFFE7E0EC.toInt(),
        checkedText = 0xFF909090.toInt(),
        onBackground = 0xFF1C1B1F.toInt(),
        dark = false,
        textSizePx = 34f,
        typeface = Typeface.DEFAULT,
        letterSpacingEm = 0.03125f,
        lineHeightPx = 60f,
        density = 2.125f,
    )

    private fun rendered(html: String, style: RichStyle = this.style): Spannable =
        SpannableStringBuilder(RichHtml.fromHtml(html)).also { RichRender.refresh(it, style) }

    private inline fun <reified T> Spannable.spansAt(position: Int): List<T> =
        getSpans(position, position + 1, T::class.java).toList()

    /** El rótulo de lista o cita de cada renglón que tiene uno, en orden. */
    private fun Spannable.labels(): List<String> =
        getSpans(0, length, RichRender.MarkerSpan::class.java).sortedBy { getSpanStart(it) }.map { it.label }

    @Test
    fun headingSizes() {
        val text = rendered("<h1>a</h1><h2>b</h2><h3>c</h3><h4>d</h4><h6>e</h6>")
        val sizes = listOf(0, 2, 4, 6, 8).map { text.spansAt<RichRender.SizeSpan>(it).single().px }
        assertEquals(listOf(25f, 22f, 19f, 17f, 17f).map { it * style.density }, sizes)
        assertTrue(text.spansAt<StyleSpan>(0).any { it.style == Typeface.BOLD })
    }

    @Test
    fun checkedItemIsGrayNotStruck() {
        val text = rendered("<ul><li data-checked=\"true\">x</li></ul>")
        assertEquals(listOf(style.checkedText), text.spansAt<ForegroundColorSpan>(0).map { it.foregroundColor })
        assertTrue(text.getSpans(0, text.length, StrikethroughSpan::class.java).isEmpty())
        assertEquals(listOf("☑ "), text.labels())
        assertEquals(listOf("☐ "), rendered("<ul><li data-checked=\"false\">x</li></ul>").labels())
    }

    @Test
    fun listMarginsMatchCopy() {
        val text = rendered("<ul><li>a<ul><li>x</li></ul></li></ul>")
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = this@RichRenderTest.style.textSizePx
            typeface = this@RichRenderTest.style.typeface
            letterSpacing = this@RichRenderTest.style.letterSpacingEm
        }
        val margin = text.spansAt<LeadingMarginSpan>(2).single()
        assertEquals(paint.measureText("  • ").roundToInt(), margin.getLeadingMargin(true))
        assertEquals(0, margin.getLeadingMargin(false))
        assertEquals(listOf("• ", "  • "), text.labels())
    }

    @Test
    fun orderedNumbersContinue() {
        val text = rendered("<ol start=\"3\"><li>a</li><li>b<ol><li>c</li></ol></li><li>d</li></ol>")
        assertEquals("ordered0@3|a\nordered0|b\nordered1|c\nordered0|d", RichDebug.describe(text))
        assertEquals(listOf("3. ", "4. ", "  1. ", "5. "), text.labels())
    }

    @Test
    fun orderedRestartsAfterParagraphOrBullet() {
        val afterParagraph = rendered("<ol><li>a</li><li>b</li></ol><div>p</div><ol><li>c</li></ol>")
        assertEquals(listOf("1. ", "2. ", "1. "), afterParagraph.labels())
        val afterBullet = rendered("<ol><li>a</li></ol><ul><li>b</li></ul><ol><li>c</li></ol>")
        assertEquals(listOf("1. ", "• ", "1. "), afterBullet.labels())
    }

    @Test
    fun quoteCodeAndDivider() {
        val text = rendered("<blockquote>q</blockquote><pre>c</pre><hr><div><code spellcheck=\"false\">i</code></div>")
        assertEquals(listOf("▎ "), text.labels())
        assertEquals(listOf(style.onSurfaceVariant), text.spansAt<ForegroundColorSpan>(0).map { it.foregroundColor })
        assertTrue(text.spansAt<StyleSpan>(0).any { it.style == Typeface.ITALIC })
        for (position in listOf(2, 6)) {
            assertEquals(listOf("monospace"), text.spansAt<TypefaceSpan>(position).map { it.family })
            assertEquals(listOf(style.surfaceVariant), text.spansAt<BackgroundColorSpan>(position).map { it.backgroundColor })
        }
        val divider = text.spansAt<ReplacementSpan>(4).single() as RichRender.DividerSpan
        // Línea de 1 dp.
        assertEquals(style.density, divider.thicknessPx)
        assertEquals(if (style.dark) 0xFF3A3A3A.toInt() else 0xFFE3E3E3.toInt(), divider.color)
    }

    @Test
    fun inlineMarks() {
        val text = rendered(
            "<div><b>b</b><i>i</i><strike>s</strike><u>u</u><a href=\"x\">l</a><sub>d</sub>" +
                "<span class=\"noted-text-red\">r</span><span class=\"noted-highlight\">h</span></div>"
        )
        assertEquals(listOf(Typeface.BOLD), text.spansAt<StyleSpan>(0).map { it.style })
        assertEquals(listOf(Typeface.ITALIC), text.spansAt<StyleSpan>(1).map { it.style })
        assertEquals(1, text.spansAt<StrikethroughSpan>(2).size)
        assertEquals(1, text.spansAt<UnderlineSpan>(3).size)
        assertEquals(1, text.spansAt<UnderlineSpan>(4).size)
        assertEquals(listOf(style.primary), text.spansAt<ForegroundColorSpan>(4).map { it.foregroundColor })
        assertEquals(1, text.spansAt<SubscriptSpan>(5).size)
        assertEquals(listOf(14f / 17f), text.spansAt<RelativeSizeSpan>(5).map { it.sizeChange })
        assertEquals(listOf(0xFFF9241C.toInt()), text.spansAt<ForegroundColorSpan>(6).map { it.foregroundColor })
        assertEquals(listOf(0xFFE7FFCD.toInt()), text.spansAt<BackgroundColorSpan>(7).map { it.backgroundColor })

        val dark = rendered("<div><span class=\"noted-text-red\">r</span><span class=\"noted-highlight-gray\">h</span></div>", style.copy(dark = true))
        assertEquals(listOf(0xFFFF5959.toInt()), dark.spansAt<ForegroundColorSpan>(0).map { it.foregroundColor })
        assertEquals(listOf(0xFF363A41.toInt()), dark.spansAt<BackgroundColorSpan>(1).map { it.backgroundColor })
    }

    @Test
    fun refreshIsIdempotent() {
        val text = rendered("<h1>a</h1><ul><li><b>b</b></li></ul><blockquote>q</blockquote><pre>c</pre><hr>")
        val count = text.getSpans(0, text.length, RenderSpan::class.java).size
        assertTrue(count > 0)
        RichRender.refresh(text, style)
        assertEquals(count, text.getSpans(0, text.length, RenderSpan::class.java).size)
        // Las marcas siguen ahí.
        assertEquals("h1|a\nbullet0|{b}b{/b}\nquote|q\ncode|c\nhr|￼", RichDebug.describe(text))
    }

    // ---- dibujo parcial ----

    /** Una nota con todo tipo de formato: títulos, listas numeradas anidadas, cita, código, colores. */
    private val sample =
        "<h1>Título <b>uno</b></h1>" +
            "<ol><li>a</li><li>b<ol><li>c</li><li>d</li></ol></li><li>e</li></ol>" +
            "<div>p <span class=\"noted-text-red\">rojo</span> <span class=\"noted-highlight\">h</span></div>" +
            "<ol start=\"3\"><li>f</li><li><i>g</i></li><li><h2>t</h2></li></ol>" +
            "<ul><li>x</li><li data-checked=\"true\">y</li></ul>" +
            "<blockquote>q<br>r</blockquote>" +
            "<pre>code</pre><pre>more</pre>" +
            "<hr>" +
            "<h2>sub</h2><div><code spellcheck=\"false\">i</code> <a href=\"x\">l</a> <u>u</u></div>" +
            "<ol><li>z</li><li>w</li></ol>"

    /** Los spans de dibujo de [text], en su orden: clase, valor, tramo y banderas. */
    private fun drawing(text: Spanned): List<String> =
        text.getSpans(0, text.length, RenderSpan::class.java).map {
            "${it.javaClass.name}:${RichRender.valueOf(it)}@${text.getSpanStart(it)}-${text.getSpanEnd(it)}" +
                "/${text.getSpanFlags(it) and Spanned.SPAN_POINT_MARK_MASK}"
        }

    /**
     * Hace [edit] sobre dos copias de [html] ya dibujadas: una se dibuja entera y la otra solo
     * con `changed`; tienen que quedar iguales.
     */
    private fun assertPartialEqualsFull(
        html: String,
        label: String,
        checkedItem: Boolean = false,
        setup: (SpannableStringBuilder) -> Unit = {},
        edit: (SpannableStringBuilder) -> IntRange,
    ) {
        fun make() = SpannableStringBuilder(RichHtml.fromHtml(html)).also {
            setup(it)
            RichRender.refresh(it, style, checkedItem)
        }
        val full = make()
        val partial = make()
        edit(full)
        val changed = edit(partial)
        RichRender.refresh(full, style, checkedItem)
        RichRender.refresh(partial, style, checkedItem, changed)
        assertEquals(label, drawing(full), drawing(partial))
    }

    @Test
    fun partialRefreshEqualsFullRefresh() {
        val length = RichHtml.fromHtml(sample).length
        for (position in 0..length) {
            assertPartialEqualsFull(sample, "escribir en $position") { it.insert(position, "x"); position..position + 1 }
            assertPartialEqualsFull(sample, "pegar en $position") {
                it.insert(position, "uno\ndos\ntres")
                position..position + 12
            }
        }
        for (position in 0 until length) {
            assertPartialEqualsFull(sample, "borrar en $position") { it.delete(position, position + 1); position..position }
        }
        val newlines = RichHtml.fromHtml(sample).withIndex().filter { it.value == '\n' }.map { it.index }
        assertTrue(newlines.size > 10)
        for (newline in newlines) {
            assertPartialEqualsFull(sample, "unir en $newline") { it.delete(newline, newline + 1); newline..newline }
        }
        for (position in 0..length) {
            assertPartialEqualsFull(sample, "ítem marcado, en $position", checkedItem = true) {
                it.insert(position, "x")
                position..position + 1
            }
        }
        // Un ítem vacío al final: el rótulo del de arriba llega al final del texto y crece con lo
        // que se escriba abajo.
        val emptyLast: (SpannableStringBuilder) -> Unit = {
            it.append("\n")
            it.setSpan(ListMark(ListKind.Ordered, 0), it.length, it.length, BLOCK_MARK_FLAGS)
        }
        for (html in listOf("<ol><li>a</li></ol>", sample)) {
            val end = RichHtml.fromHtml(html).length + 1
            assertPartialEqualsFull(html, "escribir en el ítem vacío", setup = emptyLast) { it.insert(end, "x"); end..end + 1 }
            assertPartialEqualsFull(html, "pegar en el ítem vacío", setup = emptyLast) {
                it.insert(end, "uno\ndos")
                end..end + 7
            }
        }
    }

    @Test
    fun orderedNumbersUpdateBelowChange() {
        // Sacar la marca del primer ítem: los de abajo vuelven a empezar.
        val list = "<ol><li>a</li><li>b</li><li>c</li></ol>"
        val unmark: (SpannableStringBuilder) -> IntRange = { text ->
            text.removeSpan(RichHtml.blockMarksAt(text, 0).single())
            0..0
        }
        assertPartialEqualsFull(list, "sacar la marca", edit = unmark)
        val text = rendered(list) as SpannableStringBuilder
        unmark(text)
        RichRender.refresh(text, style, changed = 0..0)
        assertEquals(listOf("1. ", "2. "), text.labels())

        // Un párrafo entre dos listas que pasa a ser ítem las une: la de abajo sigue contando.
        val split = "<ol><li>a</li></ol><div>p</div><ol><li>c</li><li>d</li></ol>"
        val mark: (SpannableStringBuilder) -> IntRange = { joined ->
            joined.setSpan(ListMark(ListKind.Ordered, 0), 2, 4, BLOCK_MARK_FLAGS)
            2..2
        }
        assertPartialEqualsFull(split, "poner la marca", edit = mark)
        val joined = rendered(split) as SpannableStringBuilder
        mark(joined)
        RichRender.refresh(joined, style, changed = 2..2)
        assertEquals(listOf("1. ", "2. ", "3. ", "4. "), joined.labels())

        // Escribir al final de una lista larga: el número sale de contar los de arriba.
        val long = "<ol>" + (1..12).joinToString("") { "<li>i$it</li>" } + "</ol>"
        val typed = rendered(long) as SpannableStringBuilder
        val end = typed.length
        typed.insert(end, "x")
        RichRender.refresh(typed, style, changed = end..end + 1)
        assertEquals((1..12).map { "$it. " }, typed.labels())
    }

    @Test
    fun partialRefreshSkipsUntouchedLines() {
        // Si se sacan a mano los spans de un renglón lejano, el dibujo parcial no los repone:
        // prueba que no recorrió ese renglón.
        val text = rendered(sample) as SpannableStringBuilder
        val last = lineStartOf(text, text.length)
        for (span in text.getSpans(last, text.length, RenderSpan::class.java)) text.removeSpan(span)
        text.insert(0, "x")
        RichRender.refresh(text, style, changed = 0..1)
        assertTrue(text.getSpans(last + 1, text.length, RenderSpan::class.java).isEmpty())
        RichRender.refresh(text, style)
        assertTrue(text.getSpans(last + 1, text.length, RenderSpan::class.java).isNotEmpty())
    }
}
