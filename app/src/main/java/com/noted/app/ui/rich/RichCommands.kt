package com.noted.app.ui.rich

import android.text.SpannableStringBuilder
import androidx.compose.ui.text.TextRange

/** Los formatos de renglón de la barra. */
enum class LineFormat { Heading2, Bullet, Ordered, Check, Quote }

/**
 * Los botones de la barra que se ven apretados cuando su formato está activo. Enlace, Desplegable, Nota dentro de esta, Separador y Adjuntar no se marcan: no son
 * un formato que se saque con otro toque. [Code] es el código en línea; el bloque de código
 * no se marca nunca.
 */
enum class BarFormat { Bold, Italic, Strike, Heading, Bullet, Ordered, Check, Quote, Code }

/**
 * Los botones de la barra sobre el texto con formato: ponen y sacan marcas
 * con las mismas reglas que hoy `wrap`, `setLinePrefix` y `prefixLine` con el Markdown.
 */
object RichCommands {

    /**
     * Con selección: si toda la selección ya tiene el formato, se saca; si no, se pone
     * a toda. La selección queda igual. Sin selección: da vuelta
     * `pending[format]`, sin tocar el texto. En los renglones de código no hace nada: el
     * `<pre>` se guarda en texto plano.
     */
    fun toggleInline(value: RichText, format: InlineFormat): RichText {
        if (value.selection.collapsed) {
            val lineStart = lineStartOf(value.plain, value.selection.min)
            if (RichHtml.blockMarksAt(value.text, lineStart).any { it is CodeBlockMark }) return value
            val next = !formatAt(value, format)
            val pending = if (next == markedAt(value, format)) value.pending - format else value.pending + (format to next)
            return RichText(value.text, value.selection, pending, value.pendingColor)
        }
        val edit = MarkEdit(value)
        val segments = inlineSegments(edit, value.selection)
        if (segments.isEmpty()) return value
        val remove = allFormatted(edit, segments, format)

        for (segment in segments) {
            edit.clearInline(segment.first, segment.last + 1) { it.isFormat(format) }
            if (!remove) edit.marks.add(Placed(newMark(format), segment.first, segment.last + 1))
        }
        return edit.build(value.selection, value.pending, value.pendingColor)
    }

    /** Si todos los caracteres de [segments] tienen el formato. */
    private fun allFormatted(edit: MarkEdit, segments: List<IntRange>, format: InlineFormat): Boolean {
        val formatted = edit.marks.filter { it.mark is InlineMark && it.mark.isFormat(format) }
        return segments.all { segment -> segment.all { i -> formatted.any { it.start <= i && i < it.end } } }
    }

    /**
     * Los botones apretados para el valor del campo con el foco. Sin selección,
     * los formatos en línea del cursor con [RichText.pending] primero, y los de renglón del
     * renglón del cursor. Con selección, la misma regla con la que el botón decide si saca:
     * toda la selección (o todos sus renglones) con el formato. En un renglón de código no se
     * marca nada.
     */
    fun activeFormats(value: RichText): Set<BarFormat> {
        val edit = MarkEdit(value)
        val result = LinkedHashSet<BarFormat>()
        val inline = listOf(
            InlineFormat.Bold to BarFormat.Bold,
            InlineFormat.Italic to BarFormat.Italic,
            InlineFormat.Strike to BarFormat.Strike,
            InlineFormat.Code to BarFormat.Code,
        )
        if (value.selection.collapsed) {
            val lineStart = lineStartOf(value.plain, value.selection.min)
            if (edit.blocksAt(lineStart).any { it.mark is CodeBlockMark }) return emptySet()
            for ((format, button) in inline) if (formatAt(value, format)) result.add(button)
        } else {
            val segments = inlineSegments(edit, value.selection)
            if (segments.isNotEmpty()) {
                for ((format, button) in inline) if (allFormatted(edit, segments, format)) result.add(button)
            }
        }
        val targets = lineTargets(edit, value.selection)
        if (targets.isNotEmpty()) {
            val lines = listOf(
                BarFormat.Heading to { mark: RichMark -> mark is HeadingMark },
                BarFormat.Bullet to { mark: RichMark -> mark.isFormat(LineFormat.Bullet) },
                BarFormat.Ordered to { mark: RichMark -> mark.isFormat(LineFormat.Ordered) },
                BarFormat.Check to { mark: RichMark -> mark.isFormat(LineFormat.Check) },
                BarFormat.Quote to { mark: RichMark -> mark is QuoteMark },
            )
            for ((button, has) in lines) {
                if (targets.all { start -> edit.blocksAt(start).any { has(it.mark) } }) result.add(button)
            }
        }
        return result
    }

    /**
     * Los renglones de la selección que toma un formato de renglón: con varios, sin los
     * vacíos; nunca los de código ni los separadores.
     */
    private fun lineTargets(edit: MarkEdit, selection: TextRange): List<Int> {
        val lines = selectedLines(edit.plain, selection)
        // Con varios renglones, los vacíos quedan como están.
        return (if (lines.size > 1) lines.filter { edit.lineText(it).isNotBlank() } else lines)
            // No van listas ni títulos dentro de un <pre>; un separador no tiene texto.
            .filter { start -> edit.blocksAt(start).none { it.mark is CodeBlockMark || it.mark is DividerMark } }
    }

    /**
     * Los tramos de la selección que toma un formato en línea. En un solo renglón, la
     * selección entera. Con varios (como hoy `wrapLines`, R35), un tramo por renglón, sin los
     * espacios de los bordes, sin los renglones vacíos ni los separadores. Nunca en un renglón
     * de código.
     */
    private fun inlineSegments(edit: MarkEdit, selection: TextRange): List<IntRange> {
        val plain = edit.plain
        val min = selection.min
        val max = selection.max
        val code = { lineStart: Int -> edit.blocksAt(lineStart).any { it.mark is CodeBlockMark } }
        if (!plain.substring(min, max).contains('\n')) {
            return if (code(lineStartOf(plain, min))) emptyList() else listOf(min until max)
        }
        val segments = ArrayList<IntRange>()
        var start = min
        while (start <= max) {
            val lineStart = lineStartOf(plain, start)
            val lineEnd = lineEndOf(plain, start)
            var s = start
            var e = minOf(lineEnd, max)
            while (s < e && plain[s].isWhitespace()) s++
            while (e > s && plain[e - 1].isWhitespace()) e--
            val divider = edit.blocksAt(lineStart).any { it.mark is DividerMark }
            if (s < e && !divider && !code(lineStart)) segments.add(s until e)
            start = lineEnd + 1
        }
        return segments
    }

    /** Si el formato está prendido en el cursor: lo de [RichText.pending] primero. */
    fun formatAt(value: RichText, format: InlineFormat): Boolean =
        value.pending[format] ?: markedAt(value, format)

    /**
     * Título, lista o cita sobre los renglones de la selección (como hoy `setLinePrefix`): si
     * todos ya lo tienen, se saca; si no, reemplaza al que tenía cada renglón.
     */
    fun toggleLine(value: RichText, format: LineFormat): RichText {
        val edit = MarkEdit(value)
        val targets = lineTargets(edit, value.selection)
        if (targets.isEmpty()) return value
        val remove = targets.all { start -> edit.blocksAt(start).any { it.mark.isFormat(format) } }

        val quoteGroup = if (format == LineFormat.Quote) newBlockGroup() else 0L
        for (start in targets) {
            val blocks = edit.blocksAt(start)
            if (remove) {
                edit.marks.removeAll(blocks.filter { it.mark.isFormat(format) }.toSet())
                continue
            }
            val depth = blocks.firstNotNullOfOrNull { (it.mark as? ListMark)?.depth } ?: 0
            val mark = when (format) {
                LineFormat.Heading2 -> HeadingMark(2)
                LineFormat.Bullet -> ListMark(ListKind.Bullet, depth)
                LineFormat.Ordered -> ListMark(ListKind.Ordered, depth)
                LineFormat.Check -> ListMark(ListKind.Check, depth, checked = false)
                LineFormat.Quote -> QuoteMark(quoteGroup)
            }
            edit.marks.removeAll(blocks.filter { it.mark is HeadingMark || it.mark is ListMark || it.mark is QuoteMark }.toSet())
            edit.marks.add(Placed(mark, start, edit.blockEnd(start)))
        }
        if (format == LineFormat.Quote && !remove) edit.joinQuotes(targets.toSet())
        return edit.build(value.selection, value.pending, value.pendingColor)
    }

    /**
     * Botón Código: con un `\n` en la selección, sus renglones pasan a un bloque de
     * código nuevo, sin otras marcas, y si no queda ningún renglón abajo se agrega un párrafo
     * vacío. Si no, es el código en línea.
     */
    fun code(value: RichText): RichText {
        val min = value.selection.min
        val max = value.selection.max
        if (!value.plain.substring(min, max).contains('\n')) return toggleInline(value, InlineFormat.Code)
        return codeBlock(value)
    }

    /**
     * Los renglones de la selección pasan a un bloque de código nuevo, o vuelven a párrafos si ya
     * lo son: la parte de bloque del botón Código ([code]). Es Ctrl+Shift+Alt+C, que lo
     * hace también con un solo renglón.
     */
    fun codeBlock(value: RichText): RichText {
        val edit = MarkEdit(value)
        // Los separadores quedan como están.
        val lines = selectedLines(edit.plain, value.selection)
            .filter { start -> edit.blocksAt(start).none { it.mark is DividerMark } }
        if (lines.isEmpty()) return value
        val withText = lines.filter { edit.lineText(it).isNotEmpty() }
        val isCode = { start: Int -> edit.blocksAt(start).any { it.mark is CodeBlockMark } }

        // Si ya son todos código, el toque los vuelve párrafos, como hoy sacar las cercas.
        if (withText.isNotEmpty() && withText.all(isCode)) {
            val below = lineEndOf(edit.plain, lines.last()) + 1
            edit.marks.removeAll(lines.flatMap { edit.blocksAt(it) }.filter { it.mark is CodeBlockMark }.toSet())
            // Lo que queda del <pre> abajo es otro bloque.
            if (below <= edit.plain.length) edit.regroupCodeFrom(below)
            return edit.build(value.selection, value.pending, value.pendingColor)
        }

        val to = lineEndOf(edit.plain, lines.last())
        edit.clearInline(lines.first(), to) { true }
        for (start in lines) edit.marks.removeAll(edit.blocksAt(start).toSet())
        if (to == edit.plain.length) edit.appendEmptyLine()
        // Un separador en el medio corta el bloque: cada tramo seguido tiene su grupo.
        var group = newBlockGroup()
        lines.forEachIndexed { index, start ->
            if (index > 0 && lineEndOf(edit.plain, lines[index - 1]) + 1 != start) group = newBlockGroup()
            edit.marks.add(Placed(CodeBlockMark(group, null), start, edit.blockEnd(start)))
        }
        return edit.build(value.selection, value.pending, value.pendingColor)
    }

    /**
     * Separador: antes del renglón del cursor agrega
     * un renglón vacío y el del separador. El cursor sigue en su renglón.
     */
    fun insertDivider(value: RichText): RichText {
        val edit = MarkEdit(value)
        val at = lineStartOf(edit.plain, value.selection.min)
        val inserted = "\n$DIVIDER_CHAR\n"
        edit.insert(at, inserted)
        edit.marks.add(Placed(DividerMark(), at + 1, at + inserted.length))
        val shift = inserted.length
        return edit.build(TextRange(value.selection.start + shift, value.selection.end + shift), value.pending, value.pendingColor)
    }

    /** Tilda o destilda el ítem del renglón de [offset], sin mover la selección. */
    fun toggleCheck(value: RichText, offset: Int): RichText {
        val edit = MarkEdit(value)
        val start = lineStartOf(edit.plain, offset.coerceIn(0, edit.plain.length))
        val item = edit.blocksAt(start).firstOrNull { (it.mark as? ListMark)?.kind == ListKind.Check } ?: return value
        val mark = item.mark as ListMark
        edit.marks[edit.marks.indexOf(item)] = item.copy(mark = ListMark(mark.kind, mark.depth, !mark.checked, mark.start))
        return edit.build(value.selection, value.pending, value.pendingColor)
    }

    // ---- atajos con Ctrl ----

    /**
     * Título del nivel [level] en los renglones de la selección (Ctrl+1…6). Si todos ya lo
     * tienen, se saca; si no, reemplaza al título, la lista o
     * la cita de cada renglón, como el botón Título ([toggleLine]).
     */
    fun setHeading(value: RichText, level: Int): RichText {
        val edit = MarkEdit(value)
        val targets = lineTargets(edit, value.selection)
        if (targets.isEmpty()) return value
        val remove = targets.all { start -> edit.blocksAt(start).any { (it.mark as? HeadingMark)?.level == level } }
        for (start in targets) {
            val blocks = edit.blocksAt(start)
            if (remove) {
                edit.marks.removeAll(blocks.filter { it.mark is HeadingMark }.toSet())
                continue
            }
            edit.marks.removeAll(blocks.filter { it.mark is HeadingMark || it.mark is ListMark || it.mark is QuoteMark }.toSet())
            edit.marks.add(Placed(HeadingMark(level), start, edit.blockEnd(start)))
        }
        return edit.build(value.selection, value.pending, value.pendingColor)
    }

    /**
     * Color de texto (Ctrl+Alt+1…8; `null` lo quita, Ctrl+Alt+0). Con selección reemplaza el
     * color de la selección, que queda igual. Sin selección queda en [RichText.pendingColor] para
     * lo que se escriba. En los renglones de código no hace nada.
     */
    fun setTextColor(value: RichText, color: String?): RichText = setColor(value, color, highlight = false)

    /**
     * Resaltado (Ctrl+Shift+Alt+1…8; `"green"` es `noted-highlight`; `null` lo quita),
     * con las mismas reglas que [setTextColor].
     */
    fun setHighlight(value: RichText, color: String?): RichText = setColor(value, color, highlight = true)

    private fun setColor(value: RichText, color: String?, highlight: Boolean): RichText {
        if (value.selection.collapsed) {
            val lineStart = lineStartOf(value.plain, value.selection.min)
            if (RichHtml.blockMarksAt(value.text, lineStart).any { it is CodeBlockMark }) return value
            val old = value.pendingColor ?: PendingColor(null, null, clearText = false, clearHighlight = false)
            val pendingColor = if (highlight) old.copy(highlight = color, clearHighlight = color == null)
            else old.copy(text = color, clearText = color == null)
            return RichText(value.text, value.selection, value.pending, pendingColor)
        }
        val edit = MarkEdit(value)
        val segments = inlineSegments(edit, value.selection)
        if (segments.isEmpty()) return value
        for (segment in segments) {
            edit.clearInline(segment.first, segment.last + 1) { if (highlight) it is HighlightMark else it is TextColorMark }
            if (color == null) continue
            val mark = if (highlight) highlightMark(color) else TextColorMark(color)
            edit.marks.add(Placed(mark, segment.first, segment.last + 1))
        }
        return edit.build(value.selection, value.pending, value.pendingColor)
    }

    /**
     * Quitar formato (Ctrl+\). Con selección saca color, resaltado y todo el formato en
     * línea menos los enlaces (lo que saca `removeFormat` del navegador). Sin selección corta
     * desde el cursor negrita, cursiva, subrayado y tachado, y el color y el resaltado.
     */
    fun removeFormat(value: RichText): RichText {
        if (value.selection.collapsed) {
            val pending = value.pending.toMutableMap()
            for (format in CUT_AT_CURSOR) {
                if (markedAt(value, format)) pending[format] = false else pending.remove(format)
            }
            return RichText(value.text, value.selection, pending, PendingColor(null, null, clearText = true, clearHighlight = true))
        }
        val edit = MarkEdit(value)
        val segments = inlineSegments(edit, value.selection)
        if (segments.isEmpty()) return value
        for (segment in segments) edit.clearInline(segment.first, segment.last + 1) { it !is LinkMark }
        return edit.build(value.selection, value.pending, value.pendingColor)
    }

    /** Lo que Quitar formato corta en el cursor (`B/I/U/S`). */
    private val CUT_AT_CURSOR = listOf(InlineFormat.Bold, InlineFormat.Italic, InlineFormat.Underline, InlineFormat.Strike)

    /**
     * Ctrl+Sangría fuera de una checklist (Ctrl+] / Ctrl+[): lo mismo que Tab y Shift+Tab en el
     * campo ([RichEditRules.indent]). Fuera de una lista no hace nada.
     */
    fun indent(value: RichText, outdent: Boolean): RichText {
        val text = SpannableStringBuilder(value.text)
        if (!RichEditRules.indent(text, value.selection, outdent)) return value
        return RichText(text, value.selection, value.pending, value.pendingColor)
    }

    /**
     * Ctrl+Alt+↑ / ↓: el ítem del renglón del cursor, con los renglones
     * más profundos que le siguen (sus hijos), cambia de lugar con el hermano de arriba o de
     * abajo: el ítem anterior o siguiente del mismo nivel y la misma clase de lista, con sus
     * hijos. En el borde (no hay hermano: un párrafo, otro nivel menos profundo u otra lista) o
     * fuera de una lista devuelve [value] tal cual. El cursor va con el renglón.
     */
    fun moveListItem(value: RichText, up: Boolean): RichText {
        val plain = value.plain
        val starts = arrayListOf(0)
        while (true) {
            val end = lineEndOf(plain, starts.last())
            if (end >= plain.length) break
            starts.add(end + 1)
        }
        fun item(line: Int) = RichHtml.blockMarksAt(value.text, starts[line]).firstOrNull { it is ListMark } as ListMark?
        val line = starts.indexOfLast { it <= value.selection.min }
        val mark = item(line) ?: return value
        fun deeper(i: Int) = (item(i)?.depth ?: -1) > mark.depth
        fun sibling(i: Int) = item(i)?.let { it.depth == mark.depth && it.kind == mark.kind } == true
        fun groupEnd(i: Int): Int {
            var j = i + 1
            while (j < starts.size && deeper(j)) j++
            return j
        }
        // Se cambian los tramos de renglones [first, middle) y [middle, last).
        val first: Int
        val middle: Int
        val last: Int
        if (up) {
            var above = line - 1
            while (above >= 0 && deeper(above)) above--
            if (above < 0 || !sibling(above)) return value
            first = above
            middle = line
            last = groupEnd(line)
        } else {
            val below = groupEnd(line)
            if (below >= starts.size || !sibling(below)) return value
            first = line
            middle = below
            last = groupEnd(below)
        }
        val order = (0 until first) + (middle until last) + (first until middle) + (last until starts.size)
        return reorderLines(value, starts, order)
    }

    /**
     * El texto con sus renglones (que empiezan en [starts]) en el orden [order], cada uno con sus
     * marcas; la selección va con su renglón.
     */
    private fun reorderLines(value: RichText, starts: List<Int>, order: List<Int>): RichText {
        val plain = value.plain
        val ends = starts.map { lineEndOf(plain, it) }
        val newStart = IntArray(starts.size)
        val out = StringBuilder()
        order.forEachIndexed { n, i ->
            if (n > 0) out.append('\n')
            newStart[i] = out.length
            out.append(plain, starts[i], ends[i])
        }
        val text = SpannableStringBuilder(out.toString())
        val lastLine = order.last()
        fun lineOf(pos: Int) = starts.indexOfLast { it <= pos }
        fun moved(pos: Int): Int {
            val i = lineOf(pos)
            return newStart[i] + (pos - starts[i]).coerceAtMost(ends[i] - starts[i])
        }
        for (mark in value.text.getSpans(0, plain.length, RichMark::class.java)) {
            val start = value.text.getSpanStart(mark)
            val end = value.text.getSpanEnd(mark)
            when (mark) {
                is BlockMark -> {
                    val i = lineOf(start)
                    val length = ends[i] - starts[i] + if (i == lastLine) 0 else 1
                    text.setSpan(mark.copy(), newStart[i], newStart[i] + length, BLOCK_MARK_FLAGS)
                }
                is InlineMark -> for (i in lineOf(start)..lineOf(maxOf(start, end - 1))) {
                    val from = maxOf(start, starts[i])
                    val to = minOf(end, ends[i])
                    if (to > from) {
                        text.setSpan(mark.copy(), newStart[i] + from - starts[i], newStart[i] + to - starts[i], INLINE_MARK_FLAGS)
                    }
                }
            }
        }
        val selection = TextRange(moved(value.selection.start), moved(value.selection.end))
        return RichText(text, selection, value.pending, value.pendingColor)
    }

    // ---- piezas ----

    /**
     * Si en el cursor hay una marca del formato: la que termina en él (lo que se escribe la
     * sigue), salvo al principio del renglón, donde vale la del carácter que sigue.
     */
    private fun markedAt(value: RichText, format: InlineFormat): Boolean {
        val text = value.text
        val pos = value.selection.min
        val atLineStart = pos == lineStartOf(value.plain, pos)
        return text.getSpans(pos, pos, InlineMark::class.java).any { mark ->
            val start = text.getSpanStart(mark)
            val end = text.getSpanEnd(mark)
            mark.isFormat(format) && if (atLineStart) start <= pos && pos < end else start < pos && pos <= end
        }
    }

    /** Las líneas de la selección, por su principio; la que empieza justo en el final no entra. */
    internal fun selectedLines(plain: String, selection: TextRange): List<Int> {
        val first = lineStartOf(plain, selection.min)
        var lastPos = selection.max
        if (!selection.collapsed && lastPos > first && plain[lastPos - 1] == '\n') lastPos--
        val last = lineStartOf(plain, lastPos)
        val starts = arrayListOf(first)
        while (starts.last() < last) starts.add(lineEndOf(plain, starts.last()) + 1)
        return starts
    }

    internal fun newMark(format: InlineFormat): InlineMark = when (format) {
        InlineFormat.Bold -> BoldMark()
        InlineFormat.Italic -> ItalicMark()
        InlineFormat.Strike -> StrikeMark("strike")
        InlineFormat.Code -> CodeMark()
        InlineFormat.Underline -> UnderlineMark()
        InlineFormat.Subscript -> SubscriptMark()
        InlineFormat.Superscript -> SuperscriptMark()
    }

    /** Si la marca es de ese formato; un tachado cuenta con cualquier etiqueta (`<s>` también). */
    internal fun RichMark.isFormat(format: InlineFormat): Boolean = when (format) {
        InlineFormat.Bold -> this is BoldMark
        InlineFormat.Italic -> this is ItalicMark
        InlineFormat.Strike -> this is StrikeMark
        InlineFormat.Code -> this is CodeMark
        InlineFormat.Underline -> this is UnderlineMark
        InlineFormat.Subscript -> this is SubscriptMark
        InlineFormat.Superscript -> this is SuperscriptMark
    }

    private fun RichMark.isFormat(format: LineFormat): Boolean = when (format) {
        LineFormat.Heading2 -> this is HeadingMark && level == 2
        LineFormat.Bullet -> this is ListMark && kind == ListKind.Bullet
        LineFormat.Ordered -> this is ListMark && kind == ListKind.Ordered
        LineFormat.Check -> this is ListMark && kind == ListKind.Check
        LineFormat.Quote -> this is QuoteMark
    }

    private data class Placed(val mark: RichMark, val start: Int, val end: Int)

    /** El texto como lista de marcas con su tramo, en el orden del texto (que da el anidado). */
    private class MarkEdit(value: RichText) {
        var plain = value.plain
            private set
        val marks: MutableList<Placed> = value.text.getSpans(0, value.text.length, RichMark::class.java)
            .mapTo(ArrayList()) { Placed(it, value.text.getSpanStart(it), value.text.getSpanEnd(it)) }

        fun lineText(start: Int) = plain.substring(start, lineEndOf(plain, start))

        /** Hasta después del `\n` del renglón, o el final del texto. */
        fun blockEnd(start: Int) = lineEndOf(plain, start).let { if (it < plain.length) it + 1 else it }

        fun blocksAt(start: Int) = marks.filter { it.mark is BlockMark && it.start == start }

        /** Saca de `[from, to)` las marcas en línea que cumplen [which]; lo de afuera queda. */
        fun clearInline(from: Int, to: Int, which: (InlineMark) -> Boolean) {
            val result = ArrayList<Placed>()
            for (placed in marks) {
                val mark = placed.mark
                if (mark !is InlineMark || !which(mark) || placed.end <= from || placed.start >= to) {
                    result.add(placed)
                    continue
                }
                if (placed.start < from) result.add(Placed(mark, placed.start, from))
                if (placed.end > to) {
                    result.add(Placed(if (placed.start < from) mark.copy() else mark, to, placed.end))
                }
            }
            marks.clear()
            marks.addAll(result)
        }

        /** Los principios de todos los renglones. */
        private fun lineStarts(): List<Int> {
            val starts = arrayListOf(0)
            while (true) {
                val end = lineEndOf(plain, starts.last())
                if (end >= plain.length) return starts
                starts.add(end + 1)
            }
        }

        /** Cambia el `group` de la marca de bloque [T] del renglón de [start]. */
        private inline fun <reified T : BlockMark> regroup(start: Int, make: (T) -> T) {
            val index = marks.indexOfFirst { it.mark is T && it.start == start }
            if (index >= 0) marks[index] = marks[index].copy(mark = make(marks[index].mark as T))
        }

        /**
         * Las citas seguidas que tocan algún renglón de [touched] pasan a ser una sola: todas
         * con el `group` de su primer renglón.
         */
        fun joinQuotes(touched: Set<Int>) {
            val run = ArrayList<Int>()
            fun flush() {
                if (run.any { it in touched }) {
                    val group = (blocksAt(run.first()).first { it.mark is QuoteMark }.mark as QuoteMark).group
                    for (start in run) regroup<QuoteMark>(start) { QuoteMark(group) }
                }
                run.clear()
            }
            for (start in lineStarts()) {
                if (blocksAt(start).any { it.mark is QuoteMark }) run.add(start) else flush()
            }
            flush()
        }

        /** Los renglones de código seguidos desde [start] con un mismo `group` pasan a otro nuevo. */
        fun regroupCodeFrom(start: Int) {
            val first = blocksAt(start).firstOrNull { it.mark is CodeBlockMark }?.mark as CodeBlockMark? ?: return
            val group = newBlockGroup()
            for (line in lineStarts().filter { it >= start }) {
                val mark = blocksAt(line).firstOrNull { it.mark is CodeBlockMark }?.mark as CodeBlockMark?
                if (mark?.group != first.group) return
                regroup<CodeBlockMark>(line) { CodeBlockMark(group, it.language) }
            }
        }

        /** Mete [inserted] en [at], que es un principio de renglón: las marcas de ahí en más se corren. */
        fun insert(at: Int, inserted: String) {
            plain = plain.substring(0, at) + inserted + plain.substring(at)
            val shift = inserted.length
            for (i in marks.indices) {
                val placed = marks[i]
                marks[i] = placed.copy(
                    start = if (placed.start >= at) placed.start + shift else placed.start,
                    end = if (placed.end > at || placed.start >= at) placed.end + shift else placed.end,
                )
            }
        }

        /** Agrega un `\n` al final con un párrafo vacío; el renglón de antes lo incluye. */
        fun appendEmptyLine() {
            val length = plain.length
            plain += "\n"
            for (i in marks.indices) {
                val placed = marks[i]
                if (placed.mark is BlockMark && placed.end == length) marks[i] = placed.copy(end = length + 1)
            }
        }

        fun build(selection: TextRange, pending: Map<InlineFormat, Boolean>, pendingColor: PendingColor?): RichText {
            val text = SpannableStringBuilder(plain)
            for (placed in marks) {
                when (placed.mark) {
                    is InlineMark -> if (placed.end > placed.start) {
                        text.setSpan(placed.mark, placed.start, placed.end, INLINE_MARK_FLAGS)
                    }
                    is BlockMark -> text.setSpan(placed.mark, placed.start, placed.end, BLOCK_MARK_FLAGS)
                }
            }
            return RichText(text, selection, pending, pendingColor)
        }
    }
}
