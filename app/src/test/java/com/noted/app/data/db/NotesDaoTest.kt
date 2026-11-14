package com.noted.app.data.db

import androidx.test.core.app.ApplicationProvider
import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import com.noted.app.data.Note
import com.noted.app.data.NotesJson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotesDaoTest {
    private lateinit var db: NotesDatabase
    private lateinit var dao: NotesDao

    @Before
    fun setUp() {
        db = NotesDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.dao()
    }

    @After
    fun tearDown() = db.close()

    private fun note(id: String, body: String = "<div>$id</div>") = Note(id = id, title = id, body = body)

    @Test
    fun noteRoundTripsThroughEntity() {
        val note = Note(
            id = "n1",
            title = "Título",
            body = "<div>hola #tag</div>",
            notebook = "libreta",
            tags = listOf("tag", "otra"),
            pinned = true,
            trashed = true,
            createdAt = 10L,
            updatedAt = 20L,
            sortOrder = 7L,
            attachments = listOf(Attachment("a1", "n1", "foto.png", "image/png", 123L, "a1.png", 5L)),
            bookmarks = listOf(
                Bookmark("b1", "https://x.com", "X", "desc", "https://x.com/i.png", "i.png", "X.com", 6L),
                Bookmark(id = "b2", url = "https://y.com", title = "Y", createdAt = 8L)
            ),
            icon = "builtin:star",
            trashedWith = "n0",
            plainText = "hola #tag",
            summary = "hola #tag",
            hasTodo = true,
            noteLinkIds = listOf("n2", "n3"),
            revision = 4L,
            legacyMarkdown = "hola #tag"
        )
        assertEquals(note, note.toEntity().toNote())
        val sinOpcionales = note.copy(
            notebook = null, icon = null, trashedWith = null, legacyMarkdown = null,
            attachments = emptyList(), bookmarks = emptyList(), tags = emptyList(),
            noteLinkIds = emptyList()
        )
        assertEquals(sinOpcionales, sinOpcionales.toEntity().toNote())
    }

    @Test
    fun saveUpsertsAndDeletes() = runTest {
        dao.save(listOf(note("a"), note("b")).map { it.toEntity() }, emptyList(), emptyList(), 50)
        assertEquals(setOf("a", "b"), dao.all().map { it.id }.toSet())

        val changed = note("a", "<div>nuevo</div>").toEntity()
        dao.save(listOf(changed), listOf("b"), emptyList(), 50)
        val all = dao.all()
        assertEquals(listOf("a"), all.map { it.id })
        assertEquals("<div>nuevo</div>", all.single().html)
    }

    @Test
    fun deleteAlsoDropsHistory() = runTest {
        val entry = NoteHistoryEntity(noteId = "b", revision = 1, savedAt = 1, html = HistoryCodec.pack("x"))
        dao.save(listOf(note("b").toEntity()), emptyList(), listOf(entry), 50)
        assertEquals(1, dao.historyCount("b"))
        dao.save(emptyList(), listOf("b"), emptyList(), 50)
        assertEquals(0, dao.historyCount("b"))
    }

    @Test
    fun historyKeepsLast50() = runTest {
        val entries = (1..60).map {
            NoteHistoryEntity(noteId = "n", revision = it.toLong(), savedAt = it * 10L, html = HistoryCodec.pack("v$it"))
        }
        // Otra nota con historial no se toca.
        val other = NoteHistoryEntity(noteId = "otra", revision = 1, savedAt = 1, html = HistoryCodec.pack("o"))
        dao.insertHistory(other)
        dao.save(listOf(note("n").toEntity()), emptyList(), entries, 50)
        assertEquals(50, dao.historyCount("n"))
        assertEquals(1, dao.historyCount("otra"))

        val cursor = db.openHelper.readableDatabase
            .query("SELECT MIN(revision), MAX(revision) FROM note_history WHERE noteId = 'n'")
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(11L, it.getLong(0))
            assertEquals(60L, it.getLong(1))
        }
    }

    @Test
    fun historyCodecRoundTrip() {
        val html = "<div>Ñandú — 日本語 &lt;b&gt; ${"x".repeat(10_000)}</div>"
        val packed = HistoryCodec.pack(html)
        assertTrue(packed.size < html.length)
        assertEquals(html, HistoryCodec.unpack(packed))
        assertEquals("", HistoryCodec.unpack(HistoryCodec.pack("")))
    }

    @Test
    fun parseSkipsDamagedNotes() {
        val text = """{"version":1,"notes":[
            {"id":"ok1","title":"uno","body":"hola"},
            {"id":"mala","title":"x","attachments":[5]},
            {"id":"ok2","title":"dos","body":"chau"}
        ]}"""
        val parsed = NotesJson.parse(text)
        assertTrue(parsed.damaged)
        assertEquals(listOf("ok1", "ok2"), parsed.notes.map { it.id })

        val clean = NotesJson.parse("""{"version":1,"notes":[{"id":"a"}]}""")
        assertFalse(clean.damaged)
        assertEquals(1, clean.notes.size)

        val broken = NotesJson.parse("{no es json")
        assertTrue(broken.damaged)
        assertTrue(broken.notes.isEmpty())
    }

    @Test
    fun saveDeletesMoreThanSqliteLimit() = runTest {
        val ids = (1..1200).map { "n$it" }
        dao.save(upserts = ids.map { note(it).toEntity() }, deletes = emptyList(), history = emptyList(), keep = 50)

        dao.save(upserts = emptyList(), deletes = ids, history = emptyList(), keep = 50)

        assertTrue(dao.all().isEmpty())
    }
}
