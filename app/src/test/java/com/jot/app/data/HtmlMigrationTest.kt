package com.jot.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.jot.app.data.db.NotesDao
import com.jot.app.data.db.NotesDatabase
import com.jot.app.data.db.NoteEntity
import com.jot.app.data.db.NoteHistoryEntity
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class HtmlMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var db: NotesDatabase
    private lateinit var dao: NotesDao
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        filesDir = folder.newFolder("files")
        db = NotesDatabase.inMemory(context)
        dao = db.dao()
        prefs = context.getSharedPreferences("jot", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() = db.close()

    private fun json(vararg notes: Note): String =
        JSONObject().put("notes", JSONArray().apply { notes.forEach { put(NotesJson.toJson(it)) } }).toString()

    private val threeNotes = json(
        Note(id = "n1", title = "Uno", body = "hola **mundo**", sortOrder = 0L),
        Note(id = "n2", title = "Dos", body = "- [ ] tarea\n- [x] hecha", sortOrder = 1L),
        Note(id = "n3", title = "Tres", body = "# Título\n\nchau #etiqueta", sortOrder = 2L),
    )

    private fun backups() = filesDir.listFiles { f -> f.name.startsWith("notes-antes-html-") }!!.toList()

    /** [databaseExisted]: la base en memoria hace de un archivo que ya estaba. */
    private fun migration(
        databaseExisted: Boolean = true,
        verify: (String, String, List<Attachment>, List<Bookmark>) -> List<String> =
            com.jot.app.data.html.ConversionCheck::verify,
    ) = HtmlMigration(filesDir, dao, prefs, databaseExisted, verify)

    @Test
    fun migratesAllNotesAndKeepsBackup() = runTest {
        File(filesDir, "notes.json").writeText(threeNotes)

        val report = migration().runIfNeeded()

        assertEquals(HtmlMigration.Report(3, emptyList()), report)
        val rows = dao.all().associateBy { it.id }
        assertEquals(3, rows.size)
        assertEquals(com.jot.app.data.html.NoteMarkdown.toHtml("hola **mundo**", emptyList(), emptyList()), rows.getValue("n1").html)
        assertTrue(rows.getValue("n1").html.startsWith("<div>"))
        assertEquals("hola mundo", rows.getValue("n1").summary)
        assertTrue(rows.getValue("n2").hasTodo)
        assertEquals("[\"etiqueta\"]", rows.getValue("n3").tags)
        rows.values.forEach { assertNull(it.legacyMarkdown) }
        assertEquals(1, backups().size)
        assertEquals(threeNotes, backups().single().readText())
        assertEquals(threeNotes, File(filesDir, "notes.json").readText())
        assertTrue(prefs.getBoolean(HtmlMigration.FLAG, false))
    }

    @Test
    fun oldBodyWithTagsAndEqualsMigratesAsText() = runTest {
        // En una nota vieja en Markdown, "<u>" y "==x==" eran texto: no se leen como marcas.
        val body = "a <u>b</u> ==x== \\<u>"
        File(filesDir, "notes.json").writeText(json(Note(id = "n1", title = "Uno", body = body, sortOrder = 0L)))

        val report = migration().runIfNeeded()

        assertEquals(HtmlMigration.Report(1, emptyList()), report)
        val row = dao.all().single()
        assertEquals("<div>a &lt;u&gt;b&lt;/u&gt; ==x== \\&lt;u&gt;</div>", row.html)
        assertNull(row.legacyMarkdown)
    }

    @Test
    fun failedNoteKeepsLegacyMarkdown() = runTest {
        File(filesDir, "notes.json").writeText(threeNotes)

        val report = migration { md, _, _, _ -> if (md.startsWith("- [ ]")) listOf("falla") else emptyList() }
            .runIfNeeded()

        assertEquals(HtmlMigration.Report(3, listOf("Dos")), report)
        val rows = dao.all().associateBy { it.id }
        assertEquals("- [ ] tarea\n- [x] hecha", rows.getValue("n2").legacyMarkdown)
        assertTrue(rows.getValue("n2").html.startsWith("<"))
        assertNull(rows.getValue("n1").legacyMarkdown)
    }

    @Test
    fun failedUntitledNoteIsReportedAsSinTitulo() = runTest {
        File(filesDir, "notes.json").writeText(json(Note(id = "n1", title = "  ", body = "x", sortOrder = 0L)))

        val report = migration { _, _, _, _ -> listOf("falla") }.runIfNeeded()

        assertEquals(HtmlMigration.Report(1, listOf("Sin título")), report)
    }

    @Test
    fun crashingNoteIsKeptAsTextLines() = runTest {
        val body = "<b>a & b</b> \"comillas\"\n\n  - [ ] con sangría\r\n\\[x](jot://att/zz)\n"
        File(filesDir, "notes.json").writeText(json(
            Note(id = "n1", title = "Rota", body = body, sortOrder = 0L),
            Note(id = "n2", title = "Sana", body = "hola", sortOrder = 1L),
        ))

        val report = migration { md, html, attachments, bookmarks ->
            if (md == body) throw IllegalStateException("se cayó")
            com.jot.app.data.html.ConversionCheck.verify(md, html, attachments, bookmarks)
        }.runIfNeeded()

        assertEquals(HtmlMigration.Report(2, listOf("Rota")), report)
        val rows = dao.all().associateBy { it.id }
        val broken = rows.getValue("n1")
        assertEquals(body, broken.legacyMarkdown)
        val divs = org.jsoup.Jsoup.parseBodyFragment(broken.html).body().children()
        assertTrue(divs.all { it.normalName() == "div" })
        // El renglón vacío es `<div><br></div>`: su texto es solo el de sus nodos de texto.
        assertEquals(body, divs.joinToString("\n") { div -> div.textNodes().joinToString("") { it.wholeText } })
        assertNull(rows.getValue("n2").legacyMarkdown)
        assertTrue(prefs.getBoolean(HtmlMigration.FLAG, false))
    }

    @Test
    fun restoredFlagWithoutDatabaseMigratesAgain() = runTest {
        File(filesDir, "notes.json").writeText(threeNotes)
        // Android restauró las preferencias de una copia, pero no la base.
        prefs.edit().putBoolean(HtmlMigration.FLAG, true).commit()

        val report = migration(databaseExisted = false).runIfNeeded()

        assertEquals(3, report!!.total)
        assertEquals(setOf("n1", "n2", "n3"), dao.all().map { it.id }.toSet())
        assertEquals(1, backups().size)
    }

    @Test
    fun restoredFlagWithoutDatabaseReadsTmp() = runTest {
        File(filesDir, "notes.json.tmp").writeText(threeNotes)
        prefs.edit().putBoolean(HtmlMigration.FLAG, true).commit()

        assertEquals(3, migration(databaseExisted = false).runIfNeeded()!!.total)
        assertEquals(3, dao.all().size)
    }

    @Test
    fun flagWithoutDatabaseAndNoJsonDoesNothing() = runTest {
        prefs.edit().putBoolean(HtmlMigration.FLAG, true).commit()

        assertNull(migration(databaseExisted = false).runIfNeeded())
        assertEquals(0, backups().size)
    }

    @Test
    fun flagWithEmptyExistingDatabaseDoesNotBringNotesBack() = runTest {
        // El usuario borró todas sus notas: la base existe y está vacía, notes.json sigue ahí.
        File(filesDir, "notes.json").writeText(threeNotes)
        prefs.edit().putBoolean(HtmlMigration.FLAG, true).commit()

        assertNull(migration(databaseExisted = true).runIfNeeded())
        assertEquals(0, dao.all().size)
        assertEquals(0, backups().size)
    }

    @Test
    fun secondRunDoesNothing() = runTest {
        File(filesDir, "notes.json").writeText(threeNotes)
        migration().runIfNeeded()
        File(filesDir, "notes.json").writeText(json(Note(id = "otra", title = "Otra", body = "x", sortOrder = 0L)))

        assertNull(migration().runIfNeeded())
        assertEquals(setOf("n1", "n2", "n3"), dao.all().map { it.id }.toSet())
        assertEquals(1, backups().size)
    }

    @Test
    fun rerunAfterCrashIsIdempotent() = runTest {
        File(filesDir, "notes.json").writeText(threeNotes)
        migration().runIfNeeded()
        val first = backups().single()
        // La app murió después de guardar y antes de prender la bandera.
        prefs.edit().putBoolean(HtmlMigration.FLAG, false).commit()

        val report = migration().runIfNeeded()

        assertEquals(3, report!!.total)
        assertEquals(3, dao.all().size)
        assertEquals(2, backups().size)
        assertTrue(first.exists())
        assertEquals(threeNotes, first.readText())
    }

    @Test
    fun failedSaveLeavesFlagOff() = runTest {
        File(filesDir, "notes.json").writeText(threeNotes)
        val failingDao = object : NotesDao by dao {
            override suspend fun save(
                upserts: List<NoteEntity>,
                deletes: List<String>,
                history: List<NoteHistoryEntity>,
                keep: Int
            ) = throw IOException("disco lleno")
        }

        val result = runCatching { HtmlMigration(filesDir, failingDao, prefs, databaseExisted = true).runIfNeeded() }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(false, prefs.getBoolean(HtmlMigration.FLAG, false))
        assertEquals(1, backups().size)
    }

    @Test
    fun noJsonMeansFreshInstall() = runTest {
        val report = migration().runIfNeeded()

        assertEquals(HtmlMigration.Report(0, emptyList()), report)
        assertTrue(prefs.getBoolean(HtmlMigration.FLAG, false))
        assertEquals(0, dao.all().size)
        assertEquals(0, backups().size)
    }

    @Test
    fun readsTmpWhenJsonIsMissing() = runTest {
        File(filesDir, "notes.json.tmp").writeText(threeNotes)

        val report = migration().runIfNeeded()

        assertEquals(3, report!!.total)
        assertEquals(3, dao.all().size)
        assertEquals(threeNotes, backups().single().readText())
    }

    @Test
    fun legacyFileGetsSortOrder() = runTest {
        val legacy = JSONObject().put("notes", JSONArray().apply {
            put(NotesJson.toJson(Note(id = "vieja", title = "Vieja", body = "a", updatedAt = 1L)).apply { remove("sortOrder") })
            put(NotesJson.toJson(Note(id = "nueva", title = "Nueva", body = "b", updatedAt = 2L)).apply { remove("sortOrder") })
        }).toString()
        File(filesDir, "notes.json").writeText(legacy)

        migration().runIfNeeded()

        val rows = dao.all().associateBy { it.id }
        assertEquals(0L, rows.getValue("nueva").sortOrder)
        assertEquals(1L, rows.getValue("vieja").sortOrder)
    }

    @Test
    fun damagedFileKeepsDamagedCopy() = runTest {
        val damaged = JSONObject().put("notes", JSONArray().apply {
            put(NotesJson.toJson(Note(id = "n1", title = "Uno", body = "a", sortOrder = 0L)))
            put("no es una nota")
        }).toString()
        File(filesDir, "notes.json").writeText(damaged)

        val report = migration().runIfNeeded()

        assertEquals(1, report!!.total)
        val copy = filesDir.listFiles { f -> f.name.startsWith("notes-danado-") }!!.single()
        assertEquals(damaged, copy.readText())
        assertTrue(report.damaged)
        assertEquals(copy.name, report.damagedBackup)
    }
}
