package com.noted.app.data.html

import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMarkdownBlocksTest {

    private val photo = Attachment("p1", "n1", "foto.jpg", "image/jpeg", 10, "p1.jpg", 0)
    private val report = Attachment("f1", "n1", "informe (final).pdf", "application/pdf", 10, "f1.pdf", 0)
    private val attachments = listOf(photo, report)

    private fun toHtml(markdown: String) = NoteMarkdown.toHtml(markdown, attachments)

    private fun fromHtml(html: String) = NoteMarkdown.fromHtml(html, attachments)

    /** El par exacto en los dos sentidos. */
    private fun assertPair(markdown: String, html: String) {
        assertEquals(html, toHtml(markdown))
        assertEquals(markdown, fromHtml(html))
    }

    /** Una forma no canónica: va al HTML de la canónica, y la canónica vuelve igual. */
    private fun assertNormalizes(markdown: String, canonical: String, html: String) {
        assertEquals(html, toHtml(markdown))
        assertEquals(canonical, fromHtml(html))
        assertEquals(html, toHtml(canonical))
    }

    private val photoHtml = "<div><img src=\"noted://att/p1\"></div>"
    private val reportHtml = "<div><a data-file-id=\"f1\" data-non-editable=\"true\" " +
        "href=\"noted://att/f1\">informe (final).pdf</a></div>"

    /** La sección plegable, con el texto de ayuda si está vacía. */
    private fun section(title: String, content: String, open: Boolean = true): String {
        val collapsed = if (open) "" else " noted-section-collapsed"
        val titleHelp = if (title == "<h3><br></h3>") " data-noted-placeholder=\"Title\"" else ""
        val contentHelp = if (content == "<div><br></div>") " data-noted-placeholder=\"Content\"" else ""
        return "<div class=\"noted-collapsible-section$collapsed\">" +
            "<div class=\"noted-section-title-wrapper\">" +
            "<div class=\"noted-section-title noted-placeholder\" data-noted-placeholder-key=\"title\"$titleHelp>" +
            "<div class=\"noted-section-title-inner\">$title</div></div></div>" +
            "<div class=\"noted-section-content noted-placeholder\" data-noted-placeholder-key=\"content\"$contentHelp>" +
            "<div class=\"noted-section-content-inner\">$content</div></div></div>"
    }

    @Test
    fun imageAttachment() {
        assertPair("[foto.jpg](noted://att/p1)", photoHtml)
        assertPair("a\n[foto.jpg](noted://att/p1)\nb", "<div>a</div>$photoHtml<div>b</div>")
        // El nombre sale de attachments, con el reemplazo de "]" y ")" del editor.
        val odd = Attachment("p2", "n1", "a]b)c.png", "image/png", 1, "p2.png", 0)
        assertEquals(
            "[a］b）c.png](noted://att/p2)",
            NoteMarkdown.fromHtml("<div><img src=\"noted://att/p2\"></div>", listOf(odd)),
        )
        // Con otro nombre en el renglón, vale el de attachments (el editor lo reescribe al guardar).
        assertNormalizes("[vieja.jpg](noted://att/p1)", "[foto.jpg](noted://att/p1)", photoHtml)
        // Con sangría, el editor lo lee como adjunto y lo guarda sin ella.
        assertNormalizes("  [foto.jpg](noted://att/p1)", "[foto.jpg](noted://att/p1)", photoHtml)
        // Un adjunto que el cuerpo no nombra no se agrega al HTML (el editor lo suma al final al abrir).
        assertEquals("<div>hola</div>", toHtml("hola"))
    }

    @Test
    fun fileAttachment() {
        assertPair("[informe (final）.pdf](noted://att/f1)", reportHtml)
        assertEquals("[informe (final）.pdf](noted://att/f1)", NoteMarkdown.attachmentToken(report))
        assertNormalizes("[otro.pdf](noted://att/f1)", "[informe (final）.pdf](noted://att/f1)", reportHtml)
    }

    @Test
    fun unknownAttachmentStaysAsLink() {
        // El editor deja el renglón como texto y al guardar lo escapa: las dos formas
        // dan el mismo HTML, un renglón de texto con su enlace.
        val html = "<div><a href=\"noted://att/7\">a</a></div>"
        assertNormalizes("[a](noted://att/7)", "\\[a](noted://att/7)", html)
        assertEquals(html, toHtml(fromHtml(html)))
        // Un archivo o una imagen del HTML con un id que la nota no tiene quedan como texto con su enlace.
        assertEquals(
            "\\[a](noted://att/7)",
            fromHtml("<div><a data-file-id=\"7\" data-non-editable=\"true\" href=\"noted://att/7\">a</a></div>"),
        )
        assertEquals("\\[](noted://att/7)", fromHtml("<div><img src=\"noted://att/7\"></div>"))
        // Sin attachments, ni la imagen conocida es adjunto.
        assertEquals(
            "<div><a href=\"noted://att/p1\">foto.jpg</a></div>",
            NoteMarkdown.toHtml("[foto.jpg](noted://att/p1)", emptyList()),
        )
    }

    @Test
    fun noteLink() {
        assertPair(
            "[Receta [de］ (mamá)](noted://note/n7)",
            "<div><a data-note-id=\"n7\" data-non-editable=\"true\" " +
                "href=\"noted://open-note?id=n7\">Receta [de］ (mamá)</a></div>",
        )
        assertEquals(
            "[Receta [de］ (mamá)](noted://note/n7)",
            NoteMarkdown.noteLinkToken("n7", "Receta [de] (mamá)"),
        )
        // Las marcas del título no son formato.
        assertPair(
            "[**a**](noted://note/n7)",
            "<div><a data-note-id=\"n7\" data-non-editable=\"true\" " +
                "href=\"noted://open-note?id=n7\">**a**</a></div>",
        )
        // Sin título, el editor escribe "Sin título".
        assertNormalizes(
            "[](noted://note/n7)",
            "[Sin título](noted://note/n7)",
            "<div><a data-note-id=\"n7\" data-non-editable=\"true\" " +
                "href=\"noted://open-note?id=n7\">Sin título</a></div>",
        )
    }

    @Test
    fun bookmarkTwoLines() {
        assertPair(
            "<!-- noted:bookmark b1 -->\n[Mate (bebida) *rico*](https://es.wikipedia.org/wiki/Mate_(bebida）)",
            "<div data-noted-bookmark=\"b1\"><a href=\"https://es.wikipedia.org/wiki/Mate_(bebida）\">" +
                "Mate (bebida) *rico*</a></div>",
        )
        assertPair(
            "antes\n<!-- noted:bookmark b1 -->\n[t](https://x.com)\ndespués",
            "<div>antes</div><div data-noted-bookmark=\"b1\"><a href=\"https://x.com\">t</a></div><div>después</div>",
        )
        // Sin un enlace en el renglón siguiente no es tarjeta: queda como texto.
        assertNormalizes(
            "<!-- noted:bookmark b1 -->\nhola",
            "\\<!-- noted:bookmark b1 -->\nhola",
            "<div>&lt;!-- noted:bookmark b1 --&gt;</div><div>hola</div>",
        )
    }

    @Test
    fun bookmarkWithListFollowsEditor() {
        val mate = Bookmark(id = "b1", url = "https://x.com/a(b)", title = "Mate [rico]")
        val list = listOf(mate)
        fun toHtml(markdown: String) = NoteMarkdown.toHtml(markdown, attachments, list)
        fun fromHtml(html: String) = NoteMarkdown.fromHtml(html, attachments, list)
        val card = "<div data-noted-bookmark=\"b1\"><a href=\"https://x.com/a(b)\">Mate [rico]</a></div>"
        val canonical = "<!-- noted:bookmark b1 -->\n[Mate [rico］](https://x.com/a(b）)"

        // Id conocido: la tarjeta sale de la lista, como la guarda serializeBlocks.
        assertEquals(card, toHtml(canonical))
        assertEquals(canonical, fromHtml(card))
        // Con un renglón siguiente que no es el enlace de la tarjeta, ese renglón queda como texto.
        assertEquals("$card<div>hola</div>", toHtml("<!-- noted:bookmark b1 -->\nhola"))
        assertEquals("<div>a</div>$card<div>hola</div><div>b</div>", toHtml("a\n<!-- noted:bookmark b1 -->\nhola\nb"))
        assertEquals("<div>a</div>$card<div>b</div>", toHtml("a\n<!-- noted:bookmark b1 -->\n[viejo](https://viejo)\nb"))
        // Al final del cuerpo, sin renglón siguiente, también es tarjeta.
        assertEquals("<div>a</div>$card", toHtml("a\n<!-- noted:bookmark b1 -->"))
        // Sin título, el editor escribe la dirección.
        val untitled = Bookmark(id = "b2", url = "https://y.com", title = "")
        assertEquals(
            "<!-- noted:bookmark b2 -->\n[https://y.com](https://y.com)",
            NoteMarkdown.fromHtml(NoteMarkdown.toHtml("<!-- noted:bookmark b2 -->\n[](u)", emptyList(), listOf(untitled)), emptyList(), listOf(untitled)),
        )

        // (a) Id desconocido: el marcador es texto y el enlace un renglón de texto; vuelve escapado.
        val unknown = "<div>&lt;!-- noted:bookmark b9 --&gt;</div><div><a href=\"https://x.com\">t</a></div>"
        assertEquals(unknown, toHtml("<!-- noted:bookmark b9 -->\n[t](https://x.com)"))
        assertEquals("\\<!-- noted:bookmark b9 -->\n[t](https://x.com)", fromHtml(unknown))
        assertEquals(unknown, toHtml(fromHtml(unknown)))
        // Una tarjeta del HTML que no está en la lista también queda como texto.
        assertEquals(
            "\\<!-- noted:bookmark b9 -->\n[t](https://x.com)",
            fromHtml("<div data-noted-bookmark=\"b9\"><a href=\"https://x.com\">t</a></div>"),
        )
        // Dentro de un desplegable, igual.
        assertEquals(
            "<details open>\n<summary>T</summary>\n$canonical\nhola\n</details>",
            fromHtml(toHtml("<details open>\n<summary>T</summary>\n<!-- noted:bookmark b1 -->\nhola\n</details>")),
        )

        // Sin lista (null) sigue como antes: marcador seguido de su enlace es tarjeta.
        val before = "<!-- noted:bookmark b9 -->\n[t](https://x.com)"
        val beforeHtml = "<div data-noted-bookmark=\"b9\"><a href=\"https://x.com\">t</a></div>"
        assertEquals(beforeHtml, NoteMarkdown.toHtml(before, attachments, null))
        assertEquals(before, NoteMarkdown.fromHtml(beforeHtml, attachments, null))
        assertEquals(NoteMarkdown.toHtml(before, attachments), NoteMarkdown.toHtml(before, attachments, null))
    }

    @Test
    fun toggleOpenAndClosed() {
        val open = "<div class=\"noted-collapsible-section\"><div class=\"noted-section-title-wrapper\">" +
            "<div class=\"noted-section-title noted-placeholder\" data-noted-placeholder-key=\"title\">" +
            "<div class=\"noted-section-title-inner\"><h3>T</h3></div></div></div>" +
            "<div class=\"noted-section-content noted-placeholder\" data-noted-placeholder-key=\"content\">" +
            "<div class=\"noted-section-content-inner\"><div>x</div></div></div></div>"
        assertPair("<details open>\n<summary>T</summary>\nx\n</details>", open)
        assertPair(
            "<details>\n<summary>T</summary>\nx\n</details>",
            open.replace("\"noted-collapsible-section\"", "\"noted-collapsible-section noted-section-collapsed\""),
        )
        // El título es texto: sus marcas no son formato y &, < y > van escapados como los guarda el editor.
        assertPair(
            "<details open>\n<summary>**a** &amp; &lt;b&gt;</summary>\nx\n</details>",
            section("<h3>**a** &amp; &lt;b&gt;</h3>", "<div>x</div>"),
        )
        // Lo que el editor normaliza al guardar.
        assertNormalizes(
            "<DETAILS  OPEN>\n  <summary>T</summary>\nx\n</details>",
            "<details open>\n<summary>T</summary>\nx\n</details>",
            open,
        )
        assertNormalizes(
            "<details open>\n<summary>a & b</summary>\nx\n</details>",
            "<details open>\n<summary>a &amp; b</summary>\nx\n</details>",
            section("<h3>a &amp; b</h3>", "<div>x</div>"),
        )
        // Sin </details>, el desplegable llega hasta el final y el editor lo cierra al guardar.
        assertNormalizes(
            "<details open>\n<summary>T</summary>\nx",
            "<details open>\n<summary>T</summary>\nx\n</details>",
            open,
        )
        // Lo que la app no usa se ignora sin perder el resto.
        val withExtras = open
            .replace("\"noted-collapsible-section\"", "\"noted-collapsible-section noted-section-highlight-red noted-section-collapsed\" data-noted-section-content=\"&lt;div&gt;viejo&lt;/div&gt;\"")
        assertEquals("<details>\n<summary>T</summary>\nx\n</details>", fromHtml(withExtras))
    }

    @Test
    fun emptyToggleUsesPlaceholders() {
        val empty = section("<h3><br></h3>", "<div><br></div>")
        assertEquals(
            "<div class=\"noted-collapsible-section\"><div class=\"noted-section-title-wrapper\">" +
                "<div class=\"noted-section-title noted-placeholder\" data-noted-placeholder-key=\"title\" " +
                "data-noted-placeholder=\"Title\"><div class=\"noted-section-title-inner\"><h3><br></h3></div></div></div>" +
                "<div class=\"noted-section-content noted-placeholder\" data-noted-placeholder-key=\"content\" " +
                "data-noted-placeholder=\"Content\"><div class=\"noted-section-content-inner\"><div><br></div></div></div></div>",
            empty,
        )
        assertPair("<details open>\n<summary></summary>\n</details>", empty)
        assertPair("<details open>\n<summary>T</summary>\n</details>", section("<h3>T</h3>", "<div><br></div>"))
        assertPair(
            "<details open>\n<summary></summary>\nx\n</details>",
            section("<h3><br></h3>", "<div>x</div>"),
        )
        // Un solo renglón vacío adentro es un cuerpo vacío: el editor no lo guarda.
        assertNormalizes(
            "<details open>\n<summary>T</summary>\n\n</details>",
            "<details open>\n<summary>T</summary>\n</details>",
            section("<h3>T</h3>", "<div><br></div>"),
        )
        // Con una imagen ya no lleva texto de ayuda; con solo una raya sí, porque no tiene
        // texto ni pre, table, img, code, li o blockquote.
        assertPair(
            "<details open>\n<summary>T</summary>\n[foto.jpg](noted://att/p1)\n</details>",
            section("<h3>T</h3>", photoHtml),
        )
        assertPair(
            "<details open>\n<summary>T</summary>\n---\n</details>",
            section("<h3>T</h3>", "<hr>").replace(
                "data-noted-placeholder-key=\"content\"",
                "data-noted-placeholder-key=\"content\" data-noted-placeholder=\"Content\"",
            ),
        )
        // Un título de solo espacios tiene texto: no lleva ayuda.
        assertPair("<details open>\n<summary> </summary>\nx\n</details>", section("<h3> </h3>", "<div>x</div>"))
    }

    @Test
    fun nestedTogglesRoundTrip() {
        val markdown = listOf(
            "antes",
            "<details open>",
            "<summary>Afuera</summary>",
            "- uno",
            "  - dos",
            "[foto.jpg](noted://att/p1)",
            "1. tres",
            "<details>",
            "<summary>Adentro</summary>",
            "- [x] cuatro",
            "[informe (final）.pdf](noted://att/f1)",
            "<details open>",
            "<summary>Más adentro</summary>",
            "cinco",
            "</details>",
            "</details>",
            "seis",
            "</details>",
            "después",
        ).joinToString("\n")
        val html = toHtml(markdown)
        assertEquals(markdown, fromHtml(html))
        assertEquals(html, toHtml(fromHtml(html)))

        // El orden y el anidado en el HTML.
        val body = Jsoup.parseBodyFragment(html).body()
        assertEquals(listOf("div", "section", "div"), body.children().map { shape(it) })
        val outer = body.child(1)
        assertEquals(listOf("ul", "img", "ol", "section", "div"), contentOf(outer).map { shape(it) })
        val inner = contentOf(outer)[3]
        assertEquals(true, inner.hasClass("noted-section-collapsed"))
        assertEquals(listOf("ul", "file", "section"), contentOf(inner).map { shape(it) })
        val innermost = contentOf(inner)[2]
        assertEquals("Más adentro", innermost.selectFirst("h3")!!.text())
        assertEquals(listOf("div"), contentOf(innermost).map { shape(it) })
        assertEquals("seis", contentOf(outer)[4].text())
    }

    private fun contentOf(section: Element): List<Element> =
        section.children()[1].children()[0].children()

    private fun shape(element: Element): String = when {
        element.hasClass("noted-collapsible-section") -> "section"
        element.selectFirst("> img") != null -> "img"
        element.selectFirst("> a[data-file-id]") != null -> "file"
        else -> element.normalName()
    }

    @Test
    fun escapedTokenLineStaysText() {
        assertPair("\\[a](noted://note/1)", "<div><a href=\"noted://note/1\">a</a></div>")
        // Con el id conocido, escapado sigue siendo texto.
        assertPair("\\[foto.jpg](noted://att/p1)", "<div><a href=\"noted://att/p1\">foto.jpg</a></div>")
        assertPair(
            "\\<details open>\n\\<summary>T</summary>\n\\</details>",
            "<div>&lt;details open&gt;</div><div>&lt;summary&gt;T&lt;/summary&gt;</div><div>&lt;/details&gt;</div>",
        )
        assertPair(
            "\\<!-- noted:bookmark b1 -->\n[t](https://x.com)",
            "<div>&lt;!-- noted:bookmark b1 --&gt;</div><div><a href=\"https://x.com\">t</a></div>",
        )
        // Un </details> suelto es texto; el editor lo escapa al guardar.
        assertNormalizes("</details>", "\\</details>", "<div>&lt;/details&gt;</div>")
    }

    @Test
    fun tokensCutTextRuns() {
        // Como en el editor, cada tramo de texto entre bloques se dibuja por separado: un cerrojo
        // cortado por un adjunto queda sin cerrar y la numeración no lo cruza.
        assertPair(
            "```\na\n[foto.jpg](noted://att/p1)\nb\n```",
            "<div>```</div><div>a</div>$photoHtml<div>b</div><div>```</div>",
        )
        assertPair(
            "1. a\n[foto.jpg](noted://att/p1)\n2. b",
            "<ol><li>a</li></ol>$photoHtml<ol start=\"2\"><li>b</li></ol>",
        )
        assertPair(
            "> a\n[foto.jpg](noted://att/p1)\n\n[foto.jpg](noted://att/p1)",
            "<blockquote>a</blockquote>$photoHtml<div><br></div>$photoHtml",
        )
    }
}
