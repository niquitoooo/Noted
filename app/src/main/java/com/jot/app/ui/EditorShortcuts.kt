package com.jot.app.ui

import android.view.KeyEvent
import com.jot.app.ui.rich.InlineFormat

/**
 * Un atajo de teclado que el campo de texto no atiende solo y pasa al editor de la nota
 * ([com.jot.app.ui.rich.RichEditText.onShortcut]). Las teclas de cada uno están en
 * [EditorShortcuts.of].
 */
sealed interface EditorShortcut {
    /** Ctrl+Z: deshace el último paso de toda la nota. */
    data object Undo : EditorShortcut

    /** Ctrl+Shift+Z o Ctrl+Y: rehace lo deshecho. */
    data object Redo : EditorShortcut

    /** Un formato en línea, como el botón: con selección la cambia, sin selección queda pendiente. */
    data class Inline(val format: InlineFormat) : EditorShortcut

    /** Título `<h1>`…`<h6>`; el mismo nivel lo saca. */
    data class Heading(val level: Int) : EditorShortcut

    /** Lo mismo que el botón [action] de la barra. */
    data class Bar(val action: BarAction) : EditorShortcut

    /** Ctrl+Shift+9: marca o desmarca el ítem. */
    data object ToggleChecked : EditorShortcut

    /** Ctrl+] y Ctrl+[: sangría de los ítems de lista. */
    data class Indent(val outdent: Boolean) : EditorShortcut

    /** Color de texto (`jot-text-<color>`); `null` lo quita. */
    data class TextColor(val color: String?) : EditorShortcut

    /** Resaltado; `"green"` es `jot-highlight` y `null` lo quita. */
    data class Highlight(val color: String?) : EditorShortcut

    /** Ctrl+\: quita color, resaltado y formato en línea. */
    data object RemoveFormat : EditorShortcut

    /** Ctrl+Alt+↑ / ↓: sube o baja el ítem de lista. */
    data class MoveListItem(val up: Boolean) : EditorShortcut
}

/** La tabla de atajos con Ctrl del editor. */
object EditorShortcuts {

    /** Los colores de Ctrl+Alt+1…8 y Ctrl+Shift+Alt+1…8, en ese orden; 0 quita. */
    private val COLORS = listOf(null, "red", "orange", "yellow", "green", "blue", "pink", "purple", "gray")

    /**
     * El atajo de la tecla [keyCode] con los modificadores [meta] (`KeyEvent.getMetaState`), o
     * `null` si no es ninguno. Sin Ctrl nunca hay atajo. Los números valen también del teclado
     * numérico.
     *
     * AltGr (Alt derecha sin Alt izquierda) no es un atajo: con el teclado físico por scrcpy desde
     * Windows llega como Ctrl izquierda + Alt derecha, y el carácter (por ejemplo `@`) tiene que
     * escribirse.
     */
    fun of(keyCode: Int, meta: Int): EditorShortcut? {
        if (meta and KeyEvent.META_CTRL_ON == 0) return null
        if (meta and KeyEvent.META_ALT_RIGHT_ON != 0 && meta and KeyEvent.META_ALT_LEFT_ON == 0) return null
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        val alt = meta and KeyEvent.META_ALT_ON != 0
        val digit = digitOf(keyCode)
        return when {
            alt -> when {
                digit != null && digit < COLORS.size ->
                    if (shift) EditorShortcut.Highlight(COLORS[digit]) else EditorShortcut.TextColor(COLORS[digit])
                shift && keyCode == KeyEvent.KEYCODE_C -> EditorShortcut.Bar(BarAction.Code)
                !shift && keyCode == KeyEvent.KEYCODE_DPAD_UP -> EditorShortcut.MoveListItem(up = true)
                !shift && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> EditorShortcut.MoveListItem(up = false)
                else -> null
            }
            shift -> when {
                digit == 9 -> EditorShortcut.ToggleChecked
                keyCode == KeyEvent.KEYCODE_X -> EditorShortcut.Inline(InlineFormat.Strike)
                keyCode == KeyEvent.KEYCODE_Z -> EditorShortcut.Redo
                keyCode == KeyEvent.KEYCODE_U -> EditorShortcut.Bar(BarAction.Quote)
                keyCode == KeyEvent.KEYCODE_C -> EditorShortcut.Inline(InlineFormat.Code)
                keyCode == KeyEvent.KEYCODE_H -> EditorShortcut.Bar(BarAction.Divider)
                keyCode == KeyEvent.KEYCODE_SEMICOLON -> EditorShortcut.Inline(InlineFormat.Subscript)
                keyCode == KeyEvent.KEYCODE_APOSTROPHE -> EditorShortcut.Inline(InlineFormat.Superscript)
                else -> null
            }
            digit != null -> when (digit) {
                in 1..6 -> EditorShortcut.Heading(digit)
                7 -> EditorShortcut.Bar(BarAction.Bullet)
                8 -> EditorShortcut.Bar(BarAction.Ordered)
                9 -> EditorShortcut.Bar(BarAction.Check)
                else -> null
            }
            else -> when (keyCode) {
                KeyEvent.KEYCODE_B -> EditorShortcut.Inline(InlineFormat.Bold)
                KeyEvent.KEYCODE_I -> EditorShortcut.Inline(InlineFormat.Italic)
                KeyEvent.KEYCODE_U -> EditorShortcut.Inline(InlineFormat.Underline)
                KeyEvent.KEYCODE_RIGHT_BRACKET -> EditorShortcut.Indent(outdent = false)
                KeyEvent.KEYCODE_LEFT_BRACKET -> EditorShortcut.Indent(outdent = true)
                KeyEvent.KEYCODE_Z -> EditorShortcut.Undo
                KeyEvent.KEYCODE_Y -> EditorShortcut.Redo
                KeyEvent.KEYCODE_BACKSLASH -> EditorShortcut.RemoveFormat
                else -> null
            }
        }
    }

    /** El número de la tecla, de la fila de arriba o del teclado numérico. */
    private fun digitOf(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}
