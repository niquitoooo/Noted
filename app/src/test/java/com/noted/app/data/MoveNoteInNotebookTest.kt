package com.noted.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noted.app.data.db.NotesDatabase
import com.noted.app.ui.PinnedSections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Arrastrar una nota dentro de un cuaderno: se mueve entre las del cuaderno y las demás no cambian. */
@RunWith(RobolectricTestRunner::class)
class MoveNoteInNotebookTest {

    private lateinit var context: Context
    private lateinit var db: NotesDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("noted", Context.MODE_PRIVATE).edit().clear().commit()
        db = NotesDatabase.inMemory(context)
    }

    @After
    fun tearDown() = db.close()

    private fun repository() =
        NotesRepository(context, db, true).also { runBlocking { it.awaitLoaded() } }

    private fun NotesRepository.titled(title: String, notebook: String?) =
        createNote(notebook = notebook).also { update(it.id) { n -> n.copy(title = title) } }

    private fun NotesRepository.titlesIn(notebook: String?) =
        notes.value.filter { !it.trashed && it.notebook == notebook }.map { it.title }

    /** Lo mismo que hace la lista al soltar: el destino sale de las notas que se ven. */
    private fun NotesRepository.drag(notebook: String, title: String, toIndex: Int) {
        val visible = notes.value.filter { !it.trashed && it.notebook == notebook }
        val ids = visible.map { it.id }
        val id = visible.first { it.title == title }.id
        val final = ids.toMutableList().apply { remove(id); add(toIndex, id) }
        val target = PinnedSections.moveTarget(ids, final, id) ?: return
        moveNote(id, target)
    }

    private fun mixed(): NotesRepository = repository().apply {
        // Las nuevas van arriba: el orden de la lista queda al revés de la creación.
        titled("c", "Casa")
        titled("y", null)
        titled("b", "Casa")
        titled("x", "Trabajo")
        titled("a", "Casa")
    }

    @Test
    fun `bajar una nota dentro del cuaderno`() {
        val repo = mixed()
        assertEquals(listOf("a", "b", "c"), repo.titlesIn("Casa"))
        repo.drag("Casa", "a", 2)
        assertEquals(listOf("b", "c", "a"), repo.titlesIn("Casa"))
        repo.drag("Casa", "b", 1)
        assertEquals(listOf("c", "b", "a"), repo.titlesIn("Casa"))
    }

    @Test
    fun `subir una nota dentro del cuaderno`() {
        val repo = mixed()
        repo.drag("Casa", "c", 0)
        assertEquals(listOf("c", "a", "b"), repo.titlesIn("Casa"))
    }

    @Test
    fun `las notas de afuera del cuaderno no cambian de orden y queda guardado`() {
        val repo = mixed()
        repo.drag("Casa", "a", 2)
        assertEquals(listOf("y"), repo.titlesIn(null))
        assertEquals(listOf("x"), repo.titlesIn("Trabajo"))
        runBlocking { repo.flushAndWait() }

        val again = repository()
        assertEquals(listOf("b", "c", "a"), again.titlesIn("Casa"))
    }
}
