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
class NoteCoverTest {

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

    private fun note(repo: NotesRepository): Note =
        repo.createNote().also { repo.update(it.id) { n -> n.copy(title = "con portada") } }

    @Test
    fun `degradado y encuadre se guardan y vuelven al abrir la app`() {
        val repo = repository()
        val n = note(repo)
        repo.setCoverPreset(n.id, "gradient:red")
        repo.setCoverCrop(n.id, 0.2f, 0.9f, 2.5f)
        runBlocking { repo.flushAndWait() }

        val again = repository().findNote(n.id)!!
        assertEquals("gradient:red", again.cover)
        assertEquals(0.2f, again.coverX)
        assertEquals(0.9f, again.coverY)
        assertEquals(2.5f, again.coverScale)
    }

    @Test
    fun `el encuadre queda dentro de los limites`() {
        val repo = repository()
        val n = note(repo)
        repo.setCoverCrop(n.id, -1f, 3f, 99f)
        val got = repo.findNote(n.id)!!
        assertEquals(0f, got.coverX)
        assertEquals(1f, got.coverY)
        assertEquals(NotesRepository.MAX_COVER_ZOOM, got.coverScale)
    }

    @Test
    fun `cambiar o borrar la portada borra la imagen anterior y reinicia el encuadre`() {
        val repo = repository()
        val n = note(repo)
        val file = File(context.filesDir, "covers/${n.id}_vieja.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        repo.update(n.id) { it.copy(cover = NotesRepository.COVER_FILE + file.name, coverScale = 3f) }
        assertTrue(repo.coverFile(repo.findNote(n.id)!!)!!.exists())

        repo.setCoverPreset(n.id, "color:#376BE1")
        assertFalse(file.exists())
        assertEquals(1f, repo.findNote(n.id)!!.coverScale)

        repo.setCoverPreset(n.id, null)
        assertNull(repo.findNote(n.id)!!.cover)
    }

    @Test
    fun `borrar la nota para siempre borra su portada`() {
        val repo = repository()
        val n = note(repo)
        val file = File(context.filesDir, "covers/${n.id}_x.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        repo.update(n.id) { it.copy(cover = NotesRepository.COVER_FILE + file.name) }
        repo.moveToTrash(n.id)
        repo.emptyTrash()
        assertFalse(file.exists())
    }

    @Test
    fun `el export lleva la portada y su encuadre`() {
        val n = Note(id = "n1", title = "T", cover = "gradient:teal", coverX = 0.25f, coverY = 0.5f, coverScale = 2f)
        val md = NoteIO.noteToMarkdownFile(n, emptyMap(), emptyMap(), null, emptyMap(), "gradient:teal")
        assertTrue(md.contains("\ncover: gradient:teal\n"))
        assertTrue(md.contains("\ncoverCrop: 0.2500,0.5000,2.0000\n"))
        assertEquals(Triple(0.25f, 0.5f, 2f), NoteIO.parseCoverCrop("0.2500,0.5000,2.0000"))
        assertNull(NoteIO.parseCoverCrop("roto"))
    }
}
