package com.noted.app.ui

import com.noted.app.data.Note
import com.noted.app.data.NoteFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScopeTest {
    private val suelta = Note(id = "a", title = "Suelta")
    private val casa = Note(id = "b", title = "Casa", notebook = "Viajes")
    private val receta = Note(id = "c", title = "Receta", notebook = "Recetas", pinned = true)
    private val tirada = Note(id = "d", title = "Tirada", notebook = "Viajes", trashed = true)

    private fun found(filter: NoteFilter, scope: SearchScope) =
        listOf(suelta, casa, receta, tirada).filter { inSearchScope(it, filter, scope) }.map { it.id }

    @Test
    fun `en Notas arranca buscando solo en Notas`() {
        assertEquals(listOf("a"), found(NoteFilter.All, defaultSearchScope(NoteFilter.All)))
    }

    @Test
    fun `en un cuaderno arranca buscando en ese cuaderno`() {
        val filter = NoteFilter.Notebook("Viajes")
        assertEquals(listOf("b"), found(filter, defaultSearchScope(filter)))
    }

    @Test
    fun `varios cuadernos y todas las notas`() {
        assertEquals(listOf("a", "c"), found(NoteFilter.All, SearchScope(false, setOf(NOTAS_PLACE, "Recetas"))))
        assertEquals(listOf("a", "b", "c"), found(NoteFilter.All, SearchScope(true, setOf(NOTAS_PLACE))))
    }

    @Test
    fun `fijadas busca en las fijadas y la papelera solo en la papelera`() {
        assertEquals(listOf("c"), found(NoteFilter.Pinned, defaultSearchScope(NoteFilter.Pinned)))
        assertEquals(listOf("d"), found(NoteFilter.Trash, SearchScope(true, null)))
    }

    @Test
    fun `cuando muestra el cuaderno de cada resultado y que dice el boton`() {
        assertFalse(showsPlace(SearchScope(false, setOf("Viajes"))))
        assertTrue(showsPlace(SearchScope(false, setOf("Viajes", "Recetas"))))
        assertTrue(showsPlace(SearchScope(true, null)))
        assertEquals("Notas", scopeLabel(setOf(NOTAS_PLACE)))
        assertEquals("Viajes", scopeLabel(setOf("Viajes")))
        assertEquals("2 cuadernos", scopeLabel(setOf("Viajes", "Recetas")))
    }
}
