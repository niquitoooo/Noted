package com.jot.app.ui

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.jot.app.data.Attachment
import com.jot.app.data.Bookmark
import com.jot.app.data.Note
import com.jot.app.data.NotesRepository
import com.jot.app.data.db.NotesDatabase
import com.jot.app.ui.rich.RichText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * El deshacer de toda la nota sobre la lista de bloques, como lo usa el editor: cada
 * cambio pasa por [BlockCommands] o [ChecklistOps] y después por [EditorUndo.bodyChanged].
 */
@RunWith(RobolectricTestRunner::class)
class NoteUndoEditorTest {

    private val photo = Attachment("p1", "n1", "foto.jpg", "image/jpeg", 10, "p1.jpg", 0)
    private val other = Attachment("p2", "n1", "otra.jpg", "image/jpeg", 10, "p2.jpg", 0)
    private val attachments = mutableMapOf("p1" to photo)
    private val card = Bookmark(id = "b1", url = "https://ejemplo.com", title = "Ejemplo", imageFile = "b1.png")
    private val bookmarks = mutableMapOf<String, Bookmark>()
    /** Las notas que existen, para los bloques de nota dentro de nota. */
    private val notes = mutableSetOf("n1")

    private var clock = 0L

    /** La nota de los tests: dos textos y un adjunto en el medio. */
    private val blocks = mutableListOf<EditorBlock>(
        EditorBlock.Text("t1", RichText("uno")),
        EditorBlock.Media("media-p1", "p1"),
        EditorBlock.Text("t2", RichText("dos")),
    )

    private val noFocus = UndoFocus(null, null, TextRange.Zero)

    /** Como el editor: un adjunto o una tarjeta está si sigue registrado en la nota. */
    private fun hasRecord(block: EditorBlock): Boolean = when (block) {
        is EditorBlock.Media -> block.attachmentId in attachments
        is EditorBlock.Bookmark -> block.bookmarkId in bookmarks
        is EditorBlock.NoteLink -> block.noteId in notes
        else -> true
    }

    private fun editor(undo: NoteUndo = NoteUndo(), list: MutableList<EditorBlock> = blocks) =
        EditorUndo(list, undo, ::hasRecord, serialize = { html(it) }) { clock }.also { it.start(noFocus) }

    private fun text(key: String, list: List<EditorBlock> = blocks): String =
        (list.first { it.key == key } as EditorBlock.Text).value.plain

    /** Escribe [value] en el texto [key] como el campo: el valor nuevo con el cursor al final. */
    private fun type(undo: EditorUndo, key: String, value: String, at: Long) {
        clock = at
        BlockCommands.updateText(blocks, key, RichText(value))
        undo.bodyChanged(typing = true, UndoFocus(key, null, TextRange(value.length)))
    }

    private fun html(list: List<EditorBlock> = blocks): String =
        HtmlBlocks.serialize(list, attachments.values.toList(), bookmarks.values.toList(), emptyMap())

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Un repositorio de verdad sobre una base en memoria, con la nota [note] y sus archivos. */
    private fun repository(note: Note, db: NotesDatabase = NotesDatabase.inMemory(context)): NotesRepository {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val repo = NotesRepository(context, db, true)
        runBlocking { repo.awaitLoaded() }
        repo.upsert(note)
        note.attachments.forEach { repo.attachmentFile(it).apply { parentFile!!.mkdirs() }.writeText("x") }
        note.bookmarks.forEach { repo.bookmarkImageFile(it)!!.apply { parentFile!!.mkdirs() }.writeText("x") }
        return repo
    }

    /** Lo que apartó el repositorio en "huerfanos", por nombre de archivo. */
    private fun quarantined(): List<String> =
        File(context.filesDir, "huerfanos").walkTopDown().filter { it.isFile }.map { it.name }.toList()

    /** Al cerrar o al ir al fondo, como el editor: queda lo que usan los bloques o la pila. */
    private fun close(repo: NotesRepository, undo: EditorUndo) {
        repo.releaseUnusedContents("n1", undo.attachmentIdsInUse(), undo.bookmarkIdsInUse())
    }

    @Test
    fun typeThenUndoRestoresText() {
        val undo = editor()
        type(undo, "t1", "unos", at = 0)

        val focus = undo.undo(UndoFocus("t1", null, TextRange(4)))

        assertEquals("uno", text("t1"))
        // Donde estaba el cambio, dentro del largo del texto de ahora.
        assertEquals(UndoFocus("t1", null, TextRange(3)), focus)
        assertEquals(UndoFocus("t1", null, TextRange(4)), undo.redo(UndoFocus("t1", null, TextRange(3))))
        assertEquals("unos", text("t1"))
    }

    @Test
    fun undoPutsCursorWhereChangeWas() {
        val undo = editor()
        type(undo, "t1", "unos", at = 0)
        type(undo, "t2", "doss", at = 1000)

        val focus = undo.undo(UndoFocus("t2", null, TextRange(4)))

        assertEquals("dos", text("t2"))
        assertEquals("unos", text("t1"))
        // En el texto del cambio que se deshizo, no en el de la foto anterior.
        assertEquals(UndoFocus("t2", null, TextRange(3)), focus)
    }

    @Test
    fun undoRemovesInsertedAttachment() {
        val undo = editor()
        clock = 1000
        val below = BlockCommands.insertAttachments(blocks, BlockTree(blocks), attachments, InsertPoint("t2", TextRange(1)), listOf(other))
        undo.bodyChanged(typing = false, UndoFocus(below, null, TextRange.Zero))
        assertTrue(blocks.any { it is EditorBlock.Media && it.attachmentId == "p2" })

        assertNotNull(undo.undo(UndoFocus(below, null, TextRange.Zero)))

        assertEquals(listOf("t1", "media-p1", "t2"), blocks.map { it.key })
        assertEquals("dos", text("t2"))
    }

    @Test
    fun undoClosesOpenGroup() {
        val undo = editor()
        // Un paso que no es escribir (como un botón de la barra), debajo del grupo.
        BlockCommands.updateText(blocks, "t1", RichText("UNO"))
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange(3)))
        type(undo, "t1", "UNOa", at = 1000)
        type(undo, "t1", "UNOab", at = 1100)

        clock = 1150
        undo.undo(UndoFocus("t1", null, TextRange(5)))
        assertEquals("UNO", text("t1"))

        // El grupo quedó cerrado: lo que se escribe enseguida es un paso nuevo y no pisa el de abajo.
        type(undo, "t1", "UNOx", at = 1200)
        undo.undo(UndoFocus("t1", null, TextRange(4)))
        assertEquals("UNO", text("t1"))
    }

    @Test
    fun undoFromChecklistItem() {
        val first = ChecklistItem("i1", RichText("leche"))
        val second = ChecklistItem("i2", RichText("pan"))
        blocks.add(EditorBlock.Checklist("c", listOf(first, second)))
        val undo = editor()

        clock = 1000
        val index = blocks.indexOfFirst { it.key == "c" }
        val checklist = blocks[index] as EditorBlock.Checklist
        blocks[index] = checklist.copy(items = ChecklistOps.toggle(checklist.items, "i1"))
        undo.bodyChanged(typing = false, UndoFocus("c", "i1", TextRange.Zero))
        assertTrue((blocks[index] as EditorBlock.Checklist).items.first { it.id == "i1" }.checked)

        val focus = undo.undo(UndoFocus("c", "i2", TextRange(2)))

        val items = (blocks.first { it.key == "c" } as EditorBlock.Checklist).items
        assertFalse(items.first { it.id == "i1" }.checked)
        assertEquals(listOf("i1", "i2"), items.map { it.id })
        // El cursor vuelve al ítem que se marcó, no al que tenía el foco.
        assertEquals(UndoFocus("c", "i1", TextRange.Zero), focus)
    }

    @Test
    fun undoAfterSaveSavesAgain() {
        val openedHtml = html()
        val undo = editor()
        type(undo, "t1", "unos", at = 0)
        val firstSave = bodyToSave(html(), openedHtml, openedHtml)
        type(undo, "t1", "unos más", at = 3000)
        val lastSaved = bodyToSave(html(), openedHtml, openedHtml)
        assertNotEquals(firstSave, lastSaved)

        undo.undo(UndoFocus("t1", null, TextRange(8)))

        // El autoguardado compara con lo último guardado: el HTML de antes vuelve a guardarse.
        val afterUndo = bodyToSave(html(), openedHtml, openedHtml)
        assertNotEquals(lastSaved, afterUndo)
        assertEquals(firstSave, afterUndo)
    }

    @Test
    fun undoToOpenedStateDoesNotCreateVersion() {
        // Un cuerpo guardado que la app escribiría distinto (por ejemplo, una nota migrada).
        val openedBody = "<div>uno</div><!-- guardado así -->"
        val openedHtml = html()
        val undo = editor()
        type(undo, "t1", "unos", at = 0)
        assertNotEquals(openedBody, bodyToSave(html(), openedHtml, openedBody))

        undo.undo(UndoFocus("t1", null, TextRange(4)))

        // Es lo mismo que el cuerpo con que se abrió: el guardado no ve nada nuevo.
        assertEquals(openedBody, bodyToSave(html(), openedHtml, openedBody))
    }

    @Test
    fun undoKeepsToggleFolding() {
        blocks.add(EditorBlock.Toggle("d", "Desplegable", isToggled = true))
        val undo = editor()
        type(undo, "t1", "unos", at = 0)
        // Plegar no es un paso: no pasa por bodyChanged.
        val index = blocks.indexOfFirst { it.key == "d" }
        blocks[index] = (blocks[index] as EditorBlock.Toggle).copy(isToggled = false)

        undo.undo(UndoFocus("t1", null, TextRange(4)))

        assertEquals("uno", text("t1"))
        assertFalse((blocks.first { it.key == "d" } as EditorBlock.Toggle).isToggled)
    }

    @Test
    fun reopenKeepsStack() {
        val vm = NotesViewModel(ApplicationProvider.getApplicationContext())
        val stack = vm.undoFor("n1")
        assertSame(stack, vm.undoFor("n1"))
        val undo = editor(stack)
        type(undo, "t1", "unos", at = 0)

        // Al volver a abrir la nota se arma la lista de nuevo desde lo guardado, con la misma pila.
        val reopened = HtmlBlocks.parse(html(), attachments.values.toList(), emptyList()).toMutableList()
        assertEquals("unos", reopened.filterIsInstance<EditorBlock.Text>().first().value.plain)
        val again = editor(vm.undoFor("n1"), reopened)
        again.undo(UndoFocus(reopened.first().key, null, TextRange(4)))

        assertEquals("uno", text("t1", reopened))
    }

    // ---- adjuntos y tarjetas ----

    @Test
    fun undoRemoveAttachmentBringsItBack() {
        val undo = editor()
        clock = 1000
        BlockCommands.removeMedia(blocks, blocks[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus(blocks.first().key, null, TextRange(3)))
        assertFalse(html().contains("p1"))

        undo.undo(UndoFocus(blocks.first().key, null, TextRange(3)))

        assertEquals(listOf("t1", "media-p1", "t2"), blocks.map { it.key })
        // El registro siguió en la nota: el adjunto vuelve a guardarse.
        assertTrue(html().contains("p1"))
    }

    @Test
    fun undoRemoveBookmarkBringsItBack() {
        bookmarks["b1"] = card
        blocks.add(2, EditorBlock.Bookmark("bookmark-b1", "b1"))
        val undo = editor()
        clock = 1000
        BlockCommands.removeBookmark(blocks, blocks[2] as EditorBlock.Bookmark)
        undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange.Zero))
        assertFalse(html().contains("ejemplo.com"))

        undo.undo(UndoFocus("t2", null, TextRange.Zero))

        assertTrue(blocks.any { it is EditorBlock.Bookmark && it.bookmarkId == "b1" })
        assertTrue(html().contains("ejemplo.com"))
    }

    @Test
    fun undoInsertThenCloseQuarantinesAttachment() {
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo, other)))
        val undo = editor()
        clock = 1000
        BlockCommands.insertAttachments(blocks, BlockTree(blocks), attachments, InsertPoint("t2", TextRange(1)), listOf(other))
        undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange.Zero))
        undo.undo(UndoFocus("t2", null, TextRange.Zero))
        // Mientras se pueda rehacer, la foto lo nombra y no se aparta.
        close(repo, undo)
        assertEquals(listOf("p1", "p2"), repo.findNote("n1")!!.attachments.map { it.id })
        // Escribir algo borra lo rehacible: ya ninguna foto lo nombra.
        type(undo, "t1", "unos", at = 2000)

        close(repo, undo)

        assertEquals(listOf("p1"), repo.findNote("n1")!!.attachments.map { it.id })
        assertFalse(repo.attachmentFile(other).exists())
        assertTrue(repo.attachmentFile(photo).exists())
        assertTrue(quarantined().any { it.endsWith("p2.jpg") })
    }

    @Test
    fun undoInsertThenCloseQuarantinesBookmark() {
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo), bookmarks = listOf(card)))
        val undo = editor()
        clock = 1000
        BlockCommands.insertBookmark(blocks, BlockTree(blocks), bookmarks, InsertPoint("t2", TextRange(1)), card)
        undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange.Zero))
        undo.undo(UndoFocus("t2", null, TextRange.Zero))
        close(repo, undo)
        assertEquals(listOf("b1"), repo.findNote("n1")!!.bookmarks.map { it.id })
        type(undo, "t1", "unos", at = 2000)

        close(repo, undo)

        val note = repo.findNote("n1")!!
        assertTrue(note.bookmarks.isEmpty())
        assertEquals(listOf("p1"), note.attachments.map { it.id })
        assertFalse(repo.bookmarkImageFile(card)!!.exists())
        assertTrue(quarantined().any { it.endsWith("b1.png") })
    }

    @Test
    fun stopKeepsAttachmentStillInUndoStack() {
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo)))
        val undo = editor()
        clock = 1000
        BlockCommands.removeMedia(blocks, blocks[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange(3)))

        // Ir al fondo y volver sin recrear el editor: la foto de antes todavía lo nombra.
        close(repo, undo)
        undo.undo(UndoFocus("t1", null, TextRange(3)))

        assertEquals(listOf("t1", "media-p1", "t2"), blocks.map { it.key })
        assertEquals(listOf("p1"), repo.findNote("n1")!!.attachments.map { it.id })
        assertTrue(repo.attachmentFile(photo).exists())
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun stopReleasesRemovedAttachment() {
        // Al ir al fondo (ON_STOP) el editor guarda, aparta lo que ninguna foto nombra y manda todo
        // a Room; después la app muere sin onDispose y se vuelve a abrir desde la base.
        val db = NotesDatabase.inMemory(context)
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo, other)), db)
        val undo = editor()
        clock = 1000
        BlockCommands.insertAttachments(blocks, BlockTree(blocks), attachments, InsertPoint("t2", TextRange(1)), listOf(other))
        undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange.Zero))
        undo.undo(UndoFocus("t2", null, TextRange.Zero))
        type(undo, "t1", "unos", at = 2000)
        repo.update("n1") { it.copy(body = html()) }

        close(repo, undo)
        runBlocking { repo.flushAndWait() }

        val reopened = NotesRepository(context, db, true).also { runBlocking { it.awaitLoaded() } }
        val note = reopened.findNote("n1")!!
        assertEquals(listOf("p1"), note.attachments.map { it.id })
        // No reaparece suelto al final.
        val media = HtmlBlocks.parse(note.body, note.attachments, note.bookmarks).filterIsInstance<EditorBlock.Media>()
        assertEquals(listOf("p1"), media.map { it.attachmentId })
        assertTrue(quarantined().any { it.endsWith("p2.jpg") })
    }

    @Test
    fun batchCopyIsBusyUntilInserted() {
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo)))
        val undo = editor()
        runBlocking {
            repo.whileBusy("n1") {
                // Se copió el primero del lote: registrado, todavía sin bloque.
                repo.update("n1") { it.copy(attachments = it.attachments + other) }
                repo.attachmentFile(other).apply { parentFile!!.mkdirs() }.writeText("x")
                // Ir al fondo entre una copia y la siguiente no lo aparta.
                close(repo, undo)
                assertTrue(repo.findNote("n1")!!.attachments.any { it.id == "p2" })
                BlockCommands.insertAttachments(blocks, BlockTree(blocks), attachments, InsertPoint("t2", TextRange(1)), listOf(other))
                undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange.Zero))
            }
        }

        close(repo, undo)

        assertEquals(listOf("p1", "p2"), repo.findNote("n1")!!.attachments.map { it.id })
        assertTrue(repo.attachmentFile(other).exists())
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun closeKeepsAttachmentsOfUntouchedOldNote() {
        // Una nota vieja cuyo cuerpo no nombra su adjunto: el editor lo muestra al final.
        val old = Note(id = "n1", body = "<div>uno</div>", attachments = listOf(photo))
        val repo = repository(old)
        val opened = HtmlBlocks.parse(old.body, old.attachments, emptyList()).toMutableList()

        close(repo, editor(list = opened))

        assertEquals(listOf("p1"), repo.findNote("n1")!!.attachments.map { it.id })
        assertTrue(repo.attachmentFile(photo).exists())
        assertTrue(quarantined().isEmpty())
    }

    /** Reabrir como el editor: el cuerpo guardado, sin agregar al final lo quitado que nombra la pila. */
    private fun reopen(body: String, stack: NoteUndo, records: List<Attachment>): MutableList<EditorBlock> =
        HtmlBlocks.parse(body, records, emptyList(), stack.removedAttachmentIds(), stack.removedBookmarkIds()).toMutableList()

    @Test
    fun reopenAfterRemoveDoesNotAppendRemovedAttachment() {
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo)))
        val stack = NoteUndo()
        val undo = editor(stack)
        clock = 1000
        BlockCommands.removeMedia(blocks, blocks[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange(3)))
        repo.update("n1") { it.copy(body = html()) }
        // Al cerrar no se aparta: la foto de antes lo nombra.
        close(repo, undo)
        val saved = repo.findNote("n1")!!
        assertEquals(listOf("p1"), saved.attachments.map { it.id })

        val reopened = reopen(saved.body, stack, saved.attachments)

        assertTrue(reopened.none { it is EditorBlock.Media })
        // Ctrl+Z lo devuelve a su lugar, entre los dos textos.
        val again = editor(stack, reopened)
        again.undo(UndoFocus(reopened.first().key, null, TextRange(3)))
        assertEquals(listOf("t1", "media-p1", "t2"), reopened.map { it.key })
    }

    @Test
    fun oldNoteUnreferencedAttachmentStillAppended() {
        // Una nota vieja, sin pila, con un adjunto que el cuerpo no nombra: sale al final, como siempre.
        val reopened = reopen("<div>uno</div>", NoteUndo(), listOf(photo))

        assertEquals("media-p1", reopened.last { it !is EditorBlock.Text }.key)
    }

    @Test
    fun reopenUntouchedOldNoteKeepsUnreferencedAttachment() {
        // La misma nota vieja abierta, cerrada sin tocar y reabierta: la foto de ahora lo nombra.
        val stack = NoteUndo()
        val first = reopen("<div>uno</div>", stack, listOf(photo))
        editor(stack, first)

        val again = reopen("<div>uno</div>", stack, listOf(photo))

        assertTrue(again.any { it is EditorBlock.Media && it.attachmentId == "p1" })
    }

    @Test
    fun restoreDropsMediaWithoutRecord() {
        val undo = editor()
        clock = 1000
        BlockCommands.removeMedia(blocks, blocks[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus(blocks.first().key, null, TextRange(3)))
        // Se cerró la nota y el adjunto quitado se apartó: la foto vieja todavía lo nombra.
        attachments.remove("p1")

        undo.undo(UndoFocus(blocks.first().key, null, TextRange(3)))

        assertTrue(blocks.none { it is EditorBlock.Media })
        // Se saca como al quitarlo: los textos de los lados quedan unidos.
        assertEquals(listOf("uno\ndos"), blocks.filterIsInstance<EditorBlock.Text>().map { it.value.plain })
    }

    @Test
    fun restoreDropsBookmarkWithoutRecord() {
        bookmarks["b1"] = card
        blocks.add(2, EditorBlock.Bookmark("bookmark-b1", "b1"))
        val undo = editor()
        clock = 1000
        BlockCommands.removeBookmark(blocks, blocks[2] as EditorBlock.Bookmark)
        undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange.Zero))
        bookmarks.remove("b1")

        undo.undo(UndoFocus("t2", null, TextRange.Zero))

        assertTrue(blocks.none { it is EditorBlock.Bookmark })
        assertTrue(blocks.any { it is EditorBlock.Media && it.attachmentId == "p1" })
    }

    // ---- reabrir con la pila conservada: la foto de ahora se compara con la nota ----

    @Test
    fun reopenAfterChildNoteDiscardedResetsStack() {
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo)))
        val stack = NoteUndo()
        val undo = editor(stack)
        // "Nota dentro de esta": la hija nueva y su bloque al final de "dos".
        val child = repo.createNote(fresh = true)
        notes += child.id
        clock = 1000
        BlockCommands.insertNoteLink(blocks, BlockTree(blocks), InsertPoint("t2", TextRange(3)), child.id)
        undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange(3)))
        repo.update("n1") { it.copy(body = html()) }
        assertTrue(repo.findNote("n1")!!.body.contains(child.id))
        // Se vuelve sin escribir en la hija: se descarta y su bloque sale del cuerpo de afuera.
        assertTrue(repo.discardIfEmpty(child.id))
        notes -= child.id
        val saved = repo.findNote("n1")!!
        assertFalse(saved.body.contains(child.id))

        val reopened = reopen(saved.body, stack, saved.attachments)
        val again = editor(stack, reopened)

        // La foto de ahora tenía el enlace y la nota no: la pila vuelve a empezar desde lo leído.
        assertFalse(stack.canUndo)
        val key = reopened.filterIsInstance<EditorBlock.Text>().last().key
        clock = 2000
        BlockCommands.updateText(reopened, key, RichText("dos!"))
        again.bodyChanged(typing = true, UndoFocus(key, null, TextRange(4)))
        again.undo(UndoFocus(key, null, TextRange(4)))
        assertTrue(reopened.none { it is EditorBlock.NoteLink })
        assertTrue(reopened.filterIsInstance<EditorBlock.Text>().any { it.value.plain == "dos" })
    }

    @Test
    fun restoreDropsNoteLinkToDeletedNote() {
        notes += "n2"
        val undo = editor()
        clock = 1000
        BlockCommands.insertNoteLink(blocks, BlockTree(blocks), InsertPoint("t2", TextRange(3)), "n2")
        undo.bodyChanged(typing = false, UndoFocus("t2", null, TextRange(3)))
        val after = blocks.last().key
        clock = 2000
        BlockCommands.updateText(blocks, after, RichText("tres"))
        undo.bodyChanged(typing = true, UndoFocus(after, null, TextRange(4)))
        // La nota de adentro se borró: la foto de antes todavía la nombra.
        notes -= "n2"

        undo.undo(UndoFocus(after, null, TextRange(4)))

        assertTrue(blocks.none { it is EditorBlock.NoteLink })
        // Se saca como al quitarlo: los textos de los lados quedan unidos.
        assertEquals(listOf("uno", "dos"), blocks.filterIsInstance<EditorBlock.Text>().map { it.value.plain })
    }

    @Test
    fun reopenUnchangedKeepsStack() {
        val stack = NoteUndo()
        val undo = editor(stack)
        type(undo, "t1", "unos", at = 0)

        val reopened = reopen(html(), stack, attachments.values.toList())
        val again = editor(stack, reopened)

        assertTrue(stack.canUndo)
        again.undo(UndoFocus(reopened.first().key, null, TextRange(4)))
        assertEquals("uno", reopened.filterIsInstance<EditorBlock.Text>().first().value.plain)
    }

    @Test
    fun reopenAfterFoldingKeepsStack() {
        blocks.add(EditorBlock.Toggle("tg", "título"))
        blocks.add(EditorBlock.Text("t3", RichText("adentro"), parentKey = "tg"))
        val stack = NoteUndo()
        val undo = editor(stack)
        type(undo, "t1", "unos", at = 0)
        // Plegar no es un paso: la foto de ahora tiene el desplegable como estaba.
        val index = blocks.indexOfFirst { it.key == "tg" }
        blocks[index] = (blocks[index] as EditorBlock.Toggle).copy(isToggled = false)

        val reopened = reopen(html(), stack, attachments.values.toList())
        editor(stack, reopened)

        assertTrue(stack.canUndo)
    }

    @Test
    fun reopenAfterRemoveAttachmentKeepsStack() {
        // R13/R13b: al reabrir después de quitar un adjunto, la foto de ahora y lo leído coinciden.
        val repo = repository(Note(id = "n1", body = html(), attachments = listOf(photo)))
        val stack = NoteUndo()
        val undo = editor(stack)
        clock = 1000
        BlockCommands.removeMedia(blocks, blocks[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange(3)))
        repo.update("n1") { it.copy(body = html()) }
        close(repo, undo)
        val saved = repo.findNote("n1")!!

        val reopened = reopen(saved.body, stack, saved.attachments)

        assertEquals(html(stack.current!!.blocks), html(reopened))
        editor(stack, reopened)
        assertTrue(stack.canUndo)
    }

    // ---- R14: una nota nueva que queda vacía se descarta al salir aunque la pila nombre algo ----

    /** Una nota nueva de esta sesión ("Nueva nota") con [list] como cuerpo y sus registros. */
    private fun freshRepository(list: List<EditorBlock>, records: List<Attachment> = emptyList(), cards: List<Bookmark> = emptyList()): NotesRepository {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val repo = NotesRepository(context, NotesDatabase.inMemory(context), true)
        runBlocking { repo.awaitLoaded() }
        assertEquals("n1", repo.createNote(id = "n1", fresh = true).id)
        repo.update("n1") { it.copy(body = html(list), attachments = records, bookmarks = cards) }
        records.forEach { repo.attachmentFile(it).apply { parentFile!!.mkdirs() }.writeText("x") }
        cards.forEach { repo.bookmarkImageFile(it)!!.apply { parentFile!!.mkdirs() }.writeText("x") }
        return repo
    }

    /** El cuerpo de una nota nueva con solo [block] entre dos renglones vacíos. */
    private fun onlyBlock(block: EditorBlock) = mutableListOf(
        EditorBlock.Text("t1", RichText("")),
        block,
        EditorBlock.Text("t2", RichText("")),
    )

    /** Al salir con la flecha o Atrás, como `leave()` del editor: guarda y después aparta y descarta. */
    private fun leave(repo: NotesRepository, stacks: UndoStacks, undo: EditorUndo, list: List<EditorBlock>): Boolean {
        repo.update("n1") { it.copy(body = html(list)) }
        return releaseAndDiscard(repo, stacks, "n1", undo)
    }

    @Test
    fun freshNoteWithRemovedOnlyAttachmentIsDiscarded() {
        val list = onlyBlock(EditorBlock.Media("media-p1", "p1"))
        val repo = freshRepository(list, records = listOf(photo))
        val stacks = UndoStacks()
        val undo = EditorUndo(list, stacks.forNote("n1"), ::hasRecord) { clock }.also { it.start(noFocus) }
        clock = 1000
        BlockCommands.removeMedia(list, list[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange.Zero))

        assertTrue(leave(repo, stacks, undo, list))

        assertNull(repo.findNote("n1"))
    }

    @Test
    fun freshNoteWithRemovedOnlyBookmarkIsDiscarded() {
        bookmarks["b1"] = card
        val list = onlyBlock(EditorBlock.Bookmark("bookmark-b1", "b1"))
        val repo = freshRepository(list, cards = listOf(card))
        val stacks = UndoStacks()
        val undo = EditorUndo(list, stacks.forNote("n1"), ::hasRecord) { clock }.also { it.start(noFocus) }
        clock = 1000
        BlockCommands.removeBookmark(list, list[1] as EditorBlock.Bookmark)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange.Zero))

        assertTrue(leave(repo, stacks, undo, list))

        assertNull(repo.findNote("n1"))
    }

    @Test
    fun freshNoteUndoInsertIsDiscarded() {
        attachments["p2"] = other
        val list = mutableListOf<EditorBlock>(EditorBlock.Text("t1", RichText("")))
        val repo = freshRepository(list)
        val stacks = UndoStacks()
        val undo = EditorUndo(list, stacks.forNote("n1"), ::hasRecord) { clock }.also { it.start(noFocus) }
        clock = 1000
        // La copia registra el adjunto en la nota y después se inserta el bloque.
        repo.update("n1") { it.copy(attachments = listOf(other)) }
        repo.attachmentFile(other).apply { parentFile!!.mkdirs() }.writeText("x")
        BlockCommands.insertAttachments(list, BlockTree(list), attachments, InsertPoint("t1", TextRange.Zero), listOf(other))
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange.Zero))
        undo.undo(UndoFocus("t1", null, TextRange.Zero))
        // Lo rehacible lo nombra todavía.
        assertTrue("p2" in undo.attachmentIdsInUse())

        assertTrue(leave(repo, stacks, undo, list))

        assertNull(repo.findNote("n1"))
    }

    @Test
    fun discardedNoteDropsUndoStack() {
        val list = onlyBlock(EditorBlock.Media("media-p1", "p1"))
        val repo = freshRepository(list, records = listOf(photo))
        val stacks = UndoStacks()
        val stack = stacks.forNote("n1")
        val undo = EditorUndo(list, stack, ::hasRecord) { clock }.also { it.start(noFocus) }
        clock = 1000
        BlockCommands.removeMedia(list, list[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange.Zero))

        assertTrue(leave(repo, stacks, undo, list))

        assertNotSame(stack, stacks.forNote("n1"))
        assertFalse(stacks.forNote("n1").canUndo)
    }

    @Test
    fun oldNoteWithRemovedOnlyAttachmentKeepsItForUndo() {
        // Una nota que no es nueva: todo como antes, la pila lo nombra y no se aparta.
        val list = onlyBlock(EditorBlock.Media("media-p1", "p1"))
        val repo = repository(Note(id = "n1", body = html(list), attachments = listOf(photo)))
        val stacks = UndoStacks()
        val stack = stacks.forNote("n1")
        val undo = EditorUndo(list, stack, ::hasRecord) { clock }.also { it.start(noFocus) }
        clock = 1000
        BlockCommands.removeMedia(list, list[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange.Zero))

        assertFalse(leave(repo, stacks, undo, list))

        assertEquals(listOf("p1"), repo.findNote("n1")!!.attachments.map { it.id })
        assertTrue(repo.attachmentFile(photo).exists())
        assertSame(stack, stacks.forNote("n1"))
        assertTrue(stack.canUndo)
    }

    @Test
    fun freshNoteWithTitleKeepsRemovedAttachmentForUndo() {
        // Nueva pero con título: no se descarta, así que se aparta como siempre, con la pila.
        val list = onlyBlock(EditorBlock.Media("media-p1", "p1"))
        val repo = freshRepository(list, records = listOf(photo))
        repo.update("n1") { it.copy(title = "Título") }
        val stacks = UndoStacks()
        val stack = stacks.forNote("n1")
        val undo = EditorUndo(list, stack, ::hasRecord) { clock }.also { it.start(noFocus) }
        clock = 1000
        BlockCommands.removeMedia(list, list[1] as EditorBlock.Media)
        undo.bodyChanged(typing = false, UndoFocus("t1", null, TextRange.Zero))

        assertFalse(leave(repo, stacks, undo, list))

        assertEquals(listOf("p1"), repo.findNote("n1")!!.attachments.map { it.id })
        assertSame(stack, stacks.forNote("n1"))
    }
}
