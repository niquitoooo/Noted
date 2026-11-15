package com.noted.app.ui

import android.text.SpannableString
import androidx.compose.ui.text.TextRange
import com.noted.app.ui.rich.BLOCK_MARK_FLAGS
import com.noted.app.ui.rich.BlockMark
import com.noted.app.ui.rich.HeadingMark
import com.noted.app.ui.rich.INLINE_MARK_FLAGS
import com.noted.app.ui.rich.InlineFormat
import com.noted.app.ui.rich.InlineMark
import com.noted.app.ui.rich.ListKind
import com.noted.app.ui.rich.ListMark
import com.noted.app.ui.rich.PendingColor
import com.noted.app.ui.rich.RichHtml
import com.noted.app.ui.rich.RichMark
import com.noted.app.ui.rich.RichText
import com.noted.app.ui.rich.concat
import com.noted.app.ui.rich.copy
import com.noted.app.ui.rich.split
import java.util.UUID

/**
 * Un ítem de un bloque de checklist. [value] tiene solo marcas en línea y nunca un `\n`.
 * [nested] es el único nivel de anidado: el padre de un ítem anidado es el ítem de
 * primer nivel más cercano de arriba.
 */
internal data class ChecklistItem(
    val id: String,
    val value: RichText,
    val checked: Boolean = false,
    val nested: Boolean = false,
)

/** Una fila en pantalla: un ítem o el padre fantasma de un subítem marcado. */
internal sealed interface ChecklistRow {
    val id: String

    data class Item(val item: ChecklistItem) : ChecklistRow {
        override val id: String get() = item.id
    }

    data class Ghost(val parent: ChecklistItem) : ChecklistRow {
        override val id: String get() = "ghost-" + parent.id
    }
}

/** Las filas de arriba, las de la sección de marcados y cuántos ítems marcados hay. */
internal data class ChecklistRows(
    val unchecked: List<ChecklistRow.Item>,
    val checked: List<ChecklistRow>,
    val checkedCount: Int,
)

/**
 * El resultado de editar la lista: los ítems nuevos y dónde queda el foco. [focusId] `null`
 * quiere decir que el foco sale del bloque hacia el bloque de arriba.
 *
 * [keepFocus] `true` quiere decir que la operación no encontró el ítem pedido (un id que ya
 * no está): el editor deja el foco donde está e ignora [focusId] (`null`) y [cursor] (0).
 */
internal data class ChecklistEdit(
    val items: List<ChecklistItem>,
    val focusId: String?,
    val cursor: Int,
    val keepFocus: Boolean = false,
)

/**
 * Las operaciones sobre la lista guardada de un bloque. Toda función que cambia la lista
 * empieza con [canonical], así "arriba" y "debajo" son lo mismo guardado y en pantalla.
 */
internal object ChecklistOps {

    /**
     * Arma las filas recorriendo los grupos en el orden guardado (un grupo es un ítem de
     * primer nivel y los anidados que lo siguen). Un grupo con el padre marcado va entero a
     * la sección, también un hijo sin marcar (nota vieja). Si el padre no está marcado, él y
     * sus hijos sin marcar van arriba, y los hijos marcados van a la sección debajo de un
     * fantasma del padre.
     */
    fun rows(items: List<ChecklistItem>): ChecklistRows {
        val unchecked = mutableListOf<ChecklistRow.Item>()
        val checked = mutableListOf<ChecklistRow>()
        for (group in groups(items)) {
            val parent = group.first()
            if (parent.checked) {
                group.mapTo(checked) { ChecklistRow.Item(it) }
            } else {
                unchecked += ChecklistRow.Item(parent)
                val children = group.drop(1)
                children.filterNot { it.checked }.mapTo(unchecked) { ChecklistRow.Item(it) }
                val checkedChildren = children.filter { it.checked }
                if (checkedChildren.isNotEmpty()) {
                    checked += ChecklistRow.Ghost(parent)
                    checkedChildren.mapTo(checked) { ChecklistRow.Item(it) }
                }
            }
        }
        return ChecklistRows(unchecked, checked, items.count { it.checked })
    }

    /**
     * Primero los grupos con el padre sin marcar y después los de padre marcado, cada tramo
     * en su orden. No cambia ningún ítem.
     */
    fun canonical(items: List<ChecklistItem>): List<ChecklistItem> {
        val (checked, unchecked) = groups(items).partition { it.first().checked }
        return (unchecked + checked).flatten()
    }

    /**
     * Marca o desmarca el ítem [id]:
     * - un ítem de primer nivel que se marca marca también a sus hijos y el grupo va al
     *   final; al desmarcarlo, el grupo va al final de los no marcados y sus hijos quedan
     *   como estaban;
     * - un subítem con el padre sin marcar solo cambia su casilla, sin moverse;
     * - desmarcar un subítem con el padre marcado desmarca también al padre, y el grupo va
     *   al final de los no marcados;
     * - marcar o desmarcar un subítem sin marcar con el padre marcado (nota vieja) solo
     *   cambia su casilla.
     */
    fun toggle(items: List<ChecklistItem>, id: String): List<ChecklistItem> {
        val groups = groups(canonical(items)).toMutableList()
        val index = groups.indexOfFirst { group -> group.any { it.id == id } }
        if (index < 0) return groups.flatten()
        val group = groups[index]
        val parent = group.first()
        val target = group.first { it.id == id }

        if (target !== parent && !(parent.checked && target.checked)) {
            groups[index] = group.map { if (it === target) it.copy(checked = !it.checked) else it }
            return groups.flatten()
        }

        val moved = when {
            target === parent && !parent.checked -> group.map { it.copy(checked = true) }
            target === parent -> listOf(parent.copy(checked = false)) + group.drop(1)
            // Un subítem marcado con el padre marcado: se desmarcan los dos.
            else -> group.map { if (it === parent || it === target) it.copy(checked = false) else it }
        }
        groups.removeAt(index)
        val end = if (moved.first().checked) groups.size else groups.count { !it.first().checked }
        groups.add(end, moved)
        return groups.flatten()
    }

    /** El primer ítem de un bloque nunca está anidado. */
    fun normalize(items: List<ChecklistItem>): List<ChecklistItem> {
        val first = items.firstOrNull() ?: return items
        return if (first.nested) listOf(first.copy(nested = false)) + items.drop(1) else items
    }

    fun newItemId(): String = UUID.randomUUID().toString()

    /**
     * Cambia el texto del ítem [id]. Si [value] trae `\n` (Enter, pegar, un teclado que manda
     * "palabra\n"), parte en ítems: el primero conserva el ítem y los nuevos van sin
     * marcar, con el formato pendiente de [value], del mismo nivel y debajo; en un padre con hijos, el nuevo es su primer hijo y los hijos siguen siendo
     * del padre. Si el ítem estaba marcado, los nuevos van sin anidar al final de los no
     * marcados, o anidados al final del grupo si el marcado es hijo de un padre sin marcar
     *. Las marcas de bloque se descartan. El foco queda en el último, en el cursor.
     * Si [id] no está, la lista no cambia y [ChecklistEdit.keepFocus] es `true`.
     */
    fun replaceText(items: List<ChecklistItem>, id: String, value: RichText): ChecklistEdit {
        val list = canonical(items).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return keepingFocus(list)
        val target = list[index]
        if ('\n' !in value.plain) {
            list[index] = target.copy(value = inlineOnly(value, value.selection, value.pending, value.pendingColor))
            return ChecklistEdit(normalize(list), id, value.selection.start)
        }

        val pieces = mutableListOf<RichText>()
        var rest = value
        var lastStart = 0
        while (true) {
            val newline = rest.plain.indexOf('\n')
            if (newline < 0) break
            val (top, bottom) = rest.split(newline, newline + 1)
            pieces += top
            rest = bottom
            lastStart += newline + 1
        }
        val cursor = (value.selection.start - lastStart).coerceIn(0, rest.plain.length)
        pieces += rest

        val group = groups(list).first { group -> group.any { it.id == id } }
        val parent = group.first()
        val (at, nested) = when {
            // Un padre con hijos: el nuevo va como su primer hijo.
            !target.checked -> index + 1 to (target.nested || list.getOrNull(index + 1)?.nested == true)
            target !== parent && !parent.checked -> list.indexOf(group.last()) + 1 to true
            else -> uncheckedEnd(list) to false
        }
        val created = pieces.drop(1).mapIndexed { i, piece ->
            val last = i == pieces.size - 2
            val text = if (last) inlineOnly(piece, TextRange(cursor), value.pending, value.pendingColor) else inlineOnly(piece)
            ChecklistItem(newItemId(), text, checked = false, nested = nested)
        }
        list[index] = target.copy(value = inlineOnly(pieces.first()))
        list.addAll(at, created)
        return ChecklistEdit(normalize(list), created.last().id, cursor)
    }

    /**
     * Backspace al principio del ítem [id]. Vacío, se borra como con ✕ ([delete], H32, H35).
     * Con texto se une al final de la fila de arriba, que conserva su nivel, y el cursor queda
     * en la unión. En la sección, la fila de arriba se busca dentro de la sección,
     * salteando los fantasmas. `null` si no pasa nada: el primero del bloque o de la
     * sección con texto.
     */
    fun backspaceAtStart(items: List<ChecklistItem>, id: String): ChecklistEdit? {
        val list = canonical(items)
        val target = list.firstOrNull { it.id == id } ?: return null
        val above = above(list, id) ?: return null
        if (target.value.plain.isEmpty()) return delete(list, id)
        val upper = (above as? Above.Row)?.item ?: return null
        val merged = upper.value.concat(target.value)
        val out = list.mapNotNull {
            when (it.id) {
                upper.id -> it.copy(value = merged)
                id -> null
                else -> it
            }
        }
        return ChecklistEdit(normalize(out), upper.id, upper.value.plain.length)
    }

    /**
     * Borra solo el ítem [id] (✕, D13): sus hijos pasan al grupo de arriba. El foco va al
     * final de la fila de arriba; si era el primer ítem del bloque, sale del bloque; si
     * era el primero de la sección, va al final del último no marcado. Si [id] no está,
     * la lista no cambia y [ChecklistEdit.keepFocus] es `true`.
     */
    fun delete(items: List<ChecklistItem>, id: String): ChecklistEdit {
        val list = canonical(items)
        val above = above(list, id) ?: return keepingFocus(list)
        val out = normalize(list.filterNot { it.id == id })
        val focus = when (above) {
            is Above.Row -> above.item
            is Above.FirstOfSection -> above.lastUnchecked
            Above.FirstUnchecked -> null
        }
        return ChecklistEdit(out, focus?.id, focus?.value?.plain?.length ?: 0)
    }

    /** Un ítem vacío de primer nivel al final de los no marcados, con el foco. */
    fun addAtEnd(items: List<ChecklistItem>): ChecklistEdit {
        val list = canonical(items).toMutableList()
        val added = ChecklistItem(newItemId(), RichText.Empty)
        list.add(uncheckedEnd(list), added)
        return ChecklistEdit(normalize(list), added.id, 0)
    }

    /**
     * Anida o desanida el ítem [id] sin mover nada. `null` si no se puede: el
     * primer ítem, un ítem de la sección o si ya está en ese nivel.
     */
    fun setNested(items: List<ChecklistItem>, id: String, nested: Boolean, cursor: Int = 0): ChecklistEdit? {
        val list = canonical(items)
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0 || list[index].nested == nested) return null
        if (rows(list).unchecked.none { it.id == id }) return null
        val out = list.mapIndexed { i, item -> if (i == index) item.copy(nested = nested) else item }
        return ChecklistEdit(normalize(out), id, cursor)
    }

    /**
     * El lugar donde cae el ítem [id] arrastrado. [rawRow] es el lugar más cercano al
     * dedo entre las filas no marcadas sin el grupo arrastrado. Un ítem de primer nivel no cae
     * dentro de un grupo: va al final de ese grupo. Un subítem cae en [rawRow].
     */
    fun dropRow(items: List<ChecklistItem>, id: String, rawRow: Int): Int {
        val list = canonical(items)
        val others = rowsWithout(list, id)
        var row = rawRow.coerceIn(0, others.size)
        val dragged = list.firstOrNull { it.id == id } ?: return row
        if (!dragged.nested) {
            while (row < others.size && others[row].nested) row++
        }
        return row
    }

    /**
     * Mueve el ítem [id] al lugar [toRow] de las filas no marcadas, pasando primero por
     * [dropRow]. Uno de primer nivel lleva a todo su grupo, también los hijos marcados que
     * están en la sección; un subítem conserva el nivel, salvo en el lugar 0. Un ítem de
     * la sección no se mueve. Si [id] no está, la lista no cambia y [ChecklistEdit.keepFocus]
     * es `true`.
     */
    fun move(items: List<ChecklistItem>, id: String, toRow: Int, cursor: Int = 0): ChecklistEdit {
        val list = canonical(items)
        if (list.none { it.id == id }) return keepingFocus(list)
        if (rows(list).unchecked.none { it.id == id }) return ChecklistEdit(normalize(list), id, cursor)
        val others = rowsWithout(list, id)
        val row = dropRow(list, id, toRow)
        val target = list.first { it.id == id }
        val moving = if (target.nested) listOf(target) else groups(list).first { it.first() === target }
        val rest = list.filterNot { item -> moving.any { it === item } }.toMutableList()
        val at = if (row < others.size) rest.indexOfFirst { it.id == others[row].id } else uncheckedEnd(rest)
        val moved = if (target.nested && row == 0) listOf(target.copy(nested = false)) else moving
        rest.addAll(at, moved)
        return ChecklistEdit(normalize(rest), id, cursor)
    }

    /**
     * Sube o baja el ítem [id] un lugar (Ctrl+Shift+P / Ctrl+Shift+O, H22), con las reglas de
     * [move]: un ítem de primer nivel salta el grupo vecino entero; un subítem pasa la
     * fila vecina. En el borde no cambia nada. Si [id] no está, la lista no cambia y
     * [ChecklistEdit.keepFocus] es `true`.
     */
    fun moveByOne(items: List<ChecklistItem>, id: String, up: Boolean, cursor: Int = 0): ChecklistEdit {
        val list = canonical(items)
        if (list.none { it.id == id }) return keepingFocus(list)
        val unchanged = ChecklistEdit(normalize(list), id, cursor)
        val position = rows(list).unchecked.indexOfFirst { it.id == id }
        if (position < 0) return unchanged
        val nested = list.first { it.id == id }.nested
        val others = rowsWithout(list, id)
        val row = when {
            up && nested -> position - 1
            up -> (position - 1 downTo 0).firstOrNull { !others[it].nested } ?: -1
            position >= others.size -> -1
            nested -> position + 1
            else -> (position + 1..others.size).first { it == others.size || !others[it].nested }
        }
        return if (row < 0) unchanged else move(list, id, row, cursor)
    }

    // ---- piezas ----

    /** Qué hay arriba de un ítem en pantalla, para Backspace y ✕. */
    private sealed interface Above {
        data class Row(val item: ChecklistItem) : Above
        data object FirstUnchecked : Above
        data class FirstOfSection(val lastUnchecked: ChecklistItem?) : Above
    }

    /**
     * La fila de arriba de [id]: entre los no marcados, la de arriba; en la sección, la de
     * arriba dentro de la sección salteando los fantasmas. `null` si [id] no está.
     */
    private fun above(items: List<ChecklistItem>, id: String): Above? {
        val rows = rows(items)
        val uncheckedIndex = rows.unchecked.indexOfFirst { it.id == id }
        if (uncheckedIndex == 0) return Above.FirstUnchecked
        if (uncheckedIndex > 0) return Above.Row(rows.unchecked[uncheckedIndex - 1].item)
        val checkedIndex = rows.checked.indexOfFirst { it is ChecklistRow.Item && it.id == id }
        if (checkedIndex < 0) return null
        for (i in checkedIndex - 1 downTo 0) {
            (rows.checked[i] as? ChecklistRow.Item)?.let { return Above.Row(it.item) }
        }
        return Above.FirstOfSection(rows.unchecked.lastOrNull()?.item)
    }

    /** Los ítems de las filas no marcadas sin el grupo de [id] (solo él si es un subítem). */
    private fun rowsWithout(items: List<ChecklistItem>, id: String): List<ChecklistItem> {
        val unchecked = rows(items).unchecked.map { it.item }
        val index = unchecked.indexOfFirst { it.id == id }
        if (index < 0) return unchecked
        var end = index + 1
        if (!unchecked[index].nested) {
            while (end < unchecked.size && unchecked[end].nested) end++
        }
        return unchecked.subList(0, index) + unchecked.subList(end, unchecked.size)
    }

    /** La lista sin cambios cuando el id pedido no está: el foco queda donde estaba. */
    private fun keepingFocus(list: List<ChecklistItem>) =
        ChecklistEdit(normalize(list), focusId = null, cursor = 0, keepFocus = true)

    /** Dónde termina el tramo de los grupos sin marcar en una lista en orden [canonical]. */
    private fun uncheckedEnd(items: List<ChecklistItem>): Int =
        groups(items).takeWhile { !it.first().checked }.sumOf { it.size }

    /** El texto con solo sus marcas en línea: un ítem no tiene marcas de bloque. */
    private fun inlineOnly(
        value: RichText,
        selection: TextRange = TextRange(value.plain.length),
        pending: Map<InlineFormat, Boolean> = emptyMap(),
        pendingColor: PendingColor? = null,
    ): RichText {
        val text = SpannableString(value.plain)
        for (mark in value.text.getSpans(0, value.plain.length, InlineMark::class.java)) {
            text.setSpan(mark, value.text.getSpanStart(mark), value.text.getSpanEnd(mark), INLINE_MARK_FLAGS)
        }
        return RichText(text, selection, pending, pendingColor)
    }

    /** Parte la lista en grupos: cada uno arranca en un ítem de primer nivel (o en el primero). */
    private fun groups(items: List<ChecklistItem>): List<List<ChecklistItem>> {
        val groups = mutableListOf<MutableList<ChecklistItem>>()
        for (item in items) {
            if (groups.isEmpty() || !item.nested) groups += mutableListOf(item) else groups.last() += item
        }
        return groups
    }
}

/**
 * Un tramo de un texto partido por [splitChecklists]: renglones comunes ([Plain]) o renglones
 * de checklist ([Items]). [Items.starts] dice dónde empezaba cada ítem en el texto original,
 * para llevar el cursor.
 */
internal sealed interface TextPiece {
    data class Plain(val value: RichText) : TextPiece

    data class Items(val items: List<ChecklistItem>, val starts: List<Int>) : TextPiece
}

/**
 * Parte [value] en tramos de renglones comunes y tramos de renglones `ListMark(Check)` (C1,
 * D1). Un renglón de checklist que además es título se queda en el texto, para no perder el
 * título; `depth >= 1` es un subítem y el primer ítem de cada tramo nunca está anidado
 *. Cada renglón común conserva todas sus marcas; un ítem, solo las en línea.
 */
internal fun splitChecklists(value: RichText): List<TextPiece> {
    val text = value.text
    val plain = value.plain
    val pieces = mutableListOf<TextPiece>()
    var plainStart = -1
    var plainEnd = 0
    val items = mutableListOf<ChecklistItem>()
    val starts = mutableListOf<Int>()

    fun flushPlain() {
        if (plainStart >= 0) pieces += TextPiece.Plain(slice(value, plainStart, plainEnd))
        plainStart = -1
    }

    fun flushItems() {
        if (items.isNotEmpty()) pieces += TextPiece.Items(ChecklistOps.normalize(items.toList()), starts.toList())
        items.clear()
        starts.clear()
    }

    var start = 0
    while (start <= plain.length) {
        val end = plain.indexOf('\n', start).let { if (it < 0) plain.length else it }
        val marks = RichHtml.blockMarksAt(text, start)
        val check = marks.filterIsInstance<ListMark>().firstOrNull { it.kind == ListKind.Check }
        if (check != null && marks.none { it is HeadingMark }) {
            flushPlain()
            val line = slice(value, start, end, inlineOnly = true)
            items += ChecklistItem(ChecklistOps.newItemId(), line, check.checked, nested = check.depth >= 1)
            starts += start
        } else {
            flushItems()
            if (plainStart < 0) plainStart = start
            plainEnd = end
        }
        start = end + 1
    }
    flushPlain()
    flushItems()
    return pieces
}

/**
 * Si [value] tiene algún renglón que [splitChecklists] llevaría a un bloque: `ListMark(Check)`
 * sin título. Es lo que hay que mirar en cada cambio de un texto, sin partirlo.
 */
internal fun hasChecklistLines(value: RichText): Boolean {
    val text = value.text
    return text.getSpans(0, text.length, ListMark::class.java).any { mark ->
        mark.kind == ListKind.Check && RichHtml.blockMarksAt(text, text.getSpanStart(mark)).none { it is HeadingMark }
    }
}

/**
 * Los renglones de una checklist como texto con formato, para escribirlos con el códec de
 * siempre: un renglón por ítem con `ListMark(Check, depth = 0 o 1, checked)` y sus
 * marcas en línea.
 */
internal fun ChecklistOps.toRichText(items: List<ChecklistItem>): RichText {
    val text = SpannableString(items.joinToString("\n") { it.value.plain })
    var start = 0
    items.forEachIndexed { index, item ->
        val value = item.value.text
        for (mark in value.getSpans(0, value.length, InlineMark::class.java)) {
            text.setSpan(mark.copy(), value.getSpanStart(mark) + start, value.getSpanEnd(mark) + start, INLINE_MARK_FLAGS)
        }
        val end = start + item.value.plain.length
        // El renglón llega hasta después de su `\n`; el último, hasta el final.
        val lineEnd = if (index == items.lastIndex) end else end + 1
        text.setSpan(ListMark(ListKind.Check, if (item.nested) 1 else 0, item.checked), start, lineEnd, BLOCK_MARK_FLAGS)
        start = end + 1
    }
    return RichText(text)
}

/**
 * El tramo `[from, to)` de [value], que empieza al principio de un renglón y termina al final
 * de otro (sin su `\n`), con sus marcas en el mismo orden: las en línea recortadas al tramo y
 * las de bloque de sus renglones, también la de un renglón vacío del final. Con
 * [inlineOnly], sin las de bloque (un ítem).
 */
private fun slice(value: RichText, from: Int, to: Int, inlineOnly: Boolean = false): RichText {
    val source = value.text
    val text = SpannableString(value.plain.substring(from, to))
    for (mark in source.getSpans(0, source.length, RichMark::class.java)) {
        val markStart = source.getSpanStart(mark)
        val markEnd = source.getSpanEnd(mark)
        when (mark) {
            is InlineMark -> {
                val start = maxOf(markStart, from)
                val end = minOf(markEnd, to)
                if (end > start) text.setSpan(mark, start - from, end - from, INLINE_MARK_FLAGS)
            }
            is BlockMark -> if (!inlineOnly && markStart in from..to) {
                text.setSpan(mark, markStart - from, minOf(markEnd, to) - from, BLOCK_MARK_FLAGS)
            }
        }
    }
    return RichText(text)
}
