package com.noted.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noted.app.data.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NotebookIconTest {

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

    /** Simula una imagen cargada: un archivo en icons/ y el valor `file:` en el cuaderno. */
    private fun withFileIcon(repo: NotesRepository, name: String): File {
        repo.setNotebookEmojiIcon(name, "📦")
        val file = File(context.filesDir, "icons/nb_prueba.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val field = NotesRepository::class.java.getDeclaredField("_notebookIcons").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>
        flow.value = flow.value + (name to NotesRepository.ICON_FILE + file.name)
        return file
    }

    @Test
    fun `emoji del cuaderno se guarda y vuelve al abrir la app`() {
        val repo = repository()
        repo.createNote(notebook = "Casa").also { repo.update(it.id) { n -> n.copy(title = "x") } }
        repo.setNotebookEmojiIcon("Casa", "🏠")
        assertEquals("emoji:🏠", repo.notebookIcons.value["Casa"])
        runBlocking { repo.flushAndWait() }

        val again = repository()
        assertEquals("emoji:🏠", again.notebookIcons.value["Casa"])
    }

    @Test
    fun `cuaderno sin notas no acepta icono`() {
        val repo = repository()
        repo.setNotebookEmojiIcon("Nada", "🏠")
        assertNull(repo.notebookIcons.value["Nada"])
    }

    @Test
    fun `al sacar la ultima nota el cuaderno sigue con su icono`() {
        val repo = repository()
        val note = repo.createNote(notebook = "Casa")
        repo.update(note.id) { it.copy(title = "x") }
        val file = withFileIcon(repo, "Casa")

        repo.setNotebook(note.id, null)
        repo.emptyTrash()

        assertEquals(NotesRepository.ICON_FILE + file.name, repo.notebookIcons.value["Casa"])
        assertTrue(file.exists())
    }

    @Test
    fun `eliminar cuaderno lo manda a la papelera con sus notas`() {
        val repo = repository()
        val note = repo.createNote(notebook = "Casa")
        repo.update(note.id) { it.copy(title = "x") }
        val file = withFileIcon(repo, "Casa")

        repo.trashNotebook("Casa")

        // El cuaderno se va de la vista pero no se borra: ni su ícono, ni sus notas.
        assertTrue("Casa" in repo.notebooksTrashed.value)
        assertEquals(NotesRepository.ICON_FILE + file.name, repo.notebookIcons.value["Casa"])
        assertTrue(file.exists())
        assertEquals("Casa", repo.findNote(note.id)!!.notebook)
    }

    @Test
    fun `el cuaderno en la papelera sigue ahi al reabrir la app`() {
        val repo = repository()
        repo.createNote(notebook = "Casa").also { repo.update(it.id) { n -> n.copy(title = "x") } }
        repo.trashNotebook("Casa")
        runBlocking { repo.flushAndWait() }

        val again = repository()
        assertEquals(setOf("Casa"), again.notebooksTrashed.value)
        assertEquals("Casa", again.notes.value.single { it.title == "x" }.notebook)
    }

    @Test
    fun `restaurar el cuaderno lo devuelve con sus notas`() {
        val repo = repository()
        val note = repo.createNote(notebook = "Casa")
        repo.update(note.id) { it.copy(title = "x") }
        repo.trashNotebook("Casa")
        repo.restoreNotebook("Casa")

        assertEquals(emptySet<String>(), repo.notebooksTrashed.value)
        assertEquals("Casa", repo.findNote(note.id)!!.notebook)
    }

    @Test
    fun `borrar el cuaderno para siempre borra sus notas y su icono`() {
        val repo = repository()
        val note = repo.createNote(notebook = "Casa")
        repo.update(note.id) { it.copy(title = "x") }
        val file = withFileIcon(repo, "Casa")
        repo.trashNotebook("Casa")

        assertEquals(1, repo.deleteNotebookPermanently("Casa"))

        assertFalse("Casa" in repo.notebookIcons.value)
        assertFalse("Casa" in repo.notebooksTrashed.value)
        assertFalse(file.exists())
        assertNull(repo.findNote(note.id))
    }

    @Test
    fun `crear cuaderno vacio queda guardado y vuelve al abrir la app`() {
        val repo = repository()
        assertEquals("Viajes", repo.createNotebook("  Viajes "))
        assertEquals("", repo.notebookIcons.value["Viajes"])
        repo.setNotebookEmojiIcon("Viajes", "✈️")
        runBlocking { repo.flushAndWait() }

        val again = repository()
        assertEquals("emoji:✈️", again.notebookIcons.value["Viajes"])
    }

    @Test
    fun `crear con el nombre de uno que ya existe devuelve ese`() {
        val repo = repository()
        repo.createNote(notebook = "Viajes").also { repo.update(it.id) { n -> n.copy(title = "x") } }
        assertEquals("Viajes", repo.createNotebook("viajes"))
        assertEquals(setOf("Viajes"), repo.notebookIcons.value.keys)
        assertNull(repo.createNotebook("   "))
    }

    @Test
    fun `quitar el icono deja el cuaderno`() {
        val repo = repository()
        repo.createNotebook("Casa")
        repo.setNotebookEmojiIcon("Casa", "🏠")
        repo.setNotebookEmojiIcon("Casa", null)
        assertEquals("", repo.notebookIcons.value["Casa"])
    }

    @Test
    fun `renombrar lleva el icono al nombre nuevo`() {
        val repo = repository()
        val note = repo.createNote(notebook = "Casa")
        repo.update(note.id) { it.copy(title = "x") }
        repo.setNotebookEmojiIcon("Casa", "🏠")
        repo.renameNotebook("Casa", "Hogar")
        assertNull(repo.notebookIcons.value["Casa"])
        assertEquals("emoji:🏠", repo.notebookIcons.value["Hogar"])
    }

    @Test
    fun `elegir emoji borra la imagen cargada anterior`() {
        val repo = repository()
        val note = repo.createNote(notebook = "Casa")
        repo.update(note.id) { it.copy(title = "x") }
        val file = withFileIcon(repo, "Casa")
        repo.setNotebookEmojiIcon("Casa", "🏡")
        assertFalse(file.exists())
        assertEquals("emoji:🏡", repo.notebookIcons.value["Casa"])
    }

    @Test
    fun `la imagen de un cuaderno no se borra como icono huerfano al abrir`() {
        val repo = repository()
        val note = repo.createNote(notebook = "Casa")
        repo.update(note.id) { it.copy(title = "x") }
        val file = withFileIcon(repo, "Casa")
        runBlocking { repo.flushAndWait() }

        val again = repository()
        assertTrue(file.exists())
        assertEquals(NotesRepository.ICON_FILE + file.name, again.notebookIcons.value["Casa"])
    }

    @Test
    fun `el orden de los cuadernos se guarda y vuelve al abrir la app`() {
        val repo = repository()
        repo.createNotebook("A")
        repo.createNotebook("B")
        repo.createNotebook("C")
        // Los nuevos van arriba de todo.
        assertEquals(listOf("C", "B", "A"), repo.notebookOrder.value)
        repo.moveNotebook("A", 0)
        assertEquals(listOf("A", "C", "B"), repo.notebookOrder.value)
        runBlocking { repo.flushAndWait() }

        val again = repository()
        assertEquals(listOf("A", "C", "B"), again.notebookOrder.value)
    }

    @Test
    fun `renombrar no cambia el lugar del cuaderno`() {
        val repo = repository()
        repo.createNotebook("A")
        repo.createNotebook("B")
        repo.createNotebook("C")
        repo.moveNotebook("A", 0)
        repo.renameNotebook("B", "Bé")
        assertEquals(listOf("A", "C", "Bé"), repo.notebookOrder.value)
    }

    @Test
    fun `borrar y volver a poner el icono queda guardado al reabrir`() {
        val repo = repository()
        repo.createNote(notebook = "Viajes").also { repo.update(it.id) { n -> n.copy(title = "x") } }
        repo.createNotebook("Prueba")
        repo.setNotebookEmojiIcon("Viajes", "🏡")
        runBlocking { repo.flushAndWait() }
        val second = repository()
        second.setNotebookEmojiIcon("Viajes", null)
        runBlocking { second.flushAndWait() }
        second.setNotebookEmojiIcon("Viajes", "🏡")
        runBlocking { second.flushAndWait() }
        second.moveNotebook("Viajes", 0)
        runBlocking { second.flushAndWait() }
        second.moveNotebook("Viajes", 1)
        runBlocking { second.flushAndWait() }

        val third = repository()
        assertEquals("emoji:🏡", third.notebookIcons.value["Viajes"])
        assertEquals(listOf("Prueba", "Viajes"), third.notebookOrder.value)
    }

    /** Una nota en «Casa» (y el cuaderno en la papelera) y otra suelta en Notas. */
    private fun withTrashedCasa(repo: NotesRepository): Pair<Note, Note> {
        val inside = repo.createNote(notebook = "Casa").also { repo.update(it.id) { n -> n.copy(title = "adentro") } }
        val loose = repo.createNote().also { repo.update(it.id) { n -> n.copy(title = "suelta") } }
        repo.trashNotebook("Casa")
        return inside to loose
    }

    @Test
    fun `no se puede mover una nota a un cuaderno de la papelera`() {
        val repo = repository()
        val (_, loose) = withTrashedCasa(repo)

        assertFalse(repo.setNotebook(loose.id, "Casa"))
        // Tampoco con otras mayúsculas: sería el mismo cuaderno con otro nombre.
        assertFalse(repo.setNotebook(loose.id, "casa"))

        assertNull(repo.findNote(loose.id)!!.notebook)
        assertEquals(setOf("Casa"), repo.notebooksTrashed.value)
    }

    @Test
    fun `no se puede crear un cuaderno con el nombre de uno de la papelera`() {
        val repo = repository()
        withTrashedCasa(repo)

        assertNull(repo.createNotebook("casa"))
        assertEquals(setOf("Casa"), repo.notebooksTrashed.value)
    }

    /** Por otro camino que [NotesRepository.setNotebook] el cuaderno tampoco vuelve solo. */
    @Test
    fun `cambiar el cuaderno con update no devuelve uno de la papelera`() {
        val repo = repository()
        val (_, loose) = withTrashedCasa(repo)

        repo.update(loose.id) { it.copy(notebook = "Casa") }

        assertEquals(setOf("Casa"), repo.notebooksTrashed.value)
    }

    @Test
    fun `editar una nota de un cuaderno en la papelera no lo devuelve`() {
        val repo = repository()
        val (inside, _) = withTrashedCasa(repo)

        repo.update(inside.id) { it.copy(title = "cambiada") }

        assertEquals(setOf("Casa"), repo.notebooksTrashed.value)
    }

    @Test
    fun `una nota que sale de la papelera de notas hacia un cuaderno en la papelera no lo devuelve`() {
        val repo = repository()
        val (_, loose) = withTrashedCasa(repo)
        repo.update(loose.id) { it.copy(trashed = true, notebook = "Casa") }

        assertEquals(setOf("Casa"), repo.notebooksTrashed.value)
    }

    /**
     * Se esconden con el cuaderno: sus notas y las que están adentro de ellas (a cualquier
     * profundidad), salvo que también estén dentro de una nota que se sigue viendo.
     */
    @Test
    fun `las notas de adentro se van con el cuaderno de la papelera`() {
        val holder = Note(id = "h", notebook = "Casa", noteLinkIds = listOf("c"))
        val child = Note(id = "c", noteLinkIds = listOf("g"))
        val grandchild = Note(id = "g", notebook = "Ideas")
        val visible = Note(id = "v", noteLinkIds = listOf("x"))
        val shared = Note(id = "x")
        val sharedHolder = Note(id = "h2", notebook = "Casa", noteLinkIds = listOf("x"))
        val loose = Note(id = "l")
        val notes = listOf(holder, child, grandchild, visible, shared, sharedHolder, loose)

        assertEquals(setOf("h", "c", "g", "h2"), hiddenWithTrashedNotebooks(notes, setOf("Casa")))
        assertEquals(emptySet<String>(), hiddenWithTrashedNotebooks(notes, emptySet()))
    }
}
