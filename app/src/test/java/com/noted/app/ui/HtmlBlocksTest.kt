package com.noted.app.ui

import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import com.noted.app.data.html.NoteMarkdown
import com.noted.app.data.html.TestBodies
import com.noted.app.ui.rich.RichDebug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cada bloque en una línea: el texto con [RichDebug.describe], la checklist con sus ítems
 * (`-` anidado, `x:` marcado, después el texto) y los demás por su id.
 */
internal fun blockShape(blocks: List<EditorBlock>): List<String> = blocks.map { block ->
    when (block) {
        is EditorBlock.Text -> "text:" + RichDebug.describe(block.value.text)
        is EditorBlock.Media -> "media:" + block.attachmentId
        is EditorBlock.Bookmark -> "bookmark:" + block.bookmarkId
        is EditorBlock.NoteLink -> "note:" + block.noteId
        is EditorBlock.Toggle -> "toggle:" + block.title + ":" + block.isToggled
        is EditorBlock.Checklist -> "checklist:" + block.items.joinToString(",") { item ->
            (if (item.nested) "-" else "") + (if (item.checked) "x:" else "") + item.value.plain
        }
    } + (if (block.parentKey != null) " (adentro)" else "")
}

@RunWith(RobolectricTestRunner::class)
class HtmlBlocksTest {

    private val photo = Attachment("p1", "n1", "foto.jpg", "image/jpeg", 10, "p1.jpg", 0)
    private val report = Attachment("f1", "n1", "informe (final).pdf", "application/pdf", 10, "f1.pdf", 0)
    private val attachments = listOf(photo, report)
    private val mate = Bookmark(id = "b1", url = "https://x.com/a(b)", title = "Mate [rico]")
    private val bookmarks = listOf(mate)
    private val titles = mapOf("n7" to "Receta [de] (mamá)")

    private val photoHtml = "<div><img src=\"noted://att/p1\"></div>"
    private val reportHtml = "<div><a data-file-id=\"f1\" data-non-editable=\"true\" " +
        "href=\"noted://att/f1\">informe (final).pdf</a></div>"
    private val cardHtml = "<div data-noted-bookmark=\"b1\"><a href=\"https://x.com/a(b)\">Mate [rico]</a></div>"

    private fun parse(html: String, attachments: List<Attachment> = this.attachments, bookmarks: List<Bookmark> = this.bookmarks) =
        HtmlBlocks.parse(html, attachments, bookmarks)

    private fun save(blocks: List<EditorBlock>, titles: Map<String, String> = this.titles) =
        HtmlBlocks.serialize(blocks, attachments, bookmarks, titles)

    private fun shape(blocks: List<EditorBlock>): List<String> = blockShape(blocks)

    private fun section(title: String, content: String, open: Boolean = true): String {
        val collapsed = if (open) "" else " noted-section-collapsed"
        return "<div class=\"noted-collapsible-section$collapsed\">" +
            "<div class=\"noted-section-title-wrapper\">" +
            "<div class=\"noted-section-title noted-placeholder\" data-noted-placeholder-key=\"title\">" +
            "<div class=\"noted-section-title-inner\"><h3>$title</h3></div></div></div>" +
            "<div class=\"noted-section-content noted-placeholder\" data-noted-placeholder-key=\"content\">" +
            "<div class=\"noted-section-content-inner\">$content</div></div></div>"
    }

    @Test
    fun openAndSaveIsIdentity() {
        val bodies = listOf(
            TestBodies.canonicalMarkdown,
            "",
            "[foto.jpg](noted://att/p1)",
            "a\n[foto.jpg](noted://att/p1)\nb",
            "[informe (final）.pdf](noted://att/f1)",
            "\\[a](noted://att/7)",
            "[Receta [de］ (mamá)](noted://note/n7)",
            "antes\n<!-- noted:bookmark b1 -->\n[Mate [rico］](https://x.com/a(b）)\ndespués",
            "> a\n[foto.jpg](noted://att/p1)\n\n[foto.jpg](noted://att/p1)",
            "1. a\n[foto.jpg](noted://att/p1)\n2. b",
            "<details open>\n<summary>T</summary>\n</details>",
            "<details open>\n<summary>a &amp; &lt;b&gt;</summary>\nx\n</details>",
            listOf(
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
            ).joinToString("\n"),
        )
        for (markdown in bodies) {
            val html = NoteMarkdown.toHtml(markdown, attachments, bookmarks)
            // Como canonicalBodyRoundTrip: sin espacios dobles no hay U+00A0 que agregar.
            assertFalse(html.contains("  "))
            // Solo los que el cuerpo nombra: los demás se agregan al final (unreferencedAttachmentsGoLast).
            val named = attachments.filter { "noted://att/${it.id}" in html }
            val cards = bookmarks.filter { "data-noted-bookmark=\"${it.id}\"" in html }
            val blocks = HtmlBlocks.parse(html, named, cards)
            assertEquals(markdown, html, HtmlBlocks.serialize(blocks, named, cards, titles))
        }
    }

    @Test
    fun loneEmptyLineIsKeptWhenNotTouched() {
        // <div><br></div> solo se lee como un texto vacío y se escribiría vacío, pero abrir
        // y guardar sin tocar deja el cuerpo original.
        val body = "<div><br></div>"
        val opened = save(parse(body, emptyList(), emptyList()))
        assertEquals("", opened)
        val saved = save(parse(body, emptyList(), emptyList()))
        assertEquals(body, bodyToSave(saved, opened, body))
        // Tocado, se guarda lo nuevo.
        assertEquals("<div>a</div>", bodyToSave("<div>a</div>", opened, body))
    }

    @Test
    fun unknownImageStaysAsLink() {
        val blocks = parse("<div><img src=\"noted://att/7\"></div>", emptyList(), emptyList())
        assertEquals(listOf("text:p|{a=noted://att/7}7{/a}"), shape(blocks))
        assertEquals("<div><a href=\"noted://att/7\">7</a></div>", HtmlBlocks.serialize(blocks, emptyList(), emptyList(), emptyMap()))
    }

    @Test
    fun unknownCardAndFileStayAsTextWithTheirLink() {
        val blocks = parse(
            "<div data-noted-bookmark=\"b9\"><a href=\"https://x.com\">t</a></div>" +
                "<div><a data-file-id=\"7\" href=\"noted://att/7\">a</a></div>",
            emptyList(),
            emptyList(),
        )
        assertEquals(listOf("text:p|{a=https://x.com}t{/a}\np|{a=noted://att/7}a{/a}"), shape(blocks))
    }

    @Test
    fun unreferencedAttachmentsGoLast() {
        val blocks = parse("<div>a</div>")
        assertEquals(listOf("text:p|a", "media:p1", "media:f1", "bookmark:b1", "text:"), shape(blocks))
        assertEquals("<div>a</div>$photoHtml$reportHtml$cardHtml", save(blocks))
    }

    @Test
    fun textSlotsAreNotSaved() {
        val html = "$photoHtml<div>a</div>$reportHtml"
        val blocks = parse(html, bookmarks = emptyList())
        assertEquals(listOf("text:", "media:p1", "text:p|a", "media:f1", "text:"), shape(blocks))
        assertEquals(html, HtmlBlocks.serialize(blocks, attachments, emptyList(), titles))
    }

    @Test
    fun emptyTextBetweenBlocksIsBlankLine() {
        val html = "$photoHtml<div><br></div>$reportHtml"
        val blocks = parse(html, bookmarks = emptyList())
        assertEquals(listOf("text:", "media:p1", "text:", "media:f1", "text:"), shape(blocks))
        assertEquals(html, HtmlBlocks.serialize(blocks, attachments, emptyList(), titles))
        // Una viñeta vacía al final no es un lugar para escribir: se guarda.
        val item = "<div>a</div><ul><li><br></li></ul>"
        assertEquals(item, save(parse(item, emptyList(), emptyList())))
    }

    @Test
    fun collapsedToggleKeepsClass() {
        val html = section("T", "<div>x</div>", open = false)
        val blocks = parse(html, emptyList(), emptyList())
        assertEquals(listOf("text:", "toggle:T:false", "text:p|x (adentro)", "text:"), shape(blocks))
        assertEquals(html, save(blocks))
        // Abierto, sin la clase.
        val open = section("T", "<div>x</div>")
        assertEquals(open, save(parse(open, emptyList(), emptyList())))
    }

    @Test
    fun emptyToggleTitleIsDesplegable() {
        val blocks = mutableListOf<EditorBlock>(EditorBlock.Toggle("t", " ", true))
        ensureTextSlots(blocks)
        assertEquals(
            NoteMarkdown.toHtml("<details open>\n<summary>Desplegable</summary>\n</details>", emptyList()),
            save(blocks),
        )
    }

    @Test
    fun noteLinkUsesCurrentTitle() {
        val html = "<div><a data-note-id=\"n7\" data-non-editable=\"true\" " +
            "href=\"noted://open-note?id=n7\">Vieja</a></div>"
        val blocks = parse(html, emptyList(), emptyList())
        assertEquals(listOf("text:", "note:n7", "text:"), shape(blocks))
        assertEquals(html.replace("Vieja", "Nueva"), save(blocks, mapOf("n7" to "Nueva")))
        // Si la nota ya no está, como hoy: "Sin título".
        assertEquals(html.replace("Vieja", "Sin título"), save(blocks, emptyMap()))
    }

    @Test
    fun shareTextHasNoMarkers() {
        val markdown = listOf(
            "- [ ] leche",
            "**b**",
            "[foto.jpg](noted://att/p1)",
            "<!-- noted:bookmark b1 -->",
            "[Mate [rico］](https://x.com/a(b）)",
            "[x](noted://note/n7)",
            "<details open>",
            "<summary>T</summary>",
            "adentro",
            "</details>",
        ).joinToString("\n")
        val blocks = parse(NoteMarkdown.toHtml(markdown, attachments, bookmarks), attachments = listOf(photo))
        assertEquals(
            "leche\nb\n📎 foto.jpg\nhttps://x.com/a(b)\nReceta [de] (mamá)\n▸ T\nadentro",
            HtmlBlocks.shareText(blocks, listOf(photo), bookmarks, titles),
        )
    }
}
