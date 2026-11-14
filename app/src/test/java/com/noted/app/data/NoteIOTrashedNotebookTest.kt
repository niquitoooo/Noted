package com.noted.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A un cuaderno que está en la papelera no se importa: esas notas se saltean. */
class NoteIOTrashedNotebookTest {
    private val trashed = setOf("Viajes")

    @Test
    fun `el cuaderno sale del encabezado y si no de la carpeta`() {
        assertEquals("Casa", NoteIO.importNotebook(mapOf("notebook" to "Casa"), "Otra"))
        assertEquals("Otra", NoteIO.importNotebook(emptyMap(), "Otra"))
        assertNull(NoteIO.importNotebook(emptyMap(), null))
    }

    @Test
    fun `una nota de un cuaderno en la papelera no se importa`() {
        assertEquals(true, NoteIO.goesToTrashedNotebook(mapOf("notebook" to "Viajes"), null, trashed))
        assertEquals(true, NoteIO.goesToTrashedNotebook(emptyMap(), "Viajes", trashed))
        // Sin importar mayúsculas: crear «viajes» también daría con el de la papelera.
        assertEquals(true, NoteIO.goesToTrashedNotebook(mapOf("notebook" to "viajes"), null, trashed))
    }

    @Test
    fun `las demas se importan`() {
        assertEquals(false, NoteIO.goesToTrashedNotebook(mapOf("notebook" to "Casa"), null, trashed))
        assertEquals(false, NoteIO.goesToTrashedNotebook(emptyMap(), null, trashed))
        assertEquals(false, NoteIO.goesToTrashedNotebook(mapOf("notebook" to "Viajes"), null, emptySet()))
    }
}
