package com.noted.app.data.html

import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import com.noted.app.data.Note
import com.noted.app.data.searchableText
import com.noted.app.util.stripMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteHtmlTest {

    private val photo = Attachment("p1", "n1", "foto.jpg", "image/jpeg", 10, "p1.jpg", 0)
    private val report = Attachment("f1", "n1", "informe.pdf", "application/pdf", 10, "f1.pdf", 0)
    private val attachments = listOf(photo, report)

    private fun toHtml(markdown: String) = NoteMarkdown.toHtml(markdown, attachments)

    /** Las palabras de un texto, sin las marcas que borra stripMarkdown. */
    private fun words(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.replace(Regex("[#>*_~`]"), "")).map { it.value }.toList()

    /**
     * Todos los bloques salvo la lista numerada y el código cercado: stripMarkdown borra el
     * código cercado entero (y plainText lo conserva) y deja los números de la lista numerada
     * (que en el HTML son de la `<ol>`, no del texto). Tampoco hay un ítem marcado: "[x]" deja
     * una "x" suelta del lado viejo.
     */
    @Test
    fun plainTextMatchesOldSearchableText() {
        val markdown = listOf(
            "# Título grande",
            "Texto con **negrita**, *cursiva*, ~~tachado~~, `código` y [enlace](https://a.com).",
            "",
            "",
            "> una cita",
            "> de dos renglones",
            "- viñeta uno",
            "  - viñeta adentro",
            "- [ ] tarea pendiente",
            "---",
            "[foto.jpg](noted://att/p1)",
            "[informe.pdf](noted://att/f1)",
            "[Otra nota](noted://note/n2)",
            "<!-- noted:bookmark b1 -->",
            "[Sitio de ejemplo](https://example.com)",
            "<details open>",
            "<summary>Desplegable &amp; más</summary>",
            "adentro del desplegable",
            "[foto.jpg](noted://att/p1)",
            "</details>",
            "final",
        ).joinToString("\n")

        val plain = NoteHtml.plainText(toHtml(markdown), attachments)

        assertEquals(words(stripMarkdown(searchableText(markdown))), words(plain))
    }

    @Test
    fun plainTextHasOneLinePerBlockWithoutRepeatedBlanks() {
        val html = toHtml("uno\n\n\n\ndos\n> a\n> b")

        assertEquals("uno\n\ndos\na\nb", NoteHtml.plainText(html))
    }

    @Test
    fun summaryIsFirstNonBlankLine() {
        assertEquals("Primer renglón", NoteHtml.summary(toHtml("\n  \nPrimer renglón\nsegundo")))
        // Una imagen o una nota de adentro no dan renglón de resumen.
        assertEquals(
            "Texto",
            NoteHtml.summary(toHtml("[foto.jpg](noted://att/p1)\n[Otra](noted://note/n2)\nTexto")),
        )
        assertEquals("a".repeat(200), NoteHtml.summary(toHtml("a".repeat(300))))
        assertEquals("", NoteHtml.summary(""))
    }

    @Test
    fun hasTodoOnlyWhenUnchecked() {
        assertTrue(NoteHtml.hasTodo(toHtml("- [x] hecha\n- [ ] falta")))
        assertFalse(NoteHtml.hasTodo(toHtml("- [x] hecha\n- [X] también")))
        assertFalse(NoteHtml.hasTodo(toHtml("- viñeta\ntexto [ ] suelto")))
    }

    @Test
    fun noteLinkBlockIdsIgnoresInlineLinks() {
        val html = toHtml(
            "[Uno](noted://note/n2)\n" +
                "texto [Dos](noted://note/n3) en el medio\n" +
                "<details>\n<summary>Grupo</summary>\n[Tres](noted://note/n4)\n</details>",
        )

        assertEquals(listOf("n2", "n4"), NoteHtml.noteLinkBlockIds(html))
    }

    @Test
    fun tagsIgnoreCodeAndLinks() {
        assertEquals(listOf("a"), NoteHtml.tags(toHtml("#a `#b` [#c](u)")))
        assertEquals(listOf("tit", "x", "y"), NoteHtml.tags(toHtml("#y\n```\n#z\n```\n<details>\n<summary>#tit</summary>\n#x\n</details>")))
        // Un renglón no se pega con el siguiente.
        assertEquals(listOf("uno"), NoteHtml.tags(toHtml("#uno\ndos#tres")))
    }

    @Test
    fun removeNoteLinkBlocks() {
        val html = toHtml("a\n[Uno](noted://note/n2)\ntexto [Uno](noted://note/n2)\n[Dos](noted://note/n3)\nb")

        assertEquals(
            toHtml("a\ntexto [Uno](noted://note/n2)\n[Dos](noted://note/n3)\nb"),
            NoteHtml.removeNoteLinkBlocks(html, "n2"),
        )
    }

    @Test
    fun removeAttachment() {
        val html = toHtml("a\n[foto.jpg](noted://att/p1)\n[informe.pdf](noted://att/f1)\nb")

        assertEquals(
            toHtml("a\n[informe.pdf](noted://att/f1)\nb"),
            NoteHtml.removeAttachment(html, "p1"),
        )
        assertEquals(
            toHtml("a\n[foto.jpg](noted://att/p1)\nb"),
            NoteHtml.removeAttachment(html, "f1"),
        )
    }

    @Test
    fun emptiedToggleLooksLikeANewOne() {
        val empty = toHtml("<details open>\n<summary>t</summary>\n</details>")

        assertEquals(
            empty,
            NoteHtml.removeAttachment(
                toHtml("<details open>\n<summary>t</summary>\n[foto.jpg](noted://att/p1)\n</details>"), "p1"
            ),
        )
        assertEquals(
            empty,
            NoteHtml.removeNoteLinkBlocks(
                toHtml("<details open>\n<summary>t</summary>\n[Uno](noted://note/n2)\n</details>"), "n2"
            ),
        )
    }

    @Test
    fun isBlankForEmptyDivs() {
        assertTrue(NoteHtml.isBlank(""))
        assertTrue(NoteHtml.isBlank(toHtml("\n\n")))
        assertTrue(NoteHtml.isBlank("<div>  </div><div><br></div>"))
        assertFalse(NoteHtml.isBlank(toHtml("hola")))
        assertFalse(NoteHtml.isBlank(toHtml("[foto.jpg](noted://att/p1)")))
        assertFalse(NoteHtml.isBlank(toHtml("[informe.pdf](noted://att/f1)")))
        assertFalse(NoteHtml.isBlank(toHtml("[Otra](noted://note/n2)")))
        assertFalse(NoteHtml.isBlank(toHtml("<!-- noted:bookmark b1 -->\n[](https://a.com)")))
        assertFalse(NoteHtml.isBlank(toHtml("<details>\n<summary></summary>\n</details>")))
        assertFalse(NoteHtml.isBlank(toHtml("---")))
    }

    /** Lo mismo que `body.isBlank()` sobre el Markdown. */
    @Test
    fun isBlankMatchesOldMarkdownIsBlank() {
        listOf("", "\n", "   ", " \n\t\n ").forEach { markdown ->
            assertTrue(markdown, markdown.isBlank())
            assertTrue(markdown, NoteHtml.isBlank(toHtml(markdown)))
        }
        listOf("- [ ] ", "- [x] ", "# ", "### ", "- ", "1. ", "> ", "```\n```", "<details>\n<summary></summary>\n</details>")
            .forEach { markdown ->
                assertFalse(markdown, markdown.isBlank())
                assertFalse(markdown, NoteHtml.isBlank(toHtml(markdown)))
            }
    }

    @Test
    fun withDerivedFillsTheFields() {
        val note = Note(
            body = toHtml("[foto.jpg](noted://att/p1)\nHola #etiqueta\n- [ ] algo\n[Otra](noted://note/n2)"),
            attachments = attachments,
        ).withDerived()

        assertEquals(listOf("etiqueta"), note.tags)
        assertEquals("foto.jpg\nHola #etiqueta\nalgo", note.plainText)
        assertEquals("Hola #etiqueta", note.summary)
        assertTrue(note.hasTodo)
        assertEquals(listOf("n2"), note.noteLinkIds)
    }

    @Test
    fun searchFindsLinkAddresses() {
        val bookmark = Bookmark(id = "b1", url = "https://github.com/example", title = "Repo")
        val markdown = listOf(
            "Mirá [esto](https://docs.example.org/guia) un rato",
            "<!-- noted:bookmark b1 -->",
            "[Repo](https://github.com/example)",
            "[foto.jpg](noted://att/p1)",
            "[informe.pdf](noted://att/f1)",
            "[Otra](noted://note/n2)",
        ).joinToString("\n")
        val html = NoteMarkdown.toHtml(markdown, attachments, listOf(bookmark))
        val note = Note(body = html, attachments = attachments, bookmarks = listOf(bookmark)).withDerived()

        // Antes, con el cuerpo en Markdown, la dirección estaba en el texto que se buscaba.
        assertTrue(searchableText(markdown).contains("github.com"))
        assertEquals("https://docs.example.org/guia\nhttps://github.com/example", note.linkText)
        assertTrue(note.matchesSearch("github.com"))
        assertTrue(note.matchesSearch("DOCS.EXAMPLE"))
        assertFalse(note.matchesSearch("noted://"))
        assertFalse(note.matchesSearch("openNote"))
        // El resumen y el texto siguen sin las direcciones.
        assertEquals("Mirá esto un rato", note.summary)
        assertFalse(note.plainText.contains("https"))
    }

    @Test
    fun withDerivedMatchesTheSingleFunctions() {
        val markdown = listOf(
            "#a `#b` [#c](https://c.com)",
            "[foto.jpg](noted://att/p1)",
            "- [ ] x",
            "[Otra](noted://note/n2)",
            "<details>",
            "<summary>#tit</summary>",
            "#x",
            "</details>",
        ).joinToString("\n")
        val html = toHtml(markdown)
        val note = Note(body = html, attachments = attachments).withDerived()

        assertEquals(NoteHtml.tags(html), note.tags)
        assertEquals(NoteHtml.plainText(html, attachments), note.plainText)
        assertEquals(NoteHtml.summary(html), note.summary)
        assertEquals(NoteHtml.hasTodo(html), note.hasTodo)
        assertEquals(NoteHtml.noteLinkBlockIds(html), note.noteLinkIds)
        assertEquals(NoteHtml.linkText(html), note.linkText)
        assertEquals(html, note.body)
    }
}
