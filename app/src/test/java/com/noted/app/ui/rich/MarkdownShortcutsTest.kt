package com.noted.app.ui.rich

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.TextRange
import com.noted.app.ui.BlockCommands
import com.noted.app.ui.EditorBlock
import com.noted.app.ui.EditorUndo
import com.noted.app.ui.NoteUndo
import com.noted.app.ui.UndoFocus
import com.noted.app.ui.rich.RichDebug.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Los atajos de Markdown al escribir. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownShortcutsTest {

    private fun html(fragment: String) = SpannableStringBuilder(RichHtml.fromHtml(fragment))

    /**
     * Escribe [typed] carácter por carácter en [cursor], como el teclado físico; [inItem] es el
     * texto de un ítem de checklist. Devuelve el cursor final.
     */
    private fun typeEach(text: SpannableStringBuilder, cursor: Int, typed: String, inItem: Boolean = false): Int {
        var at = cursor
        for (char in typed) {
            val change = RichEditRules.before(text, at, 0, 1)
            text.insert(at, char.toString())
            val result = RichEditRules.after(text, change, emptyMap(), inItem = inItem)
            at = result.cursor ?: (at + 1)
        }
        return at
    }

    /** Escribe [typed] en un párrafo vacío y devuelve el texto. */
    private fun typed(typed: String, inItem: Boolean = false): SpannableStringBuilder {
        val text = SpannableStringBuilder("")
        typeEach(text, 0, typed, inItem)
        return text
    }

    // ---- principio del renglón ----

    @Test
    fun hashSpaceMakesHeading1() {
        val text = SpannableStringBuilder("")
        val cursor = typeEach(text, 0, "# ")
        assertEquals("h1|", describe(text))
        assertEquals(0, cursor)
        typeEach(text, cursor, "Hola")
        assertEquals("h1|Hola", describe(text))
        // De 1 a 6; siete no.
        for (level in 2..6) assertEquals("h$level|x", describe(typed("#".repeat(level) + " x")))
        assertEquals("p|####### x", describe(typed("####### x")))
    }

    @Test
    fun hashInListIsNotHeading() {
        val text = html("<ul><li></li></ul>")
        typeEach(text, 0, "# ")
        assertEquals("bullet0|# ", describe(text))
    }

    @Test
    fun dashStarPlusMakeBullet() {
        assertEquals("bullet0|a", describe(typed("- a")))
        assertEquals("bullet0|a", describe(typed("* a")))
        assertEquals("bullet0|a", describe(typed("+ a")))
    }

    @Test
    fun numberDotCommaStarMakeOrdered() {
        assertEquals("ordered0|a", describe(typed("1. a")))
        assertEquals("ordered0|a", describe(typed("1, a")))
        assertEquals("ordered0|a", describe(typed("1* a")))
    }

    @Test
    fun orderedStartsAtN() {
        assertEquals("ordered0@3|a", describe(typed("3. a")))
        assertEquals("ordered0@12|a", describe(typed("12. a")))
    }

    @Test
    fun zeroDoesNotStartList() {
        assertEquals("p|0. a", describe(typed("0. a")))
        assertEquals("p|05. a", describe(typed("05. a")))
    }

    @Test
    fun bracketsSpaceMakesChecklist() {
        val text = SpannableStringBuilder("")
        val cursor = typeEach(text, 0, "[] ")
        assertEquals("check0|", describe(text))
        // En la nota, el renglón pasa a un bloque de checklist.
        val blocks = mutableListOf<EditorBlock>(EditorBlock.Text("t", RichText(text, TextRange(cursor))))
        BlockCommands.textChanged(blocks, "t")
        val list = blocks.filterIsInstance<EditorBlock.Checklist>().single()
        assertEquals(1, list.items.size)
        assertFalse(list.items[0].checked)
        assertEquals("", list.items[0].value.plain)
    }

    @Test
    fun bracketXMakesCheckedChecklist() {
        assertEquals("check0x|a", describe(typed("[x] a")))
        assertEquals("check0x|a", describe(typed("[X] a")))
    }

    @Test
    fun bracketSpaceBracketIsNotChecklist() {
        assertEquals("p|[ ] a", describe(typed("[ ] a")))
    }

    @Test
    fun checklistNotInListOrHeading() {
        val list = html("<ul><li></li></ul>")
        typeEach(list, 0, "[] ")
        assertEquals("bullet0|[] ", describe(list))
        val heading = html("<h2></h2>")
        typeEach(heading, 0, "[x] ")
        assertEquals("h2|[x] ", describe(heading))
    }

    @Test
    fun greaterSpaceMakesQuote() {
        assertEquals("quote|a", describe(typed("> a")))
        // Debajo de una cita, es la misma cita.
        val text = html("<blockquote>a</blockquote><div></div>")
        typeEach(text, 2, "> b")
        assertEquals("quote|a\nquote|b", describe(text))
        assertEquals(1, text.getSpans(0, text.length, QuoteMark::class.java).map { it.group }.toSet().size)
    }

    @Test
    fun threeBackticksMakeCodeBlock() {
        val text = SpannableStringBuilder("")
        val cursor = typeEach(text, 0, "```")
        // Apenas se escribe el tercero, sin espacio ni Enter.
        assertEquals(0, cursor)
        assertTrue(describe(text).startsWith("code|"))
        typeEach(text, cursor, "val x")
        assertTrue(describe(text).startsWith("code|val x"))
    }

    @Test
    fun dashesEnterMakesDivider() {
        for (dashes in listOf("---", "***", "___", "—-", "———")) {
            val text = SpannableStringBuilder("")
            val cursor = typeEach(text, 0, dashes + "\n")
            assertEquals(dashes, "hr|$DIVIDER_CHAR\np|", describe(text))
            assertEquals(dashes, 2, cursor)
            typeEach(text, cursor, "a")
            assertEquals(dashes, "hr|$DIVIDER_CHAR\np|a", describe(text))
        }
    }

    @Test
    fun dashesWithTextOrInListAreNotDivider() {
        // Seguido de texto, no.
        assertEquals("p|---a", describe(typed("---a")))
        val after = html("<div>---a</div>")
        typeEach(after, 3, "\n")
        assertEquals("p|---\np|a", describe(after))
        // En una lista, no.
        val list = html("<ul><li>---</li></ul>")
        typeEach(list, 3, "\n")
        assertEquals("bullet0|---\nbullet0|", describe(list))
    }

    @Test
    fun notInsideCodeBlock() {
        val text = html("<pre></pre>")
        typeEach(text, 0, "# a")
        assertTrue(describe(text).startsWith("code|# a"))
        val dashes = html("<pre>---</pre>")
        typeEach(dashes, 3, "\n")
        assertTrue(describe(dashes).startsWith("code|---\ncode|"))
    }

    @Test
    fun notInsideInlineCodeAtLineStart() {
        val text = html("<div><code>x</code></div>")
        // Lo escrito al principio toma el código del carácter que sigue.
        typeEach(text, 0, "- ")
        assertEquals("p|{code}- x{/code}", describe(text))
    }

    @Test
    fun notInChecklistItem() {
        for (pattern in listOf("# ", "- ", "1. ", "[] ", "[x] ", "> ")) {
            assertEquals(pattern, "p|${pattern}a", describe(typed(pattern + "a", inItem = true)))
        }
        assertEquals("p|```", describe(typed("```", inItem = true)))
        assertEquals("p|---\np|", describe(typed("---\n", inItem = true)))
    }

    @Test
    fun shortcutReturnsTypedText() {
        val text = SpannableStringBuilder("#")
        val change = RichEditRules.before(text, 1, 0, 1)
        text.insert(1, " ")
        val result = RichEditRules.after(text, change, emptyMap())
        assertTrue(result.autoFormatted)
        assertEquals("p|# ", describe(result.typed!!.text))
        assertEquals(TextRange(2), result.typed!!.selection)
        assertEquals("h1|", describe(text))
    }

    // ---- en la línea, tipografía y enlaces ----

    /** Inserta [typed] de una vez en [cursor], como un `commitText` del teclado en pantalla. */
    private fun commit(text: SpannableStringBuilder, cursor: Int, typed: String, inItem: Boolean = false): Int {
        val change = RichEditRules.before(text, cursor, 0, typed.length)
        text.insert(cursor, typed)
        return RichEditRules.after(text, change, emptyMap(), inItem = inItem).cursor ?: (cursor + typed.length)
    }

    @Test
    fun doubleStarMakesBold() {
        // Al escribir el cierre: los asteriscos se van y queda un espacio después.
        val text = SpannableStringBuilder("")
        val cursor = typeEach(text, 0, "a **negrita**")
        assertEquals("p|a {b}negrita{/b} ", describe(text))
        assertEquals(text.length, cursor)
        // Con el espacio después del cierre, en un mismo commitText.
        val space = SpannableStringBuilder("")
        assertEquals(5, commit(space, 0, "**bien** "))
        assertEquals("p|{b}bien{/b} ", describe(space))
    }

    @Test
    fun singleStarAndUnderscoreMakeItalic() {
        assertEquals("p|{i}a{/i} ", describe(typed("*a*")))
        assertEquals("p|{i}a{/i} ", describe(typed("_a_")))
        // Un guion bajo dentro de una palabra no abre cursiva.
        assertEquals("p|snake_case_x", describe(typed("snake_case_x")))
        // Con contenido que empieza o termina en espacio, no.
        assertEquals("p|x * a*", describe(typed("x * a*")))
    }

    @Test
    fun tildesMakeS() {
        val text = typed("~~a~~")
        assertEquals("p|{s}a{/s} ", describe(text))
        assertTrue(RichHtml.toHtml(text).contains("<s>a</s>"))
    }

    @Test
    fun backtickClosesCode() {
        val text = SpannableStringBuilder("")
        val cursor = typeEach(text, 0, "`x`")
        assertEquals("p|{code}x{/code} ", describe(text))
        typeEach(text, cursor, "y")
        assertEquals("p|{code}x{/code} y", describe(text))
    }

    @Test
    fun equalsMakeGreenHighlight() {
        // `=` no es un cierre: convierte con el espacio.
        assertEquals("p|==a==", describe(typed("==a==")))
        assertEquals("p|{hl=green}a{/hl} ", describe(typed("==a== ")))
    }

    @Test
    fun markdownLinkMakesLink() {
        val text = typed("ver [texto](https://example.com)")
        assertEquals("p|ver {a=https://example.com}texto{/a} ", describe(text))
    }

    @Test
    fun arrowsAndDashes() {
        assertEquals("p|— ", describe(typed("-- ")))
        assertEquals("p|a — b", describe(typed("a -- b")))
        assertEquals("p|-— ", describe(typed("--- ")))
        assertEquals("p|→ ", describe(typed("-> ")))
        assertEquals("p|← ", describe(typed("<- ")))
        assertEquals("p|↔ ", describe(typed("<-> ")))
        assertEquals("p|↑ ", describe(typed("|^ ")))
        assertEquals("p|↓ ", describe(typed("|v ")))
    }

    @Test
    fun threeDotsMakeEllipsis() {
        assertEquals("p|a…", describe(typed("a...")))
        assertEquals("p|a…b", describe(typed("a...b")))
    }

    @Test
    fun quotesOpenAndClose() {
        assertEquals("p|“hola”", describe(typed("\"hola\"")))
        assertEquals("p|‘hola’", describe(typed("'hola'")))
        assertEquals("p|don’t", describe(typed("don't")))
        assertEquals("p|(“a.”", describe(typed("(\"a.\"")))
    }

    @Test
    fun closeThenSpaceLeavesOneSpace() {
        // El espacio que agregó el cierre se traga el que se escribe ahí mismo.
        val text = SpannableStringBuilder("")
        val cursor = typeEach(text, 0, "**a** b")
        assertEquals("p|{b}a{/b} b", describe(text))
        assertEquals(text.length, cursor)
        // Si en el medio se escribió otra cosa, el espacio ya no se traga.
        assertEquals("p|{b}a{/b} x y", describe(typed("**a**x y")))
    }

    @Test
    fun closeBeforeExistingSpaceAddsNone() {
        val text = html("<div>**a* b</div>")
        val cursor = typeEach(text, 4, "*")
        assertEquals("p|{b}a{/b} b", describe(text))
        assertEquals(1, cursor)
    }

    @Test
    fun closeBeforeCommaAddsNone() {
        val text = html("<div>**a*, b</div>")
        val cursor = typeEach(text, 4, "*")
        assertEquals("p|{b}a{/b}, b", describe(text))
        assertEquals(1, cursor)
    }

    @Test
    fun twoQuotesOpenAndClose() {
        assertEquals("p|“”", describe(typed("\"\"")))
        assertEquals("p|‘’", describe(typed("''")))
    }

    @Test
    fun urlTrailingCommaNotInLink() {
        val text = typed("https://example.com, que")
        assertEquals("p|{a=https://example.com}https://example.com{/a}, que", describe(text))
        assertEquals("p|{a=https://example.com}example.com{/a}. ", describe(typed("example.com. ")))
    }

    private fun linkAttrs(text: SpannableStringBuilder) = text.getSpans(0, text.length, LinkMark::class.java).single().attrs

    @Test
    fun urlSpaceBecomesLink() {
        val text = typed("ir a https://example.com/x?y=1 ya")
        assertEquals("p|ir a {a=https://example.com/x?y=1}https://example.com/x?y=1{/a} ya", describe(text))
        // `<a href title spellcheck="false">`.
        assertEquals(mapOf("title" to "https://example.com/x?y=1", "spellcheck" to "false"), linkAttrs(text))
        // Con Enter también.
        val enter = typed("http://a.com\n")
        assertEquals("p|{a=http://a.com}http://a.com{/a}\np|", describe(enter))
        // Sin espacio todavía, no.
        assertEquals("p|https://example.com", describe(typed("https://example.com")))
    }

    @Test
    fun domainGetsHttps() {
        assertEquals("p|{a=https://example.com}example.com{/a} ", describe(typed("example.com ")))
        assertEquals("p|{a=https://www.example.com/x}www.example.com/x{/a} ", describe(typed("www.example.com/x ")))
        assertEquals("p|e.g. ", describe(typed("e.g. ")))
    }

    @Test
    fun emailGetsMailto() {
        assertEquals("p|{a=mailto:yo@mail.com}yo@mail.com{/a} ", describe(typed("yo@mail.com ")))
    }

    @Test
    fun notInsideInlineCode() {
        val text = html("<div><code>x</code></div>")
        // Lo escrito al final del código sigue en el código.
        typeEach(text, 1, "**a** -- example.com \"q\" ...")
        assertEquals("p|{code}x**a** -- example.com \"q\" ...{/code}", describe(text))
        val block = html("<pre></pre>")
        typeEach(block, 0, "**a** -- ")
        assertTrue(describe(block).startsWith("code|**a** -- "))
    }

    @Test
    fun worksInChecklistItem() {
        assertEquals("p|{b}a{/b} ", describe(typed("**a**", inItem = true)))
        assertEquals("p|— ", describe(typed("-- ", inItem = true)))
        assertEquals("p|{a=https://example.com}example.com{/a} ", describe(typed("example.com ", inItem = true)))
    }

    // ---- en el campo ----

    private val style = RichStyle(
        primary = 0xFF6750A4.toInt(),
        onSurfaceVariant = 0xFF49454F.toInt(),
        surfaceVariant = 0xFFE7E0EC.toInt(),
        checkedText = 0xFF909090.toInt(),
        onBackground = 0xFF1C1B1F.toInt(),
        dark = false,
        textSizePx = 16f,
        typeface = Typeface.DEFAULT,
        letterSpacingEm = 0.03125f,
        lineHeightPx = 28f,
        density = 1f,
    )

    private val emitted = ArrayList<RichText>()

    private val noFocus = UndoFocus(null, null, TextRange.Zero)

    private fun field(html: String = "<div></div>", cursor: Int? = null): RichEditText {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val field = RichEditText(activity)
        field.style = style
        field.onValueChange = { emitted.add(it) }
        activity.setContentView(
            field,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        field.requestFocus()
        val value = RichText.fromHtml(html)
        field.setValue(RichText(value.text, TextRange(cursor ?: value.plain.length)))
        idle()
        return field
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun RichEditText.input(): InputConnection = onCreateInputConnection(EditorInfo())!!

    @Test
    fun imeCommitWithSpaceTriggersShortcut() {
        val field = field()
        field.input().commitText("#", 1)
        field.input().commitText(" ", 1)
        assertEquals("h1|", describe(field.value.text))
        assertEquals(TextRange(0), field.value.selection)

        // También si el espacio viene en el mismo commitText.
        val other = field()
        other.input().commitText("- ", 1)
        assertEquals("bullet0|", describe(other.value.text))
        assertEquals(TextRange(0), other.value.selection)
    }

    @Test
    fun composingTextDoesNotTrigger() {
        val field = field()
        field.input().setComposingText("# ", 1)
        assertEquals("p|# ", describe(field.value.text))
        assertEquals("p|# ", describe(emitted.last().text))
    }

    @Test
    fun undoAfterShortcutRestoresTypedChars() {
        val field = field()
        field.input().commitText("#", 1)
        field.input().commitText(" ", 1)
        // Dos valores: lo escrito tal cual (escribir) y lo convertido (no es escribir).
        val (typed, converted) = emitted.takeLast(2)
        assertEquals("p|# ", describe(typed.text))
        assertFalse(typed.autoFormatted)
        assertEquals("h1|", describe(converted.text))
        assertTrue(converted.autoFormatted)

        // Con el deshacer de la nota, como NoteEditor: Ctrl+Z vuelve a "# ".
        val blocks = mutableListOf<EditorBlock>(EditorBlock.Text("t", RichText("")))
        var clock = 0L
        val undo = EditorUndo(blocks, NoteUndo(), now = { clock })
        undo.start(noFocus)
        for (value in emitted) {
            blocks[0] = EditorBlock.Text("t", value)
            clock += 10
            undo.bodyChanged(typing = !value.autoFormatted, noFocus)
        }
        undo.undo(noFocus)
        assertEquals("p|# ", describe((blocks[0] as EditorBlock.Text).value.text))
        undo.undo(noFocus)
        assertEquals("", (blocks[0] as EditorBlock.Text).value.plain)
    }

    @Test
    fun undoRestoresMarkdownChars() {
        val field = field()
        field.input().commitText("**a**", 1)
        val (typed, converted) = emitted.takeLast(2)
        assertEquals("p|**a**", describe(typed.text))
        assertFalse(typed.autoFormatted)
        assertEquals("p|{b}a{/b} ", describe(converted.text))
        assertTrue(converted.autoFormatted)
        assertEquals(TextRange(2), field.value.selection)

        // Ctrl+Z, con el deshacer de la nota como NoteEditor, vuelve a los asteriscos.
        val blocks = mutableListOf<EditorBlock>(EditorBlock.Text("t", RichText("")))
        var clock = 0L
        val undo = EditorUndo(blocks, NoteUndo(), now = { clock })
        undo.start(noFocus)
        for (value in emitted) {
            blocks[0] = EditorBlock.Text("t", value)
            clock += 10
            undo.bodyChanged(typing = !value.autoFormatted, noFocus)
        }
        undo.undo(noFocus)
        assertEquals("p|**a**", describe((blocks[0] as EditorBlock.Text).value.text))
        // Puesto en el campo, son los caracteres escritos, sin negrita.
        field.setValue((blocks[0] as EditorBlock.Text).value)
        assertEquals("**a**", field.text.toString())
        assertTrue(field.text.getSpans(0, field.text.length, BoldMark::class.java).isEmpty())
    }

    private fun paste(field: RichEditText, clip: String) {
        val clipboard = field.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("texto", clip))
        field.onTextContextMenuItem(android.R.id.paste)
    }

    @Test
    fun pasteDoesNotTriggerInlineShortcut() {
        // Pegar no es escribir.
        for (clip in listOf("nota **x** ", "- ", "1. ", "[] ", "-- ", "x.com ", "a...")) {
            val field = field()
            paste(field, clip)
            assertEquals(clip, "p|$clip", describe(field.value.text))
        }
        // Pegar un espacio justo después de un cierre convertido tampoco se lo traga.
        val field = field()
        field.input().commitText("**a**", 1)
        paste(field, " ")
        assertEquals("p|{b}a{/b}  ", describe(field.value.text))
    }

    @Test
    fun pasteWithNewlineDoesNotMakeDivider() {
        val field = field()
        paste(field, "---\n")
        assertEquals("p|---\np|", describe(field.value.text))
        val link = field()
        paste(link, "x.com\n")
        assertEquals("p|x.com\np|", describe(link.value.text))
    }
}
