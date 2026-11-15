package com.noted.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noted.app.data.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedLinkTest {

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

    private fun repository(): NotesRepository =
        NotesRepository(context, db, true).also { runBlocking { it.awaitLoaded() } }

    @Test
    fun wholeTextUrlIsALink() {
        val shared = SharedLink("Ejemplo de dominio", "https://example.com/")
        assertEquals("example.com", shared.host)
        assertEquals("https://example.com/", shared.url)
        assertEquals("es.wikipedia.org", SharedLink.hostOf(" https://es.wikipedia.org/wiki/Tardigrada \n"))
    }

    @Test
    fun textWithALinkInsideIsNotALink() {
        // "Mira esto https://…" es texto, sin sitio.
        assertNull(SharedLink("", "Mira esto https://example.com/?zz=6").host)
        assertNull(SharedLink("", "").host)
        assertNull(SharedLink("", "solo texto").url)
    }

    @Test
    fun savedNoteHasSubjectTextAndNotebook() {
        val repo = repository()
        val note = repo.saveShared(SharedLink("ZZ asunto", "Mira esto https://example.com/?zz=6"), "Viajes")
        val saved = repo.findNote(note.id)!!
        assertEquals("ZZ asunto", saved.title)
        assertEquals("Viajes", saved.notebook)
        assertTrue(saved.body.contains("Mira esto https://example.com/?zz=6"))
        // Primera de la lista.
        assertEquals(note.id, repo.notes.value.first { !it.pinned }.id)
        assertTrue(saved.bookmarks.isEmpty())
    }

    @Test
    fun sharedImagesGoAboveTheTextAndTheCopiesAreDeleted() = runBlocking {
        val repo = repository()
        val note = repo.saveShared(SharedLink("Fotos", "texto"), null)
        val dir = java.io.File(context.cacheDir, "compartir").apply { mkdirs() }
        val png = android.graphics.Bitmap.createBitmap(4, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val files = listOf("a.png", "b.png").map { name ->
            java.io.File(dir, name).also { f -> f.outputStream().use { png.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }

        repo.addSharedImages(note.id, files)

        val saved = repo.findNote(note.id)!!
        assertEquals(2, saved.attachments.size)
        // Las dos fotos, en su orden, antes del texto.
        val first = saved.body.indexOf(saved.attachments[0].id)
        val second = saved.body.indexOf(saved.attachments[1].id)
        assertTrue(first in 0 until second)
        assertTrue(second < saved.body.indexOf("texto"))
        assertTrue(files.none { it.exists() })
    }

    @Test
    fun withoutSubjectTheNoteHasNoTitle() {
        val repo = repository()
        val note = repo.saveShared(SharedLink("", "solo texto"), null)
        assertEquals("", repo.findNote(note.id)!!.title)
        assertNull(repo.findNote(note.id)!!.notebook)
    }
}
