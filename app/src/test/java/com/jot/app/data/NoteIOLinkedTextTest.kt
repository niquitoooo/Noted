package com.jot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Qué archivos de texto de una importación son adjuntos y no notas: los .txt que enlaza
 * algún .md (como los de una carpeta files/ de un export).
 */
@RunWith(RobolectricTestRunner::class)
class NoteIOLinkedTextTest {

    private fun index(vararg paths: String): Pair<Map<String, String>, Map<String, List<String>>> {
        val byPath = paths.associateBy { it.lowercase() }
        val byName = paths.groupBy { it.substringAfterLast('/').lowercase() }
        return byPath to byName
    }

    @Test
    fun txtLinkedFromMarkdownIsAnAttachment() {
        val (byPath, byName) = index("disenar-planos.md", "files/prompt-1.txt", "files/80526.jpg", "suelta.txt")
        val markdown = listOf(
            "disenar-planos.md" to """
                ---
                title: Diseñar Planos
                ---
                ![80526](files/80526.jpg)
                [Prompt 1](files\prompt-1.txt)
            """.trimIndent()
        )

        assertEquals(setOf("files/prompt-1.txt"), NoteIO.linkedTextFiles(markdown, byPath, byName))
    }

    @Test
    fun linkedMarkdownStaysANote() {
        val (byPath, byName) = index("padre.md", "hija.md")
        val markdown = listOf("padre.md" to "[Hija](hija.md)", "hija.md" to "hola")

        assertEquals(emptySet<String>(), NoteIO.linkedTextFiles(markdown, byPath, byName))
    }

    @Test
    fun ownExportPrefixIsRemovedOnlyInAttachmentsFolder() {
        // El export de la app antepone "<id de 8>_" en attachments/: se saca.
        assertEquals("foto.jpg", NoteIO.importedAttachmentName("attachments/1a2b3c4d_foto.jpg", "1a2b3c4d_foto.jpg"))
        // Una foto de otra app que se llama así por la fecha no se toca.
        assertEquals("20250902_105830.gif", NoteIO.importedAttachmentName("files/20250902_105830.gif", "20250902_105830.gif"))
    }

    @Test
    fun txtNobodyLinksStaysANote() {
        val (byPath, byName) = index("nota.md", "suelta.txt")
        val markdown = listOf("nota.md" to "[web](https://example.com/suelta.txt)")

        assertEquals(emptySet<String>(), NoteIO.linkedTextFiles(markdown, byPath, byName))
    }
}
