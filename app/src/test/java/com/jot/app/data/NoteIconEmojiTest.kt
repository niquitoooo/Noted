package com.jot.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jot.app.data.db.NotesDatabase
import com.jot.app.ui.emojiOf
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
class NoteIconEmojiTest {

    private lateinit var context: Context
    private lateinit var db: NotesDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("jot", Context.MODE_PRIVATE).edit().clear().commit()
        db = NotesDatabase.inMemory(context)
    }

    @After
    fun tearDown() = db.close()

    private fun repository() =
        NotesRepository(context, db, true).also { runBlocking { it.awaitLoaded() } }

    @Test
    fun `setEmojiIcon guarda emoji`() {
        val repo = repository()
        val note = repo.createNote()
        repo.setEmojiIcon(note.id, "💡")
        assertEquals("emoji:💡", repo.findNote(note.id)!!.icon)
    }

    @Test
    fun `setEmojiIcon borra el archivo propio anterior`() {
        val repo = repository()
        val note = repo.createNote()
        val dir = File(context.filesDir, "icons").apply { mkdirs() }
        val file = File(dir, "${note.id}_viejo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        repo.update(note.id) { it.copy(icon = NotesRepository.ICON_FILE + file.name) }
        assertTrue(repo.iconFile(repo.findNote(note.id)!!)!!.exists())
        repo.setEmojiIcon(note.id, "💡")
        assertFalse(file.exists())
        assertEquals("emoji:💡", repo.findNote(note.id)!!.icon)
    }

    @Test
    fun `setEmojiIcon null quita el icono`() {
        val repo = repository()
        val note = repo.createNote()
        repo.setEmojiIcon(note.id, "💡")
        repo.setEmojiIcon(note.id, null)
        assertNull(repo.findNote(note.id)!!.icon)
    }

    @Test
    fun `una nota nueva con solo un emoji no se descarta`() {
        val repo = repository()
        val note = repo.createNote(fresh = true)
        assertTrue(repo.isFreshAndBlank(note.id))
        repo.setEmojiIcon(note.id, "💡")
        assertFalse(repo.isFreshAndBlank(note.id))
    }

    @Test
    fun `emojiOf devuelve el emoji o null`() {
        assertEquals("👨‍👩‍👧‍👦", emojiOf("emoji:👨‍👩‍👧‍👦"))
        assertNull(emojiOf("builtin:home"))
        assertNull(emojiOf(null))
        assertNull(emojiOf("emoji:"))
        assertNull(emojiOf("emoji:  "))
    }

    @Test
    fun `emoji e imagen a la vez no dejan archivos sueltos ni un ícono roto`() {
        val repo = repository()
        val note = repo.createNote()
        val svg = File(context.cacheDir, "i.svg").apply { writeText("<svg xmlns='http://www.w3.org/2000/svg'/>") }
        val uri = android.net.Uri.fromFile(svg)
        repeat(30) { n ->
            val hilos = listOf(
                Thread { runBlocking { repo.setIconFromUri(note.id, uri) } },
                Thread { repo.setEmojiIcon(note.id, "💡") },
                Thread { runBlocking { repo.setIconFromUri(note.id, uri) } },
            )
            hilos.forEach { it.start() }
            hilos.forEach { it.join() }
            val icono = repo.findNote(note.id)!!.icon
            val archivos = File(context.filesDir, "icons").listFiles().orEmpty().toList()
            if (icono!!.startsWith(NotesRepository.ICON_FILE)) {
                assertEquals("vuelta $n", listOf(icono.removePrefix(NotesRepository.ICON_FILE)), archivos.map { it.name })
            } else {
                assertEquals("vuelta $n", "emoji:💡", icono)
                assertTrue("vuelta $n: sobran $archivos", archivos.isEmpty())
            }
        }
    }

    @Test
    fun `una foto de ícono se guarda tal cual, con su tamaño y formato`() {
        val repo = repository()
        val note = repo.createNote()
        val foto = File(context.cacheDir, "foto.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(1200, 800, android.graphics.Bitmap.Config.ARGB_8888)
        foto.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        assertTrue(runBlocking { repo.setIconFromUri(note.id, android.net.Uri.fromFile(foto)) })
        val guardado = repo.iconFile(repo.findNote(note.id)!!)!!
        assertEquals("jpg", guardado.extension)
        assertTrue(foto.readBytes().contentEquals(guardado.readBytes()))
    }
}
