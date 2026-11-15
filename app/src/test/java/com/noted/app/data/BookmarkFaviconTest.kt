package com.noted.app.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.noted.app.data.db.NotesDatabase
import com.noted.app.data.db.toEntity
import com.noted.app.data.db.toNote
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BookmarkFaviconTest {

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

    private fun source(name: String, text: String = "datos"): Uri {
        val dir = File(context.cacheDir, "fuentes").apply { mkdirs() }
        return Uri.fromFile(File(dir, name).apply { writeText(text) })
    }

    private fun card(id: String = "b1", favicon: String? = null, image: String? = null) =
        Bookmark(id = id, url = "https://a.com", title = "A", imageFile = image, faviconFile = favicon)

    @Test
    fun faviconSurvivesJson() {
        val back = NotesJson.bookmarksFromJson(NotesJson.bookmarksToJson(listOf(card(favicon = "b1_icon.png"))))
        assertEquals("b1_icon.png", back.single().faviconFile)
        val none = NotesJson.bookmarksFromJson(NotesJson.bookmarksToJson(listOf(card())))
        assertNull(none.single().faviconFile)
    }

    @Test
    fun faviconSurvivesRoom() {
        val note = Note(id = "n1", bookmarks = listOf(card(favicon = "b1_icon.png")))
        val back = note.toEntity().toNote()
        assertEquals("b1_icon.png", back.bookmarks.single().faviconFile)
    }

    @Test
    fun addKnownBookmarkCopiesBothImagesAndKeepsId() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(
            note.id, card(id = "known-1"), source("foto.png"), source("sitio.ico", "icono")
        )
        assertEquals("known-1", saved.id)
        assertEquals("known-1.png", saved.imageFile)
        assertEquals("known-1_icon.ico", saved.faviconFile)
        assertEquals("datos", repo.bookmarkImageFile(saved)!!.readText())
        assertEquals("icono", repo.bookmarkFaviconFile(saved)!!.readText())
        assertEquals(saved, repo.findNote(note.id)!!.bookmarks.single())
    }

    @Test
    fun importedCardWithTakenIdGetsANewIdAndKeepsTheOtherImage() = runBlocking {
        val repo = repository()
        val first = repo.createNote()
        val second = repo.createNote()
        val original = repo.addKnownBookmark(first.id, card(id = "dup"), source("a.png", "uno"), null)
        val other = repo.addKnownBookmark(second.id, card(id = "dup"), source("b.png", "dos"), null)
        assertEquals("dup", original.id)
        assertNotEquals("dup", other.id)
        assertEquals("uno", repo.bookmarkImageFile(original)!!.readText())
        assertEquals("dos", repo.bookmarkImageFile(other)!!.readText())
    }

    @Test
    fun unreadableImageGivesCardWithoutImage() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val missing = Uri.fromFile(File(context.cacheDir, "no-existe.png"))
        val saved = repo.addKnownBookmark(note.id, card(id = "k2"), missing, missing)
        assertNull(saved.imageFile)
        assertNull(saved.faviconFile)
        assertEquals(1, repo.findNote(note.id)!!.bookmarks.size)
    }

    @Test
    fun noImagesGivesCardWithoutImages() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(note.id, card(id = "k3"), null, null)
        assertNull(saved.imageFile)
        assertNull(saved.faviconFile)
    }

    @Test
    fun removingUnusedCardMovesFaviconAway() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(note.id, card(id = "k4"), source("a.png"), source("b.png"))
        val icon = repo.bookmarkFaviconFile(saved)!!
        assertTrue(icon.exists())
        repo.releaseUnusedContents(note.id, emptySet(), emptySet())
        assertFalse(icon.exists())
        assertFalse(repo.bookmarkImageFile(saved)!!.exists())
    }

    @Test
    fun cardKeptOnlyForUndoIsGoneAfterRestart() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(note.id, card(id = "k6"), source("a.png"), source("b.png"))
        // Se quitó del cuerpo; la pila de deshacer todavía la nombra, así que se conserva.
        repo.update(note.id) { it.copy(body = "<div>texto</div>") }
        repo.releaseUnusedContents(note.id, emptySet(), setOf(saved.id), heldBookmarkIds = setOf(saved.id))
        assertEquals(1, repo.findNote(note.id)!!.bookmarks.size)
        repo.flushAndWait()

        // La app se cerró: la pila se perdió y la tarjeta ya no vuelve.
        val again = repository()
        assertTrue(again.findNote(note.id)!!.bookmarks.isEmpty())
        assertFalse(again.bookmarkImageFile(saved)!!.exists())
    }

    @Test
    fun cardKeptForUndoAndPutBackStays() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(note.id, card(id = "k7"), null, null)
        repo.releaseUnusedContents(note.id, emptySet(), setOf(saved.id), heldBookmarkIds = setOf(saved.id))
        // Deshacer la devolvió al cuerpo antes de cerrar la app.
        repo.update(note.id) { it.copy(body = "<div data-noted-bookmark=\"k7\"></div>") }
        repo.flushAndWait()

        val again = repository()
        assertEquals(listOf("k7"), again.findNote(note.id)!!.bookmarks.map { it.id })
    }

    @Test
    fun emptyTrashDeletesFavicon() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(note.id, card(id = "k5"), source("a.png"), source("b.png"))
        val icon = repo.bookmarkFaviconFile(saved)!!
        repo.update(note.id) { it.copy(trashed = true) }
        repo.emptyTrash()
        assertFalse(icon.exists())
    }

    @Test
    fun deletePermanentlyDeletesFavicon() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(note.id, card(id = "k6"), source("a.png"), source("b.png"))
        val icon = repo.bookmarkFaviconFile(saved)!!
        repo.deletePermanently(note.id)
        assertFalse(icon.exists())
    }

    @Test
    fun orphanCleanupKeepsLiveFaviconAndRemovesStray() = runBlocking {
        val repo = repository()
        val note = repo.createNote()
        val saved = repo.addKnownBookmark(note.id, card(id = "k7"), source("a.png"), source("b.png"))
        val stray = File(context.filesDir, "bookmarks/suelto_icon.png").apply { writeText("x") }
        repo.flushAndWait()
        val again = NotesRepository(context, db, true).also { runBlocking { it.awaitLoaded() } }
        // La limpieza corre justo después de completar la carga.
        val deadline = System.currentTimeMillis() + 5000
        while (File(context.filesDir, "huerfanos").walkTopDown().none { it.name == "suelto_icon.png" } &&
            System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(stray.exists())
        assertTrue(again.bookmarkFaviconFile(saved)!!.exists())
        assertTrue(again.bookmarkImageFile(saved)!!.exists())
    }
}
