package com.jot.app.data

import android.content.SharedPreferences
import com.jot.app.data.db.NotesDao
import com.jot.app.data.db.toEntity
import com.jot.app.data.html.ConversionCheck
import com.jot.app.data.html.NoteMarkdown
import com.jot.app.data.html.withDerived
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pasa una sola vez las notas de notes.json (cuerpo en Markdown) a Room con el cuerpo en
 * HTML. notes.json no se toca, y antes de leerlo se deja una copia
 * `notes-antes-html-<ms>.json` que nunca se borra. Si la app muere a la mitad, la bandera
 * sigue apagada y la próxima vez se repite entera: `save` reemplaza las filas por id.
 * También se repite si la bandera está prendida pero el archivo de la base no existía
 * ([databaseExisted] en falso) y notes.json sigue ahí: pasa cuando Android restaura las
 * preferencias de una copia y no la base. Una base que existe y está vacía no alcanza: así
 * queda cuando el usuario borra todas sus notas, y no tienen que volver.
 */
class HtmlMigration(
    private val filesDir: File,
    private val dao: NotesDao,
    private val prefs: SharedPreferences,
    private val databaseExisted: Boolean,
    private val verify: (String, String, List<Attachment>, List<Bookmark>) -> List<String> =
        ConversionCheck::verify,
) {
    /**
     * [needsReview]: los títulos de las notas que quedaron con su Markdown porque no pasaron el
     * control. [damaged]: notes.json no se pudo leer entero; [damagedBackup] es el nombre de la
     * copia `notes-danado-<ms>.json` que se dejó de él.
     */
    data class Report(
        val total: Int,
        val needsReview: List<String>,
        val damaged: Boolean = false,
        val damagedBackup: String? = null,
    )

    suspend fun runIfNeeded(): Report? = withContext(Dispatchers.IO) {
        // Si la app murió en medio de un guardado de la versión anterior (la de notes.json),
        // puede haber quedado solo el .tmp.
        val index = File(filesDir, "notes.json")
        val temp = File(filesDir, "notes.json.tmp")
        val source = when {
            index.exists() -> index
            temp.exists() -> temp
            else -> null
        }
        if (prefs.getBoolean(FLAG, false) && (source == null || databaseExisted)) return@withContext null
        if (source == null) {
            markDone()
            return@withContext Report(0, emptyList())
        }

        // Si la copia falla, la excepción sube y no se migra nada.
        source.copyTo(newBackupFile())
        val parsed = NotesJson.parse(source.readText())
        val damagedBackup = if (parsed.damaged) NotesJson.backupDamaged(source, filesDir) else null

        val needsReview = ArrayList<String>()
        val notes = NotesJson.withSortOrder(parsed.notes).map { note ->
            val md = note.body
            // Si la conversión o el control se caen con una nota, esa nota queda como texto
            // y las demás siguen.
            val converted = try {
                val html = NoteMarkdown.toHtml(md, note.attachments, note.bookmarks, inlineMarks = false)
                html to verify(md, html, note.attachments, note.bookmarks).isEmpty()
            } catch (e: Exception) {
                textLines(md) to false
            }
            val (html, passed) = converted
            if (!passed) needsReview.add(note.title.ifBlank { "Sin título" })
            note.copy(body = html, legacyMarkdown = if (passed) null else md).withDerived()
        }
        dao.save(upserts = notes.map { it.toEntity() }, deletes = emptyList(), history = emptyList(), keep = 50)
        markDone()
        Report(notes.size, needsReview, parsed.damaged, damagedBackup)
    }

    /**
     * [markdown] como texto, un `<div>` por renglón, sin interpretar ninguna marca. Se escapa
     * lo que el HTML leería distinto, así no se pierde ningún carácter.
     */
    private fun textLines(markdown: String): String =
        markdown.split('\n').joinToString("") { line ->
            if (line.isEmpty()) "<div><br></div>"
            else "<div>" + line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", "&#13;") + "</div>"
        }

    /** Un nombre que no existe todavía: una copia de una vuelta anterior no se pisa. */
    private fun newBackupFile(): File {
        var ms = System.currentTimeMillis()
        while (File(filesDir, "notes-antes-html-$ms.json").exists()) ms++
        return File(filesDir, "notes-antes-html-$ms.json")
    }

    private fun markDone() {
        prefs.edit().putBoolean(FLAG, true).commit()
    }

    companion object {
        const val FLAG = "html_migration_done"
    }
}
