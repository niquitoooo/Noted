package com.noted.app.data.html

import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import com.noted.app.data.searchableText
import com.noted.app.util.stripMarkdown
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Control de la conversión Markdown → HTML: la migración deja sin convertir una
 * nota si [verify] devuelve algún motivo. Las piezas del Markdown se cuentan leyendo los
 * renglones con las mismas reglas que [NoteMarkdown.toHtml], sin mirar el HTML; las del HTML
 * se cuentan con jsoup.
 */
object ConversionCheck {

    // Marcador de lista al principio del renglón: en el HTML no es texto (es de la `<ul>`/`<ol>`).
    private val LIST_MARKER = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?")
    private val MARKS = Regex("[#>*_~`]")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private val HEADING = Regex("^(#{1,6})\\s+")
    private val QUOTE = Regex("^>\\s?")
    private val CHECKLIST = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})([.)])\\s+")
    private val DIVIDER = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val CODE_FENCE = Regex("^```[^`]*$")
    private const val FENCE = "```"

    /** Los motivos por los que [html] no dice lo mismo que [markdown]; vacía si está bien. */
    fun verify(
        markdown: String,
        html: String,
        attachments: List<Attachment>,
        bookmarks: List<Bookmark>,
    ): List<String> {
        val reasons = ArrayList<String>()
        checkWords(markdown, html, attachments, reasons)

        val before = Pieces()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        scanRange(lines, 0, lines.size, attachments.associateBy { it.id }, bookmarks.associateBy { it.id }, before)
        val after = piecesOf(html, attachments, bookmarks)

        val names = attachments.associate { it.id to it.name }
        compareIds(before.attachments, after.attachments, reasons, "el adjunto") { names[it] ?: it }
        compareIds(before.bookmarks, after.bookmarks, reasons, "la tarjeta") { it }
        compareIds(before.notes, after.notes, reasons, "la nota") { it }
        compareCount("desplegables", before.sections, after.sections, reasons)
        compareCount("títulos", before.headings, after.headings, reasons)
        compareCount("ítems de lista", before.items, after.items, reasons)
        compareCount("ítems tildados", before.checked, after.checked, reasons)
        compareCount("ítems sin tildar", before.unchecked, after.unchecked, reasons)
        compareCount("citas", before.quotes, after.quotes, reasons)
        compareCount("bloques de código", before.code, after.code, reasons)
        return reasons
    }

    // ---- Palabras ----

    /**
     * Las palabras de los dos lados se comparan como secuencia. Antes: el texto de búsqueda sin
     * el marcador de lista de cada renglón y sin Markdown. Después: el texto del HTML sin sus
     * `<pre>`, porque `stripMarkdown` borra el código cercado entero (los bloques de código se
     * controlan por cantidad). En los dos lados se sacan las marcas y el acento grave, y una
     * palabra es un tramo de letras y números.
     */
    private fun checkWords(markdown: String, html: String, attachments: List<Attachment>, reasons: MutableList<String>) {
        val oldText = stripMarkdown(
            searchableText(markdown).lineSequence().joinToString("\n") { it.replace(LIST_MARKER, "") }
        )
        val body = Jsoup.parseBodyFragment(html).body()
        body.ownerDocument()?.outputSettings()?.prettyPrint(false)
        body.select("pre").remove()
        val newText = NoteHtml.plainText(body.html(), attachments)

        val expected = words(oldText)
        val actual = words(newText)
        if (expected == actual) return
        val diff = expected.size - actual.size
        when {
            diff > 0 -> reasons.add(if (diff == 1) "falta 1 palabra" else "faltan $diff palabras")
            diff < 0 -> reasons.add(if (diff == -1) "sobra 1 palabra" else "sobran ${-diff} palabras")
            else -> {
                val at = expected.indices.first { expected[it] != actual[it] }
                reasons.add("las palabras no coinciden: \"${expected[at]}\" y \"${actual[at]}\" (palabra ${at + 1})")
            }
        }
    }

    private fun words(text: String): List<String> =
        WORD.findAll(text.replace(MARKS, "")).map { it.value }.toList()

    // ---- Piezas ----

    private class Pieces {
        val attachments = ArrayList<String>()
        val bookmarks = ArrayList<String>()
        val notes = ArrayList<String>()
        var sections = 0
        var headings = 0
        var items = 0
        var checked = 0
        var unchecked = 0
        var quotes = 0
        var code = 0
    }

    private fun piecesOf(html: String, attachments: List<Attachment>, bookmarks: List<Bookmark>): Pieces {
        val known = attachments.map { it.id }.toSet()
        val knownBookmarks = bookmarks.map { it.id }.toSet()
        val body = Jsoup.parseBodyFragment(html).body()
        val out = Pieces()
        // Imágenes y archivos, en el orden del documento.
        body.select("img, a[data-file-id]").forEach { element ->
            val id = if (element.normalName() == "img") {
                element.attr("src").takeIf { it.startsWith(NoteMarkdown.ATTACHMENT_URL) }
                    ?.removePrefix(NoteMarkdown.ATTACHMENT_URL)
            } else element.attr("data-file-id")
            if (id != null && id in known) out.attachments.add(id)
        }
        body.select("[data-noted-bookmark]").map { it.attr("data-noted-bookmark") }
            .filterTo(out.bookmarks) { it in knownBookmarks }
        out.notes.addAll(NoteHtml.noteLinkBlockIds(html))
        out.sections = body.select(".${NoteMarkdown.SECTION}").size
        out.headings = body.select("h1, h2, h3, h4, h5, h6").count { !insideSectionTitle(it) }
        out.items = body.select("li").size
        out.checked = body.select("li[data-checked=true]").size
        out.unchecked = body.select("li[data-checked=false]").size
        out.quotes = body.select("blockquote").size
        out.code = body.select("pre").size
        return out
    }

    private fun insideSectionTitle(element: Element): Boolean =
        element.parents().any { it.hasClass(NoteMarkdown.TITLE_INNER) }

    /**
     * Los renglones `[start, end)` como `NoteMarkdown.appendRange`: primero desplegables,
     * tarjetas, notas y adjuntos conocidos; lo demás se acumula en un tramo de texto.
     */
    private fun scanRange(
        lines: List<String>,
        start: Int,
        end: Int,
        known: Map<String, Attachment>,
        bookmarks: Map<String, Bookmark>,
        out: Pieces,
    ) {
        val run = ArrayList<String>()
        fun flush() {
            if (run.isNotEmpty()) scanText(run.toList(), out)
            run.clear()
        }
        var i = start
        while (i < end) {
            val trimmed = lines[i].trim()

            val details = NoteMarkdown.DETAILS_OPEN.matchEntire(trimmed)
            val summary = if (details != null) {
                lines.getOrNull(i + 1)?.trim()?.let { NoteMarkdown.SUMMARY.matchEntire(it) }
            } else null
            if (details != null && summary != null) {
                flush()
                out.sections++
                var depth = 1
                var close = i + 2
                while (close < end) {
                    val candidate = lines[close].trim()
                    when {
                        NoteMarkdown.DETAILS_OPEN.matches(candidate) -> depth++
                        NoteMarkdown.DETAILS_CLOSE.matches(candidate) -> if (--depth == 0) break
                    }
                    close++
                }
                scanRange(lines, i + 2, close, known, bookmarks, out)
                i = if (close < end) close + 1 else end
                continue
            }

            val bookmarkId = NoteMarkdown.BOOKMARK_MARKER.matchEntire(trimmed)?.groupValues?.get(1)
            if (bookmarkId != null && bookmarkId in bookmarks) {
                flush()
                out.bookmarks.add(bookmarkId)
                i += 2
                continue
            }

            val noteId = NoteMarkdown.NOTE_LINE.matchEntire(trimmed)?.groupValues?.get(1)
            if (noteId != null) {
                flush()
                out.notes.add(noteId)
                i++
                continue
            }

            val attachmentId = NoteMarkdown.ATTACHMENT_LINE.matchEntire(trimmed)?.groupValues?.get(1)
            if (attachmentId != null && attachmentId in known) {
                flush()
                out.attachments.add(attachmentId)
                i++
                continue
            }

            run.add(NoteMarkdown.unescapeTextLine(lines[i]))
            i++
        }
        flush()
    }

    /** Un tramo de texto como `NoteMarkdown.appendText`: cuenta títulos, listas, citas y código. */
    private fun scanText(lines: List<String>, out: Pieces) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (CODE_FENCE.matches(trimmed)) {
                val close = (i + 1 until lines.size).firstOrNull { CODE_FENCE.matches(lines[it].trim()) } ?: return
                if (line == trimmed && lines[close] == FENCE) out.code++
                i = close + 1
                continue
            }
            if (trimmed.isEmpty()) {
                i++
                continue
            }
            if (DIVIDER.matches(trimmed) && trimmed != "****" && trimmed != "******") {
                i++
                continue
            }
            if (HEADING.containsMatchIn(line)) {
                out.headings++
                i++
                continue
            }
            if (QUOTE.containsMatchIn(line)) {
                out.quotes++
                while (i < lines.size && QUOTE.containsMatchIn(lines[i])) i++
                continue
            }
            val check = CHECKLIST.find(line)
            when {
                check != null -> {
                    out.items++
                    if (check.groupValues[2].equals("x", ignoreCase = true)) out.checked++ else out.unchecked++
                }
                BULLET.containsMatchIn(line) || NUMBERED.containsMatchIn(line) -> out.items++
            }
            i++
        }
    }

    // ---- Comparación ----

    private fun compareIds(
        before: List<String>,
        after: List<String>,
        reasons: MutableList<String>,
        what: String,
        label: (String) -> String,
    ) {
        val missing = ArrayList(before)
        after.forEach { missing.remove(it) }
        val extra = ArrayList(after)
        before.forEach { extra.remove(it) }
        missing.forEach { reasons.add("falta $what ${label(it)}") }
        extra.forEach { reasons.add("sobra $what ${label(it)}") }
    }

    private fun compareCount(what: String, before: Int, after: Int, reasons: MutableList<String>) {
        if (before != after) reasons.add("cambia la cantidad de $what: eran $before y quedaron $after")
    }
}
