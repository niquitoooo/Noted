package com.jot.app.data.html

import com.jot.app.data.Attachment
import com.jot.app.data.Note
import com.jot.app.data.NotesRepository
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Lo que se saca del cuerpo en HTML: el texto para buscar, el resumen, las
 * tareas, las notas de adentro y las etiquetas. También las quitas de bloques.
 */
object NoteHtml {

    private const val SUMMARY_MAX = 200

    // Lo que empieza un renglón nuevo.
    private val BLOCKS = setOf(
        "div", "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "ul", "ol", "li", "hr",
        "table", "thead", "tbody", "tr", "th", "td", "center", "article", "section", "dt",
    )

    private const val NOTE_LINK = "a[data-note-id]"
    private const val FILE_LINK = "a[data-file-id]"

    // Las direcciones propias de la app (adjuntos y notas), que no son para buscar.
    private val INTERNAL_URLS = listOf("jot://")

    /**
     * El cuerpo como texto, un renglón por bloque, con el mismo criterio que
     * `searchableText`: de un adjunto queda su nombre (el de una imagen sale de
     * [attachments]) y de un desplegable su título; los enlaces a nota no cuentan; no hay
     * renglones vacíos repetidos ni al principio o al final.
     */
    fun plainText(html: String, attachments: List<Attachment> = emptyList()): String =
        plainText(body(html), attachments.associate { it.id to it.name })

    /** El primer renglón no vacío de [plainText] (sin adjuntos), hasta 200 caracteres. */
    fun summary(html: String): String = summary(body(html))

    private fun summary(body: Element): String =
        plainText(body, emptyMap()).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            .orEmpty().take(SUMMARY_MAX)

    /** Si hay alguna tarea sin marcar. */
    fun hasTodo(html: String): Boolean = hasTodo(body(html))

    private fun hasTodo(body: Element): Boolean = body.select("li[data-checked=false]").isNotEmpty()

    /** Los ids de los bloques "nota" (un `<div>` con solo el enlace a la nota), en orden. */
    fun noteLinkBlockIds(html: String): List<String> = noteLinkBlockIds(body(html))

    private fun noteLinkBlockIds(body: Element): List<String> =
        body.select("div").mapNotNull { noteLinkOf(it)?.attr("data-note-id") }

    /**
     * Las direcciones de los enlaces de párrafos y tarjetas, una por renglón, para que la
     * búsqueda las encuentre como antes, cuando el cuerpo era Markdown y la dirección estaba
     * en el texto. No cuentan los enlaces a nota ni a adjunto.
     */
    fun linkText(html: String): String = linkText(body(html))

    private fun linkText(body: Element): String =
        body.select("a[href]")
            .filterNot { it.`is`(NOTE_LINK) || it.`is`(FILE_LINK) }
            .map { it.attr("href") }
            .filterNot { it.isBlank() || INTERNAL_URLS.any { prefix -> it.startsWith(prefix) } }
            .joinToString("\n")

    /** Las etiquetas del texto del cuerpo, sin contar código ni enlaces. */
    fun tags(html: String): List<String> = tags(body(html))

    /** Ojo: le saca a [body] el código y los enlaces. */
    private fun tags(body: Element): List<String> {
        body.select("pre, code, a").remove()
        return NotesRepository.parseTags(plainText(body, emptyMap()))
    }

    /** [note] con los campos que salen del cuerpo, leyendo el HTML una sola vez. */
    internal fun derive(note: Note): Note {
        val body = body(note.body)
        val names = note.attachments.associate { it.id to it.name }
        return note.copy(
            plainText = plainText(body, names),
            summary = summary(body),
            hasTodo = hasTodo(body),
            noteLinkIds = noteLinkBlockIds(body),
            linkText = linkText(body),
            // Al final: les saca el código y los enlaces al cuerpo.
            tags = tags(body),
        )
    }

    /** El cuerpo sin los bloques "nota" de [noteId]. */
    fun removeNoteLinkBlocks(html: String, noteId: String): String {
        val body = body(html)
        body.select("div").filter { noteLinkOf(it)?.attr("data-note-id") == noteId }.forEach { it.remove() }
        refillEmptySections(body)
        return body.html()
    }

    /** El cuerpo sin el `<div>` de la imagen o del archivo [attachmentId]. */
    fun removeAttachment(html: String, attachmentId: String): String {
        val body = body(html)
        val url = NoteMarkdown.ATTACHMENT_URL + attachmentId
        body.select("img").filter { it.attr("src") == url }
            .plus(body.select(FILE_LINK).filter { it.attr("data-file-id") == attachmentId })
            .forEach { (it.parent()?.takeIf { div -> div != body && onlyChild(div) == it } ?: it).remove() }
        refillEmptySections(body)
        return body.html()
    }

    /**
     * Un desplegable que se quedó sin nada adentro queda como uno nuevo: un renglón vacío y el
     * texto de ayuda, igual que lo arma `NoteMarkdown.toHtml`.
     */
    private fun refillEmptySections(body: Element) {
        body.select(".${NoteMarkdown.CONTENT_INNER}")
            .filter { inner -> inner.childNodes().all { isBlankText(it) } }
            .forEach { inner ->
                inner.empty().appendElement("div").appendElement("br")
                inner.parent()?.attr(NoteMarkdown.PLACEHOLDER_TEXT, "Content")
            }
    }

    /** El único hijo de un `<div>` (los espacios no cuentan), si tiene uno solo. */
    private fun onlyChild(div: Element): Element? =
        if (div.normalName() != "div") null
        else div.childNodes().filterNot { it is TextNode && it.isBlank }.singleOrNull() as? Element

    /**
     * Lo mismo que `isBlank()` sobre el Markdown viejo: solo `<div>` sin texto,
     * con nada más que `<br>` adentro. Una casilla, un título, una viñeta, una cita o un bloque
     * de código vacíos no están en blanco, como tampoco adjuntos, notas, tarjetas,
     * desplegables ni separadores.
     */
    fun isBlank(html: String): Boolean = body(html).childNodes().all { node ->
        isBlankText(node) ||
            (node is Element && node.normalName() == "div" &&
                node.childNodes().all { isBlankText(it) || (it is Element && it.normalName() == "br") })
    }

    private fun isBlankText(node: Node): Boolean = node is TextNode && node.isBlank

    private fun body(html: String): Element {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        return document.body()
    }

    /** El enlace a nota si [div] tiene solo eso (los espacios no cuentan). */
    private fun noteLinkOf(div: Element): Element? = onlyChild(div)?.takeIf { it.`is`(NOTE_LINK) }

    private fun plainText(body: Element, names: Map<String, String>): String {
        val lines = ArrayList<String>()
        appendBlocks(body, lines, names)
        val out = ArrayList<String>()
        for (line in lines) {
            val blank = line.isBlank()
            if (blank && (out.isEmpty() || out.last().isEmpty())) continue
            out.add(if (blank) "" else line)
        }
        if (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }

    /**
     * Los hijos de [parent]: lo que está en línea va al renglón en curso y cada bloque
     * empieza otro. El `<br>` final de un bloque no agrega renglón (así se dibuja).
     */
    private fun appendBlocks(parent: Element, lines: MutableList<String>, names: Map<String, String>) {
        val text = StringBuilder()
        var hasInline = false
        fun flush() {
            if (hasInline) lines.addAll(text.toString().removeSuffix("\n").split('\n'))
            text.clear()
            hasInline = false
        }
        for (node in parent.childNodes()) {
            if (node is Element && node.normalName() in BLOCKS) {
                flush()
                appendBlock(node, lines, names)
            } else if (appendInline(node, text, names)) {
                hasInline = true
            }
        }
        flush()
    }

    private fun appendBlock(block: Element, lines: MutableList<String>, names: Map<String, String>) {
        when {
            block.normalName() == "hr" -> Unit
            block.hasClass(NoteMarkdown.SECTION) -> {
                block.selectFirst(".${NoteMarkdown.TITLE_INNER}")?.let { appendBlocks(it, lines, names) }
                block.selectFirst(".${NoteMarkdown.CONTENT_INNER}")?.let { appendBlocks(it, lines, names) }
            }
            else -> appendBlocks(block, lines, names)
        }
    }

    /** Agrega el texto de [node] y dice si aportó algo (un espacio suelto no aporta). */
    private fun appendInline(node: Node, text: StringBuilder, names: Map<String, String>): Boolean =
        when {
            node is TextNode -> {
                text.append(node.wholeText)
                !node.isBlank
            }
            node !is Element -> false
            node.normalName() == "br" -> {
                text.append('\n')
                true
            }
            node.normalName() == "img" -> {
                val name = names[node.attr("src").removePrefix(NoteMarkdown.ATTACHMENT_URL)]
                if (name != null) text.append(name)
                name != null
            }
            node.`is`(NOTE_LINK) -> false
            else -> node.childNodes().map { appendInline(it, text, names) }.any { it }
        }
}

/** La nota con los campos que salen del cuerpo en HTML. */
fun Note.withDerived(): Note = NoteHtml.derive(this)
