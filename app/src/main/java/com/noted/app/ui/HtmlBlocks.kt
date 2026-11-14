package com.noted.app.ui

import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import com.noted.app.data.html.ATTACHMENT_URL
import com.noted.app.data.html.DivBlock
import com.noted.app.data.html.HtmlSanitizer
import com.noted.app.data.html.SECTION_COLLAPSED
import com.noted.app.data.html.SECTION_CONTENT
import com.noted.app.data.html.SECTION_CONTENT_INNER
import com.noted.app.data.html.SECTION_TITLE
import com.noted.app.data.html.SECTION_TITLE_INNER
import com.noted.app.data.html.SECTION_TITLE_WRAPPER
import com.noted.app.data.html.appendAttachment
import com.noted.app.data.html.appendBookmark
import com.noted.app.data.html.appendNoteLink
import com.noted.app.data.html.appendSection
import com.noted.app.data.html.divBlockOf
import com.noted.app.data.html.finishSection
import com.noted.app.data.html.noteLinkTitle
import com.noted.app.ui.rich.DIVIDER_CHAR
import com.noted.app.ui.rich.RichHtml
import com.noted.app.ui.rich.RichText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Los bloques del editor desde el HTML de la nota y de vuelta. Son las reglas de
 * hoy, leídas y escritas en HTML: los adjuntos, tarjetas y notas conocidos y los
 * desplegables son bloques propios; lo que queda entre ellos es un texto con formato.
 *
 * `serialize(parse(h)) == h` para el HTML que escribe la app.
 */
internal object HtmlBlocks {

    /**
     * [notAppendedAttachments] y [notAppendedBookmarks]: los que el cuerpo no nombra pero no se
     * agregan al final, porque se quitaron en el editor y solo los nombran fotos viejas de la pila
     * de deshacer de la nota ([NoteUndo.removedAttachmentIds], R13). Deshacer los devuelve.
     */
    fun parse(
        html: String,
        attachments: List<Attachment>,
        bookmarks: List<Bookmark>,
        notAppendedAttachments: Set<String> = emptySet(),
        notAppendedBookmarks: Set<String> = emptySet(),
    ): List<EditorBlock> {
        val result = ArrayList<EditorBlock>()
        val known = attachments.associateBy { it.id }
        val knownBookmarks = bookmarks.associateBy { it.id }
        readBlocks(Jsoup.parseBodyFragment(html).body().childNodes(), null, known, knownBookmarks, result)
        // Los renglones `data-checked` seguidos de cada texto son un bloque de checklist.
        BlockCommands.normalizeChecklists(result, null)

        // Adjuntos y tarjetas que existen pero el cuerpo no nombra (notas viejas o ediciones
        // interrumpidas): se agregan al final, en la raíz.
        val referenced = result.filterIsInstance<EditorBlock.Media>().map { it.attachmentId }.toSet()
        attachments.filterNot { it.id in referenced || it.id in notAppendedAttachments }
            .forEach { result.add(EditorBlock.Media("media-${it.id}", it.id)) }
        val referencedBookmarks = result.filterIsInstance<EditorBlock.Bookmark>().map { it.bookmarkId }.toSet()
        bookmarks.filterNot { it.id in referencedBookmarks || it.id in notAppendedBookmarks }.forEach {
            result.add(EditorBlock.Bookmark("bookmark-${it.id}", it.id))
        }

        ensureTextSlots(result)
        return result
    }

    fun serialize(
        blocks: List<EditorBlock>,
        attachments: List<Attachment>,
        bookmarks: List<Bookmark>,
        noteTitles: Map<String, String>,
    ): String {
        val document = Document.createShell("")
        document.outputSettings().prettyPrint(false)
        val writer = Writer(blocks, attachments.associateBy { it.id }, bookmarks.associateBy { it.id }, noteTitles)
        writer.write(blocks.filter { it.parentKey == null }.withoutAutoSlots(leading = true), document.body())
        return HtmlSanitizer.clean(document.body().html())
    }

    /**
     * El texto plano para compartir, renglón por renglón y sin viñetas ni símbolos: un adjunto
     * es `📎 nombre`, una nota su título, una tarjeta su dirección, un ítem de checklist su
     * texto y un desplegable `▸ título` con su contenido debajo.
     */
    fun shareText(
        blocks: List<EditorBlock>,
        attachments: List<Attachment>,
        bookmarks: List<Bookmark>,
        noteTitles: Map<String, String>,
    ): String {
        val known = attachments.associateBy { it.id }
        val knownBookmarks = bookmarks.associateBy { it.id }
        val children = blocks.filter { it.parentKey != null }.groupBy { it.parentKey!! }
        val lines = ArrayList<String>()
        fun share(list: List<EditorBlock>) {
            for (block in list) {
                when (block) {
                    is EditorBlock.Text -> lines.add(block.value.plain.replace(DIVIDER_CHAR.toString(), ""))
                    is EditorBlock.Media -> known[block.attachmentId]?.let { lines.add("📎 ${it.name}") }
                    is EditorBlock.Bookmark -> knownBookmarks[block.bookmarkId]?.let { lines.add(it.url) }
                    is EditorBlock.NoteLink -> lines.add(noteTitle(noteTitles[block.noteId]))
                    // Un renglón por ítem, en el orden guardado.
                    is EditorBlock.Checklist -> block.items.mapTo(lines) { it.value.plain }
                    is EditorBlock.Toggle -> {
                        lines.add("▸ " + toggleTitle(block.title))
                        share(children[block.key].orEmpty().withoutAutoSlots(leading = false))
                    }
                }
            }
        }
        share(blocks.filter { it.parentKey == null }.withoutAutoSlots(leading = true))
        return lines.joinToString("\n")
    }

    // ---- leer ----

    /**
     * Los bloques de [nodes]: cada `<div>` que es un bloque propio da su bloque, y los demás
     * nodos seguidos forman un texto.
     */
    private fun readBlocks(
        nodes: List<Node>,
        parentKey: String?,
        attachments: Map<String, Attachment>,
        bookmarks: Map<String, Bookmark>,
        result: MutableList<EditorBlock>,
    ) {
        val run = ArrayList<Node>()
        fun flush() {
            // Los espacios entre bloques (HTML con sangría) no son un texto.
            if (run.any { !(it is TextNode && it.isBlank) }) {
                result.add(EditorBlock.Text(newKey(), RichText(RichHtml.fromNodes(run)), parentKey))
            }
            run.clear()
        }

        for (node in nodes) {
            val element = node as? Element
            when (val own = element?.let { divBlockOf(it) }) {
                DivBlock.Section -> {
                    flush()
                    readSection(node as Element, parentKey, attachments, bookmarks, result)
                }
                is DivBlock.Card -> if (own.id in bookmarks) {
                    flush()
                    result.add(EditorBlock.Bookmark("bookmark-${own.id}", own.id, parentKey))
                } else {
                    // Una tarjeta desconocida es texto con su enlace.
                    run.add(node)
                }
                // La nota enlazada se conserva aunque ya no exista: el bloque muestra que falta.
                is DivBlock.NoteLink -> {
                    flush()
                    result.add(EditorBlock.NoteLink(newKey(), own.noteId, parentKey))
                }
                is DivBlock.File -> when {
                    own.id in attachments -> {
                        flush()
                        result.add(EditorBlock.Media("media-${own.id}", own.id, parentKey))
                    }
                    // Una imagen con un id que la nota no tiene queda como texto con su enlace,
                    // como hoy; sin texto el enlace no se escribiría, así que lleva el id.
                    own.image -> run.add(Element("div").appendChild(Element("a").attr("href", ATTACHMENT_URL + own.id).appendText(own.id)))
                    // Un archivo desconocido es texto con su enlace.
                    else -> run.add(node)
                }
                null -> run.add(node)
            }
        }
        flush()
    }

    /**
     * El desplegable: abierto si no tiene `noted-section-collapsed` y con el título de su `<h3>`.
     * Se ignora lo que la app no usa: el color, el texto de ayuda y `data-noted-section-content`.
     */
    private fun readSection(
        section: Element,
        parentKey: String?,
        attachments: Map<String, Attachment>,
        bookmarks: Map<String, Bookmark>,
        result: MutableList<EditorBlock>,
    ) {
        val heading = section.childWithClass(SECTION_TITLE_WRAPPER)?.childWithClass(SECTION_TITLE)
        val title = heading?.let { plainText(it.childWithClass(SECTION_TITLE_INNER) ?: it) }.orEmpty()
        val key = newKey()
        result.add(EditorBlock.Toggle(key, title, !section.hasClass(SECTION_COLLAPSED), parentKey))
        val content = section.childWithClass(SECTION_CONTENT) ?: return
        readBlocks((content.childWithClass(SECTION_CONTENT_INNER) ?: content).childNodes(), key, attachments, bookmarks, result)
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

    // ---- escribir ----

    private class Writer(
        blocks: List<EditorBlock>,
        val attachments: Map<String, Attachment>,
        val bookmarks: Map<String, Bookmark>,
        val noteTitles: Map<String, String>,
    ) {
        private val children = blocks.filter { it.parentKey != null }.groupBy { it.parentKey!! }

        fun write(list: List<EditorBlock>, parent: Element) {
            for (block in list) {
                when (block) {
                    // Un texto vacío en el medio es un renglón vacío, como hoy.
                    is EditorBlock.Text ->
                        if (block.isBlank()) parent.appendElement("div").appendElement("br")
                        else RichHtml.appendTo(parent, block.value.text)
                    // Los bloques propios. Un adjunto o una tarjeta que ya no existe no se escribe.
                    is EditorBlock.Media -> attachments[block.attachmentId]?.let { appendAttachment(it, parent) }
                    is EditorBlock.Bookmark -> bookmarks[block.bookmarkId]?.let { appendBookmark(it, parent) }
                    is EditorBlock.NoteLink ->
                        appendNoteLink(block.noteId, noteLinkTitle(noteTitles[block.noteId].orEmpty()), parent)
                    // El HTML de siempre; sin ítems no se escribe nada.
                    is EditorBlock.Checklist ->
                        if (block.items.isNotEmpty()) RichHtml.appendTo(parent, ChecklistOps.toRichText(block.items).text)
                    is EditorBlock.Toggle -> {
                        val content = appendSection(toggleTitle(block.title), block.isToggled, parent)
                        write(children[block.key].orEmpty().withoutAutoSlots(leading = false), content)
                        finishSection(content)
                    }
                }
            }
        }
    }

    private fun noteTitle(title: String?): String = title.orEmpty().replace("\n", " ").ifBlank { "Sin título" }

    private fun toggleTitle(title: String): String = title.replace("\n", " ").ifBlank { "Desplegable" }
}

/**
 * El cuerpo a guardar: si los bloques dan el mismo HTML que al abrir ([openedHtml]), el cuerpo
 * guardado tal cual ([openedBody]), así abrir y cerrar sin tocar no guarda ni crea versión,
 * aunque [HtmlBlocks] escriba distinto (por ejemplo, `<div><br></div>` solo da vacío).
 */
internal fun bodyToSave(html: String, openedHtml: String, openedBody: String): String =
    if (html == openedHtml) openedBody else html
