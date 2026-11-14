package com.jot.app.ui.rich

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpanWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import androidx.compose.ui.text.TextRange
import com.jot.app.ui.EditorShortcut
import com.jot.app.ui.rich.RichDebug.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichEditTextTest {

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

    private val bold = mapOf(InlineFormat.Bold to true)

    private lateinit var activity: Activity
    private lateinit var field: RichEditText
    private val emitted = ArrayList<RichText>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        field = RichEditText(activity)
        field.style = style
        field.onValueChange = { emitted.add(it) }
        activity.setContentView(
            field,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        field.requestFocus()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Pone el valor desde el HTML, con el cursor en [cursor] (o al final). */
    private fun set(html: String, cursor: Int? = null, pending: Map<InlineFormat, Boolean> = emptyMap()) {
        val value = RichText.fromHtml(html)
        field.setValue(RichText(value.text, TextRange(cursor ?: value.plain.length), pending))
        idle()
    }

    private fun input(): InputConnection = field.onCreateInputConnection(EditorInfo())!!

    private fun last() = emitted.last()

    private fun press(keyCode: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        field.dispatchTouchEvent(event)
        event.recycle()
    }

    // ---- valor ----

    @Test
    fun setValueDoesNotEcho() {
        set("<div><b>a</b></div>")
        assertEquals("a", field.text.toString())
        assertEquals("p|{b}a{/b}", describe(field.value.text))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun setValueWithEmittedValueKeepsComposition() {
        set("<div>a</div>")
        input().setComposingText("ho", 1)
        val editable = field.text
        val value = last()
        assertEquals("aho", value.plain)

        field.setValue(value)

        assertSame(editable, field.text)
        assertEquals(1, BaseInputConnection.getComposingSpanStart(field.text))
        assertEquals(3, BaseInputConnection.getComposingSpanEnd(field.text))
    }

    @Test
    fun setValueWithEqualContentKeepsComposition() {
        set("<div>a</div>")
        input().setComposingText("ho", 1)
        val editable = field.text
        // Otro objeto con el mismo texto y las mismas marcas.
        field.setValue(RichText(last().text, last().selection))

        assertSame(editable, field.text)
        assertEquals(1, BaseInputConnection.getComposingSpanStart(field.text))
        assertEquals(3, BaseInputConnection.getComposingSpanEnd(field.text))
    }

    @Test
    fun typingEmitsValueWithMarks() {
        set("<div><b>a</b></div>")
        input().commitText("x", 1)
        assertEquals("p|{b}ax{/b}", describe(last().text))
        assertEquals(TextRange(2), last().selection)
    }

    @Test
    fun composingOnDividerKeepsComposingBelow() {
        // R8 con un teclado que compone: lo movido debajo del separador sigue siendo la composición.
        set("<div>a</div><hr><div>b</div>", cursor = 3)
        input().setComposingText("h", 1)
        input().setComposingText("ho", 1)
        assertEquals("<div>a</div><hr><div>ho</div><div>b</div>", last().toHtml())
        assertEquals(TextRange(6), last().selection)
    }

    @Test
    fun recomposingLinkWordKeepsAddedTextOutside() {
        // R49 con Gboard/Samsung: al poner el cursor al final de la palabra la recomponen entera.
        set("<div><a href=\"u\">ab</a></div>", cursor = 2)
        input().setComposingRegion(0, 2)
        input().setComposingText("abx", 1)
        assertEquals("p|{a=u}ab{/a}x", describe(last().text))

        // Lo mismo al principio del enlace.
        set("<div><a href=\"u\">ab</a></div>", cursor = 0)
        input().setComposingRegion(0, 2)
        input().setComposingText("xab", 1)
        assertEquals("p|x{a=u}ab{/a}", describe(last().text))

        // Recomponer cambiando la palabra del enlace (autocorrección) sigue en el enlace.
        set("<div><a href=\"u\">ab</a></div>", cursor = 2)
        input().setComposingRegion(0, 2)
        input().setComposingText("cd", 1)
        assertEquals("p|{a=u}cd{/a}", describe(last().text))
    }

    @Test
    fun pendingSurvivesSetValue() {
        set("<div>a</div>", cursor = 1)
        field.setValue(RichText(field.value.text, TextRange(1), bold))
        assertEquals(bold, field.value.pending)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun pendingSurvivesTyping() {
        set("<div>a</div>", cursor = 1, pending = bold)
        input().commitText("x", 1)
        assertEquals("p|a{b}x{/b}", describe(last().text))
        assertEquals(bold, last().pending)
        assertEquals(bold, field.value.pending)
    }

    @Test
    fun pendingClearsOnCursorMove() {
        set("<div>ab</div>", cursor = 2, pending = bold)
        field.setSelection(0)
        assertEquals(TextRange(0), last().selection)
        assertTrue(last().pending.isEmpty())
    }

    // ---- pegar y teclado con estilos (Review Focus 3) ----

    private fun clip(clip: ClipData) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
    }

    // ---- imágenes desde el teclado o el portapapeles: van a la nota abierta ----

    @Test
    fun keyboardIsToldTheFieldTakesImages() {
        field.onMedia = { _, _ -> }
        val info = EditorInfo()
        field.onCreateInputConnection(info)
        assertTrue("image/*" in androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info))
    }

    @Test
    fun keyboardImageGoesToOnMedia() {
        val got = ArrayList<android.net.Uri>()
        field.onMedia = { uris, done -> got.addAll(uris); done() }
        set("hola")
        val info = EditorInfo()
        val connection = field.onCreateInputConnection(info)!!
        val uri = android.net.Uri.parse("content://teclado/imagen.png")
        val content = androidx.core.view.inputmethod.InputContentInfoCompat(
            uri, android.content.ClipDescription("imagen", arrayOf("image/png")), null,
        )

        val ok = androidx.core.view.inputmethod.InputConnectionCompat.commitContent(connection, info, content, 0, null)

        assertTrue(ok)
        assertEquals(listOf(uri), got)
        assertEquals("hola", field.text.toString())
    }

    @Test
    fun pastedImageGoesToOnMedia() {
        val got = ArrayList<android.net.Uri>()
        field.onMedia = { uris, done -> got.addAll(uris); done() }
        set("hola")
        val uri = android.net.Uri.parse("content://portapapeles/imagen.png")
        clip(ClipData(android.content.ClipDescription("imagen", arrayOf("image/png")), ClipData.Item(uri)))

        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals(listOf(uri), got)
        assertEquals("hola", field.text.toString())
    }

    @Test
    fun pasteMenuUsesClipboardHtml() {
        set("")
        // El texto plano y el HTML difieren: se ve cuál de los dos se pegó.
        clip(ClipData.newHtmlText("plano", "plano", "<b>html</b>"))

        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals("html", field.text.toString())
        // Los estilos son solo los de dibujo de la negrita, no los del portapapeles.
        assertTrue(field.text.getSpans(0, field.text.length, StyleSpan::class.java).all { it is RenderSpan })
        assertEquals("p|{b}html{/b}", describe(last().text))
        assertEquals(TextRange(4), last().selection)
        // Pegar es un paso propio del deshacer, no se agrupa con lo escrito.
        assertTrue(last().autoFormatted)
    }

    @Test
    fun pasteAsPlainTextMenuIsPlain() {
        set("")
        clip(ClipData.newHtmlText("plano", "**a** ", "<b>html</b>"))

        field.onTextContextMenuItem(android.R.id.pasteAsPlainText)

        // Sin formato y sin atajos de Markdown.
        assertEquals("p|**a** ", describe(last().text))
        assertTrue(last().autoFormatted)
    }

    @Test
    fun pasteHtmlInChecklistItemIsPlain() {
        field.item = ItemCallbacks(checked = false, onBackspaceAtStart = { false }, onShortcut = { false })
        set("")
        clip(ClipData.newHtmlText("plano", "plano", "<b>html</b>"))

        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals("p|plano", describe(last().text))
    }

    @Test
    fun pasteHtmlInCodeIsPlain() {
        set("<pre>xy</pre><div>z</div>", cursor = 1)
        clip(ClipData.newHtmlText("plano", "a\nb", "<b>html</b><div>otra</div>"))

        field.onTextContextMenuItem(android.R.id.paste)

        // El texto plano del portapapeles, por las reglas: queda en el bloque de código.
        assertEquals("code|xa\ncode|by\np|z", describe(last().text))
        assertTrue(last().autoFormatted)
    }

    @Test
    fun pastePlainMidHeadingKeepsHeading() {
        // Texto plano en un título, por el campo.
        set("<h1>Título</h1>", cursor = 2)
        clip(ClipData.newPlainText("plano", "p\nq"))

        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals("h1|Típ\nh1|qtulo", describe(last().text))
        assertEquals(TextRange(5), last().selection)
    }

    @Test
    fun pastePlainCrlfIsNormalized() {
        set("")
        clip(ClipData.newPlainText("plano", "a\r\nb\rc"))

        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals("p|a\np|b\np|c", describe(last().text))
        assertFalse('\r' in field.text.toString())
    }

    @Test
    fun pasteUrlOverSelectionMakesLink() {
        set("<div>ver aca</div>")
        field.setSelection(4, 7)
        clip(ClipData.newPlainText("url", "https://x.com"))

        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals("p|ver {a=https://x.com}aca{/a}", describe(last().text))
    }

    @Test
    fun imeCommitOfUrlActsAsPaste() {
        set("")

        input().commitText("https://example.com", 1)

        assertEquals("p|{a=https://example.com}https://example.com{/a}", describe(last().text))
        val link = last().text.getSpans(0, last().text.length, LinkMark::class.java).single()
        assertEquals("jot-break-all", link.attrs["class"])
        assertEquals(TextRange(19), last().selection)
        assertTrue(last().autoFormatted)

        // Lo que no es una URL entera se escribe como siempre.
        set("")
        input().commitText("hola", 1)
        assertEquals("p|hola", describe(last().text))
        assertFalse(last().autoFormatted)
    }

    @Test
    fun imeCommitWithStylesIsPlain() {
        set("")
        val styled = SpannableString("x").apply { setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        input().commitText(styled, 1)

        assertTrue(field.text.getSpans(0, field.text.length, StyleSpan::class.java).isEmpty())
        assertEquals("p|x", describe(last().text))
    }

    // ---- deshacer y estado (Review Focus 4 y 5) ----

    private fun shortcut(keyCode: Int, meta: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        return field.dispatchKeyShortcutEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, ctrl or meta))
    }

    @Test
    fun ctrlZCallsOnShortcut() {
        val shortcuts = ArrayList<EditorShortcut>()
        field.onShortcut = { shortcuts.add(it); true }
        set("")
        input().commitText("ab", 1)

        assertTrue(shortcut(KeyEvent.KEYCODE_Z, 0))

        assertEquals(listOf<EditorShortcut>(EditorShortcut.Undo), shortcuts)
        // El deshacer del EditText no corre: lo hace la nota entera.
        assertEquals("ab", field.text.toString())
    }

    @Test
    fun ctrlShiftZAndCtrlYRedo() {
        val shortcuts = ArrayList<EditorShortcut>()
        field.onShortcut = { shortcuts.add(it); true }
        set("")
        input().commitText("ab", 1)

        assertTrue(shortcut(KeyEvent.KEYCODE_Z, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        assertTrue(shortcut(KeyEvent.KEYCODE_Y, 0))

        assertEquals(listOf<EditorShortcut>(EditorShortcut.Redo, EditorShortcut.Redo), shortcuts)
        assertEquals("ab", field.text.toString())
    }

    @Test
    fun systemUndoMenuStillIgnored() {
        val shortcuts = ArrayList<EditorShortcut>()
        field.onShortcut = { shortcuts.add(it); true }
        set("")
        input().commitText("ab", 1)

        assertTrue(field.onTextContextMenuItem(android.R.id.undo))
        assertTrue(field.onTextContextMenuItem(android.R.id.redo))

        assertTrue(shortcuts.isEmpty())
        assertEquals("ab", field.text.toString())
    }

    @Test
    fun viewStateIsNotSaved() {
        assertFalse(field.isSaveEnabled)
    }

    // ---- atajos con Ctrl ----

    /** Lo que hace el editor de la nota con un atajo en línea: el comando sobre el valor del campo. */
    private fun applyInlineShortcuts(shortcuts: MutableList<EditorShortcut>) {
        field.onShortcut = { shortcut ->
            shortcuts.add(shortcut)
            if (shortcut is EditorShortcut.Inline) field.setValue(RichCommands.toggleInline(field.value, shortcut.format))
            true
        }
    }

    @Test
    fun ctrlBTogglesBoldWithoutSelection() {
        // Ctrl+B sin selección prende la negrita para lo que se escriba; otra vez la apaga.
        val shortcuts = ArrayList<EditorShortcut>()
        applyInlineShortcuts(shortcuts)
        set("<div>a</div>")

        assertTrue(shortcut(KeyEvent.KEYCODE_B, 0))
        input().commitText("b", 1)
        press(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        input().commitText("c", 1)

        assertEquals(listOf(EditorShortcut.Inline(InlineFormat.Bold), EditorShortcut.Inline(InlineFormat.Bold)), shortcuts)
        assertEquals("p|a{b}b{/b}c", describe(last().text))
    }

    @Test
    fun ctrlShiftXIsStrike() {
        val shortcuts = ArrayList<EditorShortcut>()
        applyInlineShortcuts(shortcuts)
        set("<div>ab</div>")
        field.setSelection(0, 2)

        assertTrue(shortcut(KeyEvent.KEYCODE_X, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))

        assertEquals(listOf<EditorShortcut>(EditorShortcut.Inline(InlineFormat.Strike)), shortcuts)
        // No corta: el texto sigue ahí, tachado con <strike> (el botón y Ctrl+Shift+X).
        assertEquals("<div><strike>ab</strike></div>", RichHtml.toHtml(field.value.text))
    }

    @Test
    fun itemShortcutsStillWinInChecklist() {
        val itemShortcuts = ArrayList<ItemShortcut>()
        val shortcuts = ArrayList<EditorShortcut>()
        field.onShortcut = { shortcuts.add(it); true }
        field.item = ItemCallbacks(checked = false, onBackspaceAtStart = { true }) { itemShortcuts.add(it); true }
        set("<div>abc</div>", cursor = 1)
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        // Ctrl+] y Ctrl+[ son del ítem, por las dos vías de la tecla.
        assertTrue(shortcut(KeyEvent.KEYCODE_RIGHT_BRACKET, 0))
        press(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl)
        assertTrue(shortcut(KeyEvent.KEYCODE_P, shift))
        assertEquals(listOf(ItemShortcut.Indent, ItemShortcut.Outdent, ItemShortcut.MoveUp), itemShortcuts)
        assertTrue(shortcuts.isEmpty())

        // Lo que no es del ítem sigue yendo al editor.
        assertTrue(shortcut(KeyEvent.KEYCODE_B, 0))
        assertEquals(listOf<EditorShortcut>(EditorShortcut.Inline(InlineFormat.Bold)), shortcuts)
        assertEquals("abc", field.text.toString())
    }

    // ---- teclas ----

    @Test
    fun deleteKeyAtItemStartOutdents() {
        set("<ul><li>a</li><li>b</li></ul>", cursor = 2)
        press(KeyEvent.KEYCODE_DEL)
        assertEquals("bullet0|a\np|b", describe(field.text))
        assertEquals("bullet0|a\np|b", describe(last().text))
        assertEquals(TextRange(2), last().selection)
    }

    @Test
    fun deleteSurroundingTextAtItemStart() {
        set("<ul><li>a</li><li><ul><li>b</li></ul></li></ul>", cursor = 2)
        assertEquals("bullet0|a\nbullet1|b", describe(field.text))

        assertTrue(input().deleteSurroundingText(1, 0))
        assertEquals("bullet0|a\nbullet0|b", describe(last().text))
        assertEquals(TextRange(2), last().selection)

        assertTrue(input().deleteSurroundingTextInCodePoints(1, 0))
        assertEquals("bullet0|a\np|b", describe(last().text))
        assertEquals(TextRange(2), last().selection)
    }

    @Test
    fun forwardDeleteJoinsLines() {
        // Supr al final de un ítem une los renglones, sin sacar la viñeta del de abajo.
        set("<div>a</div><ul><li>b</li></ul>", cursor = 1)
        press(KeyEvent.KEYCODE_FORWARD_DEL)
        assertEquals("p|ab", describe(last().text))

        set("<div>a</div><ul><li>b</li></ul>", cursor = 1)
        input().deleteSurroundingText(0, 1)
        assertEquals("p|ab", describe(last().text))
        assertEquals(TextRange(1), last().selection)
    }

    @Test
    fun enterCarriesFormatAsPending() {
        // Después de un Enter al final de algo en negrita, lo que se escribe sigue en negrita.
        set("<div><b>a</b></div>")
        input().commitText("\n", 1)
        assertEquals(bold, last().pending)
        input().commitText("x", 1)
        assertEquals("p|{b}a{/b}\np|{b}x{/b}", describe(last().text))
    }

    @Test
    fun tabIndentsListItem() {
        set("<ul><li>a</li></ul>", cursor = 1)
        press(KeyEvent.KEYCODE_TAB)
        assertEquals("bullet1|a", describe(last().text))
        press(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        assertEquals("bullet0|a", describe(last().text))
    }

    @Test
    fun tabRelayoutsWrappedItem() {
        // Un ítem de varios renglones: con la sangría nueva, los cortes son los de un campo armado de cero.
        set("<ul><li>" + "palabra ".repeat(12).trim() + "</li></ul>", cursor = 1)
        assertTrue(field.layout.lineCount > 1)
        press(KeyEvent.KEYCODE_TAB)
        press(KeyEvent.KEYCODE_TAB)
        assertEquals("bullet2|" + "palabra ".repeat(12).trim(), describe(field.text))

        val fresh = RichEditText(activity)
        fresh.style = style
        fresh.setValue(last())
        fresh.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(field.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        fresh.layout(0, 0, fresh.measuredWidth, fresh.measuredHeight)

        assertEquals(fresh.layout.lineCount, field.layout.lineCount)
        assertEquals(fresh.layout.getLineEnd(0), field.layout.getLineEnd(0))
    }

    /** Un campo nuevo con [value], medido con el ancho de [field]: el armado de cero. */
    private fun freshField(value: RichText): RichEditText {
        val fresh = RichEditText(activity)
        fresh.style = style
        fresh.setValue(value)
        fresh.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(field.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        fresh.layout(0, 0, fresh.measuredWidth, fresh.measuredHeight)
        return fresh
    }

    /** Cada renglón del `Layout`: dónde empieza y termina, su alto, su base y su ancho. */
    private fun lines(view: TextView): List<String> {
        val layout = view.layout
        return (0 until layout.lineCount).map { line ->
            "${layout.getLineStart(line)}-${layout.getLineEnd(line)} top=${layout.getLineTop(line)} " +
                "bottom=${layout.getLineBottom(line)} base=${layout.getLineBaseline(line)} " +
                "left=${layout.getLineLeft(line)} width=${layout.getLineWidth(line)}"
        }
    }

    private val formatted = "<div>hola</div><div><b>negra</b>.<i>ita</i>.<u>subr</u>.<code>cod</code>.<sub>ind</sub>.<sup>sup</sup>.</div>" +
        "<h1>tit1</h1><h3>tit3</h3><div>mismo</div>" +
        "<ul><li>vinA</li><li>" + "palabra ".repeat(14).trim() + "</li></ul><ol><li>num1</li><li>num2</li></ol>" +
        "<blockquote>cita</blockquote><pre>bloque</pre><hr><div>" + "larga ".repeat(16).trim() + "</div>" +
        "<h2>abajo</h2><div><span style=\"color: red\">rojo</span> y <b>más</b></div>"

    @Test
    @Config(sdk = [26, 30, 34, 35])
    fun fieldTextIsRichEditable() {
        set("<div>a</div>")
        assertTrue(field.text is RichEditable)
        // También un campo al que nunca se le puso valor.
        assertTrue(RichEditText(activity).text is RichEditable)
        // Los SpanWatcher del DynamicLayout y del TextView se llaman como los busca RichEditable:
        // si no, no se saltea nada.
        val watchers = field.text.getSpans(0, field.text.length, SpanWatcher::class.java).map { it.javaClass.name }
        assertTrue(watchers.toString(), watchers.containsAll(RichEditable.SKIPPED_WATCHERS))
    }

    @Test
    @Config(sdk = [26, 30, 34, 35])
    fun typingKeepsTheLayoutOfAFreshField() {
        // Los spans que el cambio solo corre no le llegan al DynamicLayout (RichEditable): los
        // renglones tienen que quedar como los de un campo armado de cero, arriba y abajo.
        set(formatted)
        val mismo = field.text.indexOf("mismo") + 5
        field.setSelection(mismo)
        repeat(6) { press(KeyEvent.KEYCODE_A) }
        assertEquals(lines(freshField(last())), lines(field))

        // En la lista que corta en dos renglones, arriba de todo y en la última línea.
        field.setSelection(field.text.indexOf("palabra") + 3)
        repeat(12) { press(KeyEvent.KEYCODE_B) }
        field.setSelection(0)
        repeat(3) { press(KeyEvent.KEYCODE_C) }
        field.setSelection(field.text.length)
        repeat(3) { press(KeyEvent.KEYCODE_D) }
        assertEquals(lines(freshField(last())), lines(field))

        // Enter y Backspace corren los renglones de abajo para un lado y para el otro.
        field.setSelection(mismo)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(lines(freshField(last())), lines(field))
        press(KeyEvent.KEYCODE_DEL)
        repeat(4) { press(KeyEvent.KEYCODE_DEL) }
        assertEquals(lines(freshField(last())), lines(field))
    }

    @Test
    @Config(sdk = [26, 30, 34, 35])
    fun typingAboveAFirstHeadingKeepsTheLayoutOfAFreshField() {
        set("<h1>arriba</h1>" + formatted, cursor = 3)
        repeat(5) { press(KeyEvent.KEYCODE_A) }
        assertEquals(lines(freshField(last())), lines(field))
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(lines(freshField(last())), lines(field))
    }

    @Test
    fun otherSpanWatchersStillSeeShiftedSpans() {
        // Solo el DynamicLayout deja de enterarse: los demás siguen viendo los spans corridos.
        set("<div>a <b>negra</b></div>", cursor = 1)
        val shifted = ArrayList<String>()
        field.text.setSpan(
            object : SpanWatcher {
                override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = Unit
                override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) = Unit
                override fun onSpanChanged(text: Spannable, what: Any, ost: Int, oen: Int, nst: Int, nen: Int) {
                    if (what is BoldMark) shifted.add("$ost-$oen>$nst-$nen")
                }
            },
            0, field.text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        press(KeyEvent.KEYCODE_X)
        assertEquals(listOf("2-7>3-8"), shifted)
    }

    /** Anota los rangos donde se pone un [RichEditText.Relayout] en el texto del campo. */
    private fun recordRelayouts(): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        field.text.setSpan(
            object : SpanWatcher {
                override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) {
                    if (what is RichEditText.Relayout) ranges.add(start..end)
                }

                override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) = Unit
                override fun onSpanChanged(text: Spannable, what: Any, ost: Int, oen: Int, nst: Int, nen: Int) = Unit
            },
            0, field.text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return ranges
    }

    /** Anota dónde se sacan o se ponen spans de dibujo en el texto del campo. */
    private fun recordRenderSpans(): List<Pair<String, Int>> {
        val changes = ArrayList<Pair<String, Int>>()
        field.text.setSpan(
            object : SpanWatcher {
                override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) {
                    if (what is RenderSpan) changes.add("+${what.javaClass.simpleName}" to start)
                }

                override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) {
                    if (what is RenderSpan) changes.add("-${what.javaClass.simpleName}" to start)
                }

                override fun onSpanChanged(text: Spannable, what: Any, ost: Int, oen: Int, nst: Int, nen: Int) = Unit
            },
            0, field.text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return changes
    }

    @Test
    fun typingTouchesOnlyRenderSpansOfItsParagraph() {
        // R44 (b): cada tecla no saca y vuelve a poner el dibujo de los otros párrafos.
        set(
            "<h2>T</h2><div>a <b>b</b></div><blockquote>q</blockquote><div>p</div>" +
                "<ul><li><i>l</i></li></ul><h3>U</h3>",
            cursor = 9,
        )
        assertEquals('p', field.text[8])
        val changes = recordRenderSpans()
        input().commitText("x", 1)
        assertEquals("h2|T\np|a {b}b{/b}\nquote|q\np|px\nbullet0|{i}l{/i}\nh3|U", describe(last().text))
        assertTrue("cambios: $changes", changes.isEmpty())

        // En un párrafo con formato, solo cambia lo de ese párrafo.
        field.setSelection(5)
        val inBold = recordRenderSpans()
        input().commitText("y", 1)
        assertEquals("h2|T\np|a {b}by{/b}\nquote|q\np|px\nbullet0|{i}l{/i}\nh3|U", describe(last().text))
        assertTrue("cambios: $inBold", inBold.isNotEmpty() && inBold.all { (_, at) -> at in 2..6 })
    }

    @Test
    fun setValueSkipsTheValueAlreadySet() {
        // Los bloques que no se editan reciben el mismo valor en cada tecla: no se compara.
        set("<div>a</div>")
        val value = RichText(field.value.text, TextRange(1))
        field.setValue(value)
        val editable = field.text
        field.setValue(value)
        assertSame(editable, field.text)
        // Después de escribir, el mismo valor viejo vuelve a ponerse.
        input().commitText("x", 1)
        field.setValue(value)
        assertEquals("a", field.text.toString())
    }

    @Test
    fun typingInPlainParagraphDoesNotRelayout() {
        // Escribir una letra sin cambiar ningún rótulo no rearma el bloque entero.
        set("<div>a</div><ul><li>b</li></ul>", cursor = 1)
        val relayouts = recordRelayouts()
        input().commitText("x", 1)
        assertEquals("p|ax\nbullet0|b", describe(last().text))
        assertTrue("rearmados: $relayouts", relayouts.isEmpty())
    }

    @Test
    fun typingBelowAHeadingFirstDoesNotRelayout() {
        // Con un título arriba, cada tecla saca y vuelve a poner su tamaño: la caja de los
        // renglones vuelve a ser la misma y no se rearma todo el texto.
        set("<h2>T</h2><div>b</div>")
        val relayouts = recordRelayouts()
        input().commitText("x", 1)
        assertEquals("h2|T\np|bx", describe(last().text))
        assertTrue("rearmados: $relayouts", relayouts.isEmpty())
    }

    @Test
    fun firstLineLeavingHeadingRelayoutsEverything() {
        // Enter al principio del título: el primer renglón pasa a párrafo, la caja de todos los
        // renglones cambia y se rearma todo; queda como un campo armado de cero.
        set("<h2>T</h2><div>b</div><div>c</div>", cursor = 0)
        val relayouts = recordRelayouts()
        input().commitText("\n", 1)
        assertEquals("p|\nh2|T\np|b\np|c", describe(last().text))
        assertTrue("rearmados: $relayouts", relayouts.any { it.first == 0 && it.last == field.text.length })

        val fresh = RichEditText(activity)
        fresh.style = style
        fresh.setValue(last())
        fresh.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(field.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        fresh.layout(0, 0, fresh.measuredWidth, fresh.measuredHeight)
        for (line in 0 until fresh.layout.lineCount) {
            assertEquals(fresh.layout.getLineBaseline(line), field.layout.getLineBaseline(line))
        }
    }

    @Test
    fun renumberingRelayoutsWhatChanged() {
        // Enter en el primer ítem: los de abajo se renumeran y el 9 pasa a "10. ".
        set("<ol>" + "<li>x</li>".repeat(9) + "</ol>", cursor = 1)
        val relayouts = recordRelayouts()
        input().commitText("\n", 1)
        val text = field.text
        val lastItem = lineStartOf(text, text.length)
        assertEquals(1, relayouts.size)
        val range = relayouts.single()
        assertTrue("rango $range", range.first > 0 && lastItem in range && range.last == text.length)
    }

    @Test
    fun arrowDownLeavesCodeAtTheEnd() {
        set("<pre spellcheck=\"false\">a</pre>", cursor = 1)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("code|a\np|", describe(last().text))
        assertEquals(TextRange(2), last().selection)
    }

    @Test
    fun arrowUpAtFirstLineCallsPrevious() {
        set("<div>ab</div><div>cd</div>", cursor = 1)
        var previous: Float? = null
        var next: Float? = null
        field.onFocusPrevious = { previous = it }
        field.onFocusNext = { next = it }

        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(field.geometry.horizontal(1), previous!!, 0.01f)

        // En el último renglón, flecha abajo pasa al bloque de abajo.
        field.setSelection(4)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(field.geometry.horizontal(4), next!!, 0.01f)
    }

    // ---- el resto de las teclas: cada una es un paso propio del deshacer ----

    @Test
    fun enterAtQuoteStartInsertsLineBefore() {
        set("<blockquote><div>ab</div></blockquote>", cursor = 0)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("p|\nquote|ab", describe(last().text))
        assertEquals(TextRange(1), last().selection)
        assertTrue(last().autoFormatted)

        // Por el teclado en pantalla, igual.
        set("<pre spellcheck=\"false\">ab</pre>", cursor = 0)
        input().commitText("\n", 1)
        assertEquals("p|\ncode|ab", describe(last().text))
        assertEquals(TextRange(1), last().selection)
    }

    @Test
    fun backspaceAtCodeStartUnwraps() {
        set("<div>x</div><pre spellcheck=\"false\">a</pre>", cursor = 2)
        press(KeyEvent.KEYCODE_DEL)
        assertEquals("p|x\np|a", describe(last().text))
        assertEquals(TextRange(2), last().selection)
        assertTrue(last().autoFormatted)

        set("<div>x</div><pre spellcheck=\"false\">a</pre>", cursor = 2)
        assertTrue(input().deleteSurroundingText(1, 0))
        assertEquals("p|x\np|a", describe(last().text))
    }

    @Test
    fun backspaceAfterDividerRemovesIt() {
        set("<div>a</div><hr><div>b</div>", cursor = 4)
        press(KeyEvent.KEYCODE_DEL)
        assertEquals("p|a\np|b", describe(last().text))
        assertEquals(TextRange(2), last().selection)
        assertTrue(last().autoFormatted)

        set("<div>a</div><hr><div>b</div>", cursor = 4)
        assertTrue(input().deleteSurroundingText(1, 0))
        assertEquals("p|a\np|b", describe(last().text))
        assertEquals(TextRange(2), last().selection)
    }

    @Test
    fun escapeLeavesQuote() {
        set("<blockquote><div>a</div></blockquote>", cursor = 1)
        press(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("quote|a\np|", describe(last().text))
        assertEquals(TextRange(2), last().selection)
        assertTrue(last().autoFormatted)
    }

    @Test
    fun escapeOutsideBlockDoesNothing() {
        set("<div>a</div>", cursor = 1)
        press(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("a", field.text.toString())
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun arrowUpAtFirstCodeAddsLine() {
        var previous = 0
        field.onFocusPrevious = { previous++ }

        // Si el bloque no es lo primero de la nota, la flecha pasa al de arriba, como siempre.
        set("<pre spellcheck=\"false\">a</pre>", cursor = 1)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, previous)
        assertTrue(emitted.isEmpty())

        field.isFirstInNote = { true }
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("p|\ncode|a", describe(last().text))
        assertEquals(TextRange(0), last().selection)
        assertTrue(last().autoFormatted)
        assertEquals(1, previous)
    }

    @Test
    fun arrowRightLeavesInlineCode() {
        set("<div><code>x</code></div>", cursor = 1)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("p|{code}x{/code} ", describe(last().text))
        assertEquals(TextRange(2), last().selection)
        assertTrue(last().autoFormatted)
    }

    // ---- R35: la flecha al borde del campo no saca el foco a la barra ----

    private fun dispatchKey(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val consumed = field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0))
        return consumed
    }

    @Test
    fun rightArrowAtEndMovesToNextBlock() {
        set("<div>ab</div>", cursor = 2)
        var next: Float? = null
        field.onFocusNext = { next = it }
        assertTrue(dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(0f, next!!, 0.01f)
    }

    @Test
    fun rightArrowAtEndWithoutNextIsConsumed() {
        set("<div>ab</div>", cursor = 2)
        assertTrue(dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(2, field.selectionEnd)
    }

    @Test
    fun leftArrowAtStartMovesToPreviousBlock() {
        set("<div>ab</div>", cursor = 0)
        var previous: Float? = null
        field.onFocusPrevious = { previous = it }
        assertTrue(dispatchKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ARROW_EDGE_END, previous!!, 0f)
    }

    @Test
    fun rightArrowInMiddleMovesCursor() {
        set("<div>abc</div>", cursor = 1)
        var called = false
        field.onFocusNext = { called = true }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(called)
        assertEquals(2, field.selectionEnd)
    }

    @Test
    fun shiftRightAtEndStillSelects() {
        set("<div>ab</div>", cursor = 1)
        var called = false
        field.onFocusNext = { called = true }
        pressWithShift(KeyEvent.KEYCODE_DPAD_RIGHT)
        pressWithShift(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(called)
        assertEquals(1, field.selectionStart)
        assertEquals(2, field.selectionEnd)
    }

    @Test
    fun tabOutsideListInsertsEmSpace() {
        set("<div>ab</div>", cursor = 1)
        press(KeyEvent.KEYCODE_TAB)
        assertEquals("p|a b", describe(last().text))
        assertEquals(TextRange(2), last().selection)
        assertTrue(last().autoFormatted)
    }

    @Test
    fun ctrlTabDoesNotInsertEmSpace() {
        val mods = listOf(
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
        )
        for (meta in mods) {
            set("<div>ab</div>", cursor = 1)
            press(KeyEvent.KEYCODE_TAB, meta)
            // Ctrl+Tab, Meta+Tab y Alt+Tab son del sistema: no escriben el espacio eme.
            assertFalse(field.text.toString().contains(' '))
        }
    }

    @Test
    fun shiftTabRemovesEmSpace() {
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        set("<div>a b</div>", cursor = 2)
        press(KeyEvent.KEYCODE_TAB, shift)
        assertEquals("p|ab", describe(last().text))
        assertEquals(TextRange(1), last().selection)
        assertTrue(last().autoFormatted)

        // Sin espacio eme antes del cursor no hace nada.
        val count = emitted.size
        press(KeyEvent.KEYCODE_TAB, shift)
        assertEquals("ab", field.text.toString())
        assertEquals(count, emitted.size)
    }

    /** Shift sostenido con el teclado físico: Shift abajo, la tecla y Shift arriba. */
    private fun pressWithShift(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, shift))
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, shift))
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, shift))
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        // Compose devuelve el valor que el campo acaba de mandar.
        field.setValue(last())
        idle()
    }

    @Test
    fun shiftArrowExtendsSelection() {
        // Cada Shift+flecha agranda la selección, como en el título y en el campo de antes.
        set("<div>abcdef</div>", cursor = 0)
        repeat(3) { pressWithShift(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(TextRange(0, 3), last().selection)
        assertEquals("abc", field.text.substring(field.selectionStart, field.selectionEnd))

        set("<div>uno</div>", cursor = 3)
        repeat(3) { pressWithShift(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertEquals("uno", field.text.substring(minOf(field.selectionStart, field.selectionEnd), maxOf(field.selectionStart, field.selectionEnd)))
    }

    @Test
    fun shiftUpExtendsSelectionUpwards() {
        set("<div>uno</div><div>dos</div><ul><li>tres</li><li></li></ul>")
        val end = field.text.length
        repeat(2) { pressWithShift(KeyEvent.KEYCODE_DPAD_UP) }
        pressWithShift(KeyEvent.KEYCODE_MOVE_END)
        assertEquals(end, field.selectionStart)
        assertEquals(7, field.selectionEnd)
    }

    @Test
    fun keyboardSelectionDoesNotOpenSelectionToolbar() {
        // En la tablet, la barra flotante que se abre tras Shift+flecha se cierra con la
        // tecla siguiente y deja el cursor al final. El campo de antes no la abría.
        set("<div>abcdef</div>", cursor = 0)
        pressWithShift(KeyEvent.KEYCODE_DPAD_RIGHT)
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
        assertNull(field.startActionMode(callback, ActionMode.TYPE_FLOATING))
    }

    // ---- cursor y desplazamiento ----

    /** El cursor que dibuja el `Editor` del `TextView` (no el último que se le pasó). */
    private fun drawnCursor(): Drawable {
        val editor = TextView::class.java.getDeclaredField("mEditor").apply { isAccessible = true }.get(field)
        val drawable = editor.javaClass.getDeclaredField("mDrawableForCursor").apply { isAccessible = true }.get(editor)
        return drawable as Drawable
    }

    @Test
    fun drawnCursorHasPrimaryColor() {
        // El Editor se queda con el primer cursor que recibe (el del estilo inicial, con el
        // color del texto del tema): el color primario tiene que llegar a ese.
        val shape = (drawnCursor() as DrawableWrapper).drawable as GradientDrawable
        assertEquals(style.primary, shape.color!!.defaultColor)

        val dark = 0xFFB3C8FF.toInt()
        field.style = style.copy(primary = dark, dark = true)
        val again = (drawnCursor() as DrawableWrapper).drawable as GradientDrawable
        assertEquals(dark, again.color!!.defaultColor)
    }

    @Test
    fun fieldDoesNotScrollItself() {
        // El campo mide todo su texto; desplaza la nota la columna de Compose, no el campo.
        set("<div>uno</div><div>dos</div><div>tres palabras</div>", cursor = 0)
        field.layout(0, 0, field.width, field.height / 2)
        field.setSelection(field.text.length - 8, field.text.length)
        field.bringPointIntoView(field.text.length)
        field.scrollTo(0, 40)
        assertEquals(0, field.scrollY)
        assertEquals(0, field.scrollX)
    }

    // ---- cursor a la vista ----

    @Test
    fun enterAtTheEndReportsTheCursorOnTheNewLine() {
        // Al final de la lista, cada tecla cambia el relleno de abajo (el último renglón vacío)
        // y el TextView tira su Layout hasta medir de nuevo: el aviso no se puede perder.
        val reported = ArrayList<android.graphics.RectF>()
        field.onCursorRect = { reported.add(it) }
        set("<ul><li data-checked=\"false\">a</li></ul>", cursor = 1)
        val input = input()

        input.commitText("\n", 1)
        idle()
        val afterEnter = field.geometry
        assertEquals(afterEnter.lineTop(1), reported.last().top)

        input.commitText("b", 1)
        idle()
        assertEquals(field.geometry.lineTop(1), reported.last().top)
        assertEquals(field.geometry.horizontal(field.selectionEnd), reported.last().left)

        input.commitText("\n", 1)
        idle()
        assertEquals(field.geometry.lineTop(2), reported.last().top)
    }

    // ---- casillero ----

    @Test
    fun tapOnCheckboxTogglesWithoutMovingCursor() {
        set("<ul><li data-checked=\"false\">tarea</li></ul>", cursor = 5)
        val geometry = field.geometry
        // La marca va del borde al principio del texto del ítem.
        val x = geometry.horizontal(0) / 4
        val y = (geometry.lineTop(0) + geometry.lineBottom(0)) / 2

        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)

        assertEquals("check0x|tarea", describe(last().text))
        assertEquals(TextRange(5), last().selection)
        assertEquals(5, field.selectionStart)
    }

    @Test
    fun tapOnCheckboxCancelsWhenFingerLeaves() {
        set("<ul><li data-checked=\"false\">a</li></ul><div>b</div>", cursor = 1)
        val geometry = field.geometry
        val x = geometry.horizontal(0) / 4
        val y = (geometry.lineTop(0) + geometry.lineBottom(0)) / 2

        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, (geometry.lineTop(1) + geometry.lineBottom(1)) / 2)

        assertEquals("check0|a\np|b", describe(field.text))
    }

    /** Dónde empieza el casillero y dónde el texto del ítem, para un rótulo con [depth] sangrías. */
    private fun checkboxSpan(depth: Int): Pair<Float, Float> {
        val indent = "  ".repeat(depth)
        val boxLeft = field.totalPaddingLeft + field.paint.measureText(indent)
        val textStart = field.totalPaddingLeft + field.paint.measureText("$indent☐ ")
        return boxLeft to textStart
    }

    private fun tap(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
    }

    @Test
    fun checkboxTouchZoneIsAtLeast48dpWideToTheLeftOfTheText() {
        // Un ítem con sangría: a la izquierda del casillero hay lugar en el campo.
        set("<ul><li>a</li><ul><li>b</li><ul><li data-checked=\"false\">tarea</li></ul></ul></ul>", cursor = 2)
        val (_, textStart) = checkboxSpan(2)
        val geometry = field.geometry
        val y = (geometry.lineTop(2) + geometry.lineBottom(2)) / 2

        tap(textStart - 46f, y)

        assertEquals("bullet0|a\nbullet1|b\ncheck2x|tarea", describe(last().text))
        assertEquals(TextRange(2), last().selection)
    }

    @Test
    fun checkboxTouchZoneCoversTheWholeRowHeight() {
        set("<ul><li data-checked=\"false\">a</li><li data-checked=\"false\">b</li></ul>", cursor = 1)
        val (boxLeft, _) = checkboxSpan(0)
        val geometry = field.geometry

        tap(boxLeft + 1f, geometry.lineBottom(1) - 1f)

        assertEquals("check0|a\ncheck0x|b", describe(last().text))
        assertEquals(TextRange(1), last().selection)
    }

    @Test
    fun checkboxTouchZoneReachesIntoTheMarginOfTheRowBelow() {
        // Abajo, una viñeta con sangría: su margen está vacío y sigue siendo del casillero.
        set("<ul><li data-checked=\"false\">a</li><ul><li>b</li></ul></ul>", cursor = 1)
        val (boxLeft, _) = checkboxSpan(0)
        val geometry = field.geometry

        tap(boxLeft + 1f, geometry.lineTop(1) + 8f)

        assertEquals("check0x|a\nbullet1|b", describe(last().text))
    }

    @Test
    fun touchOnTheTextOfACheckItemMovesTheCursor() {
        set("<ul><li data-checked=\"false\">tarea</li></ul>", cursor = 5)
        val (_, textStart) = checkboxSpan(0)
        val geometry = field.geometry
        val y = (geometry.lineTop(0) + geometry.lineBottom(0)) / 2

        tap(textStart + 2f, y)

        assertEquals("check0|tarea", describe(field.text))
        assertEquals(0, field.selectionStart)
    }

    @Test
    fun touchOnPlainTextBelowACheckItemMovesTheCursor() {
        set("<ul><li data-checked=\"false\">a</li></ul><div>bb</div>", cursor = 1)
        val geometry = field.geometry

        tap(1f, geometry.lineTop(1) + 4f)

        assertEquals("check0|a\np|bb", describe(field.text))
        assertEquals(2, field.selectionStart)
    }

    // ---- dibujo ----

    /** Un lienzo que anota los rótulos que se dibujan con `drawText(String, …)`. */
    private class Recorder(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = ArrayList<Pair<String, Float>>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts.add(text to y)
            super.drawText(text, x, y, paint)
        }
    }

    @Test
    fun emptyLastItemDrawsMarker() {
        // Después de Enter al final de una lista, el ítem nuevo (vacío) muestra su viñeta.
        set("<ul><li>a</li></ul>")
        input().commitText("\n", 1)
        idle()
        assertEquals("bullet0|a\nbullet0|", describe(field.text))
        assertTrue(field.width > 0 && field.height > 0)

        val canvas = Recorder(Bitmap.createBitmap(field.width, field.height, Bitmap.Config.ARGB_8888))
        field.draw(canvas)

        val layout = assertNotNullLayout()
        val bullets = canvas.texts.filter { it.first == "• " }.map { it.second }
        // El campo dibuja el texto con el lienzo corrido al relleno: la y es la del Layout. El
        // último renglón vacío no pasa por el span de interlineado: su base va donde la ponía el
        // campo de antes, con la caja de un renglón de 28 sp (LineBox).
        val box = LineBox.of(field.paint.fontMetricsInt, style.lineHeightPx)
        val lastBaseline = (layout.getLineTop(1) - box.ascent).toFloat()
        assertEquals(2, bullets.size)
        assertTrue("viñeta en el último renglón: $bullets", bullets.any { kotlin.math.abs(it - lastBaseline) < 1f })
    }

    @Test
    fun emptyLastItemCursorIsAfterMarker() {
        // El cursor del ítem vacío del final se dibuja después de la viñeta.
        set("<ul><li>a</li></ul>")
        val margin = field.geometry.horizontal(0)
        input().commitText("\n", 1)
        idle()
        field.draw(Canvas(Bitmap.createBitmap(field.width, field.height, Bitmap.Config.ARGB_8888)))
        val cursor = drawnCursor()
        val shift = cursor.javaClass.getDeclaredMethod("getShift").apply { isAccessible = true }.invoke(cursor) as Float
        assertTrue("corrimiento $shift", shift > 0f)
        assertEquals(margin, shift, 0.5f)
    }

    @Test
    fun enterAtEndOfListKeepsItemMargin() {
        // El ítem vacío del final no le suma su margen al ítem de arriba.
        set("<ul><li>a</li></ul>")
        val margin = field.geometry.horizontal(0)
        input().commitText("\n", 1)
        idle()
        assertEquals(margin, field.geometry.horizontal(0), 0.5f)

        // Escribir en el ítem nuevo lo deja con su margen, igual que al abrir la nota.
        input().commitText("u", 1)
        idle()
        assertEquals(margin, field.geometry.horizontal(0), 0.5f)
        assertEquals(margin, field.geometry.horizontal(2), 0.5f)
    }

    @Test
    fun tabBeforeEmptyLastItemKeepsMargins() {
        // Tab en un ítem con un ítem vacío debajo, al final del texto.
        set("<ul><li>a</li><li>b</li></ul>", cursor = 3)
        input().commitText("\n", 1)
        field.setSelection(3)
        press(KeyEvent.KEYCODE_TAB)
        idle()
        val typed = field.geometry.horizontal(2)

        set(last().toHtml())
        assertEquals(field.geometry.horizontal(2), typed, 0.5f)
    }

    private fun assertNotNullLayout() = field.layout.also { assertNotNull(it) }

    // ---- arrastrar para desplazar la nota ----

    /** Un evento de un dedo en [time], con el botón principal si [button] (scrcpy manda así el mouse). */
    private fun finger(action: Int, x: Float, y: Float, time: Long, down: Long, button: Boolean = false) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val buttons = if (button && action != MotionEvent.ACTION_UP) MotionEvent.BUTTON_PRIMARY else 0
        val event = MotionEvent.obtain(
            down, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        field.dispatchTouchEvent(event)
        event.recycle()
    }

    private class DisallowRecorder(context: Context) : android.widget.FrameLayout(context) {
        var disallowed = false
        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            if (disallow) disallowed = true
            super.requestDisallowInterceptTouchEvent(disallow)
        }
    }

    private fun inRecorder(): DisallowRecorder {
        val parent = DisallowRecorder(activity)
        (field.parent as ViewGroup).removeView(field)
        parent.addView(field, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(parent)
        field.requestFocus()
        idle()
        set("<div>uno dos tres cuatro</div><div>cinco seis siete</div><div>ocho nueve diez</div><div>once doce trece</div>", cursor = 0)
        return parent
    }

    /**
     * Un arrastre que pasa el margen de toque yendo más vertical que horizontal es desplazar la
     * nota: el campo no le pide al padre que no intercepte ni mueve el cursor.
     */
    @Test
    fun verticalDragDoesNotTakeTheGestureFromTheScroll() {
        val parent = inRecorder()
        val before = field.selectionStart
        val t = SystemClock.uptimeMillis()
        finger(MotionEvent.ACTION_DOWN, 40f, 20f, t, t)
        finger(MotionEvent.ACTION_MOVE, 48f, 60f, t + 16, t)
        finger(MotionEvent.ACTION_MOVE, 90f, 90f, t + 32, t)
        finger(MotionEvent.ACTION_MOVE, 92f, 100f, t + 48, t)
        finger(MotionEvent.ACTION_CANCEL, 92f, 100f, t + 64, t)
        idle()
        assertFalse(parent.disallowed)
        assertEquals(before, field.selectionStart)
    }

    /**
     * Arrastrar de costado le llega al campo como siempre: con el dedo, el Editor lleva el
     * cursor adonde va el dedo (el mismo camino por el que el mouse de scrcpy selecciona).
     */
    @Test
    @org.robolectric.annotation.Config(shadows = [ShadowNoMagnifier::class])
    fun horizontalDragReachesTheField() {
        inRecorder()
        val before = field.selectionStart
        val t = SystemClock.uptimeMillis()
        finger(MotionEvent.ACTION_DOWN, 5f, 14f, t, t)
        finger(MotionEvent.ACTION_MOVE, 40f, 15f, t + 16, t)
        finger(MotionEvent.ACTION_MOVE, 90f, 15f, t + 32, t)
        finger(MotionEvent.ACTION_UP, 90f, 15f, t + 48, t)
        idle()
        assertTrue("antes $before, después ${field.selectionStart}", field.selectionStart > before)
    }

    /** Arrastrar con el botón del mouse selecciona. */
    @Test
    @org.robolectric.annotation.Config(shadows = [ShadowNoMagnifier::class])
    fun mouseDragSelects() {
        inRecorder()
        val t = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, 5f, 14f, t, t)
        mouse(MotionEvent.ACTION_MOVE, 40f, 15f, t + 16, t)
        mouse(MotionEvent.ACTION_MOVE, 90f, 15f, t + 32, t)
        mouse(MotionEvent.ACTION_UP, 90f, 15f, t + 48, t)
        idle()
        assertTrue(field.selectionEnd - field.selectionStart > 0)
    }

    private fun mouse(action: Int, x: Float, y: Float, time: Long, down: Long) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val buttons = if (action != MotionEvent.ACTION_UP) MotionEvent.BUTTON_PRIMARY else 0
        val event = MotionEvent.obtain(
            down, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_MOUSE, 0,
        )
        field.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Tocar dos veces y arrastrar extiende la selección de la palabra. */
    @Test
    @org.robolectric.annotation.Config(shadows = [ShadowNoMagnifier::class])
    fun doubleTapAndDragExtendsSelection() {
        inRecorder()
        val t = SystemClock.uptimeMillis()
        finger(MotionEvent.ACTION_DOWN, 10f, 14f, t, t)
        finger(MotionEvent.ACTION_UP, 10f, 14f, t + 40, t)
        finger(MotionEvent.ACTION_DOWN, 10f, 14f, t + 120, t + 120)
        finger(MotionEvent.ACTION_UP, 10f, 14f, t + 160, t + 120)
        idle()
        val word = field.selectionEnd - field.selectionStart
        val t2 = t + 400
        finger(MotionEvent.ACTION_DOWN, 10f, 14f, t2, t2)
        finger(MotionEvent.ACTION_UP, 10f, 14f, t2 + 40, t2)
        finger(MotionEvent.ACTION_DOWN, 10f, 14f, t2 + 120, t2 + 120)
        finger(MotionEvent.ACTION_MOVE, 12f, 50f, t2 + 140, t2 + 120)
        finger(MotionEvent.ACTION_MOVE, 14f, 90f, t2 + 160, t2 + 120)
        finger(MotionEvent.ACTION_UP, 14f, 90f, t2 + 180, t2 + 120)
        idle()
        assertTrue("palabra $word, después ${field.selectionEnd - field.selectionStart}", field.selectionEnd - field.selectionStart > word)
    }

    /** Después de un toque largo, el movimiento (también vertical) llega al campo y extiende la selección. */
    @Test
    @org.robolectric.annotation.Config(shadows = [ShadowNoMagnifier::class])
    fun moveAfterLongPressReachesTheField() {
        inRecorder()
        val t = SystemClock.uptimeMillis()
        finger(MotionEvent.ACTION_DOWN, 10f, 14f, t, t)
        field.performLongClick()
        idle()
        val word = field.selectionEnd - field.selectionStart
        finger(MotionEvent.ACTION_MOVE, 12f, 50f, t + 600, t)
        finger(MotionEvent.ACTION_MOVE, 14f, 90f, t + 620, t)
        finger(MotionEvent.ACTION_UP, 14f, 90f, t + 640, t)
        idle()
        assertTrue("palabra $word, después ${field.selectionEnd - field.selectionStart}", field.selectionEnd - field.selectionStart > word)
    }
}
