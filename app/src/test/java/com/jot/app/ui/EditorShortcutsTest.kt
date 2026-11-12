package com.jot.app.ui

import android.view.KeyEvent
import com.jot.app.ui.EditorShortcut.Bar
import com.jot.app.ui.EditorShortcut.Heading
import com.jot.app.ui.EditorShortcut.Highlight
import com.jot.app.ui.EditorShortcut.Indent
import com.jot.app.ui.EditorShortcut.Inline
import com.jot.app.ui.EditorShortcut.MoveListItem
import com.jot.app.ui.EditorShortcut.TextColor
import com.jot.app.ui.rich.InlineFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La tabla de atajos con Ctrl. Va en la JVM: solo compara números. */
class EditorShortcutsTest {

    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    private val colors = listOf(null, "red", "orange", "yellow", "green", "blue", "pink", "purple", "gray")

    private fun of(keyCode: Int, meta: Int = 0) = EditorShortcuts.of(keyCode, ctrl or meta)

    @Test
    fun altGrIsNotColorShortcut() {
        // AltGr por scrcpy desde Windows: Ctrl izquierda + Alt derecha. El carácter se escribe.
        val altGr = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        for (digit in 0..9) assertNull(of(KeyEvent.KEYCODE_0 + digit, altGr))
        assertNull(of(KeyEvent.KEYCODE_2, altGr or shift))
        assertNull(of(KeyEvent.KEYCODE_Q, altGr))
        // Con Alt izquierda sigue siendo el color.
        assertEquals(TextColor("red"), of(KeyEvent.KEYCODE_1, alt))
        assertEquals(TextColor("red"), of(KeyEvent.KEYCODE_1, alt or KeyEvent.META_ALT_RIGHT_ON))
    }

    @Test
    fun ctrlBIUAreInline() {
        assertEquals(Inline(InlineFormat.Bold), of(KeyEvent.KEYCODE_B))
        assertEquals(Inline(InlineFormat.Italic), of(KeyEvent.KEYCODE_I))
        assertEquals(Inline(InlineFormat.Underline), of(KeyEvent.KEYCODE_U))
    }

    @Test
    fun ctrlShiftXIsStrike() {
        assertEquals(Inline(InlineFormat.Strike), of(KeyEvent.KEYCODE_X, shift))
    }

    @Test
    fun ctrlDigitsOneToSixAreHeadings() {
        for (level in 1..6) assertEquals(Heading(level), of(KeyEvent.KEYCODE_0 + level))
    }

    @Test
    fun ctrlSevenEightNineAreLists() {
        assertEquals(Bar(BarAction.Bullet), of(KeyEvent.KEYCODE_7))
        assertEquals(Bar(BarAction.Ordered), of(KeyEvent.KEYCODE_8))
        assertEquals(Bar(BarAction.Check), of(KeyEvent.KEYCODE_9))
    }

    @Test
    fun ctrlShiftNineTogglesChecked() {
        assertEquals(EditorShortcut.ToggleChecked, of(KeyEvent.KEYCODE_9, shift))
    }

    @Test
    fun ctrlBracketsIndent() {
        assertEquals(Indent(false), of(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(Indent(true), of(KeyEvent.KEYCODE_LEFT_BRACKET))
    }

    @Test
    fun undoAndRedo() {
        assertEquals(EditorShortcut.Undo, of(KeyEvent.KEYCODE_Z))
        assertEquals(EditorShortcut.Redo, of(KeyEvent.KEYCODE_Z, shift))
        assertEquals(EditorShortcut.Redo, of(KeyEvent.KEYCODE_Y))
    }

    @Test
    fun ctrlShiftUIsQuote() {
        assertEquals(Bar(BarAction.Quote), of(KeyEvent.KEYCODE_U, shift))
    }

    @Test
    fun ctrlShiftCIsInlineCode() {
        assertEquals(Inline(InlineFormat.Code), of(KeyEvent.KEYCODE_C, shift))
    }

    @Test
    fun ctrlShiftAltCIsCodeBlock() {
        assertEquals(Bar(BarAction.Code), of(KeyEvent.KEYCODE_C, shift or alt))
    }

    @Test
    fun ctrlShiftHIsDivider() {
        assertEquals(Bar(BarAction.Divider), of(KeyEvent.KEYCODE_H, shift))
    }

    @Test
    fun ctrlAltDigitsAreTextColors() {
        for (digit in 0..8) assertEquals(TextColor(colors[digit]), of(KeyEvent.KEYCODE_0 + digit, alt))
    }

    @Test
    fun ctrlShiftAltDigitsAreHighlights() {
        for (digit in 0..8) assertEquals(Highlight(colors[digit]), of(KeyEvent.KEYCODE_0 + digit, shift or alt))
    }

    @Test
    fun subscriptAndSuperscript() {
        assertEquals(Inline(InlineFormat.Subscript), of(KeyEvent.KEYCODE_SEMICOLON, shift))
        assertEquals(Inline(InlineFormat.Superscript), of(KeyEvent.KEYCODE_APOSTROPHE, shift))
    }

    @Test
    fun ctrlBackslashRemovesFormat() {
        assertEquals(EditorShortcut.RemoveFormat, of(KeyEvent.KEYCODE_BACKSLASH))
    }

    @Test
    fun ctrlAltArrowsMoveListItem() {
        assertEquals(MoveListItem(true), of(KeyEvent.KEYCODE_DPAD_UP, alt))
        assertEquals(MoveListItem(false), of(KeyEvent.KEYCODE_DPAD_DOWN, alt))
    }

    @Test
    fun plainLettersAreNotShortcuts() {
        assertNull(EditorShortcuts.of(KeyEvent.KEYCODE_B, 0))
        assertNull(EditorShortcuts.of(KeyEvent.KEYCODE_1, shift))
        assertNull(EditorShortcuts.of(KeyEvent.KEYCODE_DPAD_UP, alt))
        // Con Ctrl, lo que no está en la tabla tampoco.
        assertNull(of(KeyEvent.KEYCODE_Q))
        assertNull(of(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(of(KeyEvent.KEYCODE_B, alt))
        assertNull(of(KeyEvent.KEYCODE_0))
    }

    @Test
    fun numpadDigitsWork() {
        for (level in 1..6) assertEquals(Heading(level), of(KeyEvent.KEYCODE_NUMPAD_0 + level))
        assertEquals(Bar(BarAction.Bullet), of(KeyEvent.KEYCODE_NUMPAD_7))
        assertEquals(TextColor("red"), of(KeyEvent.KEYCODE_NUMPAD_1, alt))
        assertEquals(Highlight(null), of(KeyEvent.KEYCODE_NUMPAD_0, shift or alt))
    }
}
