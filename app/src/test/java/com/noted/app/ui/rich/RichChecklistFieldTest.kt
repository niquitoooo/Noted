package com.noted.app.ui.rich

import android.app.Activity
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** El campo con formato en modo ítem de checklist (Task 4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichChecklistFieldTest {

    private val style = RichStyle(
        primary = 0xFF6750A4.toInt(),
        onSurfaceVariant = 0xFF49454F.toInt(),
        surfaceVariant = 0xFFE7E0EC.toInt(),
        checkedText = 0xFF909090.toInt(),
        onBackground = 0xFF1C1B1F.toInt(),
        dark = false,
        textSizePx = 16f,
        typeface = Typeface.DEFAULT,
        letterSpacingEm = 0f,
        lineHeightPx = 28f,
        density = 1f,
    )

    private lateinit var field: RichEditText
    private val emitted = ArrayList<RichText>()
    private var backspaces = 0
    private val shortcuts = ArrayList<ItemShortcut>()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
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

    private fun item(checked: Boolean = false, onBackspace: () -> Boolean = { backspaces++; true }) =
        ItemCallbacks(checked, onBackspace) { shortcuts.add(it); true }

    private fun set(html: String, cursor: Int) {
        val value = RichText.fromHtml(html)
        field.setValue(RichText(value.text, TextRange(cursor)))
        idle()
    }

    private fun input(): InputConnection = field.onCreateInputConnection(EditorInfo())!!

    private fun press(keyCode: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    @Test
    fun backspaceAtStartCallsItem() {
        field.item = item()
        set("<div>abc</div>", cursor = 0)
        press(KeyEvent.KEYCODE_DEL)
        assertEquals(1, backspaces)
        assertEquals("abc", field.text.toString())
    }

    @Test
    fun imeBackspaceAtStartCallsItem() {
        field.item = item()
        set("<div>abc</div>", cursor = 0)
        assertTrue(input().deleteSurroundingText(1, 0))
        assertEquals(1, backspaces)
        assertEquals("abc", field.text.toString())
        assertTrue(input().deleteSurroundingTextInCodePoints(1, 0))
        assertEquals(2, backspaces)
        assertEquals("abc", field.text.toString())
    }

    @Test
    fun backspaceAtStartConsumedWhenNothingToDo() {
        field.item = item(onBackspace = { true })
        set("<div>abc</div>", cursor = 0)
        val now = SystemClock.uptimeMillis()
        assertTrue(field.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, 0)))
        assertTrue(emitted.isEmpty())
        assertEquals("abc", field.text.toString())
    }

    @Test
    fun backspaceInsideTextIsNormal() {
        field.item = item()
        set("<div>abc</div>", cursor = 2)
        press(KeyEvent.KEYCODE_DEL)
        assertEquals(0, backspaces)
        assertEquals("ac", emitted.last().plain)
    }

    @Test
    fun shortcutsReachItem() {
        field.item = item()
        set("<div>abc</div>", cursor = 1)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        press(KeyEvent.KEYCODE_TAB)
        press(KeyEvent.KEYCODE_TAB, shift)
        press(KeyEvent.KEYCODE_RIGHT_BRACKET, ctrl)
        press(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl)
        press(KeyEvent.KEYCODE_P, ctrl or shift)
        press(KeyEvent.KEYCODE_O, ctrl or shift)
        assertEquals(
            listOf(
                ItemShortcut.Indent, ItemShortcut.Outdent, ItemShortcut.Indent,
                ItemShortcut.Outdent, ItemShortcut.MoveUp, ItemShortcut.MoveDown,
            ),
            shortcuts,
        )
        assertEquals("abc", field.text.toString())

        shortcuts.clear()
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_RIGHT_BRACKET, 0, ctrl)
        assertTrue(field.onKeyShortcut(KeyEvent.KEYCODE_RIGHT_BRACKET, event))
        assertEquals(listOf(ItemShortcut.Indent), shortcuts)
    }

    @Test
    fun imeCommitWithNewlineReachesCallback() {
        field.item = item()
        set("<div></div>", cursor = 0)
        input().commitText("hola\n", 1)
        assertEquals("hola\n", emitted.last().plain)
    }

    /** El color que se ve en [index]: el del último `ForegroundColorSpan` que la cubre. */
    private fun colorAt(index: Int): Int? {
        val text = field.text
        return text.getSpans(index, index + 1, ForegroundColorSpan::class.java)
            .filter { text.getSpanStart(it) <= index && text.getSpanEnd(it) > index }
            .lastOrNull()?.foregroundColor
    }

    @Test
    fun checkedItemIsStruckAndGray() {
        val html = """<div><span class="noted-text-red">a</span> <a href="https://x.com">b</a></div>"""
        field.item = item(checked = true)
        set(html, cursor = 0)
        assertTrue(field.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0)
        assertEquals(style.checkedItemText, colorAt(0))
        assertEquals(style.checkedItemText, colorAt(2))

        field.item = item(checked = false)
        idle()
        assertEquals(0, field.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG)
        val red = colorAt(0)
        assertNotEquals(null, red)
        assertNotEquals(style.checkedItemText, red)
    }

    @Test
    fun checkedItemTextIsOnBackgroundAt38Percent() {
        assertEquals((style.onBackground and 0x00FFFFFF) or (0x61 shl 24), style.checkedItemText)
    }

    @Test
    fun withoutItemNothingChanges() {
        set("<ul><li>a</li></ul>", cursor = 1)
        press(KeyEvent.KEYCODE_TAB)
        assertEquals("bullet1|a", RichDebug.describe(emitted.last().text))
        assertTrue(shortcuts.isEmpty())
        assertEquals(0, field.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG)
    }
}
