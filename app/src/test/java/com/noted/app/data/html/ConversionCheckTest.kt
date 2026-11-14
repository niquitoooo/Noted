package com.noted.app.data.html

import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionCheckTest {

    private val photo = Attachment("p1", "n1", "foto.jpg", "image/jpeg", 10, "p1.jpg", 0)
    private val report = Attachment("f1", "n1", "informe.pdf", "application/pdf", 10, "f1.pdf", 0)
    private val attachments = listOf(photo, report)
    private val bookmarks = listOf(Bookmark(id = "b1", url = "https://example.com", title = "Sitio"))

    private fun toHtml(markdown: String) = NoteMarkdown.toHtml(markdown, attachments, bookmarks)

    private fun verify(markdown: String, html: String) =
        ConversionCheck.verify(markdown, html, attachments, bookmarks)

    private val mixed = listOf(
        "# Título",
        "Texto con **negrita** y [enlace](https://a.com).",
        "- [ ] pendiente",
        "- [x] hecha",
        "> una cita",
        "```",
        "codigo()",
        "```",
        "[foto.jpg](noted://att/p1)",
        "[informe.pdf](noted://att/f1)",
        "[Otra](noted://note/n2)",
        "<!-- noted:bookmark b1 -->",
        "[Sitio](https://example.com)",
        "<details open>",
        "<summary>Desplegable</summary>",
        "adentro",
        "</details>",
    ).joinToString("\n")

    @Test
    fun goodConversionHasNoReasons() {
        val markdown = TestBodies.canonicalMarkdown
        assertEquals(emptyList<String>(), verify(markdown, toHtml(markdown)))
        assertEquals(emptyList<String>(), verify(mixed, toHtml(mixed)))
        assertEquals(emptyList<String>(), verify("", ""))
    }

    @Test
    fun missingWordIsReported() {
        val markdown = "uno dos tres\ncuatro"
        val html = toHtml(markdown).replace("dos ", "")
        assertEquals(listOf("falta 1 palabra"), verify(markdown, html))
        val html2 = toHtml(markdown).replace("dos tres", "")
        assertEquals(listOf("faltan 2 palabras"), verify(markdown, html2))
    }

    @Test
    fun changedWordIsReported() {
        val reasons = verify("uno dos", "<div>uno tres</div>")
        assertEquals(1, reasons.size)
        assertTrue(reasons[0], reasons[0].startsWith("las palabras no coinciden"))
    }

    @Test
    fun missingAttachmentIsReported() {
        val html = toHtml(mixed).replace(Regex("<div><img[^>]*></div>"), "")
        val reasons = verify(mixed, html)
        assertTrue(reasons.toString(), "falta el adjunto foto.jpg" in reasons)
    }

    @Test
    fun checkedCountMismatchIsReported() {
        val markdown = "- [ ] a\n- [x] b"
        val html = toHtml(markdown).replace("data-checked=\"true\"", "data-checked=\"false\"")
        val reasons = verify(markdown, html)
        assertTrue(reasons.toString(), "cambia la cantidad de ítems tildados: eran 1 y quedaron 0" in reasons)
        assertTrue(reasons.toString(), "cambia la cantidad de ítems sin tildar: eran 1 y quedaron 2" in reasons)
    }

    @Test
    fun missingToggleBookmarkAndNoteAreReported() {
        val html = toHtml(mixed)
            .replace(Regex("<div data-noted-bookmark.*?</div>"), "")
            .replace(Regex("<div><a data-note-id.*?</div>"), "")
        val reasons = verify(mixed, html)
        assertTrue(reasons.toString(), "falta la tarjeta b1" in reasons)
        assertTrue(reasons.toString(), "falta la nota n2" in reasons)
        val noToggle = verify(mixed, "<div>Desplegable adentro</div>")
        assertTrue(noToggle.toString(), "cambia la cantidad de desplegables: eran 1 y quedaron 0" in noToggle)
    }

    @Test
    fun unknownAttachmentIsNotAPiece() {
        val markdown = "hola\n[raro.bin](noted://att/zz)\n[Otra](noted://note/n2)"
        assertEquals(emptyList<String>(), verify(markdown, toHtml(markdown)))
    }

    @Test
    fun codeBlocksAreCountedNotCompared() {
        val markdown = "antes\n```\n<detalle> & más\n```\ndespués"
        assertEquals(emptyList<String>(), verify(markdown, toHtml(markdown)))
        val reasons = verify(markdown, "<div>antes</div><div>después</div>")
        assertEquals(listOf("cambia la cantidad de bloques de código: eran 1 y quedaron 0"), reasons)
    }
}
