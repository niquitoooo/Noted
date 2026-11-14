package com.jot.app.ui.rich

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.UpdateLayout
import android.util.TypedValue
import android.view.ActionMode
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.TextRange
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import com.jot.app.ui.EditorShortcut
import com.jot.app.ui.EditorShortcuts
import kotlin.math.roundToInt

/** Los atajos de teclado de un ítem de checklist. */
enum class ItemShortcut { Indent, Outdent, MoveUp, MoveDown }

/**
 * Lo que el campo necesita cuando es el texto de un ítem de checklist ([RichEditText.item]).
 * [onBackspaceAtStart] es Backspace con el cursor en 0 y sin selección y
 * [onShortcut] una tecla de [ItemShortcut]: devuelven `true` si la consumen. Backspace al
 * principio del primer ítem no hace nada pero se consume: ahí también devuelve `true`.
 */
class ItemCallbacks(
    val checked: Boolean,
    val onBackspaceAtStart: () -> Boolean,
    val onShortcut: (ItemShortcut) -> Boolean,
)

/**
 * El campo de un bloque de texto: un `EditText` con el texto con formato, con el aspecto del
 * `BasicTextField` de antes. Lo que se escribe pasa por [RichEditRules], se dibuja
 * con [RichRender] y sale por [onValueChange] como un [RichText] nuevo.
 *
 * No tiene deshacer propio: el del `EditText` desarmaría las marcas. Ctrl+Z y los de
 * rehacer van a [onShortcut], que deshace toda la nota. Pegar pega con formato
 * ([RichPaste]) y es un paso propio del deshacer. No guarda su propio estado al rotar:
 * el valor lo tiene Compose.
 */
class RichEditText(context: Context) : EditText(context) {

    /** Colores y medidas; al cambiarlos se vuelve a dibujar el texto. */
    var style: RichStyle = initialStyle()
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
            render(text)
        }

    var onValueChange: (RichText) -> Unit = {}

    /**
     * Si el campo es el texto de un ítem de checklist, y qué hacer con Backspace al principio y
     * con los atajos del ítem; `null` es un bloque de texto común. Marcado, el texto va tachado
     * y en [RichStyle.checkedItemText] encima de su formato. Enter no se atiende
     * acá: llega como `\n` en [onValueChange].
     */
    var item: ItemCallbacks? = null
        set(value) {
            val wasChecked = field?.checked == true
            field = value
            val checked = value?.checked == true
            if (checked == wasChecked) return
            paintFlags = if (checked) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            render(text)
        }

    private val checkedItem: Boolean
        get() = item?.checked == true

    /**
     * Un atajo que atiende el editor de la nota ([EditorShortcut]); devuelve `true` si lo hizo.
     * La tecla se consume igual: el deshacer del `EditText` no corre nunca.
     */
    var onShortcut: (EditorShortcut) -> Boolean = { false }

    /**
     * Imágenes o videos que llegan al campo (del teclado o pegados del portapapeles), para
     * agregarlos a la nota abierta donde está el cursor. Hay que llamar a `done` cuando ya se
     * copiaron: suelta el permiso que dio el teclado. Sin esto, el teclado no sabe que el campo
     * acepta imágenes y las manda como «Compartir», que crea una nota nueva.
     */
    var onMedia: ((uris: List<Uri>, done: () -> Unit) -> Unit)? = null

    /** Flecha arriba en el primer renglón de pantalla, con la x del cursor. */
    var onFocusPrevious: (Float) -> Unit = {}

    /** Flecha abajo en el último renglón de pantalla, con la x del cursor. */
    var onFocusNext: (Float) -> Unit = {}

    /**
     * Si el bloque de este campo es lo primero de la nota: ahí la flecha arriba al principio de
     * una cita o un código agrega un renglón arriba en lugar de pasar al título.
     */
    var isFirstInNote: () -> Boolean = { false }

    /** El rectángulo del cursor, en coordenadas del campo, después de cada cambio. */
    var onCursorRect: (RectF) -> Unit = {}

    val geometry: TextGeometry = TextViewGeometry(this)

    /** El valor que tiene ahora el campo. */
    val value: RichText
        get() = RichText(
            text, TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0)), pending, pendingColor,
        )

    // El constructor de EditText llama a métodos de abajo antes de que existan estos campos:
    // [ready] queda en false hasta el final del init.
    private var ready = false
    private var pending: Map<InlineFormat, Boolean> = emptyMap()
    private var pendingColor: PendingColor? = null
    private var lastEmitted: RichText? = null

    /** Lo escrito antes de que un atajo de Markdown lo convirtiera, hasta emitirlo ([emit]). */
    private var typedBeforeShortcut: RichText? = null

    /** El último valor puesto con [setValue], hasta que el campo emita otro. */
    private var lastSet: RichText? = null

    /** La selección que conoce Compose: la del último valor emitido o puesto. */
    private var knownSelection = TextRange.Zero

    private var settingValue = false
    private var change: RichEditRules.Change? = null
    private var inRules = false
    private var forwardDelete = false

    /**
     * Lo que tocó el cambio de texto en curso, con lo que agregan las reglas: las posiciones
     * del texto de ahora, las dos puntas incluidas (`-1` si no hay). Con esto [renderTyping]
     * dibuja solo esos renglones.
     */
    private var touchedFrom = -1
    private var touchedTo = -1

    /** Mientras se pega: pegar no es escribir y no corre ningún atajo de Markdown. */
    private var pasting = false

    /**
     * Si en esta edición se pegó: lo que se emite va con [RichText.autoFormatted], un paso propio
     * del deshacer que no se agrupa con lo escrito.
     */
    private var pasted = false

    /**
     * Si en esta edición una tecla de edición cambió el texto: lo que se emite va
     * con [RichText.autoFormatted], un paso propio del deshacer, no escribir de corrido.
     */
    private var keyStep = false

    // Una edición es una tecla, una llamada del teclado en pantalla o un comando del menú: los
    // cambios de selección que trae un cambio de texto no vacían `pending`, el cursor que
    // pidieron las reglas se pone al final y se emite una sola vez.
    private var editDepth = 0
    private var textChangedInEdit = false
    private var deferredCursor: Int? = null
    private var dirty = false

    /** Si hay que avisar el cursor al tener `Layout` de nuevo ([reportCursor]). */
    private var cursorReportPending = false

    /** El principio del ítem cuyo casillero se está tocando, o -1. */
    private var checkTap = -1
    private val checkSlop = dp(10f)

    /** El lado mínimo de la zona de toque del casillero (48 dp, como un botón). */
    private val checkTarget = dp(48f)

    /** Si la última tecla fue Shift con una de movimiento (la selección es del teclado). */
    private var keySelection = false

    /** El ancla y la punta de la última selección hecha con el teclado, o -1. */
    private var keyAnchor = -1
    private var keyEnd = -1

    /**
     * El cursor: uno solo para toda la vida del campo, porque el `Editor` se queda con el primero
     * que recibe. Al cambiar el estilo se le cambia el color.
     */
    private var cursorDrawable: ShiftedDrawable? = null
    private var cursorShape: GradientDrawable? = null

    /** El margen de arriba y abajo del campo de antes (`padding(vertical = 2.dp)`). */
    private val basePadding = dp(2f).roundToInt()

    /** La caja de los renglones en el último armado completo o al escribir. */
    private var lineBox: LineBox? = null

    /** El último renglón vacío con la letra y el interlineado del estilo; se calcula en [applyStyle]. */
    private var emptyLastLine = LastLine(0, 0)

    init {
        background = null
        setPadding(0, basePadding, 0, basePadding)
        includeFontPadding = false
        // Como KeyboardOptions.Default en un campo de varios renglones.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = false
        isSaveEnabled = false
        // El texto es un [RichEditable] desde el principio: escribir no rearma los renglones de
        // los spans que solo se corren.
        setEditableFactory(RichEditable.Factory)
        setText("")
        addTextChangedListener(Watcher())
        ready = true
        applyStyle()
    }

    /**
     * Pone el valor sin avisar [onValueChange]. Si el texto y las marcas son los que ya tiene
     * (Compose devuelve lo que el campo acaba de mandar), no toca el texto: solo la selección y
     * `pending`, así no se corta la composición del teclado. El mismo valor que ya se puso
     * (los bloques que no se editan, en cada tecla) ni se compara.
     */
    fun setValue(value: RichText) {
        settingValue = true
        try {
            if (value !== lastEmitted && value !== lastSet && !sameContent(text, value.text)) {
                setText(value.text)
                render(text)
            }
            lastSet = value
            val length = text.length
            val start = value.selection.start.coerceIn(0, length)
            val end = value.selection.end.coerceIn(0, length)
            if (selectionStart != start || selectionEnd != end) setSelection(start, end)
            pending = value.pending
            pendingColor = value.pendingColor
            knownSelection = TextRange(start, end)
        } finally {
            settingValue = false
        }
    }

    // ---- cambios de texto y de selección ----

    private inner class Watcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (settingValue || inRules) return
            touchedFrom = -1
            touchedTo = -1
            change = RichEditRules.before(s as Spanned, start, count, after, forwardDelete)
        }

        // También los cambios de las reglas: lo que tocan se dibuja con lo escrito.
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (settingValue) return
            touch(start, before, count)
        }

        override fun afterTextChanged(s: Editable) {
            if (settingValue || inRules) return
            val change = change ?: return
            this@RichEditText.change = null
            inRules = true
            val result = try {
                RichEditRules.after(s, change, pending, pendingColor, inItem = item != null, shortcuts = !pasting)
            } finally {
                inRules = false
            }
            pending = result.pending
            pendingColor = result.pendingColor
            // Un atajo de Markdown: lo escrito tal cual sale antes que lo convertido.
            if (result.typed != null) {
                if (typedBeforeShortcut == null) typedBeforeShortcut = result.typed
                render(s)
            } else {
                renderTyping(s)
            }
            result.cursor?.let { cursor ->
                val at = cursor.coerceIn(0, s.length)
                if (selectionStart != at || selectionEnd != at) setSelection(at)
                // BaseInputConnection pone la selección después del reemplazo: se repone al final.
                if (editDepth > 0) deferredCursor = at
            }
            changed()
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready || settingValue || inRules || change != null) return
        if (knownSelection.start == selStart && knownSelection.end == selEnd) return
        // El espacio que agregó un cierre solo se traga uno escrito ahí mismo.
        MarkdownShortcuts.forgetAddedSpace(text, selStart.takeIf { it == selEnd })
        // Un cambio de selección sin escribir es mover el cursor: el formato pedido se olvida.
        if (editDepth == 0 || !textChangedInEdit) {
            pending = emptyMap()
            pendingColor = null
        }
        if (editDepth > 0) dirty = true else emit()
    }

    /**
     * Suma a [touchedFrom]–[touchedTo] el reemplazo de `[start, start + before)` por [count]
     * caracteres: lo que ya estaba se corre con el texto y el tramo nuevo se agrega.
     */
    private fun touch(start: Int, before: Int, count: Int) {
        val delta = count - before
        fun moved(pos: Int) = if (pos >= start + before) pos + delta else minOf(pos, start)
        if (touchedFrom < 0) {
            touchedFrom = start
            touchedTo = start + count
        } else {
            touchedFrom = minOf(moved(touchedFrom), start)
            touchedTo = maxOf(moved(touchedTo), start + count)
        }
    }

    /** El texto o las marcas cambiaron: se emite ahora o al final de la edición. */
    private fun changed() {
        if (editDepth > 0) {
            textChangedInEdit = true
            dirty = true
        } else {
            emit()
        }
    }

    private fun <T> edit(block: () -> T): T {
        if (editDepth++ == 0) {
            textChangedInEdit = false
            deferredCursor = null
        }
        try {
            return block()
        } finally {
            if (editDepth == 1) {
                deferredCursor?.let { at ->
                    deferredCursor = null
                    val cursor = at.coerceIn(0, text.length)
                    if (selectionStart != cursor || selectionEnd != cursor) setSelection(cursor)
                }
            }
            editDepth--
            if (editDepth == 0 && dirty) emit()
            if (editDepth == 0) {
                pasted = false
                keyStep = false
            }
        }
    }

    /**
     * Pone los spans de dibujo y rearma los cortes de renglón de todo el texto: el
     * `DynamicLayout` solo los rearma por los spans `UpdateLayout`, y los rótulos de lista
     * (`LeadingMarginSpan`) no lo son. Lo usan los caminos poco frecuentes (Tab, Backspace que
     * saca una marca, flecha, casillero, `setValue`, estilo). No toca el texto: la composición
     * sigue.
     */
    private fun render(text: Editable) {
        ensureLineHeight(text)
        RichRender.refresh(text, style, checkedItem)
        relayout(text, 0, text.length)
        // El rearmado empieza por el primer renglón: todos quedan con su caja.
        lineBox = lineHeightSpan(text)?.box
        updateVerticalPadding()
    }

    /**
     * Como [render], para cada cambio de texto: rearma solo desde el primer hasta el último
     * párrafo cuyo rótulo o margen cambió (un número que pasa de "9. " a "10. "), o nada si no
     * cambió ninguno; el resto ya lo rearmó el cambio de texto. Los spans de dibujo se
     * rearman solo en los renglones que tocó el cambio ([touchedFrom]–[touchedTo]) y en los de
     * su lista, no en toda la nota.
     */
    private fun renderTyping(text: Editable) {
        val added = ensureLineHeight(text)
        val lineHeight = lineHeightSpan(text)
        // La caja después del cambio de texto, frente a la del último armado: si el primer
        // renglón cambió (Enter al principio de un título), los renglones de abajo quedaron viejos.
        val boxChanged = lineHeight?.box != lineBox
        val before = markers(text)
        // refresh saca y vuelve a poner los spans de dibujo de los renglones que cambian; si es
        // el primero y tiene título, al sacar su tamaño el `DynamicLayout` lo rearma con la letra
        // común. Con la caja congelada, esos rearmados de paso no la cambian.
        lineHeight?.frozen = true
        try {
            val length = text.length
            val touched = if (touchedFrom < 0) null else touchedFrom.coerceIn(0, length)..touchedTo.coerceIn(0, length)
            RichRender.refresh(text, style, checkedItem, touched)
        } finally {
            lineHeight?.frozen = false
        }
        val after = markers(text)
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        when {
            added || boxChanged -> relayout(text, 0, text.length)
            changed.isNotEmpty() ->
                relayout(text, changed.min(), lineEndOf(text, changed.max().coerceAtMost(text.length)))
        }
        lineBox = lineHeight?.box
        updateVerticalPadding()
    }

    // ---- alto de los renglones, como el campo de antes ([LineBox]) ----

    /** Pone el span de interlineado sobre todo el texto si falta; `true` si lo puso. */
    private fun ensureLineHeight(text: Editable): Boolean {
        val spans = text.getSpans(0, text.length, LineHeightRender::class.java)
        val current = spans.singleOrNull()
        if (current != null && current.lineHeightPx == style.lineHeightPx &&
            text.getSpanStart(current) == 0 && text.getSpanEnd(current) == text.length
        ) {
            return false
        }
        spans.forEach { text.removeSpan(it) }
        text.setSpan(LineHeightRender(style.lineHeightPx), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return true
    }

    private fun lineHeightSpan(text: Spanned): LineHeightRender? =
        text.getSpans(0, text.length, LineHeightRender::class.java).firstOrNull()

    /**
     * El último renglón vacío no pasa por el span: [LastLine.extra] es lo que le falta de alto
     * y [LastLine.baselineShift] cuánto más abajo iría su base en el campo de antes.
     */
    private class LastLine(val extra: Int, val baselineShift: Int)

    private fun lastLine(): LastLine {
        val text = text
        return if (text.isEmpty() || text[text.length - 1] == '\n') emptyLastLine else NO_LAST_LINE
    }

    /** El margen de antes, más lo que sobresale la letra del primer renglón y el último vacío. */
    private fun updateVerticalPadding(): Boolean {
        val lineHeight = lineHeightSpan(text)
        val top = basePadding + (lineHeight?.topOverflow ?: 0)
        val bottom = basePadding + (lineHeight?.bottomOverflow ?: 0) + lastLine().extra
        if (paddingTop == top && paddingBottom == bottom) return false
        setPadding(0, top, 0, bottom)
        return true
    }

    /**
     * La caja del primer renglón se conoce recién al armar el `Layout`, que puede ser en esta
     * medición (la primera, o con otro ancho): si cambia el relleno, se mide de nuevo.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!ready) return
        // Un Layout armado al medir tiene todos los renglones con la caja de ahora.
        lineBox = lineHeightSpan(text)?.box
        if (updateVerticalPadding()) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** El rótulo y el margen de cada párrafo que tiene uno, por su principio. */
    private fun markers(text: Spanned): Map<Int, Pair<String, Int>> =
        text.getSpans(0, text.length, RichRender.MarkerSpan::class.java)
            .associate { text.getSpanStart(it) to (it.label to it.getLeadingMargin(true)) }

    private fun relayout(text: Editable, from: Int, to: Int) {
        val relayout = Relayout()
        text.setSpan(relayout, from, to, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        text.removeSpan(relayout)
    }

    /** Un span vacío que solo hace que el `DynamicLayout` rearme los renglones que cubre. */
    internal class Relayout : UpdateLayout

    /**
     * Emite el valor. Si un atajo de Markdown convirtió lo escrito, emite antes lo escrito tal
     * cual (un paso de escribir) y después lo convertido con [RichText.autoFormatted], así el
     * deshacer de la nota vuelve a los caracteres escritos. Lo pegado sale también con
     * [RichText.autoFormatted]: es un paso propio.
     */
    private fun emit() {
        dirty = false
        val typed = typedBeforeShortcut
        typedBeforeShortcut = null
        if (typed != null) {
            lastEmitted = typed
            onValueChange(typed)
        }
        val separateStep = typed != null || pasted || keyStep
        pasted = false
        keyStep = false
        val value = value.let { if (!separateStep) it else RichText(it.text, it.selection, it.pending, it.pendingColor, autoFormatted = true) }
        lastEmitted = value
        lastSet = null
        knownSelection = value.selection
        onValueChange(value)
        reportCursor()
    }

    /**
     * Avisa dónde quedó el cursor. Si el `TextView` tiró su `Layout` (cambiar el relleno, como al
     * escribir en el último renglón vacío, lo tira hasta medir de nuevo), el aviso queda para
     * después del próximo layout: si no, la nota no sigue al cursor al escribir al final.
     */
    private fun reportCursor() {
        if (layout == null) {
            cursorReportPending = true
            return
        }
        cursorReportPending = false
        val offset = selectionEnd.coerceIn(0, text.length)
        val line = geometry.lineForOffset(offset)
        val x = geometry.horizontal(offset)
        onCursorRect(RectF(x, geometry.lineTop(line), x + 1f, geometry.lineBottom(line)))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (ready && cursorReportPending) reportCursor()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (ready && focused) reportCursor()
    }

    // ---- teclas ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = edit {
        // Shift sola no cuenta: la tecla que importa es la que viene con ella.
        if (!KeyEvent.isModifierKey(keyCode)) keySelection = event.isShiftPressed && keyCode in SELECTION_KEYS
        if (itemShortcut(keyCode, event)) return@edit true
        if (editorShortcut(keyCode, event)) return@edit true
        if (keySelection) return@edit extendSelection { super.onKeyDown(keyCode, event) }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                (event.hasNoModifiers() && enterAtBlockStart()) || super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DEL -> backspaceAtLineStart() || super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_FORWARD_DEL -> forwardDeleting { super.onKeyDown(keyCode, event) }
            // Ctrl+Tab, Meta+Tab y Alt+Tab son del sistema: no sangran ni escriben el espacio eme.
            KeyEvent.KEYCODE_TAB ->
                if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) false
                else indent(outdent = event.isShiftPressed) || tabOutsideList(outdent = event.isShiftPressed) ||
                    super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                (!event.isShiftPressed && arrow(up = keyCode == KeyEvent.KEYCODE_DPAD_UP)) ||
                    super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                (event.hasNoModifiers() && (keyRule { RichEditRules.arrowRightOutOfCode(text, it) } || horizontalEdge(forward = true))) ||
                    super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_LEFT ->
                (event.hasNoModifiers() && horizontalEdge(forward = false)) || super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_ESCAPE ->
                keyRule { RichEditRules.escapeOutOfBlock(text, it) } || super.onKeyDown(keyCode, event)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Shift con una tecla de movimiento agranda la selección desde su ancla, como el campo de
     * antes. En la tablet el `TextView` de Samsung no la conserva: si está abierta la barra
     * de selección (doble toque) la cierra al apretar la tecla y deja solo el cursor, y Shift+flecha
     * arriba puede dejar el ancla y la punta al revés. Por eso el campo recuerda el ancla y la punta
     * de la selección hecha con el teclado, la pone así antes de mover y, después, se queda con la
     * punta que se movió.
     */
    private fun extendSelection(move: () -> Boolean): Boolean {
        val start = selectionStart
        val end = selectionEnd
        val ours = keyAnchor >= 0 && minOf(start, end) == minOf(keyAnchor, keyEnd) &&
            maxOf(start, end) == maxOf(keyAnchor, keyEnd)
        val anchor = if (ours) keyAnchor else start
        val tip = if (ours) keyEnd else end
        if (start != anchor || end != tip) setSelection(anchor, tip)
        val handled = move()
        val movedStart = selectionStart
        val movedEnd = selectionEnd
        val newTip = if (movedEnd == anchor) movedStart else movedEnd
        if (movedStart != anchor || movedEnd != newTip) setSelection(anchor, newTip)
        keyAnchor = anchor
        keyEnd = newTip
        return handled
    }

    /** La selección hecha con el teclado no abre la barra flotante, como antes. */
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        if (keySelection && type == ActionMode.TYPE_FLOATING) null else super.startActionMode(callback, type)

    /**
     * Los atajos con Ctrl de [EditorShortcuts] (deshacer, formato, colores…) van a [onShortcut]
     *. Pueden llegar por acá o por [onKeyDown]: se atienden en los dos.
     * En modo ítem, los del ítem ([ItemShortcut]) se prueban primero y mandan.
     */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (itemShortcut(keyCode, event)) return true
        if (editorShortcut(keyCode, event)) return true
        return super.onKeyShortcut(keyCode, event)
    }

    /** Si la tecla es un [EditorShortcut]: se pasa a [onShortcut] y se consume siempre. */
    private fun editorShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val shortcut = EditorShortcuts.of(keyCode, event.metaState) ?: return false
        onShortcut(shortcut)
        return true
    }

    /** En modo ítem, si la tecla es un [ItemShortcut] y el ítem la consumió. */
    private fun itemShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val item = item ?: return false
        val shortcut = shortcutOf(keyCode, event) ?: return false
        return item.onShortcut(shortcut)
    }

    /** Tab / Shift+Tab, Ctrl+] / Ctrl+[ y Ctrl+Shift+P / Ctrl+Shift+O. */
    private fun shortcutOf(keyCode: Int, event: KeyEvent): ItemShortcut? {
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        return when {
            keyCode == KeyEvent.KEYCODE_TAB && !ctrl -> if (shift) ItemShortcut.Outdent else ItemShortcut.Indent
            !ctrl || event.isAltPressed -> null
            keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET && !shift -> ItemShortcut.Indent
            keyCode == KeyEvent.KEYCODE_LEFT_BRACKET && !shift -> ItemShortcut.Outdent
            keyCode == KeyEvent.KEYCODE_P && shift -> ItemShortcut.MoveUp
            keyCode == KeyEvent.KEYCODE_O && shift -> ItemShortcut.MoveDown
            else -> null
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo, android.R.id.redo -> true
        android.R.id.paste -> edit { pasting { paste() } }
        android.R.id.pasteAsPlainText -> edit { pasting { pastePlain() } }
        else -> edit { super.onTextContextMenuItem(id) }
    }

    /** Las imágenes o videos del portapapeles, si es eso lo que hay (sin texto). */
    private fun pastedMedia(): List<Uri>? {
        val clip = primaryClip() ?: return null
        if (MEDIA_TYPES.none { clip.description.hasMimeType(it) }) return null
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return uris.takeIf { it.isNotEmpty() && it.size == clip.itemCount }
    }

    private fun primaryClip(): ClipData? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.takeIf { it.itemCount > 0 }

    /**
     * Texto plano, por las reglas de lo escrito, como el pegar del `TextView`. Si trae `\r`, se
     * pega acá con los renglones normalizados ([RichPaste.normalizeNewlines]): el `TextView` lo
     * pegaría tal cual.
     */
    private fun pastePlain(): Boolean {
        val clip = primaryClip()
        val plain = clip?.let { (0 until it.itemCount).joinToString("\n") { i -> it.getItemAt(i).coerceToText(context) } }
        val selection = TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
        // En un título, los renglones pegados siguen siendo título.
        if (plain != null && item == null && RichPaste.keepsHeading(text, selection, plain)) {
            return pasteMarks { RichPaste.insertPlain(text, selection, plain) }
        }
        if (plain == null || '\r' !in plain) return super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        text.replace(minOf(start, end), maxOf(start, end), RichPaste.normalizeNewlines(plain))
        return true
    }

    /** Soltar texto arrastrado es como pegar: sin atajos de Markdown y un paso propio. */
    override fun onDragEvent(event: DragEvent): Boolean =
        if (event.action == DragEvent.ACTION_DROP) edit { pasting { super.onDragEvent(event) } }
        else super.onDragEvent(event)

    private inline fun <T> pasting(block: () -> T): T {
        pasting = true
        pasted = true
        try {
            return block()
        } finally {
            pasting = false
        }
    }

    /**
     * Pegar: con HTML en el portapapeles, con formato ([RichPaste.insertHtml]); una URL
     * sola, como enlace ([RichPaste.insertPlain]); si no, o en código o en un ítem de checklist
     * ([RichPaste.plainOnly]), texto plano como siempre, por las reglas de lo escrito (el ítem
     * conserva su regla de varios renglones).
     */
    private fun paste(): Boolean {
        pastedMedia()?.let { uris -> onMedia?.let { it(uris) {}; return true } }
        val clip = primaryClip()?.getItemAt(0)
        val selection = TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
        if (clip == null || RichPaste.plainOnly(text, selection, inItem = item != null)) return pastePlain()
        val html = clip.htmlText
        val plain = clip.coerceToText(context)?.toString().orEmpty()
        return when {
            html != null -> pasteMarks { RichPaste.insertHtml(text, selection, html) }
            RichPaste.urlOf(plain) != null -> pasteMarks { RichPaste.insertPlain(text, selection, plain) }
            else -> pastePlain()
        }
    }

    /**
     * Un pegado que pone sus propias marcas: las reglas de lo escrito no corren sobre él. Después
     * se dibuja, se pone el cursor y se emite al final de la edición.
     */
    private fun pasteMarks(insert: () -> Int): Boolean {
        inRules = true
        val cursor = try {
            insert()
        } finally {
            inRules = false
        }
        MarkdownShortcuts.forgetAddedSpace(text, null)
        render(text)
        val at = cursor.coerceIn(0, text.length)
        if (selectionStart != at || selectionEnd != at) setSelection(at)
        if (editDepth > 0) deferredCursor = at
        changed()
        return true
    }

    /**
     * El teclado manda una URL entera: se corta la composición y se pega como enlace.
     * En código o en un ítem de checklist se escribe como siempre.
     */
    private fun commitUrl(typed: CharSequence?): Boolean {
        val url = typed?.toString()?.takeIf { RichPaste.isUrl(it) } ?: return false
        val text = text
        val composingStart = BaseInputConnection.getComposingSpanStart(text)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(text)
        val composing = composingStart >= 0 && composingEnd >= 0
        val range = if (composing) TextRange(minOf(composingStart, composingEnd), maxOf(composingStart, composingEnd))
        else TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
        if (RichPaste.plainOnly(text, range, inItem = item != null)) return false
        BaseInputConnection.removeComposingSpans(text)
        return pasting {
            pasteMarks {
                // Lo que se estaba componiendo es lo que la URL reemplaza: no es una selección.
                if (composing && range.min < range.max) text.delete(range.min, range.max)
                RichPaste.insertPlain(text, if (composing) TextRange(range.min) else range, url)
            }
        }
    }

    /**
     * Backspace sin selección al principio de un renglón: no borra, saca la marca. En modo
     * ítem, al principio del texto, lo atiende el ítem (P4: anda por la tecla y por el teclado en
     * pantalla).
     */
    private fun backspaceAtLineStart(): Boolean {
        if (selectionStart != selectionEnd) return false
        item?.let { item -> if (selectionStart == 0) return item.onBackspaceAtStart() }
        // K2 antes que R9 (en un código dentro de un ítem, sale del código); K3 después.
        if (keyRule { RichEditRules.backspaceAtCodeStart(text, it) }) return true
        RichEditRules.backspaceAtLineStart(text, selectionStart)?.let { cursor ->
            render(text)
            changed()
            if (selectionStart != cursor) setSelection(cursor)
            return true
        }
        return keyRule { RichEditRules.backspaceAfterDivider(text, it) }
    }

    private fun indent(outdent: Boolean): Boolean {
        if (!RichEditRules.indent(text, TextRange(selectionStart, selectionEnd), outdent)) return false
        render(text)
        changed()
        return true
    }

    /**
     * Tab y Shift+Tab fuera de una lista: el espacio eme. Shift+Tab sin espacio eme antes
     * del cursor no hace nada, pero la tecla se consume. En un ítem de checklist no corre.
     */
    private fun tabOutsideList(outdent: Boolean): Boolean {
        if (item != null) return false
        val selection = TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
        val cursor = RichEditRules.tabOutsideList(text, selection, outdent) ?: return outdent
        return applied(cursor)
    }

    /** Enter al principio de una cita o un código, por la tecla o por el teclado en pantalla. */
    private fun enterAtBlockStart(): Boolean = keyRule { RichEditRules.enterAtBlockStart(text, it) }

    /**
     * Una regla de las teclas de edición sobre el cursor, sin selección y fuera de un ítem de
     * checklist: si aplica, [applied].
     */
    private inline fun keyRule(rule: (Int) -> Int?): Boolean {
        if (item != null || selectionStart != selectionEnd || selectionStart < 0) return false
        val cursor = rule(selectionStart) ?: return false
        return applied(cursor)
    }

    /** Una tecla de edición cambió el texto: se dibuja, va el cursor y se emite como paso propio. */
    private fun applied(cursor: Int): Boolean {
        render(text)
        keyStep = true
        changed()
        val at = cursor.coerceIn(0, text.length)
        if (selectionStart != at || selectionEnd != at) setSelection(at)
        if (editDepth > 0) deferredCursor = at
        return true
    }

    /**
     * Flecha derecha al final del texto (o izquierda al principio), sin selección, pasa al
     * principio del bloque siguiente (o al final del anterior); sin vecino no hace nada. Nunca
     * deja que el `EditText` mueva el foco a la barra.
     */
    private fun horizontalEdge(forward: Boolean): Boolean {
        if (selectionStart != selectionEnd) return false
        val offset = selectionEnd
        if (if (forward) offset != text.length else offset != 0) return false
        if (forward) onFocusNext(0f) else onFocusPrevious(ARROW_EDGE_END)
        return true
    }

    /**
     * Flecha abajo primero sale de un código o una cita al final. Después, en el primer
     * o el último renglón de pantalla, pasa al bloque vecino con la x del cursor.
     */
    private fun arrow(up: Boolean): Boolean {
        if (!up) {
            RichEditRules.arrowDownOutOfBlock(text, selectionEnd)?.let { cursor ->
                render(text)
                changed()
                setSelection(cursor.coerceIn(0, text.length))
                return true
            }
        }
        val layout = layout ?: return false
        val offset = selectionEnd.coerceIn(0, text.length)
        val line = layout.getLineForOffset(offset)
        if (if (up) line != 0 else line != layout.lineCount - 1) return false
        // En el primer renglón de una cita o un código que es lo primero de la nota.
        if (up && isFirstInNote() && keyRule { RichEditRules.arrowUpOutOfBlock(text, it) }) return true
        val x = geometry.horizontal(offset)
        if (up) onFocusPrevious(x) else onFocusNext(x)
        return true
    }

    private inline fun <T> forwardDeleting(block: () -> T): T {
        forwardDelete = true
        try {
            return block()
        } finally {
            forwardDelete = false
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)?.let { RichInputConnection(it) } ?: return null
        val media = onMedia ?: return connection
        EditorInfoCompat.setContentMimeTypes(outAttrs, MEDIA_TYPES)
        return InputConnectionCompat.createWrapper(connection, outAttrs) { content, flags, _ ->
            val grant = flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
            if (grant && !runCatching { content.requestPermission() }.isSuccess) return@createWrapper false
            media(listOf(content.contentUri)) { if (grant) content.releasePermission() }
            true
        }
    }

    /** Lo que manda el teclado en pantalla, cada llamada como una edición. */
    private inner class RichInputConnection(target: InputConnection) : InputConnectionWrapper(target, true) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int) =
            edit { commitEnter(text) || commitUrl(text) || super.commitText(text, newCursorPosition) }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            edit { commitEnter(text) || commitUrl(text) || super.commitText(text, newCursorPosition, textAttribute) }

        /** El teclado en pantalla manda el Enter como texto: K1 vale también por acá. */
        private fun commitEnter(typed: CharSequence?): Boolean =
            typed?.toString() == "\n" && BaseInputConnection.getComposingSpanStart(this@RichEditText.text) < 0 &&
                enterAtBlockStart()

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
            edit { super.setComposingText(text, newCursorPosition) }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            edit { super.setComposingText(text, newCursorPosition, textAttribute) }

        override fun finishComposingText() = edit { super.finishComposingText() }

        override fun commitCompletion(text: CompletionInfo?) = edit { super.commitCompletion(text) }

        override fun commitCorrection(correctionInfo: CorrectionInfo?) = edit { super.commitCorrection(correctionInfo) }

        override fun setSelection(start: Int, end: Int) = edit { super.setSelection(start, end) }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun replaceText(
            start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?,
        ) = edit { super.replaceText(start, end, text, newCursorPosition, textAttribute) }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = edit {
            when {
                beforeLength == 1 && afterLength == 0 && backspaceAtLineStart() -> true
                beforeLength == 0 && afterLength > 0 -> forwardDeleting { super.deleteSurroundingText(0, afterLength) }
                else -> super.deleteSurroundingText(beforeLength, afterLength)
            }
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = edit {
            when {
                beforeLength == 1 && afterLength == 0 && backspaceAtLineStart() -> true
                beforeLength == 0 && afterLength > 0 ->
                    forwardDeleting { super.deleteSurroundingTextInCodePoints(0, afterLength) }
                else -> super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
        }
    }

    // ---- casillero ----

    /**
     * Un toque en la zona del casillero del primer renglón de pantalla de un ítem tildable
     * ([checkboxAt]) no mueve el cursor; al levantar el dedo sobre el mismo ítem o su zona lo
     * tilda o destilda. Si el dedo se va del ítem, no pasa nada.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) keySelection = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> checkboxAt(event.x, event.y)?.let { item ->
                checkTap = item
                return true
            }
            MotionEvent.ACTION_MOVE -> if (checkTap >= 0) return true
            MotionEvent.ACTION_UP -> if (checkTap >= 0) {
                val item = checkTap
                checkTap = -1
                if (checkboxAt(event.x, event.y) == item || paragraphAt(event.x, event.y) == item) toggleCheck(item)
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (checkTap >= 0) {
                checkTap = -1
                return true
            }
        }
        if (swallowsFingerMove(event)) return true
        return super.onTouchEvent(event)
    }

    /** Qué se hace con el movimiento del dedo en este toque. */
    private enum class FingerDrag { Undecided, Scroll, Pass }

    private var fingerDrag = FingerDrag.Pass
    /** El dedo bajó en el campo: dónde, y si después hubo un toque largo (selección). */
    private var fingerDownX = 0f
    private var fingerDownY = 0f
    private var longPressed = false
    /** El último toque que se levantó: para reconocer el segundo de un doble toque. */
    private var lastUpTime = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f

    override fun performLongClick(): Boolean {
        longPressed = true
        return super.performLongClick()
    }

    /**
     * Arrastrar el dedo para arriba o para abajo sobre el texto desplaza la nota, como con el
     * `BasicTextField` de antes. Si el `EditText` recibe ese movimiento, el Editor de Android
     * 11+ puede arrastrar el cursor y pedirle al padre que no intercepte
     * (`requestDisallowInterceptTouchEvent`), y Compose ya no desplaza. Por eso, cuando el
     * dedo pasa el margen de toque yendo más vertical que horizontal, sus movimientos no le
     * llegan al `EditText`: es desplazar la nota. Si arranca más horizontal (seleccionar
     * arrastrando, también el mouse que scrcpy manda como dedo), si es el segundo toque de un
     * doble toque (extender la selección) o después de un toque largo, le llegan como siempre.
     * El toque corto sigue poniendo el cursor. El mouse y el lápiz no cambian.
     */
    private fun swallowsFingerMove(event: MotionEvent): Boolean {
        val config = android.view.ViewConfiguration.get(context)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = event.getToolType(0)
                val pointer = tool == MotionEvent.TOOL_TYPE_MOUSE || tool == MotionEvent.TOOL_TYPE_STYLUS ||
                    tool == MotionEvent.TOOL_TYPE_ERASER
                val doubleSlop = config.scaledDoubleTapSlop
                val secondTap = event.eventTime - lastUpTime <= android.view.ViewConfiguration.getDoubleTapTimeout() &&
                    kotlin.math.hypot(event.x - lastUpX, event.y - lastUpY) <= doubleSlop
                fingerDrag = if (pointer || secondTap) FingerDrag.Pass else FingerDrag.Undecided
                fingerDownX = event.x
                fingerDownY = event.y
                longPressed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) return false
                if (fingerDrag == FingerDrag.Undecided) {
                    val dx = kotlin.math.abs(event.x - fingerDownX)
                    val dy = kotlin.math.abs(event.y - fingerDownY)
                    if (kotlin.math.hypot(dx, dy) > config.scaledTouchSlop) {
                        fingerDrag = if (dy > dx) FingerDrag.Scroll else FingerDrag.Pass
                        // Desplazar no es un toque largo, como en View.onTouchEvent.
                        if (fingerDrag == FingerDrag.Scroll) cancelLongPress()
                    }
                }
                return fingerDrag == FingerDrag.Scroll
            }
            MotionEvent.ACTION_UP -> {
                lastUpTime = event.eventTime
                lastUpX = event.x
                lastUpY = event.y
            }
        }
        return false
    }

    /**
     * El principio del ítem tildable cuyo casillero está en (x, y), o `null`. La zona de toque
     * es más grande que el dibujo: a lo ancho, al menos [checkTarget] terminando donde empieza
     * el texto del ítem (un toque en el texto pone el cursor); a lo alto, todo el renglón y,
     * hasta completar [checkTarget] centrado en él, el margen vacío de los renglones vecinos (a
     * la izquierda de donde empieza su texto). Si dos casilleros alcanzan el toque, gana el más
     * cercano.
     */
    private fun checkboxAt(x: Float, y: Float): Int? {
        val layout = layout ?: return null
        val localY = y - totalPaddingTop
        if (localY < -checkTarget / 2 || localY > layout.height + checkTarget / 2) return null
        val touched = layout.getLineForVertical(localY.toInt().coerceIn(0, layout.height))
        val inTouched = localY >= layout.getLineTop(touched) && localY <= layout.getLineBottom(touched)
        // Fuera del renglón tocado (arriba o abajo del texto) no hay texto que tocar.
        val touchedTextLeft = if (inTouched) totalPaddingLeft + layout.getPrimaryHorizontal(layout.getLineStart(touched)) else Float.MAX_VALUE
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for (line in (touched - 2).coerceAtLeast(0)..(touched + 2).coerceAtMost(layout.lineCount - 1)) {
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(line).toFloat()
            val center = (top + bottom) / 2
            val half = maxOf((bottom - top) / 2, checkTarget / 2)
            val distance = kotlin.math.abs(localY - center)
            if (distance > half || distance >= bestDistance) continue
            val zone = checkboxZone(layout, line) ?: continue
            if (x < zone.first || x > zone.second) continue
            // En otro renglón, solo el margen vacío de ese renglón es del casillero.
            if (line != touched && x >= touchedTextLeft) continue
            best = layout.getLineStart(line)
            bestDistance = distance
        }
        return best
    }

    /** De dónde a dónde (en x) se toca el casillero del renglón [line], o `null` si no tiene. */
    private fun checkboxZone(layout: Layout, line: Int): Pair<Float, Float>? {
        val lineStart = layout.getLineStart(line)
        val text = text
        if (lineStartOf(text, lineStart) != lineStart) return null
        if (RichHtml.blockMarksAt(text, lineStart).none { it is ListMark && it.kind == ListKind.Check }) return null
        val marker = text.getSpans(lineStart, lineStart, RichRender.MarkerSpan::class.java)
            .firstOrNull { text.getSpanStart(it) == lineStart } ?: return null
        // El rótulo es la sangría, el casillero y un espacio.
        val label = marker.label
        val boxLeft = totalPaddingLeft + paint.measureText(label, 0, label.length - 2)
        val textStart = totalPaddingLeft + paint.measureText(label)
        return minOf(boxLeft - checkSlop, textStart - checkTarget) to textStart
    }

    /** El principio del renglón (del texto) que está en (x, y), o -1 fuera del campo. */
    private fun paragraphAt(x: Float, y: Float): Int {
        val layout = layout ?: return -1
        if (x < 0 || x > width || y < 0 || y > height) return -1
        val line = layout.getLineForVertical((y - totalPaddingTop).toInt())
        return lineStartOf(text, layout.getLineStart(line))
    }

    private fun toggleCheck(lineStart: Int) {
        val toggled = RichCommands.toggleCheck(value, lineStart)
        val text = text
        for (mark in text.getSpans(0, text.length, RichMark::class.java)) text.removeSpan(mark)
        val marks = toggled.text
        for (mark in marks.getSpans(0, marks.length, RichMark::class.java)) {
            text.setSpan(mark, marks.getSpanStart(mark), marks.getSpanEnd(mark), marks.getSpanFlags(mark))
        }
        render(text)
        emit()
    }

    // ---- ítem vacío al final ----

    /**
     * El `Layout` no dibuja una marca de largo 0 en el último renglón (un ítem vacío después de
     * Enter al final de la lista) ni le deja su margen: el campo dibuja el rótulo y corre el
     * cursor de ese renglón a donde empezaría el texto.
     */
    override fun onDraw(canvas: Canvas) {
        val marker = emptyLastMarker()
        val cursorAtEnd = selectionStart == text.length && selectionEnd == text.length
        val last = lastLine()
        cursorDrawable?.shift = if (marker != null && cursorAtEnd) marker.width.toFloat() else 0f
        // En el último renglón vacío el cursor ocupa todo el alto del renglón, como antes.
        cursorDrawable?.extraBottom = if (cursorAtEnd) last.extra else 0
        super.onDraw(canvas)
        val layout = layout
        if (marker == null || layout == null) return
        val line = layout.lineCount - 1
        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        marker.drawLeadingMargin(
            canvas, paint, 0, 1, layout.getLineTop(line), layout.getLineBaseline(line) + last.baselineShift,
            layout.getLineBottom(line) + last.extra, text, text.length, text.length, true, layout,
        )
        canvas.restore()
    }

    private fun emptyLastMarker(): RichRender.MarkerSpan? {
        val text = text
        val length = text.length
        if (length == 0 || text[length - 1] != '\n') return null
        return text.getSpans(length, length, RichRender.MarkerSpan::class.java)
            .firstOrNull { text.getSpanStart(it) == length }
    }

    /** El cursor, corrido [shift] píxeles a la derecha y [extraBottom] más largo abajo. */
    private class ShiftedDrawable(drawable: Drawable) : DrawableWrapper(drawable) {
        var shift = 0f
        var extraBottom = 0

        override fun draw(canvas: Canvas) {
            val inner = drawable
            if (extraBottom != 0 && inner != null) {
                val bounds = copyBounds()
                inner.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom + extraBottom)
                drawShifted(canvas)
                inner.bounds = bounds
            } else {
                drawShifted(canvas)
            }
        }

        private fun drawShifted(canvas: Canvas) {
            if (shift == 0f) return super.draw(canvas)
            canvas.save()
            canvas.translate(shift, 0f)
            super.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * El campo mide todo su texto: no se desplaza por dentro. Desplaza la nota la columna
     * de Compose; el `TextView` igual intenta correrse al haber una selección.
     */
    override fun scrollTo(x: Int, y: Int) = super.scrollTo(0, 0)

    // ---- aspecto ----

    /** El aspecto del `BasicTextField` de hoy: `bodyLarge`, 28 sp, `onBackground`, cursor primario. */
    private fun applyStyle() {
        if (!ready) return
        val style = style
        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSizePx)
        typeface = style.typeface
        letterSpacing = style.letterSpacingEm
        setTextColor(style.onBackground)
        // La selección como la de Compose: el primario al 40 %.
        highlightColor = (style.primary and 0x00FFFFFF) or (0x66 shl 24)
        // El alto de cada renglón lo pone LineHeightRender, como el campo de antes.
        setLineSpacing(0f, 1f)
        val fm = paint.fontMetricsInt
        val box = LineBox.of(fm, style.lineHeightPx)
        emptyLastLine = LastLine((box.descent - box.ascent) - (fm.descent - fm.ascent), fm.ascent - box.ascent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) applyCursor(style.primary)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun applyCursor(primary: Int) {
        val shape = cursorShape ?: GradientDrawable().apply { setSize(dp(2f).roundToInt(), 0) }.also { shape ->
            cursorShape = shape
            cursorDrawable = ShiftedDrawable(shape).also { setTextCursorDrawable(it) }
        }
        shape.setColor(primary)
        textSelectHandle?.let { setTextSelectHandle(it.mutate().apply { setTint(primary) }) }
        textSelectHandleLeft?.let { setTextSelectHandleLeft(it.mutate().apply { setTint(primary) }) }
        textSelectHandleRight?.let { setTextSelectHandleRight(it.mutate().apply { setTint(primary) }) }
    }

    /** Hasta que Compose pase el suyo, el estilo sale de lo que el `EditText` ya tiene. */
    private fun initialStyle(): RichStyle {
        val color = currentTextColor
        return RichStyle(
            primary = color,
            onSurfaceVariant = color,
            surfaceVariant = 0,
            checkedText = color,
            onBackground = color,
            dark = false,
            textSizePx = textSize,
            typeface = typeface ?: Typeface.DEFAULT,
            letterSpacingEm = letterSpacing,
            lineHeightPx = lineHeight.toFloat(),
            density = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics),
        )
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        /** Lo que el campo acepta del teclado o del portapapeles como adjunto. */
        val MEDIA_TYPES = arrayOf("image/*", "video/*")

        val NO_LAST_LINE = LastLine(0, 0)

        /** Las teclas que con Shift agrandan la selección. */
        val SELECTION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )

        /** Si dos textos tienen el mismo texto y las mismas marcas en los mismos lugares. */
        fun sameContent(a: Spanned, b: Spanned): Boolean {
            if (a.length != b.length || a.toString() != b.toString()) return false
            val marks = a.getSpans(0, a.length, RichMark::class.java)
            val others = b.getSpans(0, b.length, RichMark::class.java).toMutableList()
            if (marks.size != others.size) return false
            for (mark in marks) {
                val index = others.indexOfFirst {
                    it.sameFormat(mark) && b.getSpanStart(it) == a.getSpanStart(mark) && b.getSpanEnd(it) == a.getSpanEnd(mark)
                }
                if (index < 0) return false
                others.removeAt(index)
            }
            return true
        }
    }
}

/** La x con que la flecha izquierda entra al bloque anterior: pasa del final de cualquier renglón. */
const val ARROW_EDGE_END = Float.MAX_VALUE
