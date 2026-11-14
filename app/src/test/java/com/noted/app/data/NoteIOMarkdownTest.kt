package com.noted.app.data

import com.noted.app.data.html.NoteMarkdown
import com.noted.app.data.html.withDerived
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteIOMarkdownTest {

    private val photo = Attachment("p1", "n1", "foto.jpg", "image/jpeg", 1, "p1.jpg", 0)
    private val file = Attachment("f1", "n1", "a]b (c).pdf", "application/pdf", 1, "f1.pdf", 0)
    private val card = Bookmark(id = "b1", url = "https://x.com/mate", title = "Mate")

    // Un cuerpo con todos los bloques, en el Markdown canónico de la app.
    private val markdown = listOf(
        "# Título",
        "hola **negrita** y *cursiva* con ~~tachado~~ y `código` #mate",
        "- uno",
        "  - dos",
        "1. primero",
        "- [ ] tarea",
        "- [x] hecha",
        "> cita",
        "```kotlin",
        "val x = 1 < 2 && 3 > 2",
        "```",
        "---",
        "",
        "[foto.jpg](noted://att/p1)",
        "[a］b (c）.pdf](noted://att/f1)",
        "[Otra nota](noted://note/n2)",
        "<!-- noted:bookmark b1 -->",
        "[Mate](https://x.com/mate)",
        "<details open>",
        "<summary>Plegable</summary>",
        "adentro",
        "[foto.jpg](noted://att/p1)",
        "</details>",
        "fin",
    ).joinToString("\n")

    // Lo que arma el export de e2378a0 (Save State 18) para ese mismo Markdown, más el renglón bookmarks:.
    private val exported = """
        ---
        id: n1
        title: Nota completa
        pinned: true
        notebook: Casa
        tags: mate
        bookmarks: [{"id":"b1","url":"https://x.com/mate","title":"Mate","description":"","siteName":null,"image":null,"favicon":null}]
        created: 1970-01-01T00:00:00Z
        updated: 1970-01-01T00:00:01Z
        ---

        # Título
        hola **negrita** y *cursiva* con ~~tachado~~ y `código` #mate
        - uno
          - dos
        1. primero
        - [ ] tarea
        - [x] hecha
        > cita
        ```kotlin
        val x = 1 < 2 && 3 > 2
        ```
        ---

        [foto.jpg](attachments/p1_foto.jpg)
        [a］b (c）.pdf](attachments/f1_a%5Db%20%28c%29.pdf)
        [Otra nota](Otra%20nota.md)
        <!-- noted:bookmark b1 -->
        [Mate](https://x.com/mate)
        <details open>
        <summary>Plegable</summary>
        adentro
        [foto.jpg](attachments/p1_foto.jpg)
        </details>
        fin
    """.trimIndent()

    private val attachmentPaths = linkedMapOf("p1" to "attachments/p1_foto.jpg", "f1" to "attachments/f1_a]b (c).pdf")
    private val noteFiles = mapOf("n1" to "Nota completa.md", "n2" to "Otra nota.md")

    private fun note(): Note = Note(
        id = "n1",
        title = "Nota completa",
        body = NoteMarkdown.toHtml(markdown, listOf(photo, file), listOf(card)),
        notebook = "Casa",
        pinned = true,
        createdAt = 0L,
        updatedAt = 1000L,
        attachments = listOf(photo, file),
        bookmarks = listOf(card),
    ).withDerived()

    /** Como la importación: los archivos vuelven como adjuntos y los .md como notas de adentro. */
    private fun resolve(label: String, target: String): NoteIO.LinkReplacement? = when (target) {
        "attachments/p1_foto.jpg" -> NoteIO.LinkReplacement(NoteMarkdown.attachmentToken(photo), photo)
        "attachments/f1_a%5Db%20%28c%29.pdf" -> NoteIO.LinkReplacement(NoteMarkdown.attachmentToken(file), file)
        "Otra%20nota.md" -> NoteIO.LinkReplacement("[$label](noted://note/n2)")
        else -> null
    }

    @Test
    fun exportThenImportKeepsBody() {
        val note = note()
        // El Markdown de partida es el canónico: el HTML lo devuelve igual.
        assertEquals(markdown, NoteMarkdown.fromHtml(note.body, note.attachments, note.bookmarks))

        val text = NoteIO.noteToMarkdownFile(note, attachmentPaths, noteFiles, iconValue = null)
        assertEquals(exported, text)

        val (meta, content) = NoteIO.splitFrontMatter(text)
        assertEquals("Nota completa", meta["title"])
        assertEquals(note.body, runBlocking { NoteIO.markdownFileToBody(content, note.bookmarks, ::resolve) })
    }

    private fun withIcon(icon: String?): Note = note().copy(icon = icon)

    private fun exportWithIcon(icon: String?): String =
        NoteIO.noteToMarkdownFile(withIcon(icon), attachmentPaths, noteFiles, iconValue = icon)

    /** Lo que la importación hace con el renglón `icon:` del .md. */
    private fun importedIcon(text: String): String? =
        NoteIO.inlineIcon(NoteIO.splitFrontMatter(text).first["icon"])

    private val thermometer = "emoji:🌡️"
    private val family = "emoji:👨‍👩‍👧‍👦"

    @Test
    fun exportWritesEmojiIcon() {
        assertTrue(exportWithIcon(thermometer).lines().contains("icon: $thermometer"))
    }

    @Test
    fun importKeepsFamilyEmojiWithJoiners() {
        assertEquals(family, importedIcon(exportWithIcon(family)))
    }

    @Test
    fun emojiRoundTripKeepsVariationSelector() {
        assertEquals(thermometer, importedIcon(exportWithIcon(thermometer)))
    }

    @Test
    fun emptyEmojiIconIsIgnoredAndNotLookedUpAsFile() {
        val text = "---\nid: n1\nicon: emoji:\n---\n\nhola"
        val icon = NoteIO.splitFrontMatter(text).first["icon"]
        assertNull(importedIcon(text))
        assertFalse(NoteIO.iconIsFile(icon))
    }

    @Test
    fun builtinAndEmojiIconsAreNotFiles() {
        assertEquals("builtin:star", NoteIO.inlineIcon("builtin:star"))
        assertFalse(NoteIO.iconIsFile("builtin:star"))
        assertFalse(NoteIO.iconIsFile(thermometer))
        assertTrue(NoteIO.iconIsFile("icons/a.png"))
    }
}
