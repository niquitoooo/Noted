package com.jot.app.data.html

import com.jot.app.data.Attachment
import com.jot.app.data.Bookmark
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Conversor ida y vuelta entre el Markdown de la app (el que escribía y dibujaba el
 * editor Markdown de antes) y el HTML que se guarda.
 *
 * [toHtml] lee cada renglón con las mismas reglas que tenía `MarkdownVisualTransformation`
 * en ese editor, así el HTML dice lo que se veía. [fromHtml] escribe la forma canónica de la app:
 * `fromHtml(toHtml(md)) == md` para todo `md` canónico y
 * `toHtml(fromHtml(toHtml(md))) == toHtml(md)` para todo `md`.
 */
object NoteMarkdown {

    // Renglones que el editor lee como bloque propio: adjunto, tarjeta, nota y desplegable.
    internal val ATTACHMENT_LINE = Regex("^\\[[^\\]]*]\\(jot://att/([^)]+)\\)$")
    internal val BOOKMARK_MARKER = Regex("^<!--\\s*jot:bookmark\\s+(\\S+)\\s*-->$")
    internal val NOTE_LINE = Regex("^\\[[^\\]]*]\\(jot://note/([^)]+)\\)$")
    internal val DETAILS_OPEN = Regex("^<details(\\s+open)?>$", RegexOption.IGNORE_CASE)
    internal val SUMMARY = Regex("^<summary>(.*)</summary>$", RegexOption.IGNORE_CASE)
    internal val DETAILS_CLOSE = Regex("^</details>$", RegexOption.IGNORE_CASE)

    internal val STRUCTURE_LINES = listOf(
        ATTACHMENT_LINE, BOOKMARK_MARKER, NOTE_LINE, DETAILS_OPEN, SUMMARY, DETAILS_CLOSE
    )

    /**
     * Un renglón de texto que, leído de nuevo, se confundiría con un adjunto, un
     * enlace, una nota o un desplegable. También cuenta si ya viene escapado con "\",
     * para que el escape se pueda deshacer exacto.
     */
    internal fun looksStructural(line: String): Boolean {
        val content = line.trim().trimStart('\\')
        return STRUCTURE_LINES.any { it.matches(content) }
    }

    /** Al guardar: "\" delante para que se lea como texto (es el escape de Markdown). */
    internal fun escapeTextLine(line: String): String {
        if (!looksStructural(line)) return line
        val indent = line.length - line.trimStart().length
        return line.substring(0, indent) + "\\" + line.substring(indent)
    }

    /** Al leer: saca el "\" que puso [escapeTextLine]. */
    internal fun unescapeTextLine(line: String): String {
        val indent = line.length - line.trimStart().length
        if (!line.startsWith("\\", indent) || !looksStructural(line)) return line
        return line.substring(0, indent) + line.substring(indent + 1)
    }

    /** El renglón de un adjunto, como lo guarda el editor: "]" y ")" del nombre no cortan el enlace. */
    fun attachmentToken(attachment: Attachment): String {
        val safeName = attachment.name
            .replace("]", "］")
            .replace(")", "）")
            .replace("\n", " ")
        return "[$safeName](jot://att/${attachment.id})"
    }

    /** El renglón de una nota dentro de la nota, como lo guarda el editor. */
    fun noteLinkToken(noteId: String, title: String): String =
        "[${noteLinkTitle(title)}](jot://note/$noteId)"

    // Formato de renglón, igual que MarkdownVisualTransformation en el editor Markdown de antes.
    private val HEADING = Regex("^(#{1,6})\\s+")
    private val QUOTE = Regex("^>\\s?")
    private val CHECKLIST = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})([.)])\\s+")
    private val DIVIDER = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val CODE_FENCE = Regex("^```[^`]*$")

    // Lo que se escribe en Markdown.
    private const val FENCE = "```"
    private const val INDENT = "  "

    // La tarjeta de enlace es el marcador y, en el renglón siguiente, este enlace.
    private val BOOKMARK_LINK = Regex("^\\[([^\\]]*)]\\(([^)]*)\\)$")

    // La sección plegable y las direcciones viven en HtmlParts.kt; estos nombres quedan para
    // NoteHtml y ConversionCheck.
    internal const val SECTION = SECTION_CLASS
    internal const val TITLE_INNER = SECTION_TITLE_INNER
    internal const val CONTENT_INNER = SECTION_CONTENT_INNER
    internal const val PLACEHOLDER_TEXT = com.jot.app.data.html.PLACEHOLDER_TEXT
    internal const val ATTACHMENT_URL = com.jot.app.data.html.ATTACHMENT_URL

    /**
     * El cuerpo en HTML. Un cuerpo vacío es un HTML vacío.
     *
     * Con [bookmarks], una tarjeta se lee como en el editor: solo si su id está en la lista, y
     * sale de la lista. Sin lista (`null`) no se sabe qué tarjetas hay, y es tarjeta todo
     * marcador seguido de su enlace.
     *
     * Con [inlineMarks] (lo normal) se leen también las marcas que escribe [fromHtml] para lo que
     * el Markdown no tiene: `<u>`, `<sub>`, `<sup>`, `<span class="jot-text-…">`,
     * `<span class="jot-highlight-…">` y `==x==` (el resaltado verde). Una "\" delante hace que
     * esos mismos signos queden como texto. La migración de las notas viejas lo apaga: ahí eran texto.
     */
    fun toHtml(
        markdown: String,
        attachments: List<Attachment>,
        bookmarks: List<Bookmark>? = null,
        inlineMarks: Boolean = true,
    ): String {
        if (markdown.isEmpty()) return ""
        val document = Document.createShell("")
        document.outputSettings().prettyPrint(false)
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val before = readInlineMarks.get()
        readInlineMarks.set(inlineMarks)
        try {
            appendRange(
                lines, 0, lines.size, document.body(),
                attachments.associateBy { it.id }, bookmarks?.associateBy { it.id },
            )
        } finally {
            readInlineMarks.set(before)
        }
        return HtmlSanitizer.clean(document.body().html())
    }

    // Si [toHtml] lee las marcas en línea de [InlineMarks]. Va por hilo para no pasarlo por toda la cadena.
    private val readInlineMarks: ThreadLocal<Boolean> = ThreadLocal.withInitial { true }

    /** El cuerpo en el Markdown canónico de la app. [bookmarks] como en [toHtml]. */
    fun fromHtml(html: String, attachments: List<Attachment>, bookmarks: List<Bookmark>? = null): String {
        val out = MarkdownOut(attachments.associateBy { it.id }, bookmarks?.associateBy { it.id })
        writeBlocks(Jsoup.parseBodyFragment(html).body(), out)
        return out.build()
    }

    // ---- Markdown → HTML ----

    /**
     * Los renglones `[start, end)` leídos como los leía el editor Markdown de antes:
     * desplegables, tarjetas, notas y adjuntos conocidos son bloques propios, y lo que queda entre ellos es un tramo
     * de texto (cada renglón sin su escape, como al abrir la nota).
     */
    private fun appendRange(
        lines: List<String>,
        start: Int,
        end: Int,
        parent: Element,
        known: Map<String, Attachment>,
        bookmarks: Map<String, Bookmark>?,
    ) {
        val run = ArrayList<String>()
        fun flush() {
            if (run.isNotEmpty()) appendText(run.toList(), parent)
            run.clear()
        }

        var i = start
        while (i < end) {
            val trimmed = lines[i].trim()

            val details = DETAILS_OPEN.matchEntire(trimmed)
            val summary = if (details != null) lines.getOrNull(i + 1)?.trim()?.let { SUMMARY.matchEntire(it) } else null
            if (details != null && summary != null) {
                flush()
                // El título se guarda con &, < y > escapados (así lo guardaba el editor Markdown).
                val title = summary.groupValues[1]
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                val content = appendSection(title, details.groupValues[1].isNotBlank(), parent)
                // El </details> de un hijo no cierra al padre: se cuenta la profundidad.
                var depth = 1
                var close = i + 2
                while (close < end) {
                    val candidate = lines[close].trim()
                    when {
                        DETAILS_OPEN.matches(candidate) -> depth++
                        DETAILS_CLOSE.matches(candidate) -> if (--depth == 0) break
                    }
                    close++
                }
                // Sin </details>, el desplegable llega hasta el final del tramo.
                appendRange(lines, i + 2, close, content, known, bookmarks)
                finishSection(content)
                i = if (close < end) close + 1 else end
                continue
            }

            val bookmarkId = BOOKMARK_MARKER.matchEntire(trimmed)?.groupValues?.get(1)
            if (bookmarkId != null) {
                // Con la lista, como el editor: la tarjeta existe si su id es conocido y sale de la
                // lista; el renglón siguiente se saltea solo si es el `[t](u)` de la tarjeta (si no,
                // queda como texto). Con un id desconocido la marca es texto.
                val bookmark = bookmarks?.get(bookmarkId)
                val link = if (i + 1 < end) BOOKMARK_LINK.matchEntire(lines[i + 1].trim()) else null
                if (bookmark != null || (bookmarks == null && link != null)) {
                    flush()
                    if (bookmark != null) {
                        appendBookmark(bookmark, parent)
                    } else {
                        appendBookmark(bookmarkId, link!!.groupValues[2], link.groupValues[1], parent)
                    }
                    i += if (link != null) 2 else 1
                    continue
                }
            }

            val noteId = NOTE_LINE.matchEntire(trimmed)?.groupValues?.get(1)
            if (noteId != null) {
                flush()
                // El editor escribe el título de la nota; vacío, "Sin título" (noteLinkToken).
                val title = trimmed.substring(1, trimmed.indexOf(']')).ifBlank { "Sin título" }
                appendNoteLink(noteId, title, parent)
                i++
                continue
            }

            val attachment = ATTACHMENT_LINE.matchEntire(trimmed)?.groupValues?.get(1)?.let { known[it] }
            if (attachment != null) {
                flush()
                appendAttachment(attachment, parent)
                i++
                continue
            }

            // Lo demás es texto. También un adjunto con un id que la nota no tiene: el editor
            // lo deja como texto y al guardar lo escapa, así que vuelve con "\" delante.
            run.add(unescapeTextLine(lines[i]))
            i++
        }
        flush()
    }

    /**
     * Un tramo de renglones de texto (ya sin su escape), como lo dibuja un campo del
     * editor: la numeración y los bloques de código no pasan de un tramo a otro.
     */
    private fun appendText(lines: List<String>, parent: Element) {
        val lists = ListBuilder(parent)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (CODE_FENCE.matches(trimmed)) {
                lists.close()
                val close = (i + 1 until lines.size).firstOrNull { CODE_FENCE.matches(lines[it].trim()) }
                if (close == null) {
                    // Sin cierre, el editor muestra el resto como código, pero no es un bloque
                    // completo: se guarda como texto tal cual, así vuelve idéntico.
                    lines.subList(i, lines.size).forEach { appendLiteral(it, parent) }
                    return
                }
                if (line != trimmed || lines[close] != FENCE) {
                    // MVT dibuja los cerrojos enteros: si alguno tiene espacios o el de cierre
                    // tiene algo después de ```, el bloque se guarda tal cual para no perderlo.
                    lines.subList(i, close + 1).forEach { appendLiteral(it, parent) }
                } else {
                    appendCode(line.substring(FENCE.length), lines.subList(i + 1, close), parent)
                }
                i = close + 1
                continue
            }

            if (trimmed.isEmpty()) {
                lists.close()
                appendLiteral(line, parent)
                i++
                continue
            }

            // "****" y "******" no son separador: son formato vacío (negrita, o negrita y
            // cursiva), como el que deja un botón sin selección.
            if (DIVIDER.matches(trimmed) && trimmed != "****" && trimmed != "******") {
                lists.close()
                parent.appendElement("hr")
                i++
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                lists.close()
                val tag = "h${heading.groupValues[1].length}"
                appendLines(parent.appendElement(tag), listOf(line to heading.range.last + 1))
                i++
                continue
            }

            if (QUOTE.containsMatchIn(line)) {
                lists.close()
                val quoteLines = ArrayList<Pair<String, Int>>()
                while (i < lines.size) {
                    val quote = QUOTE.find(lines[i]) ?: break
                    quoteLines.add(lines[i] to quote.range.last + 1)
                    i++
                }
                appendLines(parent.appendElement("blockquote"), quoteLines)
                continue
            }

            val check = CHECKLIST.find(line)
            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            when {
                check != null -> {
                    val item = lists.item(level(check.groupValues[1]), "ul", 1)
                    item.attr("data-checked", check.groupValues[2].equals("x", ignoreCase = true).toString())
                    appendLines(item, listOf(line to check.range.last + 1))
                }
                bullet != null -> {
                    val item = lists.item(level(bullet.groupValues[1]), "ul", 1)
                    appendLines(item, listOf(line to bullet.range.last + 1))
                }
                numbered != null -> {
                    val item = lists.item(level(numbered.groupValues[1]), "ol", numbered.groupValues[2].toInt())
                    appendLines(item, listOf(line to numbered.range.last + 1))
                }
                else -> {
                    lists.close()
                    appendLines(parent.appendElement("div"), listOf(line to 0))
                }
            }
            i++
        }
    }

    /** El nivel de un ítem: dos espacios por nivel; un Tab cuenta cuatro, como lo dibuja el editor. */
    private fun level(indent: String): Int = indent.replace("\t", "    ").length / 2

    /**
     * Un renglón sin leer sus marcas: un renglón vacío, o uno de un código sin cerrar o con
     * un cerrojo que no es exacto.
     */
    private fun appendLiteral(line: String, parent: Element) {
        val div = parent.appendElement("div")
        if (line.isEmpty()) div.appendElement("br") else div.appendText(if (readInlineMarks.get() == true) unescapeMarks(line) else line)
    }

    /**
     * El texto de un renglón guardado tal cual (no se lee su formato), sin el escape de
     * [appendEscapedText]: de cada tanda de "\" delante de una marca queda la mitad, y la marca
     * es texto.
     */
    private fun unescapeMarks(line: String): String {
        val text = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (line[i] != '\\') {
                text.append(line[i])
                i++
                continue
            }
            var j = i
            while (j < line.length && line[j] == '\\') j++
            val length = markLengthAt(line, j, line.length)
            text.append("\\".repeat(if (length == 0) j - i else (j - i) / 2))
            i = j
        }
        return text.toString()
    }

    /**
     * El contenido de uno o más renglones (texto y desde dónde empieza) separados por
     * `<br>`, con la regla común de [appendLinesWithBr].
     */
    private fun appendLines(block: Element, lines: List<Pair<String, Int>>) {
        val (lastSource, lastFrom) = lines.last()
        appendLinesWithBr(block, lines.size, lastFrom >= lastSource.length) { index, into ->
            val (source, from) = lines[index]
            appendInline(source, from, source.length, into)
        }
    }

    /**
     * Las marcas en línea de un renglón, con las reglas que tenía MarkdownVisualTransformation
     * en el editor Markdown de antes (`opens`, `closes`, `findItalicClose`, `findCloseParen`). Lo que no abre y cierra
     * queda como texto.
     */
    private fun appendInline(source: String, from: Int, to: Int, parent: Element) {
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) parent.appendText(text.toString())
            text.clear()
        }

        val marks = readInlineMarks.get() == true
        var i = from
        while (i < to) {
            when {
                source.startsWith("***", i) && opens(source, i, 3, to) &&
                    findClose(source, "***", i + 3, to) >= 0 -> {
                    val close = findClose(source, "***", i + 3, to)
                    flush()
                    appendInline(source, i + 3, close, parent.appendElement("b").appendElement("i"))
                    i = close + 3
                }

                // Un "**" sin cierre no es negrita: es la cursiva vacía de la rama de "*".
                source.startsWith("**", i) && opens(source, i, 2, to) &&
                    findClose(source, "**", i + 2, to) >= 0 -> {
                    val close = findClose(source, "**", i + 2, to)
                    flush()
                    appendInline(source, i + 2, close, parent.appendElement("b"))
                    i = close + 2
                }

                source.startsWith("~~", i) -> {
                    val close = if (opens(source, i, 2, to)) findClose(source, "~~", i + 2, to) else -1
                    if (close in (i + 2) until to) {
                        flush()
                        appendInline(source, i + 2, close, parent.appendElement("strike"))
                        i = close + 2
                    } else {
                        text.append(source[i])
                        i++
                    }
                }

                source[i] == '`' -> {
                    val close = source.indexOf('`', i + 1)
                    if (close in (i + 1) until to) {
                        flush()
                        val code = parent.appendElement("code").attr("spellcheck", "false")
                        if (close > i + 1) code.appendText(source.substring(i + 1, close))
                        i = close + 1
                    } else {
                        text.append(source[i])
                        i++
                    }
                }

                source[i] == '*' -> {
                    val close = if (opens(source, i, 1, to)) findItalicClose(source, i + 1, to) else -1
                    if (close in (i + 1) until to) {
                        flush()
                        appendInline(source, i + 1, close, parent.appendElement("i"))
                        i = close + 1
                    } else {
                        text.append(source[i])
                        i++
                    }
                }

                source[i] == '[' -> {
                    val closeBracket = source.indexOf(']', i + 1)
                    val openParen = closeBracket + 1
                    val closeParen = if (closeBracket >= 0 && openParen < to &&
                        source.startsWith("(", openParen)
                    ) {
                        findCloseParen(source, openParen, to)
                    } else {
                        -1
                    }
                    if (closeBracket in (i + 1) until to &&
                        closeParen in (openParen + 1) until to
                    ) {
                        flush()
                        val link = parent.appendElement("a")
                            .attr("href", source.substring(openParen + 1, closeParen))
                        if (closeBracket > i + 1) link.appendText(source.substring(i + 1, closeBracket))
                        i = closeParen + 1
                    } else {
                        text.append(source[i])
                        i++
                    }
                }

                // "\" delante de una marca en línea: se saca la mitad (las parejas valen "\") y, si
                // sobra una, la marca queda como texto.
                marks && source[i] == '\\' -> {
                    var j = i
                    while (j < to && source[j] == '\\') j++
                    val length = markLengthAt(source, j, to)
                    if (length == 0) {
                        text.append(source, i, j)
                        i = j
                    } else {
                        val slashes = j - i
                        text.append("\\".repeat(slashes / 2))
                        if (slashes % 2 == 1) {
                            text.append(source, j, j + length)
                            i = j + length
                        } else {
                            i = j
                        }
                    }
                }

                marks && source[i] == '<' -> {
                    val tag = tagLengthAt(source, i, to).takeIf { it > 0 }?.let { source.substring(i, i + it) }
                    val close = if (tag != null && !tag.startsWith("</")) findTagClose(source, tag, i + tag.length, to) else -1
                    if (tag != null && close >= 0) {
                        flush()
                        appendInline(source, i + tag.length, close, openInlineElement(tag, parent))
                        i = close + closingTag(tag).length
                    } else {
                        text.append('<')
                        i++
                    }
                }

                marks && source.startsWith("==", i) -> {
                    val close = if (opens(source, i, 2, to)) findEqualsClose(source, i + 2, to) else -1
                    if (close >= 0) {
                        flush()
                        appendInline(source, i + 2, close, parent.appendElement("span").addClass(HIGHLIGHT_GREEN))
                        i = close + 2
                    } else {
                        text.append('=')
                        i++
                    }
                }

                else -> {
                    val next = nextMarker(source, i, to, marks)
                    text.append(source, i, next)
                    i = next
                }
            }
        }
        flush()
    }

    // ---- Marcas en línea que el Markdown no tiene (subrayado, índices, colores) ----

    private const val HIGHLIGHT_GREEN = "jot-highlight"
    private val TEXT_COLOR_NAMES = listOf("red", "orange", "yellow", "green", "blue", "pink", "purple", "gray")

    // El verde no lleva sufijo (`jot-highlight`): sale como `==x==`.
    private val HIGHLIGHT_COLOR_NAMES = TEXT_COLOR_NAMES - "green"

    // Las etiquetas que escribe [fromHtml], exactas; cualquier otra "<" es texto.
    private val INLINE_TAG: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        "<u>|</u>|<sub>|</sub>|<sup>|</sup>|</span>" +
            "|<span class=\"jot-text-(?:" + TEXT_COLOR_NAMES.joinToString("|") + ")\">" +
            "|<span class=\"" + HIGHLIGHT_GREEN + "\">" +
            "|<span class=\"jot-highlight-(?:" + HIGHLIGHT_COLOR_NAMES.joinToString("|") + ")\">"
    )

    /** El largo de la etiqueta en línea que empieza en [at] (sin pasar de [to]), o 0. */
    private fun tagLengthAt(source: String, at: Int, to: Int): Int {
        if (at >= to || source[at] != '<') return 0
        val matcher = INLINE_TAG.matcher(source).region(at, to)
        return if (matcher.lookingAt()) matcher.end() - at else 0
    }

    /**
     * El largo de la marca (etiqueta o "==") que empieza en [at], o 0. Si [at] es justo el fin
     * [to] del tramo, se mira más allá: ahí está la marca de cierre que cortó el tramo.
     */
    private fun markLengthAt(source: String, at: Int, to: Int): Int {
        val limit = if (at == to) source.length else to
        if (at >= limit) return 0
        if (source.startsWith("==", at) && at + 2 <= limit) return 2
        return tagLengthAt(source, at, limit)
    }

    /** Cuántas "\" seguidas hay justo antes de [at], sin pasar de [from]. */
    private fun slashesBefore(source: String, at: Int, from: Int): Int {
        var n = 0
        while (at - n - 1 >= from && source[at - n - 1] == '\\') n++
        return n
    }

    private fun closingTag(opening: String): String =
        if (opening.startsWith("<span")) "</span>" else "</" + opening.substring(1)

    /** El `<` de cierre de [opening] que le toca, contando las marcas iguales de adentro; -1 si no hay. */
    private fun findTagClose(source: String, opening: String, from: Int, to: Int): Int {
        val closing = closingTag(opening)
        val family = if (opening.startsWith("<span")) "<span" else opening
        var depth = 1
        var j = from
        while (j < to) {
            val skipped = skipOpaque(source, j, to)
            if (skipped > j) {
                j = skipped
                continue
            }
            val length = tagLengthAt(source, j, to)
            if (length > 0 && slashesBefore(source, j, from) % 2 == 0) {
                val tag = source.substring(j, j + length)
                if (tag == closing) {
                    if (--depth == 0) return j
                } else if (tag.startsWith(family)) {
                    depth++
                }
                j += length
            } else {
                j++
            }
        }
        return -1
    }

    /** El "==" que cierra un resaltado: pegado al texto, y no el que lleva "\" delante. */
    private fun findEqualsClose(source: String, from: Int, to: Int): Int {
        var j = from
        while (j + 2 <= to) {
            val skipped = skipOpaque(source, j, to)
            if (skipped > j) {
                j = skipped
                continue
            }
            if (source.startsWith("==", j) && slashesBefore(source, j, from) % 2 == 0 &&
                closes(source, j, from, 2, to)
            ) {
                return j
            }
            j++
        }
        return -1
    }

    /**
     * Si en [at] empieza un código en línea o un enlace `[..](..)` completos, el índice donde
     * terminan; si no, [at]. La búsqueda del cierre de una marca los saltea, como [appendInline].
     */
    private fun skipOpaque(source: String, at: Int, to: Int): Int {
        when (source[at]) {
            '`' -> {
                val close = indexOfBefore(source, '`', at + 1, to)
                if (close >= 0) return close + 1
            }
            '[' -> {
                val closeBracket = indexOfBefore(source, ']', at + 1, to)
                val openParen = closeBracket + 1
                if (closeBracket >= 0 && openParen < to && source.startsWith("(", openParen)) {
                    val closeParen = findCloseParen(source, openParen, to)
                    if (closeParen in (openParen + 1) until to) return closeParen + 1
                }
            }
        }
        return at
    }

    /** Donde está [char] entre [from] y [to] (sin pasarse), o -1. */
    private fun indexOfBefore(source: String, char: Char, from: Int, to: Int): Int {
        for (j in from until to) if (source[j] == char) return j
        return -1
    }

    private fun openInlineElement(tag: String, parent: Element): Element = when {
        tag.startsWith("<span") -> parent.appendElement("span").attr("class", tag.substringAfter('"').substringBefore('"'))
        else -> parent.appendElement(tag.substring(1, tag.length - 1))
    }

    // El cierre de una cursiva es el próximo "*" suelto: se saltean las negritas de adentro.
    private fun findItalicClose(source: String, from: Int, to: Int): Int {
        var j = from
        while (j < to) {
            if (source.startsWith("**", j) && opens(source, j, 2, to)) {
                val boldClose = findClose(source, "**", j + 2, to)
                if (boldClose >= 0) {
                    j = boldClose + 2
                    continue
                }
            }
            if (source[j] == '*' && closes(source, j, from, 1, to)) return j
            j++
        }
        return -1
    }

    // Una marca abre formato si el texto sigue pegado a ella ("*hola", no "* hola") y
    // no está entre dos números ("2*3").
    private fun opens(source: String, at: Int, length: Int, to: Int): Boolean {
        val next = source.getOrNull(at + length)?.takeIf { at + length < to } ?: return false
        if (next.isWhitespace()) return false
        val prev = source.getOrNull(at - 1)
        return !(prev != null && prev.isDigit() && next.isDigit())
    }

    // Y cierra si va pegada al texto de la izquierda ("hola*", no "o *.pdf"). La marca
    // vacía que deja un botón sin selección ("**", "****") también vale.
    private fun closes(source: String, at: Int, contentStart: Int, length: Int, to: Int): Boolean {
        if (at == contentStart) return true
        val prev = source[at - 1]
        if (prev.isWhitespace()) return false
        val next = source.getOrNull(at + length)?.takeIf { at + length < to }
        return !(prev.isDigit() && next != null && next.isDigit())
    }

    private fun findClose(source: String, marker: String, from: Int, to: Int): Int {
        var j = source.indexOf(marker, from)
        while (j in from until to && j + marker.length <= to) {
            if (closes(source, j, from, marker.length, to)) return j
            j = source.indexOf(marker, j + 1)
        }
        return -1
    }

    // El paréntesis que cierra la dirección de un enlace, contando los de adentro:
    // "(https://es.wikipedia.org/wiki/Mate_(bebida))".
    private fun findCloseParen(source: String, openParen: Int, to: Int): Int {
        var depth = 0
        for (j in openParen until to) {
            when (source[j]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return j
            }
        }
        return -1
    }

    private fun nextMarker(source: String, from: Int, to: Int, marks: Boolean): Int {
        var i = from
        while (i < to) {
            when {
                source.startsWith("**", i) -> return i
                source.startsWith("~~", i) -> return i
                source[i] == '`' || source[i] == '*' || source[i] == '[' -> return i
                marks && (source[i] == '\\' || source[i] == '<' || source.startsWith("==", i)) -> return i
                else -> i++
            }
        }
        return to
    }

    // ---- HTML → Markdown ----

    /**
     * El Markdown que arma [fromHtml]. Como el editor al guardar, un renglón de texto que
     * parece token lleva "\" delante; los renglones de un bloque propio van tal cual.
     */
    private class MarkdownOut(
        val attachments: Map<String, Attachment>,
        val bookmarks: Map<String, Bookmark>? = null,
    ) {
        private val lines = ArrayList<String>()
        private val tokens = HashSet<Int>()

        fun add(line: String) {
            lines.add(line)
        }

        fun addAll(texts: List<String>) {
            lines.addAll(texts)
        }

        fun token(line: String) {
            tokens.add(lines.size)
            lines.add(line)
        }

        fun addAll(other: MarkdownOut) {
            other.lines.forEachIndexed { index, line -> if (index in other.tokens) token(line) else add(line) }
        }

        /** Los renglones como texto, para un bloque que va dentro de otro (cita, ítem, título). */
        fun texts(): List<String> = lines

        /** Un solo renglón vacío: el cuerpo vacío de un desplegable. */
        fun isOneEmptyLine(): Boolean = lines.size == 1 && lines[0].isEmpty() && tokens.isEmpty()

        fun build(): String =
            lines.indices.joinToString("\n") { if (it in tokens) lines[it] else escapeTextLine(lines[it]) }
    }

    /** Los bloques de [container]; lo suelto entre bloques (texto, marcas) es un renglón más. */
    private fun writeBlocks(container: Element, out: MarkdownOut) {
        val loose = ArrayList<Node>()
        fun flushLoose() {
            if (loose.isNotEmpty()) out.addAll(blockLines(loose, out.attachments))
            loose.clear()
        }
        for (node in container.childNodes()) {
            when {
                node is Element && node.isBlock -> {
                    flushLoose()
                    writeBlock(node, out)
                }
                // Espacios entre bloques (HTML con sangría).
                node is TextNode && node.isBlank && loose.isEmpty() -> Unit
                else -> loose.add(node)
            }
        }
        flushLoose()
    }

    private fun writeBlock(block: Element, out: MarkdownOut) {
        when (val tag = block.normalName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" ->
                out.add("#".repeat(tag[1].digitToInt()) + " " + singleLine(block.childNodes(), out.attachments))
            "ul", "ol" -> writeList(block, 0, out)
            "blockquote" -> blockLines(block.childNodes(), out.attachments).forEach { out.add("> $it") }
            "pre" -> {
                out.add(FENCE + block.attr("data-language"))
                out.addAll(codeLines(block))
                out.add(FENCE)
            }
            "hr" -> out.add("---")
            "div" -> when (val own = divBlockOf(block)) {
                DivBlock.Section -> writeSection(block, out)
                is DivBlock.Card -> writeBookmark(block, out)
                else -> {
                    val token = tokenLine(own, out.attachments)
                    when {
                        token != null -> out.token(token)
                        block.children().any { it.isBlock } -> writeBlocks(block, out)
                        else -> out.addAll(blockLines(block.childNodes(), out.attachments))
                    }
                }
            }
            else -> writeBlocks(block, out)
        }
    }

    /**
     * El renglón de un `<div>` que es solo un adjunto conocido o una nota ([divBlockOf]), como
     * lo escribía el editor Markdown ([attachmentToken], [noteLinkToken]). Un adjunto con un id
     * que la nota no tiene queda como texto con su enlace, como en el editor.
     */
    private fun tokenLine(own: DivBlock?, attachments: Map<String, Attachment>): String? = when (own) {
        is DivBlock.File -> attachments[own.id]?.let { attachmentToken(it) }
        is DivBlock.NoteLink -> noteLinkToken(own.noteId, own.title)
        else -> null
    }

    /**
     * La tarjeta: el marcador y el enlace en el renglón siguiente, como la guardaba el editor Markdown.
     * Con la lista de tarjetas, el enlace sale de la lista; una tarjeta que no está en la
     * lista queda como texto (el editor la muestra así y al guardar escapa el marcador).
     */
    private fun writeBookmark(div: Element, out: MarkdownOut) {
        val marker = "<!-- jot:bookmark ${div.attr("data-jot-bookmark")} -->"
        val bookmarks = out.bookmarks
        if (bookmarks != null) {
            val bookmark = bookmarks[div.attr("data-jot-bookmark")]
            if (bookmark == null) {
                out.add(marker)
                out.addAll(blockLines(div.childNodes(), out.attachments))
            } else {
                // Tal cual la guardaba el editor Markdown.
                val safeTitle = bookmark.title.replace("]", "］").replace("\n", " ").ifBlank { bookmark.url }
                out.token(marker)
                out.token("[$safeTitle](${bookmark.url.replace(")", "）")})")
            }
            return
        }
        val link = div.selectFirst("a")
        val title = link?.wholeText().orEmpty().replace("]", "］").replace("\n", " ")
        val url = link?.attr("href").orEmpty().replace(")", "）")
        out.token(marker)
        out.token("[$title]($url)")
    }

    /**
     * La sección plegable como desplegable. Se ignora lo que la app no usa: el color
     * (`jot-section-highlight-*`), el texto de ayuda y `data-jot-section-content`.
     */
    private fun writeSection(section: Element, out: MarkdownOut) {
        val heading = section.childWithClass(SECTION_TITLE_WRAPPER)?.childWithClass(SECTION_TITLE)
        val title = heading?.let { plainText(it.childWithClass(TITLE_INNER) ?: it) }.orEmpty()
        val inner = MarkdownOut(out.attachments, out.bookmarks)
        section.childWithClass(SECTION_CONTENT)?.let { writeBlocks(it.childWithClass(CONTENT_INNER) ?: it, inner) }

        out.token(if (section.hasClass(SECTION_COLLAPSED)) "<details>" else "<details open>")
        // Como lo guardaba el editor Markdown: &, < y > escapados.
        val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        out.token("<summary>$safeTitle</summary>")
        // Un solo renglón vacío es el cuerpo de una sección vacía: el editor no lo guarda.
        if (!inner.isOneEmptyLine()) out.addAll(inner)
        out.token("</details>")
    }

    private fun Element.childWithClass(name: String): Element? = children().firstOrNull { it.hasClass(name) }

    /** El título, que en la app es texto sin formato. Un `<br>` del medio es un espacio. */
    private fun plainText(element: Element): String {
        val text = StringBuilder()
        fun collect(node: Node) {
            when {
                node is TextNode -> text.append(node.wholeText.replace('\n', ' '))
                node is Element && node.normalName() == "br" -> text.append('\n')
                node is Element -> node.childNodes().forEach { collect(it) }
            }
        }
        element.childNodes().forEach { collect(it) }
        return text.toString().trimEnd('\n').replace('\n', ' ')
    }

    /** Un nivel de lista: las listas de adentro van con dos espacios más. */
    private fun writeList(list: Element, level: Int, out: MarkdownOut) {
        val ordered = list.normalName() == "ol"
        var number = list.attr("start").toIntOrNull() ?: 1
        for (child in list.children()) {
            when (child.normalName()) {
                "ul", "ol" -> writeList(child, level + 1, out)
                "li" -> {
                    // El editor solo lee números de hasta tres cifras.
                    val marker = when {
                        child.hasAttr("data-checked") ->
                            if (child.attr("data-checked") == "true") "- [x] " else "- [ ] "
                        ordered -> "${number.coerceIn(0, 999)}. "
                        else -> "- "
                    }
                    if (ordered) number++
                    val (nested, content) = child.childNodes().partition {
                        it is Element && (it.normalName() == "ul" || it.normalName() == "ol")
                    }
                    out.add(INDENT.repeat(level) + marker + singleLine(content, out.attachments))
                    nested.forEach { writeList(it as Element, level + 1, out) }
                }
                else -> writeBlock(child, out)
            }
        }
    }

    /** Un título o un ítem son un solo renglón: un `<br>` del medio queda como espacio. */
    private fun singleLine(nodes: List<Node>, attachments: Map<String, Attachment>): String =
        blockLines(nodes, attachments).joinToString(" ")

    /**
     * Los renglones de un bloque. Un `<br>` corta el renglón y un bloque de adentro va en
     * sus propios renglones; lo vacío que queda al final no es un renglón más (el navegador
     * no lo dibuja).
     */
    private fun blockLines(nodes: List<Node>, attachments: Map<String, Attachment>): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var empty = true
        for (node in nodes) {
            when {
                node is Element && node.normalName() == "br" -> {
                    lines.add(current.toString())
                    current.clear()
                    empty = true
                }
                node is Element && node.isBlock -> {
                    if (!empty) lines.add(current.toString())
                    current.clear()
                    empty = true
                    val inner = MarkdownOut(attachments)
                    writeBlock(node, inner)
                    lines.addAll(inner.texts())
                }
                else -> {
                    writeInline(node, current)
                    empty = false
                }
            }
        }
        if (!empty || lines.isEmpty()) lines.add(current.toString())
        return lines
    }

    /**
     * Las marcas en línea. El subrayado, los índices, el color de texto y el resaltado salen como
     * HTML en línea, y el resaltado verde como `==x==`; las etiquetas que la app no sabe
     * escribir (`s` y cualquier otra) quedan como su texto.
     */
    private fun writeInline(node: Node, out: StringBuilder) {
        if (node is TextNode) {
            appendEscapedText(node.wholeText.replace('\n', ' '), out)
            return
        }
        if (node !is Element) return
        when (node.normalName()) {
            "b" -> wrap(node, "**", out)
            "i" -> wrap(node, "*", out)
            "strike", "s" -> wrap(node, "~~", out)
            "u", "sub", "sup" -> wrapTag(node, node.normalName(), out)
            "span" -> writeSpan(node, out)
            "code" -> out.append('`').append(node.wholeText()).append('`')
            "a" -> out.append('[').append(node.wholeText()).append("](").append(node.attr("href")).append(')')
            // Una imagen que no es un adjunto conocido de la nota queda como su enlace.
            "img" -> if (node.attr("src").startsWith(ATTACHMENT_URL)) out.append("[](").append(node.attr("src")).append(')')
            "br" -> out.append(' ')
            else -> node.childNodes().forEach { writeInline(it, out) }
        }
    }

    /**
     * Una marca que [toHtml] lee de verdad. Las "\" que ya estaban justo antes se duplican, para
     * que no se lean como el escape de la marca (ver [appendEscapedText]).
     */
    private fun appendMark(mark: String, out: StringBuilder) {
        var slashes = 0
        while (slashes < out.length && out[out.length - 1 - slashes] == '\\') slashes++
        out.append("\\".repeat(slashes)).append(mark)
    }

    /**
     * Texto del usuario: un signo que [toHtml] leería como marca (`<u>`, `</span>`, `==`, …) lleva
     * "\" delante, y las "\" que ya lo preceden se duplican (2n+1 en total). Lo demás va tal cual.
     */
    private fun appendEscapedText(text: String, out: StringBuilder) {
        var i = 0
        while (i < text.length) {
            val length = when {
                text.startsWith("==", i) -> 2
                else -> tagLengthAt(text, i, text.length)
            }
            if (length == 0) {
                out.append(text[i])
                i++
                continue
            }
            var slashes = 0
            while (slashes < out.length && out[out.length - 1 - slashes] == '\\') slashes++
            out.append("\\".repeat(slashes + 1)).append(text, i, i + length)
            i += length
        }
    }

    private fun wrapTag(element: Element, tag: String, out: StringBuilder) {
        appendMark("<$tag>", out)
        element.childNodes().forEach { writeInline(it, out) }
        appendMark("</$tag>", out)
    }

    /** `span` con color de texto y/o resaltado; cualquier otro `span` queda como su contenido. */
    private fun writeSpan(span: Element, out: StringBuilder) {
        val classes = span.classNames()
        val color = TEXT_COLOR_NAMES.firstOrNull { "jot-text-$it" in classes }
        val highlight = when {
            HIGHLIGHT_GREEN in classes -> "green"
            else -> HIGHLIGHT_COLOR_NAMES.firstOrNull { "jot-highlight-$it" in classes }
        }
        if (color != null) appendMark("<span class=\"jot-text-$color\">", out)
        when (highlight) {
            null -> span.childNodes().forEach { writeInline(it, out) }
            "green" -> writeEquals(span, out)
            else -> {
                appendMark("<span class=\"jot-highlight-$highlight\">", out)
                span.childNodes().forEach { writeInline(it, out) }
                appendMark("</span>", out)
            }
        }
        if (color != null) appendMark("</span>", out)
    }

    /** `==x==`; los espacios de los bordes quedan afuera, que si no la marca no se vuelve a leer. */
    private fun writeEquals(span: Element, out: StringBuilder) {
        val inner = StringBuilder()
        span.childNodes().forEach { writeInline(it, inner) }
        val text = inner.toString()
        val core = text.trim { it.isWhitespace() }
        val lead = if (core.isEmpty()) "" else text.substring(0, text.indexOf(core))
        val trail = if (core.isEmpty()) "" else text.substring(lead.length + core.length)
        val before = lead.lastOrNull() ?: out.lastOrNull()
        val after = trail.firstOrNull() ?: (span.nextSibling() as? TextNode)?.wholeText?.firstOrNull()
        if (text.isEmpty() || core.startsWith('=') || core.endsWith('=') || before == '=' || after == '=') {
            // Vacío, o con un "=" pegado a la marca, "==" no se leería bien: va como los otros colores.
            appendMark("<span class=\"$HIGHLIGHT_GREEN\">", out)
            out.append(text)
            appendMark("</span>", out)
            return
        }
        if (core.isEmpty()) {
            out.append(text)
            return
        }
        out.append(lead)
        appendMark("==", out)
        out.append(core)
        appendMark("==", out)
        out.append(trail)
    }

    private fun wrap(element: Element, marker: String, out: StringBuilder) {
        out.append(marker)
        element.childNodes().forEach { writeInline(it, out) }
        out.append(marker)
    }

    /** El texto de un `<pre>` en renglones: un `<br>` es un salto y el último salto no abre renglón. */
    private fun codeLines(pre: Element): List<String> {
        val text = StringBuilder()
        fun collect(node: Node) {
            when {
                node is TextNode -> text.append(node.wholeText)
                node is Element && node.normalName() == "br" -> text.append('\n')
                node is Element -> node.childNodes().forEach { collect(it) }
            }
        }
        pre.childNodes().forEach { collect(it) }
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }
}
