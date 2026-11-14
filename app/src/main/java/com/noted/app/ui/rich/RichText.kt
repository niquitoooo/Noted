package com.noted.app.ui.rich

import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import androidx.compose.ui.text.TextRange

/** Los formatos en línea que se prenden o apagan con un botón o un atajo. */
enum class InlineFormat { Bold, Italic, Strike, Code, Underline, Subscript, Superscript }

/**
 * El color de texto y el resaltado pedidos sin selección para lo próximo que se escriba
 *. [text] y [highlight] son el color que toma ([highlight] `"green"` es el verde,
 * `noted-highlight`); [clearText] y [clearHighlight], que lo escrito va sin ese color aunque lo
 * tenga lo de al lado ("Sin color" corta desde el cursor).
 */
data class PendingColor(val text: String?, val highlight: String?, val clearText: Boolean, val clearHighlight: Boolean)

/**
 * El valor de un campo con formato: el texto con sus marcas, la selección y [pending], el
 * formato prendido o apagado con un botón sin selección para lo próximo que se escriba en
 * [selection]. [pendingColor] es lo mismo para el color y el resaltado.
 *
 * [text] es una copia que guarda solo los [RichMark]: ni los [RenderSpan] ni los spans del
 * teclado (composición, sugerencias, ortografía) ni estilos ajenos.
 *
 * [autoFormatted] lo pone el campo en el valor que sale de un atajo de Markdown al escribir
 *: el editor lo anota como un paso aparte del deshacer, no como escribir, así Ctrl+Z
 * vuelve a los caracteres escritos. Lo pone también en lo pegado: pegar es un paso
 * propio, no se agrupa con lo escrito. Las copias ([withSelection] y demás) no lo llevan.
 */
class RichText(
    text: CharSequence,
    val selection: TextRange = TextRange(text.length),
    val pending: Map<InlineFormat, Boolean> = emptyMap(),
    val pendingColor: PendingColor? = null,
    val autoFormatted: Boolean = false,
) {
    val text: SpannedString = marksOnly(text)

    val plain: String = this.text.toString()

    companion object {
        val Empty = RichText("")

        fun fromHtml(fragment: String): RichText = RichText(RichHtml.fromHtml(fragment))
    }
}

fun RichText.withSelection(selection: TextRange): RichText = RichText(text, selection, pending, pendingColor)

fun RichText.toHtml(): String = RichHtml.toHtml(text)

/**
 * Parte el texto en `[0, start)` y `[end, largo)`; lo del medio se va. Lo de arriba
 * conserva sus marcas y queda con el cursor al final. Lo de abajo arranca con el cursor en
 * 0 y su primer renglón queda sin marca de bloque (al partir `- ab`, abajo queda `b` sin
 * viñeta, como hoy), salvo con [keepBlockBelow]: así lo usa soltar un bloque en el texto,
 * porque un título sigue siendo título.
 *
 * Una marca en línea que cruza el corte sigue de los dos lados.
 */
fun RichText.split(start: Int, end: Int = start, keepBlockBelow: Boolean = false): Pair<RichText, RichText> {
    val from = start.coerceIn(0, plain.length)
    val to = end.coerceIn(from, plain.length)

    val top = MarkedText(plain.substring(0, from))
    val bottom = MarkedText(plain.substring(to))
    // El renglón donde cae `end` es el primero de abajo; su marca de bloque empieza ahí.
    val firstLineBelow = lineStartOf(plain, to)
    forEachMark { mark, markStart, markEnd ->
        when (mark) {
            is InlineMark -> {
                if (markStart < from) top.add(mark, markStart, minOf(markEnd, from))
                if (markEnd > to) bottom.add(mark, maxOf(markStart, to) - to, markEnd - to)
            }
            is BlockMark -> {
                // Arriba quedan los renglones que empiezan antes del corte, y el renglón
                // vacío del final si el corte cae al final del texto.
                if (markStart < from || (markStart == from && markEnd == from)) {
                    top.add(mark, markStart, minOf(markEnd, from))
                }
                when {
                    markStart > to -> bottom.add(mark, markStart - to, markEnd - to)
                    markStart == firstLineBelow && keepBlockBelow -> bottom.add(mark, 0, markEnd - to)
                }
            }
        }
    }
    return top.build(TextRange(from)) to bottom.build(TextRange(0))
}

/**
 * Une dos textos, como hoy `mergeTextAround`: si los dos tienen algo van separados por un
 * `\n` y cada renglón conserva sus marcas; si uno está vacío del todo (sin texto ni marca de
 * bloque) queda el otro. Una viñeta vacía no está vacía: se conserva su renglón. El
 * cursor va al punto de unión.
 */
fun joinRich(top: RichText, bottom: RichText): RichText {
    if (RichHtml.isBlank(top.text)) return bottom.withSelection(TextRange(0))
    if (RichHtml.isBlank(bottom.text)) return top.withSelection(TextRange(top.plain.length))
    return joinLines(top, bottom)
}

/**
 * Une dos textos siempre con un `\n` en el medio, aunque uno esté vacío (un ítem vacío que
 * pasa a ser un renglón, D2): cada renglón conserva sus marcas y el cursor va al principio de
 * [bottom].
 */
fun joinLines(top: RichText, bottom: RichText): RichText {
    val length = top.plain.length
    val shift = length + 1
    val joined = MarkedText(top.plain + "\n" + bottom.plain)
    top.forEachMark { mark, markStart, markEnd ->
        // El último renglón de arriba ahora termina con el `\n` de la unión. Si arriba termina
        // en `\n`, ese renglón es el vacío del final: la marca del de antes no se estira.
        val lastLine = markStart == markEnd || !top.plain.endsWith('\n')
        val end = if (mark is BlockMark && markEnd == length && lastLine) markEnd + 1 else markEnd
        joined.add(mark, markStart, end)
    }
    bottom.forEachMark { mark, markStart, markEnd -> joined.add(mark, markStart + shift, markEnd + shift) }
    return joined.build(TextRange(shift))
}

/**
 * Une [other] al final de este texto en el mismo renglón, sin `\n` (unir dos ítems de una
 * checklist, H33). Las marcas en línea de [other] se corren `plain.length`; sus marcas de
 * bloque se descartan y la del último renglón de arriba se estira hasta el final. El cursor
 * queda en la unión.
 */
fun RichText.concat(other: RichText): RichText {
    val length = plain.length
    val joined = MarkedText(plain + other.plain)
    forEachMark { mark, markStart, markEnd ->
        val end = if (mark is BlockMark && markEnd == length) markEnd + other.plain.length else markEnd
        joined.add(mark, markStart, end)
    }
    other.forEachMark { mark, markStart, markEnd ->
        if (mark is InlineMark) joined.add(mark, markStart + length, markEnd + length)
    }
    return joined.build(TextRange(length))
}

/** Saca el `\n` del final, si hay, con el renglón vacío que abría. */
fun RichText.dropTrailingNewline(): RichText {
    if (!plain.endsWith('\n')) return this
    val length = plain.length - 1
    val result = MarkedText(plain.substring(0, length))
    forEachMark { mark, markStart, markEnd ->
        if (markStart <= length && !(mark is BlockMark && markStart == plain.length)) {
            result.add(mark, markStart, minOf(markEnd, length))
        }
    }
    return result.build(TextRange(selection.start.coerceAtMost(length), selection.end.coerceAtMost(length)))
}

/**
 * Deja un renglón vacío (un párrafo) al final, con el cursor ahí, como hoy `focusLastText`.
 * Un texto vacío o que ya termina en `\n` queda igual.
 */
fun RichText.withEmptyLineAtEnd(): RichText {
    if (plain.isEmpty() || plain.endsWith('\n')) return withSelection(TextRange(plain.length))
    val result = MarkedText(plain + "\n")
    forEachMark { mark, markStart, markEnd ->
        val end = if (mark is BlockMark && markEnd == plain.length) markEnd + 1 else markEnd
        result.add(mark, markStart, end)
    }
    return result.build(TextRange(plain.length + 1))
}

/** Principio del renglón donde está [pos] (con el cursor en 0, siempre 0). */
fun lineStartOf(text: CharSequence, pos: Int): Int =
    if (pos <= 0) 0 else text.lastIndexOf('\n', pos - 1) + 1

/** Fin del renglón donde está [pos], antes de su `\n`. */
fun lineEndOf(text: CharSequence, pos: Int): Int =
    text.indexOf('\n', pos).let { if (it < 0) text.length else it }

// ---- piezas ----

private inline fun RichText.forEachMark(action: (mark: RichMark, start: Int, end: Int) -> Unit) {
    for (mark in text.getSpans(0, text.length, RichMark::class.java)) {
        action(mark, text.getSpanStart(mark), text.getSpanEnd(mark))
    }
}

/**
 * Un texto que se arma con las marcas en sus lugares exactos: no se agrega texto después de
 * poner una marca, así una marca en línea no crece sobre un `\n` agregado al lado.
 */
private class MarkedText(private val plain: String) {
    private val spannable = SpannableString(plain)

    fun add(mark: RichMark, start: Int, end: Int) {
        if (end < start) return
        // Las de largo 0 solo valen para el renglón vacío del final.
        if (mark is InlineMark && end == start) return
        if (mark is BlockMark && end == start && start != plain.length) return
        // Un mismo objeto no puede estar dos veces en un texto: si ya está, va una copia.
        val span = if (spannable.getSpanStart(mark) >= 0) mark.copy() else mark
        spannable.setSpan(span, start, end, flagsOf(mark))
    }

    fun build(selection: TextRange) = RichText(spannable, selection)
}

private fun flagsOf(mark: RichMark) = when (mark) {
    is InlineMark -> INLINE_MARK_FLAGS
    is BlockMark -> BLOCK_MARK_FLAGS
}

/** La copia de un texto con solo sus [RichMark], en el mismo orden (que da el anidado). */
private fun marksOnly(text: CharSequence): SpannedString {
    val copy = SpannableString(text.toString())
    if (text is Spanned) {
        for (mark in text.getSpans(0, text.length, RichMark::class.java)) {
            copy.setSpan(mark, text.getSpanStart(mark), text.getSpanEnd(mark), flagsOf(mark))
        }
    }
    return SpannedString(copy)
}

/** Una marca nueva con los mismos campos. */
internal fun RichMark.copy(): RichMark = when (this) {
    is BoldMark -> BoldMark()
    is ItalicMark -> ItalicMark()
    is UnderlineMark -> UnderlineMark()
    is StrikeMark -> StrikeMark(tag)
    is SubscriptMark -> SubscriptMark()
    is SuperscriptMark -> SuperscriptMark()
    is CodeMark -> CodeMark()
    is LinkMark -> LinkMark(href, attrs)
    is TextColorMark -> TextColorMark(color)
    is HighlightMark -> HighlightMark(color)
    is HeadingMark -> HeadingMark(level)
    is ListMark -> ListMark(kind, depth, checked, start)
    is QuoteMark -> QuoteMark(group)
    is CodeBlockMark -> CodeBlockMark(group, language)
    is DividerMark -> DividerMark()
}
