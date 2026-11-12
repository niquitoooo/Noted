package com.jot.app.data.db

import androidx.test.core.app.ApplicationProvider
import com.jot.app.data.Note
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** La columna `searchKey` sigue a la nota cuando se crea, se cambia y se borra. */
@RunWith(RobolectricTestRunner::class)
class SearchIndexTest {
    private lateinit var db: NotesDatabase
    private lateinit var dao: NotesDao

    @Before
    fun setUp() {
        db = NotesDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.dao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun search(query: String) = dao.searchIds(SearchKey.fold(query)).toSet()

    private suspend fun save(vararg notes: Note) =
        dao.save(notes.map { it.toEntity() }, emptyList(), emptyList(), 50)

    @Test
    fun insertedNoteIsFound() = runTest {
        save(
            Note(id = "a", title = "Compras", plainText = "Leche y pan"),
            Note(id = "b", title = "Viaje", linkText = "https://ejemplo.com/Ñandú")
        )
        assertEquals(setOf("a"), search("compr"))
        assertEquals(setOf("a"), search("LECHE"))
        assertEquals(setOf("b"), search("ñandú"))
        assertEquals(setOf("b"), search("EJEMPLO.COM"))
        assertEquals(emptySet<String>(), search("nada de esto"))
    }

    @Test
    fun updatedNoteIsFoundByTheNewTextOnly() = runTest {
        save(Note(id = "a", title = "Uno", plainText = "texto viejo"))
        assertEquals(setOf("a"), search("viejo"))

        save(Note(id = "a", title = "Uno", plainText = "texto nuevo"))
        assertEquals(emptySet<String>(), search("viejo"))
        assertEquals(setOf("a"), search("nuevo"))
    }

    @Test
    fun deletedNoteIsNotFound() = runTest {
        save(Note(id = "a", title = "Borrame"), Note(id = "b", title = "Quedate"))
        assertEquals(setOf("a"), search("borra"))

        dao.save(emptyList(), listOf("a"), emptyList(), 50)
        assertEquals(emptySet<String>(), search("borra"))
        assertEquals(setOf("b"), search("queda"))
    }

    /** Un campo no se pega con el siguiente: el final del título y el principio del texto no forman una palabra. */
    @Test
    fun fieldsDoNotRunTogether() = runTest {
        save(Note(id = "a", title = "casa", plainText = "miento", linkText = "x"))
        assertEquals(emptySet<String>(), search("casamiento"))
        assertEquals(setOf("a"), search("casa"))
    }

    /** Las mayúsculas se comparan igual que `contains(…, ignoreCase = true)`, letra por letra. */
    @Test
    fun foldMatchesKotlinIgnoreCase() {
        val samples = "aAáÁñÑüÜçÇßẞİıIiσςΣǅǆǄÿŸ ".toList()
        for (x in samples) for (y in samples) {
            val kotlin = x.toString().contains(y.toString(), ignoreCase = true)
            val folded = SearchKey.fold(x.toString()).contains(SearchKey.fold(y.toString()))
            assertEquals("«$x» contiene «$y»", kotlin, folded)
        }
        assertTrue(SearchKey.fold("ÁRBOL") == SearchKey.fold("árbol"))
        assertFalse(SearchKey.fold("arbol") == SearchKey.fold("árbol"))
    }
}
