package com.noted.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.noted.app.data.db.HistoryCodec
import com.noted.app.data.db.NotesDatabase
import com.noted.app.data.html.NoteMarkdown
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NotesRepositoryRoomTest {

    private lateinit var context: Context
    private lateinit var db: NotesDatabase
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        prefs = context.getSharedPreferences("noted", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        db = NotesDatabase.inMemory(context)
    }

    @After
    fun tearDown() = db.close()

    private fun html(markdown: String) = NoteMarkdown.toHtml(markdown, emptyList())

    private fun writeNotesJson(vararg notes: Note) {
        val array = JSONArray().apply { notes.forEach { put(NotesJson.toJson(it)) } }
        File(context.filesDir, "notes.json").writeText(JSONObject().put("notes", array).toString())
    }

    /** [databaseExisted]: la base en memoria hace de un archivo que ya estaba. */
    private fun repository(databaseExisted: Boolean = true): NotesRepository =
        NotesRepository(context, db, databaseExisted).also { runBlocking { it.awaitLoaded() } }

    private fun rows() = runBlocking { db.dao().all() }.associateBy { it.id }

    private fun historyCount(noteId: String) = runBlocking { db.dao().historyCount(noteId) }

    /** Las versiones viejas guardadas de la nota, de la más vieja a la más nueva. */
    private fun history(noteId: String): List<Pair<Long, String>> {
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT revision, html FROM note_history WHERE noteId = ? ORDER BY revision",
            arrayOf(noteId)
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(it.getLong(0) to HistoryCodec.unpack(it.getBlob(1)))
            }
        }
    }

    @Test
    fun loadRunsMigrationThenReadsRoom() {
        writeNotesJson(Note(id = "n1", title = "Uno", body = "hola **mundo**", sortOrder = 0L))

        val repo = repository()

        val note = repo.notes.value.single()
        assertEquals(html("hola **mundo**"), note.body)
        assertEquals("hola mundo", note.summary)
        assertEquals(setOf("n1"), rows().keys)
        assertTrue(prefs.getBoolean(HtmlMigration.FLAG, false))
        assertNull(repo.takePendingProblem())
    }

    @Test
    fun loadReadsRoomWhenAlreadyMigrated() {
        prefs.edit().putBoolean(HtmlMigration.FLAG, true).commit()
        // notes.json ya no se lee: manda lo que está en Room.
        writeNotesJson(Note(id = "viejo", title = "Viejo", body = "x"))
        val first = repository()
        first.upsert(Note(id = "n1", title = "Uno", body = html("hola")))
        runBlocking { first.flushAndWait() }

        val second = repository()

        assertEquals(listOf("n1"), second.notes.value.map { it.id })
        assertEquals("hola", second.notes.value.single().summary)
    }

    @Test
    fun damagedNotesJsonIsReportedWithItsCopy() {
        File(context.filesDir, "notes.json").writeText("{\"notes\": [")

        val repo = repository()

        val problem = repo.takePendingProblem()
        assertNotNull(problem)
        assertTrue(problem!!, problem.startsWith("El archivo de notas estaba dañado: puede que falten notas."))
        val copy = context.filesDir.listFiles()!!.single { it.name.startsWith("notes-danado-") }
        assertTrue(problem, problem.contains("(${copy.name})"))
    }

    @Test
    fun failedMigrationReportsAndWritesNothing() {
        // Una carpeta en lugar del archivo: leerlo tira una excepción de E/S.
        File(context.filesDir, "notes.json").mkdirs()

        val repo = repository()

        assertNotNull(repo.takePendingProblem())
        assertFalse(prefs.getBoolean(HtmlMigration.FLAG, false))
        repo.upsert(Note(id = "n1", title = "Uno", body = html("hola")))
        runBlocking { repo.flushAndWait() }
        assertTrue(rows().isEmpty())
    }

    @Test
    fun updateDerivesFields() {
        val repo = repository()
        val note = repo.createNote()

        repo.update(note.id) { it.copy(body = html("Hola #etiqueta\n- [ ] x")) }

        val updated = repo.findNote(note.id)!!
        assertTrue(updated.hasTodo)
        assertEquals("Hola #etiqueta", updated.summary)
        assertEquals("Hola #etiqueta\nx", updated.plainText)
        assertEquals(listOf("etiqueta"), updated.tags)
    }

    @Test
    fun persistWritesHistoryOnlyWhenBodyChanges() {
        val repo = repository()
        val note = repo.createNote()
        repo.update(note.id) { it.copy(body = html("primera")) }
        runBlocking { repo.flushAndWait() }
        assertEquals(0, historyCount(note.id))

        repo.togglePin(note.id)
        runBlocking { repo.flushAndWait() }
        assertEquals(0, historyCount(note.id))
        assertEquals(0L, rows().getValue(note.id).revision)
        assertTrue(rows().getValue(note.id).pinned)

        repo.update(note.id) { it.copy(body = html("segunda")) }
        runBlocking { repo.flushAndWait() }
        assertEquals(listOf(0L to html("primera")), history(note.id))
        assertEquals(1L, rows().getValue(note.id).revision)
        assertEquals(html("segunda"), rows().getValue(note.id).html)
        assertEquals(1L, repo.findNote(note.id)!!.revision)

        // Sin cambios no se guarda otra versión.
        runBlocking { repo.flushAndWait() }
        assertEquals(1, historyCount(note.id))
    }

    @Test
    fun deletePermanentlyRemovesRowsAndHistory() {
        val repo = repository()
        val note = repo.createNote()
        repo.update(note.id) { it.copy(body = html("uno")) }
        runBlocking { repo.flushAndWait() }
        repo.update(note.id) { it.copy(body = html("dos")) }
        runBlocking { repo.flushAndWait() }
        assertEquals(1, historyCount(note.id))

        repo.deletePermanently(note.id)
        runBlocking { repo.flushAndWait() }

        assertFalse(note.id in rows())
        assertEquals(0, historyCount(note.id))
    }

    @Test
    fun emptyTrashReturnsTheEmptiedNotes() {
        val repo = repository()
        val kept = repo.createNote()
        val first = repo.createNote()
        val second = repo.createNote()
        repo.moveToTrash(first.id)
        repo.moveToTrash(second.id)

        // El ViewModel borra con estas ids lo que la nota dejó guardado aparte (checklist_closed_*).
        assertEquals(setOf(first.id, second.id), repo.emptyTrash().toSet())
        assertEquals(listOf(kept.id), repo.notes.value.map { it.id })
    }

    @Test
    fun freshEmptyNoteIsNotSaved() {
        val repo = repository()
        val note = repo.createNote(fresh = true)
        runBlocking { repo.flushAndWait() }
        assertTrue(rows().isEmpty())

        repo.update(note.id) { it.copy(title = "Ahora sí") }
        runBlocking { repo.flushAndWait() }
        assertEquals("Ahora sí", rows().getValue(note.id).title)
    }

    @Test
    fun freshEmptyNoteInsideAnotherIsSaved() {
        val repo = repository()
        val parent = repo.createNote()
        val child = repo.createNote(fresh = true)
        repo.update(parent.id) { it.copy(body = html("[Hija](noted://note/${child.id})")) }

        runBlocking { repo.flushAndWait() }

        // Si la app muere antes de descartarla, la tarjeta de la otra apunta a algo que existe.
        assertEquals(setOf(parent.id, child.id), rows().keys)
    }

    @Test
    fun nestedNoteIdsUsesDerivedLinks() {
        // El cuerpo no tiene el enlace: cuenta solo noteLinkIds.
        val parent = Note(id = "a", noteLinkIds = listOf("b"))
        val child = Note(id = "b")
        val notes = listOf(parent, child)

        assertEquals(setOf("b"), nestedNoteIds(notes))
        assertEquals(setOf("b"), notesTrashedWith(notes, "a"))
        assertEquals(setOf("b"), linkedNoteIds(parent))
        assertEquals("a", containerOf(notes, "b")?.id)
    }

    @Test
    fun discardIfEmptyRemovesChildLinkFromParents() {
        val repo = repository()
        val parent = repo.createNote()
        val child = repo.createNote(fresh = true)
        repo.update(parent.id) {
            it.copy(body = html("antes\n[Hija](noted://note/${child.id})\ndespués"))
        }
        assertEquals(listOf(child.id), repo.findNote(parent.id)!!.noteLinkIds)

        assertTrue(repo.discardIfEmpty(child.id))

        val updated = repo.findNote(parent.id)!!
        assertEquals(emptyList<String>(), updated.noteLinkIds)
        assertEquals("antes\ndespués", updated.plainText)
        assertNull(repo.findNote(child.id))
    }

    @Test
    fun discardIfEmptyKeepsNoteWithEmptyCheckbox() {
        val repo = repository()
        val note = repo.createNote(fresh = true)
        repo.update(note.id) { it.copy(body = html("- [ ] ")) }

        assertFalse(repo.discardIfEmpty(note.id))
        assertNotNull(repo.findNote(note.id))
    }

    @Test
    fun searchIdsReadsWhatWasSaved() {
        val repo = repository()
        val note = repo.createNote()
        repo.update(note.id) { it.copy(title = "Receta de Ñoquis", body = html("con papa")) }
        runBlocking { repo.flushAndWait() }

        assertEquals(setOf(note.id), runBlocking { repo.searchIds("ÑOQUI") })
        assertEquals(setOf(note.id), runBlocking { repo.searchIds("papa") })
        assertEquals(emptySet<String>(), runBlocking { repo.searchIds("batata") })
    }

    /** Lo que la columna no puede buscar vuelve null: la lista busca en memoria, como antes. */
    @Test
    fun searchIdsGivesUpOnWhatTheColumnCannotSearch() {
        val repo = repository()
        assertNull(runBlocking { repo.searchIds("") })
        assertNull(runBlocking { repo.searchIds("ab") })
    }
}
