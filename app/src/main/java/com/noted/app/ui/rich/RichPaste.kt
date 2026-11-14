package com.noted.app.ui.rich

import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.compose.ui.text.TextRange
import com.noted.app.data.html.HtmlSanitizer

/**
 * Pegar con formato: el HTML del portapapeles pasa por
 * [HtmlSanitizer.cleanPasted] y [RichHtml.fromHtml] y sus marcas se insertan en la selección.
 * En código, y en un ítem de checklist, se pega texto plano ([plainOnly], lo mira el campo). Una URL sola sobre
 * texto seleccionado lo hace enlace; sin selección se inserta como enlace. Nunca se crea una
 * tarjeta.
 *
 * Las dos funciones cambian [Editable] en el lugar y devuelven dónde va el cursor.
 */
object RichPaste {

    /** Una URL entera, como se reconoce al pegar o al recibirla del teclado. */
    private val URL = Regex("^[:a-zA-Z0-9_-]{1,20}://\\S+$")

    /** La clase de una URL suelta pegada o escrita: se corta en cualquier letra. */
    const val BREAK_ALL = "noted-break-all"

    /** Los esquemas que nunca son enlace, como en el saneado. */
    private val DANGEROUS_SCHEME = Regex("^(javascript|vbscript|data):", RegexOption.IGNORE_CASE)

    /** Si [value] es una URL entera, tal cual (sin blancos alrededor), y no de un esquema peligroso. */
    fun isUrl(value: String): Boolean = URL.matches(value) && !DANGEROUS_SCHEME.containsMatchIn(value)

    /** La URL de un texto pegado que es solo una URL (con blancos alrededor), o `null`. */
    fun urlOf(plain: String): String? = plain.trim().takeIf { isUrl(it) }

    /** `\r\n` y `\r` sueltos pasan a `\n`: el texto del campo solo corta renglones con `\n`. */
    fun normalizeNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * Si en [selection] se pega texto plano: en un ítem de checklist (su regla de varios
     * renglones es la de una checklist) o dentro de código, en bloque o en línea. Lo decide quien
     * llama antes de [insertHtml].
     */
    fun plainOnly(text: Spanned, selection: TextRange, inItem: Boolean): Boolean =
        inItem || inCode(text, selection.min, selection.max)

    /** Inserta el HTML con sus marcas; en código o en un ítem ([plainOnly]) no se llama. */
    fun insertHtml(text: Editable, selection: TextRange, html: String): Int {
        val fragment = RichHtml.fromHtml(HtmlSanitizer.cleanPasted(normalizeNewlines(html)))
        // Solo había lo que se descarta (una imagen, D4): no se pega nada.
        if (RichHtml.isBlank(fragment)) return selection.max
        // Varios renglones son bloques: van en sus renglones.
        val blocks = '\n' in fragment
        return splice(text, selection.min, selection.max, fragment, blocks)
    }

    /**
     * Si el texto plano [plain] de varios renglones se pega en un título (sin lista): los
     * renglones siguen siendo título. Las reglas de lo escrito los harían párrafos.
     */
    fun keepsHeading(text: Spanned, selection: TextRange, plain: String): Boolean {
        if ('\n' !in normalizeNewlines(plain) || urlOf(plain) != null) return false
        val marks = RichHtml.blockMarksAt(text, lineStartOf(text, selection.min))
        // En un código en línea van las reglas de código, como siempre.
        return marks.any { it is HeadingMark } && marks.none { it is ListMark || it is QuoteMark || it is CodeBlockMark } &&
            !inCode(text, selection.min, selection.max)
    }

    fun insertPlain(text: Editable, selection: TextRange, pasted: String): Int {
        val plain = normalizeNewlines(pasted)
        val start = selection.min
        val end = selection.max
        val url = urlOf(plain)
        if (url != null && !inCode(text, start, end)) {
            if (start < end) {
                linkOver(text, start, end, url)
                return end
            }
            val link = SpannableStringBuilder(url)
            link.setSpan(LinkMark(url, mapOf("class" to BREAK_ALL, "spellcheck" to "false")), 0, url.length, INLINE_MARK_FLAGS)
            return splice(text, start, start, link, blocks = false)
        }
        if (keepsHeading(text, selection, plain)) {
            // Cada renglón con el título del renglón donde se pega; el primero sigue ese
            // renglón y el último se une con el resto.
            val heading = RichHtml.blockMarksAt(text, lineStartOf(text, start)).first { it is HeadingMark } as HeadingMark
            val lines = SpannableStringBuilder(plain)
            val starts = lineStarts(plain)
            starts.forEachIndexed { index, from ->
                val to = starts.getOrNull(index + 1) ?: plain.length
                lines.setSpan(HeadingMark(heading.level), from, to, BLOCK_MARK_FLAGS)
            }
            return splice(text, start, end, lines, blocks = false)
        }
        // Como lo escrito, con las reglas de siempre (código, renglones) y sin atajos.
        val change = RichEditRules.before(text, start, end - start, plain.length)
        text.replace(start, end, plain)
        val result = RichEditRules.after(text, change, emptyMap(), shortcuts = false)
        return result.cursor ?: (start + plain.length)
    }

    // ---- piezas ----

    /** Si `[start, end)` toca un renglón de código o cae dentro de un código en línea. */
    private fun inCode(text: Spanned, start: Int, end: Int): Boolean {
        var line = lineStartOf(text, start)
        while (true) {
            if (RichHtml.blockMarksAt(text, line).any { it is CodeBlockMark }) return true
            val next = lineEndOf(text, line) + 1
            if (next > end || next > text.length) break
            line = next
        }
        return text.getSpans(start, end, CodeMark::class.java).any { mark ->
            val markStart = text.getSpanStart(mark)
            val markEnd = text.getSpanEnd(mark)
            if (start == end) markStart < start && start < markEnd else markStart < end && markEnd > start
        }
    }

    /** La selección pasa a ser un enlace a [url]; si ya lo era, cambia el `href`. */
    private fun linkOver(text: Editable, start: Int, end: Int, url: String) {
        for (link in text.getSpans(start, end, LinkMark::class.java)) {
            val linkStart = text.getSpanStart(link)
            val linkEnd = text.getSpanEnd(link)
            if (linkEnd <= start || linkStart >= end) continue
            text.removeSpan(link)
            if (linkStart < start) text.setSpan(LinkMark(link.href, link.attrs), linkStart, start, INLINE_MARK_FLAGS)
            if (linkEnd > end) text.setSpan(LinkMark(link.href, link.attrs), end, linkEnd, INLINE_MARK_FLAGS)
        }
        text.setSpan(LinkMark(url, mapOf("title" to url)), start, end, INLINE_MARK_FLAGS)
    }

    /**
     * Pone [fragment] en lugar de `[start, end)`. Las marcas en línea del texto no se estiran
     * sobre lo pegado.
     *
     * Con [blocks] (varios renglones de HTML), cada renglón pegado va en su propio renglón: lo
     * de antes y lo de después del cursor quedan en renglones aparte con su formato, y no queda
     * ninguno vacío. Los renglones pegados se adaptan al lugar ([adapt]): en un
     * título pierden sus títulos, en una lista son ítems de esa lista y en una cita
     * son renglones de esa cita.
     *
     * Sin [blocks], el primer renglón sigue el renglón donde se pega y el último se une con lo
     * que había después; los del medio son los suyos (texto plano en un título). El
     * primer renglón toma la marca del fragmento solo si el renglón donde se pega estaba vacío.
     * Un separador va siempre en su propio renglón.
     */
    private fun splice(text: Editable, start: Int, end: Int, source: Spanned, blocks: Boolean): Int {
        val lineStart = lineStartOf(text, start)
        val lineEnd = lineEndOf(text, end)
        val prefixEmpty = start == lineStart
        val suffixEmpty = end == lineEnd
        val startMarks = RichHtml.blockMarksAt(text, lineStart)
        val fragment = if (blocks) adapt(source, startMarks, atLineStart = prefixEmpty) else source
        val fragmentLines = lineStarts(fragment)
        val fragmentMarks = fragmentLines.map { RichHtml.blockMarksAt(fragment, it) }
        val single = fragmentLines.size == 1
        val before = if (!prefixEmpty && (blocks || fragmentMarks.first().any { it is DividerMark })) "\n" else ""
        val after = if (!suffixEmpty && (blocks || fragmentMarks.last().any { it is DividerMark })) "\n" else ""
        val fragmentStart = start + before.length
        val inserted = before.length + fragment.length + after.length
        val delta = inserted - (end - start)

        val out = SpannableStringBuilder()
        out.append(text, 0, start).append(before).append(fragment.toString()).append(after).append(text, end, text.length)
        // `append` copia los spans: se ponen todos de nuevo, en sus lugares exactos.
        out.getSpans(0, out.length, Any::class.java).forEach { out.removeSpan(it) }

        // Marcas en línea: las del texto, cortadas alrededor de lo pegado, y las del fragmento.
        for (mark in text.getSpans(0, text.length, InlineMark::class.java)) {
            val markStart = text.getSpanStart(mark)
            val markEnd = text.getSpanEnd(mark)
            when {
                markEnd <= start -> setInline(out, mark, markStart, markEnd)
                markStart >= end -> setInline(out, mark, markStart + delta, markEnd + delta)
                else -> {
                    if (markStart < start) setInline(out, mark, markStart, start)
                    if (markEnd > end) setInline(out, mark.copy() as InlineMark, end + delta, markEnd + delta)
                }
            }
        }
        for (mark in fragment.getSpans(0, fragment.length, InlineMark::class.java)) {
            setInline(out, mark.copy() as InlineMark, fragment.getSpanStart(mark) + fragmentStart, fragment.getSpanEnd(mark) + fragmentStart)
        }

        // Marcas de bloque, renglón por renglón.
        val lines = ArrayList<Pair<Int, List<BlockMark>>>()
        var line = 0
        while (line < lineStart) {
            lines += line to RichHtml.blockMarksAt(text, line)
            line = lineEndOf(text, line) + 1
        }
        val firstMarks = fragmentMarks.first().map { it.copy() as BlockMark }
        when {
            before.isNotEmpty() -> {
                lines += lineStart to startMarks
                lines += fragmentStart to firstMarks
            }
            blocks || (prefixEmpty && (!single || suffixEmpty) && firstMarks.isNotEmpty()) -> lines += lineStart to firstMarks
            else -> lines += lineStart to startMarks
        }
        for (index in 1 until fragmentLines.size) {
            lines += (fragmentStart + fragmentLines[index]) to fragmentMarks[index].map { it.copy() as BlockMark }
        }
        if (after.isNotEmpty()) {
            // Lo que sigue al cursor conserva el formato de su renglón.
            val endMarks = RichHtml.blockMarksAt(text, lineStartOf(text, end)).map { continuedMark(it) }
            lines += (fragmentStart + fragment.length + 1) to endMarks
        }
        line = lineEnd + 1
        while (line <= text.length) {
            lines += (line + delta) to RichHtml.blockMarksAt(text, line)
            line = lineEndOf(text, line) + 1
        }
        lines.forEachIndexed { index, (from, marks) ->
            val to = lines.getOrNull(index + 1)?.first ?: out.length
            for (mark in marks) out.setSpan(mark, from, to, BLOCK_MARK_FLAGS)
        }

        // Las marcas viejas se sacan antes: al reemplazar todo, quedarían apretadas en un borde.
        text.getSpans(0, text.length, RichMark::class.java).forEach { text.removeSpan(it) }
        text.replace(0, text.length, out)
        return fragmentStart + fragment.length
    }

    /**
     * Los renglones de [fragment] adaptados al renglón donde se pegan, que tiene [context]:
     * - en una cita, cada renglón es de esa misma cita (en el editor de antes un renglón de cita
     *   no lleva otra marca, así que una viñeta pegada queda como renglón de la cita);
     * - en un título, solo los títulos de lo pegado se desarman: una cita o una lista
     *   pegadas siguen siéndolo, con su `start`;
     * - en un ítem, cada renglón es un ítem de esa lista; un ítem anidado de lo pegado queda
     *   anidado igual desde el nivel del ítem. Una checklist pegada en una checklist
     *   conserva sus tildes, y pegado al principio del primer ítem ([atLineStart]) se conserva el
     *   `start` de la lista.
     * Un separador o un bloque de código siguen siendo lo que son.
     */
    private fun adapt(fragment: Spanned, context: List<BlockMark>, atLineStart: Boolean): Spanned {
        val quote = context.firstOrNull { it is QuoteMark } as QuoteMark?
        val item = context.firstOrNull { it is ListMark } as ListMark?
        val heading = context.any { it is HeadingMark }
        if (quote == null && item == null && !heading) return fragment
        val out = SpannableStringBuilder(fragment)
        val starts = lineStarts(fragment)
        starts.forEachIndexed { index, from ->
            val to = starts.getOrNull(index + 1) ?: fragment.length
            val marks = RichHtml.blockMarksAt(out, from)
            if (marks.any { it is DividerMark || it is CodeBlockMark }) return@forEachIndexed
            val own = marks.firstOrNull { it is ListMark } as ListMark?
            val adapted = when {
                quote != null -> listOf(QuoteMark(quote.group))
                item != null -> listOfNotNull(
                    ListMark(
                        item.kind,
                        item.depth + (own?.depth ?: 0),
                        // Una checklist pegada en una checklist conserva sus tildes.
                        checked = item.kind == ListKind.Check && own?.kind == ListKind.Check && own.checked,
                        // Pegado al principio del primer ítem, la lista sigue empezando donde empezaba.
                        start = item.start.takeIf { index == 0 && atLineStart },
                    ),
                    marks.firstOrNull { it is HeadingMark && !heading }?.copy() as BlockMark?,
                )
                // En un título solo se desarman los títulos: lo demás queda, con su `start`.
                else -> marks.filter { it !is HeadingMark }.map { it.copy() as BlockMark }
            }
            marks.forEach { out.removeSpan(it) }
            for (mark in adapted) out.setSpan(mark, from, to, BLOCK_MARK_FLAGS)
        }
        return out
    }

    /** Una copia de la marca para un renglón que sigue: sin el `start` del primer ítem. */
    private fun continuedMark(mark: BlockMark): BlockMark = when (mark) {
        is ListMark -> ListMark(mark.kind, mark.depth, mark.checked)
        else -> mark.copy() as BlockMark
    }

    private fun setInline(out: SpannableStringBuilder, mark: InlineMark, from: Int, to: Int) {
        if (to > from) out.setSpan(mark, from, to, INLINE_MARK_FLAGS)
    }

    /** El principio de cada renglón de [text]. */
    private fun lineStarts(text: CharSequence): List<Int> {
        val starts = arrayListOf(0)
        var at = text.indexOf('\n')
        while (at >= 0) {
            starts += at + 1
            at = text.indexOf('\n', at + 1)
        }
        return starts
    }
}
