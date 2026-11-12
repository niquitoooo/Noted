package com.jot.app.ui.rich

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.compose.ui.text.TextRange
import com.jot.app.ui.rich.RichDebug.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RichPasteTest {

    private fun html(fragment: String) = SpannableStringBuilder(RichHtml.fromHtml(fragment))

    private fun links(text: Spanned) = text.getSpans(0, text.length, LinkMark::class.java).toList()

    @Test
    fun pasteHtmlKeepsBoldAndList() {
        val empty = html("")
        val cursor = RichPaste.insertHtml(empty, TextRange(0), "<div><b>x</b></div><ul><li>y</li><li>z</li></ul>")
        assertEquals("p|{b}x{/b}\nbullet0|y\nbullet0|z", describe(empty))
        assertEquals(empty.length, cursor)

        // En el medio de un renglón: los bloques pegados van en sus renglones y el resto
        // conserva su formato.
        val text = html("<div>ab</div><div>c</div>")
        val at = RichPaste.insertHtml(text, TextRange(1), "<b>x</b><ul><li>y</li></ul>")
        assertEquals("p|a\np|{b}x{/b}\nbullet0|y\np|b\np|c", describe(text))
        assertEquals(5, at)
    }

    /** Lo que se pega en las pruebas de pegar: un párrafo en negrita y una viñeta. */
    private val blocks = "<div><b>x</b></div><ul><li>y</li></ul>"

    @Test
    fun pasteBlocksMidHeadingSplitsHeading() {
        // El título se parte y los títulos de lo pegado se desarman.
        val text = html("<h1>Título</h1>")
        val at = RichPaste.insertHtml(text, TextRange(2), blocks)
        assertEquals("h1|Tí\np|{b}x{/b}\nbullet0|y\nh1|tulo", describe(text))
        assertEquals(6, at)

        val titled = html("<h1>Título</h1>")
        RichPaste.insertHtml(titled, TextRange(2), "<h2>x</h2><div>y</div>")
        assertEquals("h1|Tí\np|x\np|y\nh1|tulo", describe(titled))
    }

    @Test
    fun pasteBlocksMidBulletStaysInList() {
        // Todo queda en la misma lista.
        val text = html("<ul><li>comprar</li></ul>")
        RichPaste.insertHtml(text, TextRange(3), blocks)
        assertEquals("bullet0|com\nbullet0|{b}x{/b}\nbullet0|y\nbullet0|prar", describe(text))
        assertEquals("<ul><li>com</li><li><b>x</b></li><li>y</li><li>prar</li></ul>", RichHtml.toHtml(text))

        val ordered = html("<ol start=\"3\"><li>ab</li></ol>")
        RichPaste.insertHtml(ordered, TextRange(1), blocks)
        assertEquals("ordered0@3|a\nordered0|{b}x{/b}\nordered0|y\nordered0|b", describe(ordered))
    }

    @Test
    fun pasteBlocksMidQuoteStaysInQuote() {
        // Todo queda dentro de la misma cita. En el editor de antes un renglón de cita no lleva
        // viñeta: "y" queda como renglón de la cita.
        val text = html("<blockquote>ab</blockquote>")
        RichPaste.insertHtml(text, TextRange(1), blocks)
        assertEquals("quote|a\nquote|{b}x{/b}\nquote|y\nquote|b", describe(text))
        assertEquals(1, text.getSpans(0, text.length, QuoteMark::class.java).map { it.group }.distinct().size)
    }

    @Test
    fun pasteBlocksAtHeadingStartGoAbove() {
        // Lo pegado va arriba y el título sigue entero; no queda un título vacío.
        val text = html("<h1>Título</h1>")
        val at = RichPaste.insertHtml(text, TextRange(0), blocks)
        assertEquals("p|{b}x{/b}\nbullet0|y\nh1|Título", describe(text))
        assertEquals(3, at)

        val empty = html("<h1><br></h1>")
        RichPaste.insertHtml(empty, TextRange(0), blocks)
        assertEquals("p|{b}x{/b}\nbullet0|y", describe(empty))
    }

    @Test
    fun pasteQuoteMidHeadingKeepsQuote() {
        // Pegar solo desarma los títulos: una cita pegada en un título sigue siendo cita.
        val text = html("<h1>Título</h1>")
        RichPaste.insertHtml(text, TextRange(2), "<blockquote>q</blockquote><div>x</div>")
        assertEquals("h1|Tí\nquote|q\np|x\nh1|tulo", describe(text))
    }

    @Test
    fun pasteAtOrderedStartKeepsStart() {
        // Al principio del primer ítem, la lista sigue empezando en 3.
        val ordered = html("<ol start=\"3\"><li>ab</li></ol>")
        RichPaste.insertHtml(ordered, TextRange(0), blocks)
        assertEquals("ordered0@3|{b}x{/b}\nordered0|y\nordered0|ab", describe(ordered))
        assertEquals("<ol start=\"3\"><li><b>x</b></li><li>y</li><li>ab</li></ol>", RichHtml.toHtml(ordered))

        // Una lista que empieza en 5, pegada en un título, sigue empezando en 5.
        val heading = html("<h1>Título</h1>")
        RichPaste.insertHtml(heading, TextRange(2), "<ol start=\"5\"><li>a</li><li>b</li></ol>")
        assertEquals("h1|Tí\nordered0@5|a\nordered0|b\nh1|tulo", describe(heading))
    }

    @Test
    fun pasteCheckedItemsKeepChecked() {
        val checks = "<ul><li data-checked=\"true\">x</li><li data-checked=\"false\">y</li></ul>"
        val text = html("<ul><li data-checked=\"false\">ab</li></ul>")
        RichPaste.insertHtml(text, TextRange(1), checks)
        assertEquals("check0|a\ncheck0x|x\ncheck0|y\ncheck0|b", describe(text))

        // En una lista de viñetas no hay tildes.
        val bullets = html("<ul><li>ab</li></ul>")
        RichPaste.insertHtml(bullets, TextRange(1), checks)
        assertEquals("bullet0|a\nbullet0|x\nbullet0|y\nbullet0|b", describe(bullets))
    }

    @Test
    fun pastePlainInInlineCodeInHeading() {
        // Dentro de un código en línea mandan las reglas de código, como al escribir.
        val text = html("<h1>a<code>bc</code></h1>")
        assertFalse(RichPaste.keepsHeading(text, TextRange(2), "p\nq"))
        RichPaste.insertPlain(text, TextRange(2), "p\nq")
        val typed = html("<h1>a<code>bc</code></h1>")
        RichDebug.type(typed, 2, "p\nq")
        assertEquals(describe(typed), describe(text))
    }

    @Test
    fun pastePlainMidHeadingKeepsHeading() {
        // Texto plano en un título: los renglones siguen siendo título.
        val text = html("<h1>Título</h1>")
        val at = RichPaste.insertPlain(text, TextRange(2), "p\nq")
        assertEquals("h1|Típ\nh1|qtulo", describe(text))
        assertEquals(5, at)
    }

    @Test
    fun pasteReplacesSelection() {
        val text = html("<div>a<i>bc</i>d</div><div>ef</div>")
        val at = RichPaste.insertHtml(text, TextRange(2, 6), "<u>x</u>")
        assertEquals("p|a{i}b{/i}{u}x{/u}f", describe(text))
        assertEquals(3, at)
    }

    @Test
    fun pasteDividerGoesOnItsOwnLine() {
        val text = html("<div>ab</div>")
        RichPaste.insertHtml(text, TextRange(1), "<hr>")
        assertEquals("p|a\nhr|￼\np|b", describe(text))
    }

    @Test
    fun pasteStyledSpansBecomeMarks() {
        val text = html("")
        RichPaste.insertHtml(
            text, TextRange(0),
            """<div><span style="font-weight:700">neg</span> y <span style="font-style:italic">cur</span>""" +
                """ <span style="color:#f9241c">roj</span><img src="https://x.com/a.png"></div>""",
        )
        assertEquals("p|{b}neg{/b} y {i}cur{/i} {color=red}roj{/color}", describe(text))
    }

    @Test
    fun pasteInCodeIsPlain() {
        // En código se pega texto plano: lo decide plainOnly (el campo pega por sus reglas).
        val group = newBlockGroup()
        val code = SpannableStringBuilder("xy\nz").apply {
            setSpan(CodeBlockMark(group, "kotlin"), 0, 3, BLOCK_MARK_FLAGS)
        }
        assertTrue(RichPaste.plainOnly(code, TextRange(1), inItem = false))
        assertTrue(RichPaste.plainOnly(html("<div><code>xy</code></div>"), TextRange(1), inItem = false))
        assertFalse(RichPaste.plainOnly(html("<div><code>xy</code></div>"), TextRange(0), inItem = false))

        // Una URL en código tampoco es enlace.
        val url = html("<div><code>xy</code></div>")
        RichPaste.insertPlain(url, TextRange(1), "https://x.com")
        assertTrue(links(url).isEmpty())
    }

    @Test
    fun javascriptUrlIsNotLink() {
        for (bad in listOf("javascript://x%0Aalert(1)", "JavaScript://x", "data://x", "vbscript://x")) {
            assertEquals(bad, null, RichPaste.urlOf(bad))
            val text = html("<div>a</div>")
            RichPaste.insertPlain(text, TextRange(1), bad)
            assertTrue(bad, links(text).isEmpty())
        }
    }

    @Test
    fun crlfIsNormalized() {
        val plain = html("")
        RichPaste.insertPlain(plain, TextRange(0), "a\r\nb\rc")
        assertEquals("p|a\np|b\np|c", describe(plain))

        val rich = html("")
        RichPaste.insertHtml(rich, TextRange(0), "<pre>x\r\ny\rz</pre><div>u\r\nv</div>")
        assertEquals("code|x\ncode|y\ncode|z\np|u v", describe(rich))
        assertFalse('\r' in rich.toString())
    }

    @Test
    fun pasteInChecklistItemIsPlain() {
        val text = html("<div>ab</div>")
        assertTrue(RichPaste.plainOnly(text, TextRange(1), inItem = true))
        assertFalse(RichPaste.plainOnly(text, TextRange(1), inItem = false))
        // Como texto plano, los renglones quedan como `\n` para la regla del ítem.
        RichPaste.insertPlain(text, TextRange(1), "x\ny")
        assertEquals("p|ax\np|yb", describe(text))
    }

    @Test
    fun urlOverSelectionMakesLink() {
        val text = html("<div>ver aca</div>")
        val at = RichPaste.insertPlain(text, TextRange(4, 7), "https://x.com")
        assertEquals("p|ver {a=https://x.com}aca{/a}", describe(text))
        assertEquals(mapOf("title" to "https://x.com"), links(text).single().attrs)
        assertEquals(7, at)

        // Si ya era un enlace, le cambia el href.
        val link = html("""<div><a href="u">aca</a></div>""")
        RichPaste.insertPlain(link, TextRange(0, 3), "https://y.com")
        assertEquals("p|{a=https://y.com}aca{/a}", describe(link))
        assertEquals(1, links(link).size)
    }

    @Test
    fun bareUrlGetsBreakAllClass() {
        val text = html("<div>a</div>")
        val at = RichPaste.insertPlain(text, TextRange(1), " https://x.com ")
        assertEquals("p|a{a=https://x.com}https://x.com{/a}", describe(text))
        assertEquals(14, at)
        assertEquals(
            """<div>a<a href="https://x.com" class="jot-break-all" spellcheck="false">https://x.com</a></div>""",
            RichHtml.toHtml(text),
        )
    }

    @Test
    fun pastePlainTextUnchanged() {
        val text = html("<div>ab</div>")
        val at = RichPaste.insertPlain(text, TextRange(1), "x **y** z.com")
        assertEquals("p|ax **y** z.comb", describe(text))
        assertEquals(14, at)
        assertTrue(links(text).isEmpty())
    }
}
