package com.jot.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jot.app.data.Attachment
import com.jot.app.data.Bookmark
import com.jot.app.data.Note
import com.jot.app.data.NoteFilter
import com.jot.app.data.NotesRepository
import com.jot.app.data.NoteIO
import com.jot.app.data.containerOf
import com.jot.app.data.html.NoteMarkdown
import com.jot.app.data.nestedNoteIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@OptIn(kotlinx.coroutines.FlowPreview::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NotesRepository.get(application)

    // Los avisos del almacenamiento (no se pudo guardar, archivo dañado) salen como
    // mensaje en la pantalla que esté abierta.
    private val problemListener: (String) -> Unit = { message -> emit(message) }

    init {
        repository.onProblem = problemListener
        repository.takePendingProblem()?.let { emit(it) }
    }

    override fun onCleared() {
        if (repository.onProblem === problemListener) repository.onProblem = null
        super.onCleared()
    }

    private val preferences =
        application.getSharedPreferences("jot", android.content.Context.MODE_PRIVATE)

    private val _toolbarLayout = MutableStateFlow(
        ToolbarLayout.parse(
            preferences.getString(TOOLBAR_ORDER_KEY, null),
            preferences.getString(TOOLBAR_HIDDEN_KEY, null)
        )
    )

    /** El orden de los botones de la barra del editor y cuáles están ocultos. */
    val toolbarLayout = _toolbarLayout.asStateFlow()

    /** Se guarda en el acto, al soltar una fila. */
    fun setToolbarLayout(layout: ToolbarLayout) {
        _toolbarLayout.value = layout
        preferences.edit()
            .putString(TOOLBAR_ORDER_KEY, layout.orderKeys())
            .putString(TOOLBAR_HIDDEN_KEY, layout.hiddenKeys())
            .apply()
    }

    val notes = repository.notes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    /** Ícono de cada cuaderno, por nombre (`emoji:…` o `file:…`). */
    val notebookIcons = repository.notebookIcons.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyMap()
    )

    /**
     * Posición de la lista de notas. Vive acá y no en la pantalla: al entrar a una nota la
     * lista sale de pantalla y, al volver, sigue en el mismo lugar.
     */
    val listState = LazyListState()

    /** El orden de los cuadernos en el menú. */
    val notebookOrder = repository.notebookOrder.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    /** Los cuadernos que están en la papelera. */
    val notebooksTrashed = repository.notebooksTrashed.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptySet()
    )

    var filter by mutableStateOf<NoteFilter>(NoteFilter.All)

    var query by mutableStateOf("")

    /** El resultado de buscar [query] en la base; [ids] null: la lista busca en memoria. */
    data class SearchResult(val query: String, val ids: Set<String>?)

    private val _search = MutableStateFlow<SearchResult?>(null)

    /**
     * La búsqueda en la base para la [query] de ahora, o la anterior mientras llega. Se hace
     * [SEARCH_DEBOUNCE_MS] después de la última letra y otra vez cada vez que Room queda al día.
     */
    val search = _search.asStateFlow()

    init {
        viewModelScope.launch {
            combine(snapshotFlow { query }.debounce(SEARCH_DEBOUNCE_MS), repository.savedCount) { q, _ -> q }
                .collectLatest { q ->
                    _search.value = if (q.isBlank()) null else SearchResult(q, repository.searchIds(q))
                }
        }
    }

    // Cuántas tareas largas hay en marcha: "Procesando…" sigue mientras quede una.
    private var busyCount by mutableIntStateOf(0)
    val busy: Boolean get() = busyCount > 0

    private val _pendingNote = MutableStateFlow<String?>(null)
    val pendingNote = _pendingNote.asStateFlow()

    // Los mensajes esperan a que la pantalla los muestre: si llegan mientras se
    // recrea, no se pierden.
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // ------------------------------------------------------------------ notas

    fun createNote(notebook: String? = null) {
        val note = repository.createNote(notebook, fresh = true)
        _pendingNote.value = note.id
    }

    private var lastLinkedNoteAt = 0L

    /**
     * Crea una nota para poner dentro de otra; no la abre sola como [createNote].
     * Devuelve null si se pidió otra hace menos de 0,8 s (doble toque).
     */
    fun createLinkedNoteOnce(notebook: String?): Note? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLinkedNoteAt < 800) return null
        lastLinkedNoteAt = now
        return repository.createNote(notebook, fresh = true)
    }

    fun updateNote(noteId: String, transform: (Note) -> Note) {
        repository.update(noteId, transform)
    }

    fun togglePin(noteId: String) = repository.togglePin(noteId)

    fun moveNote(noteId: String, targetId: String) = repository.moveNote(noteId, targetId)

    fun moveToTrash(noteId: String) {
        val companions = repository.moveToTrash(noteId)
        emit(
            when (companions) {
                0 -> "Nota movida a la papelera"
                1 -> "Nota movida a la papelera con 1 nota de adentro"
                else -> "Nota movida a la papelera con $companions notas de adentro"
            },
            // «Deshacer» la saca de la papelera con las que se fueron con ella, sin otro aviso.
            undo = { repository.restore(noteId) }
        )
    }

    fun restore(noteId: String) {
        repository.restore(noteId)
        // Si estaba dentro de otra, no vuelve a su lista: avisar dónde quedó.
        val container = containerOf(repository.notes.value, noteId)
        emit(
            if (container != null && noteId in nestedNoteIds(repository.notes.value)) {
                "Nota restaurada dentro de «${container.displayTitle}»"
            } else {
                "Nota restaurada"
            }
        )
    }

    fun deletePermanently(noteId: String) {
        repository.deletePermanently(noteId)
        preferences.edit().remove(checklistClosedKey(noteId)).apply()
        emit("Nota eliminada")
    }

    fun emptyTrash() {
        val emptied = repository.emptyTrash()
        preferences.edit().apply { emptied.forEach { remove(checklistClosedKey(it)) } }.apply()
        emit("Papelera vaciada")
    }

    fun renameNotebook(from: String, to: String) {
        val name = to.trim()
        if (name.isEmpty()) {
            emit("El nombre del cuaderno no puede quedar vacío")
            return
        }
        if (name == from) return
        // Con el nombre de otro cuaderno se mezclarían las notas de los dos.
        if (notes.value.any { it.notebook == name } || name in repository.notebookIcons.value) {
            emit("Ya hay un cuaderno «$name»: elegí otro nombre")
            return
        }
        repository.renameNotebook(from, to)
        if (filter == NoteFilter.Notebook(from)) filter = NoteFilter.Notebook(to.trim())
        emit("Cuaderno renombrado a «${to.trim()}»")
    }

    /**
     * Crea un cuaderno vacío (o usa el que ya se llama así), le pone el ícono elegido (un emoji
     * o una imagen del aparato) y lo abre.
     */
    fun createNotebook(name: String, emoji: String? = null, image: Uri? = null) {
        val trashed = repository.notebooksTrashed.value.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
        if (trashed != null) {
            emit("El cuaderno «$trashed» está en la papelera: restauralo desde ahí o elegí otro nombre")
            return
        }
        val created = repository.createNotebook(name) ?: return
        filter = NoteFilter.Notebook(created)
        when {
            emoji != null -> repository.setNotebookEmojiIcon(created, emoji)
            image != null -> setNotebookIconFromUri(created, image)
        }
    }

    /** Lleva el cuaderno [name] al lugar [toIndex] del menú. */
    fun moveNotebook(name: String, toIndex: Int) = repository.moveNotebook(name, toIndex)

    fun trashNotebook(name: String) {
        val count = repository.notes.value.count { it.notebook == name }
        repository.trashNotebook(name)
        emit(
            when (count) {
                0 -> "Cuaderno «$name» a la papelera"
                1 -> "Cuaderno «$name» y su nota a la papelera"
                else -> "Cuaderno «$name» y sus $count notas a la papelera"
            }
        )
    }

    fun restoreNotebook(name: String) {
        repository.restoreNotebook(name)
        emit("Cuaderno «$name» restaurado, con sus notas")
    }

    fun deleteNotebookPermanently(name: String) {
        val count = repository.deleteNotebookPermanently(name)
        if (filter == NoteFilter.Notebook(name) || filter == NoteFilter.Trash) filter = NoteFilter.Trash
        emit(
            when (count) {
                0 -> "Cuaderno «$name» eliminado"
                1 -> "Cuaderno «$name» eliminado con su nota"
                else -> "Cuaderno «$name» eliminado con sus $count notas"
            }
        )
    }

    fun setNotebook(noteId: String, notebook: String?) {
        val before = repository.findNote(noteId)?.notebook
        if (!repository.setNotebook(noteId, notebook)) {
            // El nombre como está en la papelera, aunque se haya escrito con otras mayúsculas.
            val name = repository.notebooksTrashed.value.firstOrNull { it.equals(notebook?.trim(), ignoreCase = true) }
                ?: notebook?.trim()
            emit("El cuaderno «$name» está en la papelera: restauralo para mover notas ahí")
            return
        }
        // «Movida a» + ícono y nombre del cuaderno (o de «Notas»); «Deshacer» la devuelve adonde estaba.
        emit(
            "Movida a",
            undo = { repository.setNotebook(noteId, before) },
            place = notebook?.trim()?.takeIf { it.isNotEmpty() } ?: NOTAS_PLACE
        )
    }

    fun setEmojiIcon(noteId: String, emoji: String?) = repository.setEmojiIcon(noteId, emoji)

    fun setNotebookEmojiIcon(name: String, emoji: String?) = repository.setNotebookEmojiIcon(name, emoji)

    fun setNotebookIconFromUri(name: String, uri: Uri) {
        viewModelScope.launch {
            if (!repository.setNotebookIconFromUri(name, uri)) {
                emit("No se pudo usar ese archivo: tiene que ser una imagen válida (un SVG, de hasta 512 KB)")
            }
        }
    }

    fun notebookIconFile(icon: String?): File? = repository.notebookIconFile(icon)

    fun setIconFromUri(noteId: String, uri: Uri) {
        viewModelScope.launch {
            if (!repository.setIconFromUri(noteId, uri)) {
                emit("No se pudo usar ese archivo: tiene que ser una imagen válida (un SVG, de hasta 512 KB)")
            }
        }
    }

    fun iconFile(note: Note): File? = repository.iconFile(note)

    fun coverFile(note: Note): File? = repository.coverFile(note)

    fun setCoverPreset(noteId: String, value: String?) = repository.setCoverPreset(noteId, value)

    fun setCoverCrop(noteId: String, x: Float, y: Float, scale: Float) = repository.setCoverCrop(noteId, x, y, scale)

    /** Copia la imagen como portada; [onDone] corre solo si salió bien (para abrir Ajustar). */
    fun setCoverFromUri(noteId: String, uri: Uri, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (repository.setCoverFromUri(noteId, uri)) {
                onDone()
            } else {
                emit("No se pudo usar ese archivo como portada: tiene que ser una imagen")
            }
        }
    }

    /**
     * Posición de scroll de las notas desde las que se entró a una nota de
     * adentro, para volver al mismo lugar. Se consume al leerla.
     */
    private val editorScroll = HashMap<String, Int>()

    fun saveEditorScroll(noteId: String, value: Int) { editorScroll[noteId] = value }

    fun takeEditorScroll(noteId: String): Int = editorScroll.remove(noteId) ?: 0

    /**
     * Si la sección de marcados de las checklists de la nota está plegada (D6, como
     * ). Arranca desplegada; no va al HTML.
     */
    fun isChecklistClosed(noteId: String): Boolean =
        preferences.getBoolean(checklistClosedKey(noteId), false)

    fun setChecklistClosed(noteId: String, closed: Boolean) {
        preferences.edit().putBoolean(checklistClosedKey(noteId), closed).apply()
    }

    private fun checklistClosedKey(noteId: String) = "checklist_closed_$noteId"

    fun clearEditorScrolls() = editorScroll.clear()

    /** La pila de deshacer de cada nota abierta: vive en memoria mientras dura la app. */
    private val undoStacks = UndoStacks()

    /** La pila de [noteId]; la misma cada vez, así volver a abrir la nota no la pierde. */
    internal fun undoFor(noteId: String): NoteUndo = undoStacks.forNote(noteId)

    /** Al salir de la nota: aparta lo que no se usa y la descarta si es nueva y quedó vacía. */
    internal fun releaseAndDiscard(noteId: String, undo: EditorUndo): Boolean =
        releaseAndDiscard(repository, undoStacks, noteId, undo)

    fun consumePending() { _pendingNote.value = null }

    fun attachmentFile(attachment: com.jot.app.data.Attachment): File =
        repository.attachmentFile(attachment)

    fun bookmarkImageFile(bookmark: Bookmark): File? =
        repository.bookmarkImageFile(bookmark)

    fun bookmarkFaviconFile(bookmark: Bookmark): File? =
        repository.bookmarkFaviconFile(bookmark)

    /**
     * Corre en el scope del ViewModel: si el usuario sale de la nota mientras carga,
     * el enlace se termina de agregar igual y el mensaje dice lo que realmente pasó.
     * Antes, salir cancelaba la espera y avisaba "No se pudo" aunque se hubiera agregado.
     */
    suspend fun addBookmark(noteId: String, url: String): Bookmark? =
        viewModelScope.async {
            busyCount++
            val bookmark = runCatching { repository.addBookmark(noteId, url) }.getOrNull()
            busyCount--
            emit(if (bookmark != null) "Enlace agregado" else "No se pudo agregar el enlace")
            bookmark
        }.await()

    /** Se quitó una tarjeta en el editor: el aviso de siempre. El registro sigue hasta cerrar la nota. */
    fun bookmarkRemoved(undo: (() -> Unit)? = null) = emit("Enlace quitado", undo)

    // --------------------------------------------------------------- adjuntos

    /** Se quitó un adjunto en el editor: el aviso de siempre. El registro sigue hasta cerrar la nota. */
    fun attachmentRemoved(undo: (() -> Unit)? = null) = emit("Adjunto eliminado", undo)

    /** La nota ocupada mientras corre [block]: ver [NotesRepository.whileBusy]. */
    suspend fun <T> whileBusy(noteId: String, block: suspend () -> T): T = repository.whileBusy(noteId, block)

    /** La nota como está registrada ahora, con sus adjuntos y tarjetas reales. */
    fun currentNote(noteId: String): Note? = repository.findNote(noteId)

    /** Al cerrar la nota: aparta los adjuntos y tarjetas que el cuerpo final no usa. */
    fun releaseUnusedContents(noteId: String, usedAttachmentIds: Set<String>, usedBookmarkIds: Set<String>) =
        repository.releaseUnusedContents(noteId, usedAttachmentIds, usedBookmarkIds)

    /**
     * Copia los archivos elegidos al almacenamiento privado y devuelve los
     * adjuntos creados, en el mismo orden. El editor los inserta en el cursor.
     */
    suspend fun attachFiles(noteId: String, uris: List<Uri>): List<Attachment> {
        if (uris.isEmpty()) return emptyList()
        busyCount++
        val added = ArrayList<Attachment>(uris.size)
        try {
            uris.forEach { uri ->
                repository.addAttachment(noteId, uri)?.let(added::add)
            }
        } finally {
            busyCount--
        }
        emit(
            when {
                added.isEmpty() -> "No se pudo agregar el adjunto"
                added.size == 1 -> "Adjunto agregado"
                else -> "${added.size} adjuntos agregados"
            }
        )
        return added
    }

    // ---------------------------------------------------------- share / import

    fun shareIncoming(uris: List<Uri>, text: String?) {
        viewModelScope.launch {
            // Con la app cerrada, esperar a que se lean las notas: si no, la nota
            // compartida no quedaba primera en la lista.
            repository.awaitLoaded()
            val note = repository.createNote()
            val title = text
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?.removePrefix("#")
                ?.trim()
                ?.take(70)
                .orEmpty()

            repository.update(note.id) {
                it.copy(
                    title = title,
                    body = NoteMarkdown.toHtml(text.orEmpty(), emptyList(), emptyList(), inlineMarks = false),
                    updatedAt = System.currentTimeMillis()
                )
            }

            busyCount++
            var ok = 0
            uris.forEach { if (repository.addAttachment(note.id, it) != null) ok++ }
            busyCount--

            _pendingNote.value = note.id
            if (ok > 0) emit("Nota creada con $ok adjunto(s)")
        }
    }

    fun importNotesTree(uri: Uri) {
        // Fuera de viewModelScope: si se cierra la pantalla a mitad de camino,
        // la importación termina igual y queda guardada.
        busyCount++
        longTaskScope.launch {
            val result = runCatching {
                NoteIO.importFolder(getApplication(), repository, uri)
            }
            runCatching { repository.flushAndWait() }
            busyCount--
            result.fold(
                onSuccess = {
                    emit(
                        "Importadas ${it.notes} nota(s) y ${it.attachments} adjunto(s)" +
                            (if (it.skipped > 0) " (${it.skipped} ya existían)" else "") +
                            if (it.inTrashedNotebook > 0) {
                                ". ${it.inTrashedNotebook} no se importaron: su cuaderno está en la papelera"
                            } else {
                                ""
                            }
                    )
                },
                onFailure = { emit("No se pudo importar: ${it.message ?: "error desconocido"}") }
            )
        }
    }

    fun exportAllTo(uri: Uri) {
        busyCount++
        longTaskScope.launch {
            val result = runCatching {
                NoteIO.exportAll(getApplication(), repository, uri)
            }
            busyCount--
            result.fold(
                onSuccess = {
                    emit(
                        "Exportadas ${it.notes} nota(s) a Markdown" +
                            if (it.failed > 0) " (${it.failed} no se pudieron escribir)" else ""
                    )
                },
                onFailure = { emit("No se pudo exportar: ${it.message ?: "error desconocido"}") }
            )
        }
    }

    fun flush() {
        repository.flush()
    }

    private fun emit(message: String, undo: (() -> Unit)? = null, place: String? = null) {
        _messages.trySend(UiMessage(message, undo, place))
    }

    private companion object {
        // Vive con el proceso, no con la pantalla.
        val longTaskScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** Espera después de la última letra antes de buscar en la base. */
        const val SEARCH_DEBOUNCE_MS = 150L

        const val TOOLBAR_ORDER_KEY = "toolbar_order"
        const val TOOLBAR_HIDDEN_KEY = "toolbar_hidden"
    }
}
