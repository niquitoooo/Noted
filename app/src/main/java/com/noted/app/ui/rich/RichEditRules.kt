package com.noted.app.ui.rich

import android.text.Editable
import android.text.NoCopySpan
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.text.style.SuggestionSpan
import androidx.compose.ui.text.TextRange
import com.noted.app.ui.rich.RichCommands.isFormat

/**
 * Lo mínimo del teclado sobre el texto con formato (tabla "Qué entra del teclado en este
 * tramo"): Enter, Backspace al principio del renglón, Tab y flecha abajo, como en el editor Markdown
 * de antes, y además el resto (K1–K7: Enter al principio
 * de un bloque, Backspace en código y después de un separador, Escape, flecha arriba y derecha,
 * Tab fuera de una lista), que el campo llama directo desde la tecla.
 *
 * El campo llama a [before] desde `beforeTextChanged` y a [after] desde `afterTextChanged`. Las
 * reglas solo tocan marcas y el `\n` del Enter, y sus propios cambios no las vuelven a disparar.
 */
object RichEditRules {

    /** Lo que había en el lugar del cambio antes de hacerlo. */
    class Change internal constructor(
        internal val start: Int,
        internal val removed: Int,
        internal val added: Int,
        internal val removedText: String,
        /** Principio del primer renglón que toca el cambio. */
        internal val lineStart: Int,
        /** Las marcas de bloque de cada renglón que toca, en orden. */
        internal val lines: List<List<BlockMark>>,
        /** El formato en línea que toma lo escrito. */
        internal val formats: List<InlineMark>,
        /** Si fue un borrado hacia adelante (Supr): une los renglones, sin R9. */
        internal val forwardDelete: Boolean,
        /** El fin del enlace dentro de cuyo texto cae el cambio, si cae en uno. */
        internal val linkEnd: Int?,
        /**
         * Si lo reemplazado empieza o termina justo donde empieza o termina el enlace que toma
         * lo escrito: lo agregado en ese borde queda afuera del enlace.
         */
        internal val linkAtStart: Boolean,
        internal val linkAtEnd: Boolean,
        /** Un cambio hecho por las propias reglas: [after] no hace nada. */
        internal val skip: Boolean,
    )

    private val skipped = Change(0, 0, 0, "", 0, emptyList(), emptyList(), false, null, false, false, skip = true)

    /**
     * Lo que devuelve [after]: el cursor nuevo (`null` si no lo mueve) y el `pending` que el
     * campo tiene que tener desde ahora (R7; después de un Enter al final de algo con formato,
     * ese formato), y lo mismo para el color y el resaltado ([pendingColor]).
     *
     * [shortcuts] en `false` (pegar, R21) no corre ningún atajo de Markdown.
     *
     * Si un atajo de Markdown convirtió lo escrito ([MarkdownShortcuts]), [typed] es el
     * valor tal como quedó escrito, antes de convertir, y [autoFormatted] es `true`: el campo
     * emite primero [typed] y después lo convertido.
     */
    class Result(
        val cursor: Int?,
        val pending: Map<InlineFormat, Boolean>,
        val pendingColor: PendingColor? = null,
        val typed: RichText? = null,
    ) {
        val autoFormatted: Boolean get() = typed != null

        /** El principio del renglón de arriba de un Enter que partió un renglón (para `---`). */
        internal var enterLine: Int? = null
    }

    /**
     * Se llama desde `beforeTextChanged`. [forwardDelete] lo sabe el campo (Supr o
     * `deleteSurroundingText(0, n)`).
     */
    fun before(text: Spanned, start: Int, removed: Int, added: Int, forwardDelete: Boolean = false): Change {
        if (text.getSpans(0, text.length, Applying::class.java).isNotEmpty()) return skipped
        val lineStart = lineStartOf(text, start)
        val last = lineStartOf(text, start + removed)
        val lines = ArrayList<List<BlockMark>>()
        var line = lineStart
        while (true) {
            lines.add(RichHtml.blockMarksAt(text, line))
            if (line >= last) break
            line = lineEndOf(text, line) + 1
        }
        val formats = formatsFor(text, start, removed)
        val link = formats.firstOrNull { it is LinkMark }
        return Change(
            start, removed, added, text.substring(start, start + removed), lineStart, lines,
            formats, forwardDelete, linkEndAround(text, start, start + removed),
            linkAtStart = link != null && text.getSpanStart(link) == start,
            linkAtEnd = link != null && text.getSpanEnd(link) == start + removed,
            skip = false,
        )
    }

    /** Repone las marcas de bloque que guardó [before] y aplica las reglas. */
    fun after(
        text: Editable,
        change: Change,
        pending: Map<InlineFormat, Boolean>,
        pendingColor: PendingColor? = null,
        inItem: Boolean = false,
        shortcuts: Boolean = true,
    ): Result {
        if (change.skip) return Result(null, pending, pendingColor)
        val inserted = text.substring(change.start, change.start + change.added)
        return applying(text) {
            // El espacio que agregó un cierre se traga el que se escribe ahí mismo.
            if (shortcuts && MarkdownShortcuts.swallowSpace(text, change.start, change.removed, change.added)) {
                return@applying Result(change.start, pending, pendingColor)
            }
            if (!shortcuts) MarkdownShortcuts.forgetAddedSpace(text, null)
            val result = apply(text, change, pending, pendingColor)
            // Pegar no es escribir, no corre ningún atajo.
            (if (shortcuts) shortcuts(text, change, inserted, result, inItem) else null) ?: result
        }
    }

    /**
     * Los atajos de Markdown sobre lo que se acaba de escribir ([MarkdownShortcuts]),
     * después de las reglas. `null` si no hubo ninguno. Nunca dentro de código ni en lo que el
     * teclado todavía compone. En un ítem de checklist no andan los del principio del renglón ni
     * el separador.
     */
    private fun shortcuts(text: Editable, change: Change, inserted: String, result: Result, inItem: Boolean): Result? {
        if (inserted.isEmpty()) return null
        result.enterLine?.let { upper ->
            if (inItem) return null
            val newline = lineEndOf(text, upper)
            if (newline >= text.length) return null
            val typed = RichText(text, TextRange(newline + 1), result.pending, result.pendingColor)
            val cursor = if (MarkdownShortcuts.onEnter(text, upper, newline)) lineEndOf(text, upper) + 1
            else MarkdownShortcuts.autoLink(text, newline + 1) ?: return null
            return Result(cursor, result.pending, result.pendingColor, typed)
        }
        if ('\n' in inserted) return null
        val last = inserted.last()
        if (last !in TRIGGERS) return null
        val at = (result.cursor ?: (change.start + change.added)).coerceIn(0, text.length)
        if (at == 0 || text[at - 1] != last) return null
        if (composing(text, at - 1) || inCode(text, at - 1)) return null
        val typed = RichText(text, TextRange(at), result.pending, result.pendingColor)
        val lineStart = lineStartOf(text, at)
        val cursor = (if (inItem) null else MarkdownShortcuts.lineStart(text, lineStart, at))
            ?: MarkdownShortcuts.inline(text, at, last)
            ?: MarkdownShortcuts.typography(text, at, last)
            ?: (if (last == ' ') MarkdownShortcuts.autoLink(text, at) else null)
            ?: return null
        return Result(cursor, result.pending, result.pendingColor, typed)
    }

    /** Los caracteres con los que puede terminar un atajo. */
    private const val TRIGGERS = " `*_~).\"'"

    /** Si el carácter de [at] lo está componiendo el teclado. */
    private fun composing(text: Spanned, at: Int): Boolean =
        text.getSpans(at, at + 1, Any::class.java).any { span ->
            text.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0 &&
                text.getSpanStart(span) <= at && text.getSpanEnd(span) > at
        }

    /** Si el carácter de [at] es código, en línea o en bloque (ahí no hay atajos). */
    private fun inCode(text: Spanned, at: Int): Boolean {
        if (RichHtml.blockMarksAt(text, lineStartOf(text, at)).any { it is CodeBlockMark }) return true
        return text.getSpans(at, at + 1, CodeMark::class.java)
            .any { text.getSpanStart(it) <= at && text.getSpanEnd(it) > at }
    }

    /**
     * Backspace con el cursor al principio de un renglón: un ítem baja un nivel, o pasa a
     * párrafo en el nivel 0; el primer renglón de una cita sale de la cita. `null` si no
     * aplica; si aplica, el campo no borra nada.
     */
    fun backspaceAtLineStart(text: Editable, cursor: Int): Int? {
        val lineStart = lineStartOf(text, cursor)
        if (cursor != lineStart) return null
        val above = if (lineStart == 0) emptyList() else RichHtml.blockMarksAt(text, lineStartOf(text, lineStart - 1))
        val marks = RichHtml.blockMarksAt(text, lineStart)
        val outdented = outdented(marks, above) ?: return null
        setLineMarks(text, lineStart, marks, outdented)
        splitQuoteBelow(text, lineStart, marks, outdented)
        return cursor
    }

    /**
     * Tab y Shift+Tab: suben o bajan un nivel los ítems de la selección (mínimo 0).
     * Fuera de una lista devuelve `false`, y Tab hace lo de siempre.
     */
    fun indent(text: Editable, selection: TextRange, outdent: Boolean): Boolean {
        var inList = false
        for (lineStart in RichCommands.selectedLines(text.toString(), selection)) {
            val marks = RichHtml.blockMarksAt(text, lineStart)
            val item = marks.firstOrNull { it is ListMark } as ListMark? ?: continue
            inList = true
            val depth = if (outdent) maxOf(0, item.depth - 1) else item.depth + 1
            if (depth == item.depth) continue
            setLineMarks(text, lineStart, marks, marks.map { if (it === item) item.withDepth(depth) else it })
        }
        return inList
    }

    /**
     * Flecha abajo en el último renglón del texto, si es de código o de cita: agrega un
     * párrafo vacío debajo y devuelve su posición.
     */
    fun arrowDownOutOfBlock(text: Editable, cursor: Int): Int? {
        val lineStart = lineStartOf(text, cursor)
        if (lineEndOf(text, lineStart) != text.length) return null
        val marks = RichHtml.blockMarksAt(text, lineStart)
        if (marks.none { it is CodeBlockMark || it is QuoteMark }) return null
        applying(text) {
            text.append("\n")
            setLineMarks(text, lineStart, marks, marks)
            for (stray in RichHtml.blockMarksAt(text, text.length)) text.removeSpan(stray)
        }
        return text.length
    }

    /**
     * Enter con el cursor al principio de una cita o un bloque de código con texto:
     * agrega un párrafo vacío antes del bloque y devuelve el cursor, que queda en el bloque. En
     * el medio del bloque o en un renglón vacío, `null`: Enter hace lo de siempre.
     * En un ítem de lista, tampoco: mandan las reglas de la lista. Si el bloque está dentro de
     * una cita que sigue arriba, el renglón nuevo queda en esa cita.
     */
    fun enterAtBlockStart(text: Editable, cursor: Int): Int? {
        val lineStart = lineStartOf(text, cursor)
        if (cursor != lineStart || lineEndOf(text, lineStart) == lineStart) return null
        val marks = RichHtml.blockMarksAt(text, lineStart)
        if (marks.any { it is ListMark }) return null
        val above = if (lineStart == 0) emptyList() else RichHtml.blockMarksAt(text, lineStartOf(text, lineStart - 1))
        val starting = marks.filter { it.isBlock() && !above.hasGroupOf(it) }
        if (starting.isEmpty()) return null
        val outer = marks.filter { it is QuoteMark && it !in starting }.map { QuoteMark((it as QuoteMark).group) }
        applying(text) { insertEmptyLine(text, lineStart, outer) }
        return lineStart + 1
    }

    /**
     * Backspace con el cursor al principio de un bloque de código: todo el bloque
     * pasa a renglones normales con el mismo texto. Va antes que R9: en un código dentro de un
     * ítem, sale del código y no de la lista. `null` si no aplica.
     */
    fun backspaceAtCodeStart(text: Editable, cursor: Int): Int? {
        val lineStart = lineStartOf(text, cursor)
        if (cursor != lineStart) return null
        val code = RichHtml.blockMarksAt(text, lineStart).firstOrNull { it is CodeBlockMark } as CodeBlockMark? ?: return null
        if (lineStart > 0 && RichHtml.blockMarksAt(text, lineStartOf(text, lineStart - 1)).hasGroupOf(code)) return null
        var line = lineStart
        while (true) {
            val mark = RichHtml.blockMarksAt(text, line).firstOrNull { it is CodeBlockMark && it.group == code.group } ?: break
            text.removeSpan(mark)
            val end = lineEndOf(text, line)
            if (end >= text.length) break
            line = end + 1
        }
        return cursor
    }

    /**
     * Backspace con el cursor al principio del renglón que sigue a un separador:
     * borra el separador y el renglón queda como estaba. Va después de R9 (un ítem o una cita
     * primero salen). Devuelve el cursor, o `null` si no aplica.
     */
    fun backspaceAfterDivider(text: Editable, cursor: Int): Int? {
        val lineStart = lineStartOf(text, cursor)
        if (cursor != lineStart || lineStart == 0) return null
        val above = lineStartOf(text, lineStart - 1)
        val marks = RichHtml.blockMarksAt(text, above)
        if (!marks.isDivider()) return null
        applying(text) {
            for (mark in marks) text.removeSpan(mark)
            text.delete(above, lineStart)
        }
        return above
    }

    /**
     * Escape dentro de una cita o un bloque de código: agrega un párrafo vacío
     * después del bloque y devuelve su posición. Si hay código y cita, sale del código (lo de
     * más adentro) y el renglón nuevo queda en la cita. `null` fuera de esos bloques.
     */
    fun escapeOutOfBlock(text: Editable, cursor: Int): Int? {
        var line = lineStartOf(text, cursor)
        val marks = RichHtml.blockMarksAt(text, line)
        val block = marks.firstOrNull { it is CodeBlockMark } ?: marks.firstOrNull { it is QuoteMark } ?: return null
        // El último renglón del bloque.
        while (true) {
            val end = lineEndOf(text, line)
            if (end >= text.length || !RichHtml.blockMarksAt(text, end + 1).hasGroupOf(block)) break
            line = end + 1
        }
        val outer = if (block is CodeBlockMark) {
            RichHtml.blockMarksAt(text, line).filterIsInstance<QuoteMark>().map { QuoteMark(it.group) }
        } else {
            emptyList()
        }
        val end = lineEndOf(text, line)
        return applying(text) {
            if (end < text.length) {
                insertEmptyLine(text, end + 1, outer)
                end + 1
            } else {
                appendEmptyLine(text, line, outer)
            }
        }
    }

    /**
     * Flecha arriba en el primer renglón del texto, si es de código o de cita: agrega
     * un párrafo vacío arriba y devuelve su posición. El campo la usa solo si su bloque es lo
     * primero de la nota.
     */
    fun arrowUpOutOfBlock(text: Editable, cursor: Int): Int? {
        if (lineStartOf(text, cursor) != 0) return null
        if (RichHtml.blockMarksAt(text, 0).none { it.isBlock() }) return null
        applying(text) { insertEmptyLine(text, 0, emptyList()) }
        return 0
    }

    /**
     * Flecha derecha al final de un código en línea que termina el renglón: escribe
     * un espacio afuera del código y devuelve el cursor después de él. `null` si no aplica.
     */
    fun arrowRightOutOfCode(text: Editable, cursor: Int): Int? {
        if (cursor == 0 || cursor != lineEndOf(text, cursor)) return null
        val code = text.getSpans(cursor - 1, cursor, CodeMark::class.java)
            .firstOrNull { text.getSpanStart(it) < cursor && text.getSpanEnd(it) == cursor } ?: return null
        applying(text) {
            text.insert(cursor, " ")
            text.setSpan(code, text.getSpanStart(code), cursor, INLINE_MARK_FLAGS)
        }
        return cursor + 1
    }

    /**
     * Tab y Shift+Tab fuera de una lista: Tab escribe un espacio eme (U+2003) en
     * lugar de la selección, por las reglas de lo escrito (toma el formato de al lado); Shift+Tab
     * borra un espacio eme justo antes del cursor. Devuelve el cursor, o `null` si no hizo nada
     * o si algún renglón de la selección es de una lista (eso es [indent]).
     */
    fun tabOutsideList(text: Editable, selection: TextRange, outdent: Boolean): Int? {
        val lines = RichCommands.selectedLines(text.toString(), selection)
        if (lines.any { line -> RichHtml.blockMarksAt(text, line).any { it is ListMark } }) return null
        if (!outdent) {
            text.replace(selection.min, selection.max, EM_SPACE.toString())
            return selection.min + 1
        }
        val at = selection.start
        if (!selection.collapsed || at == 0 || text[at - 1] != EM_SPACE) return null
        text.delete(at - 1, at)
        return at - 1
    }

    /** El espacio eme que escribe Tab fuera de una lista. */
    const val EM_SPACE = ' '

    private fun BlockMark.isBlock() = this is QuoteMark || this is CodeBlockMark

    /** Si en estas marcas está el mismo bloque (cita o código) que [mark]. */
    private fun List<BlockMark>.hasGroupOf(mark: BlockMark) = any {
        when (mark) {
            is QuoteMark -> it is QuoteMark && it.group == mark.group
            is CodeBlockMark -> it is CodeBlockMark && it.group == mark.group
            else -> false
        }
    }

    /**
     * Agrega un renglón vacío con [marks] en [lineStart], el principio de un renglón que baja
     * uno. Las marcas de bloque de ese renglón y del de arriba quedan en sus renglones.
     */
    private fun insertEmptyLine(text: Editable, lineStart: Int, marks: List<BlockMark>) {
        val below = RichHtml.blockMarksAt(text, lineStart)
        val aboveStart = if (lineStart == 0) -1 else lineStartOf(text, lineStart - 1)
        val above = if (aboveStart < 0) emptyList() else RichHtml.blockMarksAt(text, aboveStart)
        text.insert(lineStart, "\n")
        setLineMarks(text, lineStart + 1, below, below)
        if (aboveStart >= 0) setLineMarks(text, aboveStart, above, above)
        for (stray in RichHtml.blockMarksAt(text, lineStart)) text.removeSpan(stray)
        for (mark in marks) text.setSpan(mark, lineStart, lineStart + 1, BLOCK_MARK_FLAGS)
    }

    /** Agrega un renglón vacío con [marks] al final del texto, debajo de [lastLine]; devuelve su principio. */
    private fun appendEmptyLine(text: Editable, lastLine: Int, marks: List<BlockMark>): Int {
        val last = RichHtml.blockMarksAt(text, lastLine)
        text.append("\n")
        setLineMarks(text, lastLine, last, last)
        for (stray in RichHtml.blockMarksAt(text, text.length)) text.removeSpan(stray)
        for (mark in marks) text.setSpan(mark, text.length, text.length, BLOCK_MARK_FLAGS)
        return text.length
    }

    // ---- las reglas ----

    private fun apply(text: Editable, change: Change, pending: Map<InlineFormat, Boolean>, pendingColor: PendingColor?): Result {
        val start = change.start
        var typedStart = start
        var typedEnd = start + change.added
        removeForeignStyles(text, typedStart, typedEnd)
        val typed = text.substring(typedStart, typedEnd)
        val old = change.lines
        val lineStart = change.lineStart
        var cursor: Int? = null
        var splitQuote: Long? = null
        var nextPending = pending
        var nextPendingColor = pendingColor
        var enterLine: Int? = null
        val plan: MutableList<List<BlockMark>>

        val newlines = typed.count { it == '\n' }
        val upperMarks = old.first()
        // R9 por cualquier vía: borrar el `\n` que deja el cursor al principio de un ítem o
        // de una cita repone el `\n` y saca la marca, en lugar de unir. Con Supr, no.
        val outdentedBelow = if (
            change.removed == 1 && change.removedText == "\n" && change.added == 0 && old.size == 2 &&
            !change.forwardDelete
        ) outdented(old[1], upperMarks) else null
        when {
            // Lo que se escribe en un separador va a un párrafo nuevo debajo.
            change.removed == 0 && typed.isNotEmpty() && upperMarks.isDivider() -> {
                // La composición del teclado sigue sobre lo movido; si no, lo próximo que
                // componga se escribe al lado en lugar de reemplazarlo.
                val composing = text.getSpans(typedStart, typedEnd, Any::class.java)
                    .filter { text.getSpanFlags(it) and Spanned.SPAN_COMPOSING != 0 }
                    .map { it to text.getSpanFlags(it) }
                text.delete(typedStart, typedEnd)
                val at = lineEndOf(text, lineStart)
                val inserted = if (typed.startsWith("\n")) typed else "\n" + typed
                text.insert(at, inserted)
                typedEnd = at + inserted.length
                typedStart = typedEnd - typed.length
                for ((span, flags) in composing) text.setSpan(span, typedStart, typedEnd, flags)
                cursor = typedEnd
                plan = (listOf(upperMarks) + List(inserted.count { it == '\n' }) { emptyList() }).toMutableList()
            }
            outdentedBelow != null -> {
                text.insert(start, "\n")
                cursor = start + 1
                if (old[1].any { it is QuoteMark } && outdentedBelow.none { it is QuoteMark }) {
                    splitQuote = (old[1].first { it is QuoteMark } as QuoteMark).group
                }
                plan = mutableListOf(upperMarks, outdentedBelow)
            }
            // Un Enter: un único `\n`, al final de lo agregado.
            newlines == 1 && typed.endsWith('\n') -> {
                var newline = typedEnd - 1
                // Dentro del texto de un enlace, el salto va después del enlace, sin partirlo.
                if (change.linkEnd != null) {
                    val linkEnd = change.linkEnd - change.removed + change.added - 1
                    text.delete(newline, newline + 1)
                    text.insert(linkEnd, "\n")
                    newline = linkEnd
                    typedEnd--
                    cursor = newline + 1
                }
                val enter = enter(text, lineStart, newline, upperMarks)
                plan = enter.plan.toMutableList()
                splitQuote = enter.splitQuote
                if (!enter.removedNewline) enterLine = lineStart
                if (enter.removedNewline) {
                    typedEnd = minOf(typedEnd, newline)
                    cursor = newline
                }
                // Lo que se escriba en el renglón nuevo sigue con el formato del cursor,
                // también el color y el resaltado.
                if (enter.lowerEmpty && !enter.removedNewline) {
                    nextPending = carried(change.formats, pending)
                    nextPendingColor = carriedColor(change.formats, pendingColor)
                }
            }
            // Lo demás (escribir, borrar, pegar varios renglones): el primer renglón queda con
            // las marcas del de arriba y los renglones nuevos son párrafos; dentro de un
            // bloque de código, siguen en ese bloque.
            else -> {
                val first = joinedMarks(text, lineStart, old)
                val code = first.firstOrNull { it is CodeBlockMark } as CodeBlockMark?
                plan = (listOf(first) + List(newlines) { listOfNotNull(code?.let { CodeBlockMark(it.group, it.language) }) })
                    .toMutableList()
            }
        }

        // Un renglón separador nunca guarda texto.
        if (old.any { it.isDivider() }) {
            val removedBefore = dropDividerChars(text, lineStart, plan, typedEnd)
            if (removedBefore >= 0) {
                typedEnd -= removedBefore
                typedStart = minOf(typedStart, typedEnd)
                cursor = typedEnd
            }
        }

        val regionEnd = placeLines(text, lineStart, plan, old.flatten())
        if (splitQuote != null) splitQuoteFrom(text, regionEnd, splitQuote)
        if (typedEnd > typedStart) {
            formatTyped(
                text, typedStart, typedEnd, change.formats, pending, pendingColor,
                linkRange(change, typed, typedStart, typedEnd),
            )
        }
        splitAtNewlines(text, lineStart, regionEnd)
        mergeInline(text, lineStart, regionEnd)
        return Result(cursor, nextPending, nextPendingColor).also { it.enterLine = enterLine }
    }

    /**
     * Las marcas del renglón que queda de unir los renglones [old]: las del de arriba.
     * Si se une con un separador, el separador se va y quedan las del renglón con texto
     *; si lo que queda es solo el separador, sigue siéndolo.
     */
    private fun joinedMarks(text: Editable, lineStart: Int, old: List<List<BlockMark>>): List<BlockMark> = when {
        old.none { it.isDivider() } -> old.first()
        text.substring(lineStart, lineEndOf(text, lineStart)) == DIVIDER_CHAR.toString() -> old.first { it.isDivider() }
        else -> old.firstOrNull { !it.isDivider() } ?: emptyList()
    }

    private fun List<BlockMark>.isDivider() = any { it is DividerMark }

    /**
     * En los renglones de [plan] que tienen algo más que el separador, saca su carácter y su
     * marca. Devuelve cuántos caracteres sacó antes de [before], o -1 si no tocó nada.
     */
    private fun dropDividerChars(text: Editable, from: Int, plan: MutableList<List<BlockMark>>, before: Int): Int {
        var touched = false
        var removedBefore = 0
        var lineStart = from
        for (i in plan.indices) {
            var lineEnd = lineEndOf(text, lineStart)
            if (text.substring(lineStart, lineEnd) != DIVIDER_CHAR.toString()) {
                if (plan[i].isDivider()) {
                    plan[i] = plan[i].filter { it !is DividerMark }
                    touched = true
                }
                for (at in lineEnd - 1 downTo lineStart) {
                    if (text[at] != DIVIDER_CHAR) continue
                    text.delete(at, at + 1)
                    if (at < before) removedBefore++
                    lineEnd--
                    touched = true
                }
            }
            lineStart = lineEnd + 1
        }
        return if (touched) removedBefore else -1
    }

    /** El `pending` del renglón nuevo después de un Enter: el formato del cursor. */
    private fun carried(formats: List<InlineMark>, pending: Map<InlineFormat, Boolean>): Map<InlineFormat, Boolean> {
        val result = pending.toMutableMap()
        for (format in InlineFormat.entries) {
            if (format !in result && formats.any { it.isFormat(format) }) result[format] = true
        }
        return result
    }

    /**
     * El color pendiente del renglón nuevo después de un Enter: lo pedido en
     * [pendingColor] y, si no se pidió nada, el color y el resaltado del cursor.
     */
    private fun carriedColor(formats: List<InlineMark>, pendingColor: PendingColor?): PendingColor? {
        val pending = pendingColor ?: PendingColor(null, null, clearText = false, clearHighlight = false)
        val textColor = (formats.firstOrNull { it is TextColorMark } as TextColorMark?)?.color
        val highlight = (formats.firstOrNull { it is HighlightMark } as HighlightMark?)?.let { it.color ?: GREEN }
        val result = pending.copy(
            text = if (pending.text != null || pending.clearText) pending.text else textColor,
            highlight = if (pending.highlight != null || pending.clearHighlight) pending.highlight else highlight,
        )
        return result.takeIf { it.text != null || it.highlight != null || it.clearText || it.clearHighlight }
    }

    private class Enter(
        val plan: List<List<BlockMark>>,
        val splitQuote: Long?,
        val removedNewline: Boolean,
        val lowerEmpty: Boolean,
    )

    /** Enter con el `\n` en [newline], en el renglón que tenía [marks]. */
    private fun enter(text: Editable, lineStart: Int, newline: Int, marks: List<BlockMark>): Enter {
        val upperEmpty = newline == lineStart
        val lowerEmpty = lineEndOf(text, newline + 1) == newline + 1
        val item = marks.firstOrNull { it is ListMark } as ListMark?
        val quote = marks.firstOrNull { it is QuoteMark } as QuoteMark?
        val heading = marks.firstOrNull { it is HeadingMark } as HeadingMark?

        // En un ítem vacío, el `\n` se saca; sube un nivel o pasa a párrafo.
        if (item != null && upperEmpty && lowerEmpty) {
            text.delete(newline, newline + 1)
            val outdented = marks.mapNotNull { if (it === item) item.outdentedOrNull() else it }
            return Enter(listOf(outdented), splitQuote = null, removedNewline = true, lowerEmpty = true)
        }

        // En un renglón vacío de una cita, el nuevo es un párrafo afuera.
        val leavesQuote = quote != null && item == null && upperEmpty && lowerEmpty
        val upper = marks.toMutableList()
        val lower = marks.mapNotNullTo(ArrayList()) { mark ->
            when (mark) {
                is ListMark -> ListMark(mark.kind, mark.depth) // Sin tildar y sin start
                is QuoteMark -> if (leavesQuote) null else QuoteMark(mark.group) // R3
                is CodeBlockMark -> CodeBlockMark(mark.group, mark.language) // Siempre
                is HeadingMark, is DividerMark -> null // R5
            }
        }
        if (upperEmpty) {
            // Enter al principio: el título y la tilde bajan con el texto.
            if (heading != null) {
                upper.remove(heading)
                if (!lowerEmpty) lower.add(HeadingMark(heading.level))
            }
            if (item != null && item.kind == ListKind.Check && item.checked && !lowerEmpty) {
                upper[upper.indexOf(item)] = ListMark(item.kind, item.depth, checked = false, start = item.start)
                val index = lower.indexOfFirst { it is ListMark }
                lower[index] = ListMark(item.kind, item.depth, checked = true)
            }
        }
        return Enter(listOf(upper, lower), if (leavesQuote) quote!!.group else null, removedNewline = false, lowerEmpty = lowerEmpty)
    }

    /**
     * Las marcas del renglón después de un Backspace al principio, o `null` si no aplica:
     * un ítem baja un nivel (o deja de serlo en el 0); una cita sale, si [above] no es la
     * misma cita.
     */
    private fun outdented(marks: List<BlockMark>, above: List<BlockMark>): List<BlockMark>? {
        val item = marks.firstOrNull { it is ListMark } as ListMark?
        if (item != null) return marks.mapNotNull { if (it === item) item.outdentedOrNull() else it }
        val quote = marks.firstOrNull { it is QuoteMark } as QuoteMark? ?: return null
        if (above.any { it is QuoteMark && it.group == quote.group }) return null
        return marks.filter { it !== quote }
    }

    private fun ListMark.outdentedOrNull(): ListMark? = if (depth > 0) withDepth(depth - 1) else null

    private fun ListMark.withDepth(depth: Int) = ListMark(kind, depth, checked, start)

    // ---- piezas ----

    /** Una marca puesta en el texto mientras las reglas lo cambian: [before] no hace nada. */
    private class Applying : NoCopySpan

    private inline fun <T> applying(text: Editable, block: () -> T): T {
        val mark = Applying()
        text.setSpan(mark, 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        try {
            return block()
        } finally {
            text.removeSpan(mark)
        }
    }

    /**
     * El formato en línea que toma lo que se escribe: el del primer carácter que se
     * reemplaza; si no, el del carácter de antes; al principio del renglón, el del que sigue
     *. Un enlace solo se lo da a lo escrito dentro de su texto, no en sus bordes.
     */
    private fun formatsFor(text: Spanned, start: Int, removed: Int): List<InlineMark> {
        val at = when {
            removed > 0 && text[start] != '\n' -> start
            start == lineStartOf(text, start) -> start.takeIf { it < text.length && text[it] != '\n' }
            else -> start - 1
        } ?: return emptyList()
        return text.getSpans(at, at + 1, InlineMark::class.java)
            .filter { text.getSpanStart(it) <= at && text.getSpanEnd(it) > at }
            .filter { it !is LinkMark || insideLink(text, it, start, removed) }
    }

    /** Si lo que se escribe en `[start, start + removed)` queda dentro del texto del enlace. */
    private fun insideLink(text: Spanned, link: LinkMark, start: Int, removed: Int): Boolean {
        val linkStart = text.getSpanStart(link)
        val linkEnd = text.getSpanEnd(link)
        return if (removed > 0) linkStart <= start && start + removed <= linkEnd else linkStart < start && start < linkEnd
    }

    /**
     * El tramo de lo escrito `[from, to)` que va en el enlace. Si el teclado recompone la
     * palabra del enlace y le agrega algo en el borde (reemplaza "ab" por "abx" o "xab"), lo
     * agregado queda afuera; si no, todo.
     */
    private fun linkRange(change: Change, typed: String, from: Int, to: Int): IntRange {
        val old = change.removedText
        if (old.isEmpty() || typed.length <= old.length) return from until to
        return when {
            change.linkAtEnd && typed.startsWith(old) -> from until from + old.length
            change.linkAtStart && typed.endsWith(old) -> to - old.length until to
            else -> from until to
        }
    }

    /** El fin del enlace que tiene a `[from, to]` estrictamente adentro de su texto. */
    private fun linkEndAround(text: Spanned, from: Int, to: Int): Int? =
        text.getSpans(from, to, LinkMark::class.java)
            .firstOrNull { text.getSpanStart(it) < from && text.getSpanEnd(it) > to }
            ?.let { text.getSpanEnd(it) }

    /**
     * Lo escrito toma el formato de [formats] cambiado por [pending]. En un renglón de
     * código no toma ninguno: el `<pre>` se guarda en texto plano.
     */
    private fun formatTyped(
        text: Editable,
        from: Int,
        to: Int,
        formats: List<InlineMark>,
        pending: Map<InlineFormat, Boolean>,
        pendingColor: PendingColor?,
        link: IntRange,
    ) {
        val wanted = formats
            .filter { mark -> pending.none { (format, on) -> !on && mark.isFormat(format) } }
            .mapTo(ArrayList()) { it.copy() as InlineMark }
        for ((format, on) in pending) {
            if (on && wanted.none { it.isFormat(format) }) wanted.add(RichCommands.newMark(format))
        }
        if (pendingColor != null) {
            // El color pedido reemplaza al de al lado; "Sin color" lo saca.
            if (pendingColor.text != null || pendingColor.clearText) wanted.removeAll { it is TextColorMark }
            if (pendingColor.highlight != null || pendingColor.clearHighlight) wanted.removeAll { it is HighlightMark }
            pendingColor.text?.let { wanted.add(TextColorMark(it)) }
            pendingColor.highlight?.let { wanted.add(highlightMark(it)) }
        }
        clearInline(text, from, to)
        var runStart = from
        while (runStart < to) {
            val runEnd = text.indexOf('\n', runStart).let { if (it < 0 || it > to) to else it }
            val code = RichHtml.blockMarksAt(text, lineStartOf(text, runStart)).any { it is CodeBlockMark }
            if (runEnd > runStart && !code) {
                for (mark in wanted) {
                    if (mark !is LinkMark) {
                        text.setSpan(mark.copy(), runStart, runEnd, INLINE_MARK_FLAGS)
                        continue
                    }
                    val linkStart = maxOf(runStart, link.first)
                    val linkEnd = minOf(runEnd, link.last + 1)
                    if (linkEnd > linkStart) text.setSpan(mark.copy(), linkStart, linkEnd, INLINE_MARK_FLAGS)
                }
            }
            runStart = runEnd + 1
        }
    }

    /**
     * Saca de lo escrito los estilos que no son marcas ni de dibujo ni de la composición (los
     * que manda el teclado en `commitText`; Review Focus 3).
     */
    private fun removeForeignStyles(text: Editable, from: Int, to: Int) {
        if (to <= from) return
        for (span in text.getSpans(from, to, Any::class.java)) {
            // Los SuggestionSpan quedan: Gboard los usa para deshacer la autocorrección.
            if (span is RichMark || span is RenderSpan || span is NoCopySpan || span is SuggestionSpan) continue
            if (span !is CharacterStyle && span !is ParagraphStyle) continue
            if (text.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0) continue
            if (text.getSpanStart(span) >= from && text.getSpanEnd(span) <= to) text.removeSpan(span)
        }
    }

    /**
     * Pone las marcas de [plan] en los renglones seguidos desde [from], después de sacar las
     * guardadas ([saved]) y las que hayan quedado en esos renglones. Devuelve el fin del
     * último renglón.
     */
    private fun placeLines(text: Editable, from: Int, plan: List<List<BlockMark>>, saved: List<BlockMark>): Int {
        for (mark in saved) text.removeSpan(mark)
        var lineStart = from
        var end = from
        for (marks in plan) {
            for (stray in RichHtml.blockMarksAt(text, lineStart)) text.removeSpan(stray)
            end = blockEnd(text, lineStart)
            for (mark in marks) text.setSpan(mark, lineStart, end, BLOCK_MARK_FLAGS)
            lineStart = end
        }
        return end
    }

    /** Cambia las marcas [old] del renglón por [new], en su orden. */
    internal fun setLineMarks(text: Editable, lineStart: Int, old: List<BlockMark>, new: List<BlockMark>) {
        for (mark in old) text.removeSpan(mark)
        val end = blockEnd(text, lineStart)
        for (mark in new) text.setSpan(mark, lineStart, end, BLOCK_MARK_FLAGS)
    }

    /** Hasta después del `\n` del renglón, o el final del texto. */
    internal fun blockEnd(text: CharSequence, lineStart: Int) =
        lineEndOf(text, lineStart).let { if (it < text.length) it + 1 else it }

    /** Si el renglón salió de una cita, lo de abajo de esa cita pasa a ser otra. */
    private fun splitQuoteBelow(text: Editable, lineStart: Int, old: List<BlockMark>, new: List<BlockMark>) {
        val quote = old.firstOrNull { it is QuoteMark } as QuoteMark? ?: return
        if (new.any { it is QuoteMark }) return
        splitQuoteFrom(text, blockEnd(text, lineStart), quote.group)
    }

    /** Los renglones seguidos desde [from] en la cita [group] pasan a una cita nueva. */
    internal fun splitQuoteFrom(text: Editable, from: Int, group: Long) {
        val newGroup = newBlockGroup()
        var lineStart = from
        while (true) {
            val marks = RichHtml.blockMarksAt(text, lineStart)
            val quote = marks.firstOrNull { it is QuoteMark && it.group == group } ?: return
            setLineMarks(text, lineStart, marks, marks.map { if (it === quote) QuoteMark(newGroup) else it })
            val end = blockEnd(text, lineStart)
            if (end == lineStart) return
            lineStart = end
        }
    }

    /** Saca de `[from, to)` las marcas en línea; lo de afuera queda. */
    internal fun clearInline(text: Editable, from: Int, to: Int) {
        for (mark in text.getSpans(from, to, InlineMark::class.java)) {
            val start = text.getSpanStart(mark)
            val end = text.getSpanEnd(mark)
            if (end <= from || start >= to) continue
            if (start < from) text.setSpan(mark, start, from, INLINE_MARK_FLAGS) else text.removeSpan(mark)
            if (end > to) text.setSpan(if (start < from) mark.copy() else mark, to, end, INLINE_MARK_FLAGS)
        }
    }

    /** Ninguna marca en línea cruza un `\n`: la que lo cruza se parte. */
    private fun splitAtNewlines(text: Editable, from: Int, to: Int) {
        for (mark in text.getSpans(from, to, InlineMark::class.java)) {
            val start = text.getSpanStart(mark)
            val end = text.getSpanEnd(mark)
            if (text.indexOf('\n', start).let { it < 0 || it >= end }) continue
            text.removeSpan(mark)
            var pieceStart = start
            var first = true
            while (pieceStart < end) {
                val pieceEnd = text.indexOf('\n', pieceStart).let { if (it < 0 || it > end) end else it }
                if (pieceEnd > pieceStart) {
                    text.setSpan(if (first) mark else mark.copy(), pieceStart, pieceEnd, INLINE_MARK_FLAGS)
                    first = false
                }
                pieceStart = pieceEnd + 1
            }
        }
    }

    /**
     * En cada renglón de `[from, to]`, las marcas en línea del mismo formato que se tocan pasan
     * a ser una sola, y las vacías se van.
     */
    private fun mergeInline(text: Editable, from: Int, to: Int) {
        var lineStart = from
        while (true) {
            val lineEnd = lineEndOf(text, lineStart)
            mergeLine(text, lineStart, lineEnd)
            if (lineEnd >= minOf(to, text.length)) return
            lineStart = lineEnd + 1
        }
    }

    private fun mergeLine(text: Editable, lineStart: Int, lineEnd: Int) {
        while (true) {
            val marks = text.getSpans(lineStart, lineEnd, InlineMark::class.java)
                .filter { text.getSpanStart(it) >= lineStart && text.getSpanEnd(it) <= lineEnd }
            val empty = marks.firstOrNull { text.getSpanStart(it) == text.getSpanEnd(it) }
            if (empty != null) {
                text.removeSpan(empty)
                continue
            }
            val pair = marks.withIndex().firstNotNullOfOrNull { (i, a) ->
                marks.drop(i + 1).firstOrNull { b ->
                    a.sameFormat(b) && text.getSpanStart(b) <= text.getSpanEnd(a) && text.getSpanStart(a) <= text.getSpanEnd(b)
                }?.let { a to it }
            } ?: return
            val (keep, drop) = pair
            val start = minOf(text.getSpanStart(keep), text.getSpanStart(drop))
            val end = maxOf(text.getSpanEnd(keep), text.getSpanEnd(drop))
            text.removeSpan(drop)
            text.setSpan(keep, start, end, INLINE_MARK_FLAGS)
        }
    }
}
