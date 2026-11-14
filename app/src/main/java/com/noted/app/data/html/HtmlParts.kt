package com.jot.app.data.html

import com.jot.app.data.Attachment
import com.jot.app.data.Bookmark
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// Las piezas del HTML de la nota que escriben igual el conversor Markdown (NoteMarkdown) y
// el campo con formato (RichHtml, HtmlBlocks): así el editor no depende del conversor.

// La sección plegable.
internal const val SECTION_CLASS = "jot-collapsible-section"
internal const val SECTION_COLLAPSED = "jot-section-collapsed"
internal const val SECTION_TITLE_WRAPPER = "jot-section-title-wrapper"
internal const val SECTION_TITLE = "jot-section-title"
internal const val SECTION_TITLE_INNER = "jot-section-title-inner"
internal const val SECTION_CONTENT = "jot-section-content"
internal const val SECTION_CONTENT_INNER = "jot-section-content-inner"
private const val PLACEHOLDER = "jot-placeholder"
private const val PLACEHOLDER_KEY = "data-jot-placeholder-key"
internal const val PLACEHOLDER_TEXT = "data-jot-placeholder"

// Las direcciones de un adjunto y de otra nota.
internal const val ATTACHMENT_URL = "jot://att/"
internal const val NOTE_URL = "jot://open-note?id="

// ---- bloques propios: adjunto, tarjeta, nota y desplegable ----

/** Lo que es un `<div>` del cuerpo cuando no es texto, sin mirar todavía si su id es conocido. */
internal sealed interface DivBlock {
    /** `div.jot-collapsible-section`. */
    data object Section : DivBlock

    /** `div[data-jot-bookmark]`. */
    data class Card(val id: String) : DivBlock

    /** Un `<div>` con solo `a[data-note-id]`; [title] es el texto del enlace. */
    data class NoteLink(val noteId: String, val title: String) : DivBlock

    /** Un `<div>` con solo `<img src="jot://att/…">` ([image]) o `a[data-file-id]`. */
    data class File(val id: String, val image: Boolean) : DivBlock
}

/** Qué bloque propio es [div], o `null` si es texto. Los espacios de sangría no cuentan. */
internal fun divBlockOf(div: Element): DivBlock? {
    if (div.normalName() != "div") return null
    if (div.hasClass(SECTION_CLASS)) return DivBlock.Section
    if (div.hasAttr("data-jot-bookmark")) return DivBlock.Card(div.attr("data-jot-bookmark"))
    val only = div.childNodes().filterNot { it is TextNode && it.isBlank }.singleOrNull() as? Element ?: return null
    return when {
        only.normalName() == "img" && only.attr("src").startsWith(ATTACHMENT_URL) ->
            DivBlock.File(only.attr("src").removePrefix(ATTACHMENT_URL), image = true)
        only.normalName() == "a" && only.hasAttr("data-file-id") -> DivBlock.File(only.attr("data-file-id"), image = false)
        only.normalName() == "a" && only.hasAttr("data-note-id") -> DivBlock.NoteLink(only.attr("data-note-id"), only.wholeText())
        else -> null
    }
}

/** El adjunto: una imagen con su `<img>` y otro archivo con su enlace y su nombre. */
internal fun appendAttachment(attachment: Attachment, parent: Element) {
    val div = parent.appendElement("div")
    if (attachment.isImage) {
        div.appendElement("img").attr("src", ATTACHMENT_URL + attachment.id)
    } else {
        div.appendElement("a")
            .attr("data-file-id", attachment.id)
            .attr("data-non-editable", "true")
            .attr("href", ATTACHMENT_URL + attachment.id)
            .appendTextIfAny(attachment.name)
    }
}

/** La tarjeta de enlace con su dirección y su texto. */
internal fun appendBookmark(id: String, href: String, text: String, parent: Element) {
    parent.appendElement("div")
        .attr("data-jot-bookmark", id)
        .appendElement("a")
        .attr("href", href)
        .appendTextIfAny(text)
}

/** La tarjeta de una de la lista: sin título, el texto es la dirección. */
internal fun appendBookmark(bookmark: Bookmark, parent: Element) =
    appendBookmark(bookmark.id, bookmark.url, bookmark.title.ifBlank { bookmark.url }, parent)

/** Otra nota dentro de esta, con [title] ya pasado por [noteLinkTitle]. */
internal fun appendNoteLink(noteId: String, title: String, parent: Element) {
    parent.appendElement("div").appendElement("a")
        .attr("data-note-id", noteId)
        .attr("data-non-editable", "true")
        .attr("href", NOTE_URL + noteId)
        .appendText(title)
}

/** El título de una nota como lo escribe el editor: "]" como "］", en un renglón y, vacío, "Sin título". */
internal fun noteLinkTitle(title: String): String =
    title.replace("]", "］").replace("\n", " ").ifBlank { "Sin título" }

private fun Element.appendTextIfAny(text: String): Element = if (text.isEmpty()) this else appendText(text)

/**
 * El contenido de [count] renglones de un mismo bloque, separados por `<br>`; [content]
 * escribe el renglón `index` en el bloque. Si el último está vacío ([lastIsEmpty]) lleva un
 * `<br>` más, porque el navegador no dibuja el `<br>` final de un bloque: así un renglón
 * vacío es `<div><br></div>`.
 */
internal fun appendLinesWithBr(
    block: Element,
    count: Int,
    lastIsEmpty: Boolean,
    content: (index: Int, into: Element) -> Unit,
) {
    for (index in 0 until count) {
        if (index > 0) block.appendElement("br")
        content(index, block)
    }
    if (lastIsEmpty) block.appendElement("br")
}

/**
 * Bloque de código con el texto y sus saltos. El parser de HTML se come un salto pegado
 * a `<pre>`, así que si el código empieza con un renglón vacío ese salto va como `<br>`.
 */
internal fun appendCode(language: String, lines: List<String>, parent: Element) {
    val pre = parent.appendElement("pre").attr("spellcheck", "false")
    if (language.isNotEmpty()) pre.attr("data-language", language)
    var text = lines.joinToString("\n")
    if (lines.lastOrNull() == "") text += "\n"
    if (text.startsWith("\n")) {
        pre.appendElement("br")
        text = text.substring(1)
    }
    if (text.isNotEmpty()) pre.appendText(text)
}

/**
 * Arma las listas: una `<ul>` u `<ol>` por nivel, y la de un nivel más adentro va
 * dentro de la de afuera, después del `<li>` del que cuelga.
 */
internal class ListBuilder(private val parent: Element) {
    private class Open(val level: Int, val tag: String, val element: Element) {
        var items = 0
    }

    private val open = ArrayList<Open>()

    fun close() = open.clear()

    /** Un ítem nuevo en el nivel [level] de una lista [tag]; devuelve su `<li>`. */
    fun item(level: Int, tag: String, number: Int): Element {
        while (open.isNotEmpty() && open.last().level > level) open.removeAt(open.lastIndex)
        if (open.isNotEmpty() && open.last().level == level && open.last().tag != tag) {
            open.removeAt(open.lastIndex)
        }
        // Un ítem que salta niveles deja listas sin ítems en el medio.
        while ((open.lastOrNull()?.level ?: -1) < level) {
            val outer = open.lastOrNull()
            val container = outer?.element ?: parent
            open.add(Open((outer?.level ?: -1) + 1, tag, container.appendElement(tag)))
        }
        val list = open.last()
        if (tag == "ol" && list.items == 0 && number != 1) list.element.attr("start", number.toString())
        list.items++
        return list.element.appendElement("li")
    }
}

/** La sección plegable con su título; devuelve el `div` donde van los hijos. */
internal fun appendSection(title: String, open: Boolean, parent: Element): Element {
    val section = parent.appendElement("div").addClass(SECTION_CLASS)
    if (!open) section.addClass(SECTION_COLLAPSED)
    val heading = section.appendElement("div").addClass(SECTION_TITLE_WRAPPER)
        .appendElement("div").addClass(SECTION_TITLE).addClass(PLACEHOLDER).attr(PLACEHOLDER_KEY, "title")
        .appendElement("div").addClass(SECTION_TITLE_INNER)
        .appendElement("h3")
    if (title.isEmpty()) heading.appendElement("br") else heading.appendText(title)
    return section.appendElement("div").addClass(SECTION_CONTENT).addClass(PLACEHOLDER).attr(PLACEHOLDER_KEY, "content")
        .appendElement("div").addClass(SECTION_CONTENT_INNER)
}

/**
 * Sin hijos, el cuerpo es un renglón vacío, como el de una sección nueva.
 * Y el título o el cuerpo sin texto llevan el texto de ayuda.
 */
internal fun finishSection(content: Element) {
    if (content.childNodeSize() == 0) content.appendElement("div").appendElement("br")
    val section = content.parent()!!.parent()!!
    val title = section.child(0).child(0)
    if (needsPlaceholder(title)) title.attr(PLACEHOLDER_TEXT, "Title")
    val body = content.parent()!!
    if (needsPlaceholder(body)) body.attr(PLACEHOLDER_TEXT, "Content")
}

private fun needsPlaceholder(box: Element): Boolean =
    !hasText(box) && box.select("pre, table, img, code, li, blockquote").isEmpty()

/** Si tiene `textContent`: un `<br>` no es texto. */
private fun hasText(node: Node): Boolean =
    if (node is TextNode) node.wholeText.isNotEmpty() else node.childNodes().any { hasText(it) }
