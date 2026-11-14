package com.noted.app.ui.rich

import android.text.Editable
import android.text.NoCopySpan
import android.text.Spannable
import android.text.Spanned

/**
 * Los atajos de Markdown al escribir: siempre prendidos y nunca dentro de
 * código, ni en línea ni en bloque. Los llama [RichEditRules.after] después de sus reglas, con el
 * texto ya en orden; cada función convierte en el lugar y devuelve el cursor nuevo, o `null` (o
 * `false`) si no había atajo. El campo emite antes lo escrito tal cual.
 */
object MarkdownShortcuts {

    /**
     * Al principio del renglón: si el texto de [lineStart] a [cursor] es
     * exactamente uno de los patrones, se saca y el renglón toma el formato. Todos terminan en
     * espacio salvo ```` ``` ````, que convierte al escribir el tercer acento.
     */
    fun lineStart(text: Editable, lineStart: Int, cursor: Int): Int? {
        if (cursor <= lineStart || cursor > text.length) return null
        val prefix = text.substring(lineStart, cursor)
        val marks = RichHtml.blockMarksAt(text, lineStart)
        if (marks.any { it is CodeBlockMark || it is DividerMark }) return null
        val list = marks.firstOrNull { it is ListMark } as ListMark?
        val heading = marks.firstOrNull { it is HeadingMark }
        val depth = list?.depth ?: 0
        var quoteGroup: Long? = null
        val new: BlockMark = if (prefix == CODE_FENCE) {
            CodeBlockMark(newBlockGroup(), null)
        } else {
            if (!prefix.endsWith(' ')) return null
            val pattern = prefix.dropLast(1)
            when {
                // No en una lista.
                HEADING.matches(pattern) -> if (list != null) return null else HeadingMark(pattern.length)
                pattern in BULLETS -> ListMark(ListKind.Bullet, depth)
                ORDERED.matches(pattern) -> {
                    // `start` solo en el primer ítem, si no es 1.
                    val number = pattern.dropLast(1).toIntOrNull() ?: return null
                    val continues = (aboveMarks(text, lineStart).firstOrNull { it is ListMark } as ListMark?)
                        ?.let { it.kind == ListKind.Ordered && it.depth == depth } == true
                    ListMark(ListKind.Ordered, depth, start = number.takeIf { it != 1 && !continues })
                }
                // No en una lista ni en un título. `[ ] ` no.
                pattern == "[]" || pattern == "[x]" || pattern == "[X]" ->
                    if (list != null || heading != null) return null
                    else ListMark(ListKind.Check, 0, checked = pattern != "[]")
                pattern == ">" -> QuoteMark(quoteGroupFor(text, lineStart).also { quoteGroup = it })
                else -> return null
            }
        }

        text.delete(lineStart, cursor)
        dropEmptyInline(text, lineStart)
        if (new is CodeBlockMark) {
            // El `<pre>` se guarda en texto plano.
            RichEditRules.clearInline(text, lineStart, lineEndOf(text, lineStart))
        }
        // Como los botones (toggleLine, setHeading): el título, la lista y la cita se reemplazan.
        val kept = if (new is CodeBlockMark) emptyList()
        else marks.filter { it !is HeadingMark && it !is ListMark && it !is QuoteMark }
        RichEditRules.setLineMarks(text, lineStart, marks, kept + new)
        quoteGroup?.let { joinQuoteBelow(text, lineStart, it) }
        return lineStart
    }

    /**
     * Enter al final de un renglón de [lineStart] a [lineEnd] que es solo `---`, `***`, `___`,
     * `—-` o `———`: pasa a ser un separador y el renglón nuevo queda abajo. No en una
     * lista, ni si el renglón nuevo tiene texto.
     */
    fun onEnter(text: Editable, lineStart: Int, lineEnd: Int): Boolean {
        if (lineEnd >= text.length || text[lineEnd] != '\n') return false
        if (text.substring(lineStart, lineEnd) !in DIVIDERS) return false
        val marks = RichHtml.blockMarksAt(text, lineStart)
        if (marks.any { it is ListMark || it is CodeBlockMark || it is DividerMark }) return false
        if (lineEndOf(text, lineEnd + 1) != lineEnd + 1) return false
        text.replace(lineStart, lineEnd, DIVIDER_CHAR.toString())
        RichEditRules.clearInline(text, lineStart, lineStart + 1)
        RichEditRules.setLineMarks(text, lineStart, marks, listOf(DividerMark()))
        // El separador corta la cita: lo de abajo es otra.
        (marks.firstOrNull { it is QuoteMark } as QuoteMark?)?.let { RichEditRules.splitQuoteFrom(text, lineStart + 2, it.group) }
        return true
    }

    /**
     * Marcas en la línea: al escribir el cierre (`*`, `_`, `~`, `` ` ``, `)`) o un
     * espacio después del cierre, `**x**` → negrita, `*x*` y `_x_` → cursiva, `~~x~~` → `<s>`,
     * `` `x` `` → código, `==x==` → resaltado verde y `[texto](url)` → enlace. Los símbolos se
     * van y queda un espacio después. Si se escribió el cierre, el espacio se agrega, salvo que
     * ya siga un espacio o un signo; el agregado se traga el espacio que se escriba ahí mismo
     * ([swallowSpace], R18). [typed] es el carácter recién escrito, en `cursor - 1`.
     */
    fun inline(text: Editable, cursor: Int, typed: Char): Int? {
        val lineStart = lineStartOf(text, cursor)
        val closer = typed != ' '
        val end = if (closer) cursor else cursor - 1
        if (end <= lineStart) return null
        val found = findInline(text.substring(lineStart, end), if (closer) typed else null) ?: return null
        val open = lineStart + found.open
        val contentStart = lineStart + found.contentStart
        val contentEnd = lineStart + found.contentEnd
        val code = text.getSpans(open, end, CodeMark::class.java)
            .any { text.getSpanEnd(it) > open && text.getSpanStart(it) < end }
        if (code) return null
        val next = text.getOrNull(end)
        val addSpace = closer && (next == null || next == '\n' || !(next.isWhitespace() || next in PUNCTUATION))
        text.delete(contentEnd, end)
        text.delete(open, contentStart)
        val markEnd = open + (contentEnd - contentStart)
        if (addSpace) {
            text.insert(markEnd, " ")
            text.setSpan(AddedSpace(), markEnd, markEnd + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.setSpan(found.mark, open, markEnd, INLINE_MARK_FLAGS)
        return if (closer && !addSpace) markEnd else markEnd + 1
    }

    /**
     * Si lo recién escrito es un espacio en [start], justo después del que agregó un cierre
     * ([inline]), se saca y devuelve `true`. Lo hace antes de las reglas, con el texto como lo
     * dejó el `EditText`. En cualquier otro cambio, el espacio agregado ya no traga nada.
     */
    fun swallowSpace(text: Editable, start: Int, removed: Int, added: Int): Boolean {
        val marks = text.getSpans(0, text.length, AddedSpace::class.java)
        if (marks.isEmpty()) return false
        val swallow = removed == 0 && added == 1 && text.getOrNull(start) == ' ' &&
            marks.any { text.getSpanEnd(it) == start }
        for (mark in marks) text.removeSpan(mark)
        if (swallow) text.delete(start, start + 1)
        return swallow
    }

    /** Mover el cursor fuera del lugar del espacio agregado lo olvida. */
    fun forgetAddedSpace(text: Spannable, cursor: Int?) {
        for (mark in text.getSpans(0, text.length, AddedSpace::class.java)) {
            if (cursor == null || text.getSpanEnd(mark) != cursor) text.removeSpan(mark)
        }
    }

    /** El espacio que agregó un cierre ([inline]); no es formato, el códec no lo ve. */
    private class AddedSpace : NoCopySpan

    /**
     * Tipografía (castellano: “ ” y ‘ ’): `-- ` → `— `, `-> ` → `→ `, `<- ` → `← `,
     * `<-> ` → `↔ `, `|^ ` → `↑ `, `|v ` → `↓ `, `...` → `…` al escribir el tercer punto, y las
     * comillas rectas pasan a abrir o cerrar según lo que tengan antes.
     */
    fun typography(text: Editable, cursor: Int, typed: Char): Int? {
        val lineStart = lineStartOf(text, cursor)
        val before = text.substring(lineStart, cursor - 1)
        when (typed) {
            ' ' -> {
                val (from, to) = ARROWS.firstOrNull { (from, _) -> before.endsWith(from) } ?: return null
                text.replace(cursor - 1 - from.length, cursor - 1, to)
                return cursor - from.length + to.length
            }
            '.' -> {
                if (!before.endsWith("..")) return null
                text.replace(cursor - 3, cursor, "…")
                return cursor - 2
            }
            '"', '\'' -> {
                // Abre al principio, después de un espacio o de algo que abre; si no, cierra.
                val opens = before.isEmpty() || before.last().isWhitespace() || before.last() in OPENERS
                val quote = when (typed) {
                    '"' -> if (opens) "“" else "”"
                    else -> if (opens) "‘" else "’"
                }
                text.replace(cursor - 1, cursor, quote)
                return cursor
            }
            else -> return null
        }
    }

    /**
     * Enlace automático: la palabra antes del espacio o el Enter de `cursor - 1`, si es
     * una URL con protocolo, un correo (`mailto:`) o un dominio (`https://`), pasa a ser un enlace
     * `<a href title spellcheck="false">`.
     */
    fun autoLink(text: Editable, cursor: Int): Int? {
        val wordEnd = cursor - 1
        var wordStart = wordEnd
        while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) wordStart--
        // La puntuación del final no es parte del enlace ("https://example.com, que").
        var linkEnd = wordEnd
        while (linkEnd > wordStart && text[linkEnd - 1] in PUNCTUATION) linkEnd--
        if (wordStart == linkEnd) return null
        val word = text.substring(wordStart, linkEnd)
        val href = when {
            URL.matches(word) -> word
            EMAIL.matches(word) -> "mailto:$word"
            DOMAIN.matches(word) -> "https://$word"
            else -> return null
        }
        if (RichHtml.blockMarksAt(text, lineStartOf(text, wordStart)).any { it is CodeBlockMark }) return null
        val touching = text.getSpans(wordStart, linkEnd, InlineMark::class.java)
            .any { (it is LinkMark || it is CodeMark) && text.getSpanEnd(it) > wordStart && text.getSpanStart(it) < linkEnd }
        if (touching) return null
        text.setSpan(LinkMark(href, linkedMapOf("title" to href, "spellcheck" to "false")), wordStart, linkEnd, INLINE_MARK_FLAGS)
        return cursor
    }

    /** Una marca en línea encontrada en el renglón, con posiciones dentro de él. */
    private class Found(val open: Int, val contentStart: Int, val contentEnd: Int, val mark: InlineMark)

    /**
     * La marca en línea que termina al final de [line]. [closer] es el carácter de cierre recién
     * escrito, o `null` si se escribió un espacio después.
     */
    private fun findInline(line: String, closer: Char?): Found? {
        if (closer == null || closer == ')') {
            LINK.find(line)?.let { match ->
                val label = match.groups[1]!!
                return Found(match.range.first, label.range.first, label.range.last + 1, LinkMark(match.groupValues[2], emptyMap()))
            }
        }
        for ((delimiter, make) in DELIMITERS) {
            if (closer != null && delimiter.last() != closer) continue
            if (!line.endsWith(delimiter)) continue
            val closeStart = line.length - delimiter.length
            val char = delimiter[0]
            val single = delimiter.length == 1
            if (single && closeStart > 0 && line[closeStart - 1] == char) continue
            val open = line.lastIndexOf(delimiter, closeStart - delimiter.length - 1)
            if (open < 0) continue
            val content = line.substring(open + delimiter.length, closeStart)
            if (content.isEmpty() || content.first().isWhitespace() || content.last().isWhitespace()) continue
            if (if (single) char in content else delimiter in content) continue
            if (single && open > 0 && line[open - 1] == char) continue
            // Un `_` dentro de una palabra no abre (como en Markdown).
            if (char == '_' && open > 0 && line[open - 1].isLetterOrDigit()) continue
            return Found(open, open + delimiter.length, closeStart, make())
        }
        return null
    }

    // ---- piezas ----

    private val DELIMITERS: List<Pair<String, () -> InlineMark>> = listOf(
        "**" to { BoldMark() },
        "~~" to { StrikeMark("s") }, // El Markdown da `<s>`
        "==" to { HighlightMark(null) }, // el verde, `noted-highlight`
        "*" to { ItalicMark() },
        "_" to { ItalicMark() },
        "`" to { CodeMark() },
    )
    private val LINK = Regex("""\[([^\[\]]+)]\(([^\s()]+)\)$""")
    private val ARROWS = listOf("<->" to "↔", "--" to "—", "->" to "→", "<-" to "←", "|^" to "↑", "|v" to "↓")
    // Después de una comilla que abre, la siguiente cierra: "" da “”.
    private const val OPENERS = "([{<"
    /** Los signos que pueden seguir a un cierre o terminar una URL sin ser parte de ella. */
    private const val PUNCTUATION = ".,;:!?)]}»…”’\"'"
    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")
    private val EMAIL = Regex("""[^\s@/]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val DOMAIN = Regex("""([A-Za-z0-9-]+\.)+[A-Za-z]{2,}(/\S*)?""")

    private const val CODE_FENCE = "```"
    private val HEADING = Regex("#{1,6}")
    private val BULLETS = setOf("-", "*", "+")
    private val ORDERED = Regex("[1-9][0-9]*[.,*]")
    private val DIVIDERS = setOf("---", "***", "___", "—-", "———")

    private fun aboveMarks(text: Spanned, lineStart: Int): List<BlockMark> =
        if (lineStart == 0) emptyList() else RichHtml.blockMarksAt(text, lineStartOf(text, lineStart - 1))

    /** Las marcas del renglón de abajo, o vacío si no hay. */
    private fun belowMarks(text: Spanned, lineStart: Int): List<BlockMark> {
        val lineEnd = lineEndOf(text, lineStart)
        return if (lineEnd >= text.length) emptyList() else RichHtml.blockMarksAt(text, lineEnd + 1)
    }

    /** La cita nueva se une a la de arriba o a la de abajo, si la hay. */
    private fun quoteGroupFor(text: Spanned, lineStart: Int): Long =
        (aboveMarks(text, lineStart).firstOrNull { it is QuoteMark } as QuoteMark?)?.group
            ?: (belowMarks(text, lineStart).firstOrNull { it is QuoteMark } as QuoteMark?)?.group
            ?: newBlockGroup()

    /** Si abajo sigue otra cita, pasa a ser la misma que la del renglón. */
    private fun joinQuoteBelow(text: Editable, lineStart: Int, group: Long) {
        var line = RichEditRules.blockEnd(text, lineStart)
        if (line == lineStart || lineEndOf(text, lineStart) >= text.length) return
        val below = (RichHtml.blockMarksAt(text, line).firstOrNull { it is QuoteMark } as QuoteMark?)?.group ?: return
        if (below == group) return
        while (line <= text.length) {
            val marks = RichHtml.blockMarksAt(text, line)
            val quote = marks.firstOrNull { it is QuoteMark && it.group == below } ?: return
            RichEditRules.setLineMarks(text, line, marks, marks.map { if (it === quote) QuoteMark(group) else it })
            val end = RichEditRules.blockEnd(text, line)
            if (end == line) return
            line = end
        }
    }

    /** Las marcas en línea que quedaron vacías en [at] al sacar el patrón. */
    private fun dropEmptyInline(text: Editable, at: Int) {
        for (mark in text.getSpans(at, at, InlineMark::class.java)) {
            if (text.getSpanStart(mark) == at && text.getSpanEnd(mark) == at) text.removeSpan(mark)
        }
    }
}
