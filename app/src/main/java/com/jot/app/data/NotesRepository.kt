package com.jot.app.data

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.jot.app.data.db.HistoryCodec
import com.jot.app.data.db.NoteHistoryEntity
import com.jot.app.data.db.NotebookEntity
import com.jot.app.data.db.NotesDatabase
import com.jot.app.data.db.SearchKey
import com.jot.app.data.db.toEntity
import com.jot.app.data.db.toNote
import com.jot.app.data.html.NoteHtml
import com.jot.app.data.html.withDerived
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * [databaseExisted]: si el archivo de la base ya estaba antes de abrirla (lo usa
 * [HtmlMigration] para no repetir la migración cuando la base solo quedó vacía).
 */
class NotesRepository internal constructor(
    context: Context,
    db: NotesDatabase,
    private val databaseExisted: Boolean,
) {

    private val appContext = context.applicationContext
    private val dao = db.dao()
    private val prefs = appContext.getSharedPreferences("jot", Context.MODE_PRIVATE)
    private val attachmentsRoot = File(appContext.filesDir, "attachments").apply { mkdirs() }
    private val bookmarksRoot = File(appContext.filesDir, "bookmarks").apply { mkdirs() }
    private val iconsRoot = File(appContext.filesDir, "icons").apply { mkdirs() }
    private val coversRoot = File(appContext.filesDir, "covers").apply { mkdirs() }
    private val orphansRoot = File(appContext.filesDir, "huerfanos")
    private val orphanBatch = System.currentTimeMillis().toString()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    /**
     * Los cuadernos y su ícono, por nombre (`emoji:…`, `file:…` o "" si no tiene). Un cuaderno
     * existe aunque esté vacío: se crea con [createNotebook] o al mover una nota a un nombre
     * nuevo, y solo se va con [removeNotebook], que borra también su ícono y su archivo.
     */
    private val _notebookIcons = MutableStateFlow<Map<String, String>>(emptyMap())
    val notebookIcons: StateFlow<Map<String, String>> = _notebookIcons.asStateFlow()

    /**
     * La verificación y el cambio de ícono de emoji e imagen (de notas y de cuadernos) corren
     * bajo este lock: gana siempre el último elegido. Va antes del `init`: [load] ya lo usa.
     */
    private val iconLock = Any()

    /**
     * Los cuadernos que están en la papelera. Sus notas no se tocan: siguen llevando el nombre
     * del cuaderno, y por eso no aparecen sueltas en ninguna lista. Ver [trashNotebook].
     */
    private val _notebooksTrashed = MutableStateFlow<Set<String>>(emptySet())
    val notebooksTrashed: StateFlow<Set<String>> = _notebooksTrashed.asStateFlow()
    private var lastPersistedTrashed: Set<String> = emptySet()

    /** Los íconos de cuaderno como quedaron en Room en el último guardado. */
    private var lastPersistedNotebooks: Map<String, String> = emptyMap()

    /**
     * El orden de los cuadernos en el menú (se cambia arrastrando). Va aparte del mapa de íconos:
     * dos mapas con lo mismo en otro orden son iguales y el cambio no se vería.
     */
    private val _notebookOrder = MutableStateFlow<List<String>>(emptyList())
    val notebookOrder: StateFlow<List<String>> = _notebookOrder.asStateFlow()
    private var lastPersistedOrder: List<String> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    /**
     * Se completa cuando terminó la migración y se leyó la base. Hasta entonces no se
     * guarda: si no, una nota creada al arrancar (por ejemplo, algo compartido con la app
     * cerrada) llegaría a Room antes que las notas migradas.
     */
    private val loaded = CompletableDeferred<Unit>()

    /**
     * Si se puede escribir en Room. No, si al arrancar no se pudo migrar o leer la base:
     * lo que haya en memoria no se guarda, para no pisar por id filas con datos a medias.
     */
    @Volatile
    private var writable = false

    /** El último guardado en Room falló: la base quedó atrás de la lista y no sirve para buscar. */
    @Volatile
    private var lastSaveFailed = false

    private val _savedCount = MutableStateFlow(0L)

    /** Sube cada vez que Room queda al día con la lista: la búsqueda se vuelve a hacer. */
    val savedCount: StateFlow<Long> = _savedCount.asStateFlow()

    /** Las notas como quedaron en Room en el último guardado (o al leerla), por id. */
    private var lastPersisted: Map<String, Note> = emptyMap()

    /** Avisos para mostrar (no se pudo guardar, archivo dañado). */
    @Volatile
    var onProblem: ((String) -> Unit)? = null
    private var pendingProblem: String? = null

    /**
     * Notas creadas con "Nueva nota" en esta sesión: solo esas se descartan si se
     * cierran vacías. Una nota vieja que el usuario vació no se borra sola.
     */
    private val freshIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Notas con un adjunto o un enlace cargándose: no se descartan mientras tanto. */
    private val busyNotes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun beginWork(noteId: String) {
        busyNotes.merge(noteId, 1) { a, b -> a + b }
    }

    private fun endWork(noteId: String) {
        busyNotes.computeIfPresent(noteId) { _, count -> if (count <= 1) null else count - 1 }
    }

    /**
     * La nota queda ocupada mientras corre [block]: no se descarta ni se le apartan adjuntos o
     * tarjetas ([releaseUnusedContents]). El editor envuelve así un lote de archivos o una tarjeta
     * hasta insertar sus bloques: entre una copia y la siguiente la nota no queda libre.
     */
    suspend fun <T> whileBusy(noteId: String, block: suspend () -> T): T {
        beginWork(noteId)
        try {
            return block()
        } finally {
            endWork(noteId)
        }
    }

    private fun report(message: String) {
        val callback = onProblem
        if (callback != null) callback(message) else pendingProblem = message
    }

    /** Entrega un aviso que llegó antes de que hubiera una pantalla para mostrarlo. */
    fun takePendingProblem(): String? = pendingProblem.also { pendingProblem = null }

    suspend fun awaitLoaded() = loaded.await()

    init {
        scope.launch { load() }
    }

    // ---------------------------------------------------------------- lectura

    private suspend fun load() = withContext(Dispatchers.IO) {
        val migration: HtmlMigration.Report?
        val stored: List<Note>
        val storedNotebooks: Map<String, String>
        val storedTrashed: Set<String>
        try {
            migration = HtmlMigration(appContext.filesDir, dao, prefs, databaseExisted).runIfNeeded()
            stored = dao.all().map { it.toNote() }
            // En el orden del menú: por su lugar y, si no tienen (todos en 0), alfabético.
            val notebookRows = dao.notebooks()
                .sortedWith(compareBy<NotebookEntity> { it.position }.thenBy { it.name.lowercase() })
            storedNotebooks = notebookRows.associate { it.name to it.icon }
            storedTrashed = notebookRows.filter { it.trashed }.map { it.name }.toSet()
        } catch (e: Exception) {
            // La bandera de la migración queda apagada: se vuelve a intentar la próxima vez.
            report(
                "No se pudieron leer las notas. Los cambios no se van a guardar: " +
                    "se vuelve a intentar la próxima vez que se abra la app."
            )
            loaded.complete(Unit)
            return@withContext
        }

        val problems = ArrayList<String>()
        if (migration?.damaged == true) {
            problems.add(
                "El archivo de notas estaba dañado: puede que falten notas. " +
                    "Quedó una copia del original (${migration.damagedBackup})."
            )
        }
        if (!migration?.needsReview.isNullOrEmpty()) {
            problems.add(
                "Se pasaron las notas al formato nuevo. Conviene revisar: " +
                    migration!!.needsReview.joinToString(", ")
            )
        }
        // Un solo aviso: el que queda pendiente hasta que haya pantalla es uno.
        if (problems.isNotEmpty()) report(problems.joinToString("\n"))

        lastPersisted = stored.associateBy { it.id }
        // Se suman las notas creadas mientras se leía la base, en vez de pisarlas.
        val storedIds = lastPersisted.keys
        _notes.update { current ->
            (stored + current.filterNot { it.id in storedIds }).sortedWith(ORDER)
        }
        lastPersistedNotebooks = storedNotebooks
        lastPersistedOrder = storedNotebooks.keys.toList()
        lastPersistedTrashed = storedTrashed
        synchronized(iconLock) {
            _notebookIcons.value = storedNotebooks
            _notebookOrder.value = storedNotebooks.keys.toList()
            _notebooksTrashed.value = storedTrashed
        }
        writable = true
        loaded.complete(Unit)
        // Si el archivo vino dañado faltan notas: no se puede saber qué íconos sobran.
        if (migration?.damaged != true) {
            registerNotebooks()
            removeOrphanIcons()
            removeOrphanFiles()
            scheduleSave()
        }
    }

    /**
     * Adjuntos e imágenes de enlaces que ninguna nota usa (de una nota borrada
     * mientras se copiaban, o de una copia cortada). Solo al abrir la app.
     */
    private fun removeOrphanFiles() {
        val notes = _notes.value
        // Por la ruta real de cada adjunto (carpeta de attachment.noteId), que es
        // donde lo busca attachmentFile; las carpetas de notas que existen se
        // conservan aunque estén vacías.
        val usedFiles = notes.flatMap { note -> note.attachments.map { attachmentFile(it).canonicalPath } }.toSet()
        val keptDirs = (notes.map { it.id } + notes.flatMap { n -> n.attachments.map { it.noteId } }).toSet()
        attachmentsRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (dir.name !in keptDirs) {
                quarantine(dir)
            } else {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.canonicalPath !in usedFiles) quarantine(file)
                }
            }
        }
        val covers = notes.mapNotNull { coverFile(it)?.name }.toSet()
        coversRoot.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in covers) quarantine(file)
        }
        val images = notes.flatMap { note -> note.bookmarks.flatMap { listOfNotNull(it.imageFile, it.faviconFile) } }.toSet()
        bookmarksRoot.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in images) quarantine(file)
        }
        // Lo apartado hace más de 7 días ya se puede borrar.
        val limit = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        orphansRoot.listFiles()?.forEach { batch ->
            if (batch.lastModified() < limit) batch.deleteRecursively()
        }
    }

    /**
     * Los archivos sueltos no se borran enseguida: se apartan en "huerfanos" y se
     * borran a los 7 días. Si alguno resultara estar en uso, todavía se recupera.
     */
    private fun quarantine(file: File) {
        val batch = File(orphansRoot, orphanBatch).apply { mkdirs() }
        val relative = file.relativeTo(appContext.filesDir).path.replace(File.separatorChar, '_')
        if (!file.renameTo(File(batch, relative))) file.deleteRecursively()
    }

    /**
     * Borra de la carpeta de íconos los archivos que ninguna nota usa: los que quedaron
     * de una nota borrada mientras se copiaba su ícono, o de una copia cortada a la mitad.
     * Solo corre al abrir la app, cuando todavía no se está eligiendo ningún ícono.
     */
    private fun removeOrphanIcons() {
        val used = (_notes.value.mapNotNull { note -> iconFile(note)?.name } +
            _notebookIcons.value.values.mapNotNull { notebookIconFile(it)?.name }).toSet()
        iconsRoot.listFiles()?.forEach { file ->
            if (file.name !in used) file.delete()
        }
    }

    fun findNote(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- escritura

    /**
     * [id]: para conservar el id original al importar un export propio. Si ya existe
     * o no sirve como nombre de carpeta, se usa uno nuevo. [fresh]: nota creada con
     * "Nueva nota", que se descarta si se cierra vacía.
     */
    fun createNote(notebook: String? = null, id: String? = null, fresh: Boolean = false): Note {
        val usable = id?.takeIf { isSafeId(it) && findNote(it) == null }
        val note = Note(
            id = usable ?: UUID.randomUUID().toString(),
            notebook = notebook,
            sortOrder = (_notes.value.minOfOrNull { it.sortOrder } ?: 0L) - 1L
        )
        _notes.update { (it + note).sortedWith(ORDER) }
        if (fresh) freshIds.add(note.id)
        scheduleSave()
        return note
    }

    fun upsert(note: Note) {
        val clean = note.withDerived()
        _notes.update { list -> (list.filterNot { it.id == clean.id } + clean).sortedWith(ORDER) }
        scheduleSave()
    }

    fun update(noteId: String, transform: (Note) -> Note) {
        _notes.update { list ->
            list.map { note ->
                if (note.id == noteId) {
                    transform(note).withDerived()
                } else {
                    note
                }
            }.sortedWith(ORDER)
        }
        scheduleSave()
    }

    fun touch(noteId: String) = update(noteId) { it.copy(updatedAt = System.currentTimeMillis()) }

    fun togglePin(noteId: String) = update(noteId) {
        it.copy(pinned = !it.pinned, updatedAt = System.currentTimeMillis())
    }

    /**
     * Manda la nota a la papelera junto con las notas que tiene adentro (si no están
     * también dentro de otra nota que sigue activa). Si no, las de adentro quedaban
     * sueltas en la lista y parecía que "reaparecían" notas. Devuelve cuántas se
     * fueron con ella.
     */
    fun moveToTrash(noteId: String): Int {
        var companions = 0
        _notes.update { list ->
            val with = notesTrashedWith(list, noteId)
            companions = with.size
            list.map { note ->
                when {
                    note.id == noteId -> note.copy(trashed = true, trashedWith = null)
                    note.id in with -> note.copy(trashed = true, trashedWith = noteId)
                    else -> note
                }
            }.sortedWith(ORDER)
        }
        scheduleSave()
        return companions
    }

    /**
     * Restaura la nota y las que se habían ido a la papelera con ella. También las de
     * adentro que se habían ido con otra nota (una nota dentro de dos): si no, la nota
     * restaurada mostraría un bloque "falta".
     */
    fun restore(noteId: String) {
        _notes.update { list ->
            val back = linkedSetOf(noteId)
            list.filter { it.trashed && it.trashedWith == noteId }.forEach { back.add(it.id) }
            val byId = list.associateBy { it.id }
            val pending = ArrayDeque(back.toList())
            while (pending.isNotEmpty()) {
                val current = byId[pending.removeFirst()] ?: continue
                linkedNoteIds(current).forEach { child ->
                    val note = byId[child]
                    if (note != null && note.trashed && note.trashedWith != null && back.add(child)) {
                        pending.addLast(child)
                    }
                }
            }
            list.map { note ->
                if (note.id in back) note.copy(trashed = false, trashedWith = null) else note
            }.sortedWith(ORDER)
        }
        scheduleSave()
    }

    /**
     * Borra para siempre la nota y las que se habían ido a la papelera con ella,
     * salvo las que también están dentro de otra nota: esas pasan a depender de esa
     * otra (o vuelven a la lista, si está activa).
     */
    fun deletePermanently(noteId: String) {
        val list = _notes.value
        val candidates = list.filter { it.id == noteId || (it.trashed && it.trashedWith == noteId) }
        val candidateIds = candidates.map { it.id }.toSet()
        val kept = HashMap<String, Note>()
        candidates.filter { it.id != noteId }.forEach { child ->
            val holder = list.firstOrNull { it.id !in candidateIds && child.id in linkedNoteIds(it) }
            if (holder != null) {
                kept[child.id] = if (holder.trashed) {
                    child.copy(trashedWith = holder.trashedWith ?: holder.id)
                } else {
                    child.copy(trashed = false, trashedWith = null)
                }
            }
        }
        if (kept.isNotEmpty()) {
            _notes.update { current -> current.map { kept[it.id] ?: it }.sortedWith(ORDER) }
        }
        val doomed = candidates.filter { it.id !in kept }
        doomed.forEach { note ->
            note.bookmarks.forEach { bookmarkImageFile(it)?.delete(); bookmarkFaviconFile(it)?.delete() }
            iconFile(note)?.delete()
            coverFile(note)?.delete()
            File(attachmentsRoot, note.id).deleteRecursively()
        }
        val ids = doomed.map { it.id }.toSet()
        _notes.update { list -> list.filterNot { it.id in ids } }
        scheduleSave()
    }

    /** Borra para siempre las notas de la papelera y devuelve sus ids. */
    fun emptyTrash(): List<String> {
        val trashed = _notes.value.filter { it.trashed }
        trashed.forEach { note ->
            File(attachmentsRoot, note.id).deleteRecursively()
            note.bookmarks.forEach { bookmarkImageFile(it)?.delete(); bookmarkFaviconFile(it)?.delete() }
            iconFile(note)?.delete()
            coverFile(note)?.delete()
        }
        _notes.update { list -> list.filterNot { it.trashed } }
        scheduleSave()
        return trashed.map { it.id }
    }

    /**
     * Mueve la nota al cuaderno. A uno que está en la papelera no se puede, ni con otras
     * mayúsculas (como en [createNotebook]): devuelve false.
     */
    fun setNotebook(noteId: String, notebook: String?): Boolean {
        val name = notebook?.trim()?.takeIf { it.isNotBlank() }
        if (name != null && _notebooksTrashed.value.any { it.equals(name, ignoreCase = true) }) return false
        update(noteId) { it.copy(notebook = name) }
        return true
    }

    /** Renombra un cuaderno en todas sus notas (también las de la papelera); el ícono lo sigue. */
    fun renameNotebook(from: String, to: String) {
        val name = to.trim().takeIf { it.isNotBlank() } ?: return
        synchronized(iconLock) {
            // Mismo lugar en el menú, con el nombre nuevo.
            _notebookIcons.update { icons ->
                if (from !in icons) return@update icons
                icons.entries.associate { (key, icon) -> (if (key == from) name else key) to icon }
            }
            _notebookOrder.update { order -> order.map { if (it == from) name else it }.distinct() }
            // Las notas van en el mismo lock: si no, [registerNotebooks] puede ver el nombre
            // viejo en una nota y el nuevo en el menú, y vuelve a crear el cuaderno viejo.
            _notes.update { list ->
                list.map { if (it.notebook == from) it.copy(notebook = name) else it }
            }
        }
        scheduleSave()
    }

    /**
     * Manda el cuaderno a la papelera con todo lo que tiene adentro: las notas no se tocan
     * (siguen llevando el nombre del cuaderno), así que dejan de verse sueltas y vuelven
     * enteras al restaurarlo. Sus archivos se borran recién con [deleteNotebookPermanently].
     */
    fun trashNotebook(name: String) {
        if (name !in _notebookIcons.value && _notes.value.none { it.notebook == name }) return
        synchronized(iconLock) {
            // Un cuaderno con notas pero sin fila propia: se la crea para poder marcarlo.
            _notebookIcons.update { if (name in it) it else it + (name to "") }
            _notebooksTrashed.update { it + name }
        }
        scheduleSave()
    }

    /** Devuelve el cuaderno de la papelera al menú, con todas sus notas. */
    fun restoreNotebook(name: String) {
        synchronized(iconLock) { _notebooksTrashed.update { it - name } }
        scheduleSave()
    }

    /**
     * Borra el cuaderno para siempre: sus notas, sus adjuntos, portadas, íconos y tarjetas.
     * Devuelve cuántas notas se borraron.
     */
    fun deleteNotebookPermanently(name: String): Int {
        val doomed = _notes.value.filter { it.notebook == name }
        doomed.forEach { note ->
            File(attachmentsRoot, note.id).deleteRecursively()
            note.bookmarks.forEach { bookmarkImageFile(it)?.delete(); bookmarkFaviconFile(it)?.delete() }
            iconFile(note)?.delete()
            coverFile(note)?.delete()
        }
        val ids = doomed.map { it.id }.toSet()
        if (ids.isNotEmpty()) _notes.update { list -> list.filterNot { it.id in ids } }
        synchronized(iconLock) {
            notebookIconFile(_notebookIcons.value[name])?.delete()
            _notebookIcons.update { it - name }
            _notebookOrder.update { it - name }
            _notebooksTrashed.update { it - name }
        }
        scheduleSave()
        return ids.size
    }

    /**
     * Mueve el cuaderno [name] al lugar [toIndex] del menú (los demás se corren). El orden es el
     * de [notebookIcons].
     */
    fun moveNotebook(name: String, toIndex: Int) {
        synchronized(iconLock) {
            _notebookOrder.update { order ->
                if (name !in order) return@update order
                order.toMutableList().apply {
                    remove(name)
                    add(toIndex.coerceIn(0, size), name)
                }
            }
        }
        scheduleSave()
    }

    /** El orden queda con los mismos cuadernos que el mapa: saca los que ya no están y pone arriba de todo los nuevos. */
    private fun reconcileNotebookOrder() {
        synchronized(iconLock) {
            val names = _notebookIcons.value.keys
            _notebookOrder.update { order ->
                val kept = order.filter { it in names }
                names.filterNot { it in kept } + kept
            }
        }
    }

    /**
     * Crea un cuaderno vacío y devuelve su nombre. Si ya hay uno que se llama igual salvo
     * mayúsculas, devuelve ese y no crea otro. Null si el nombre queda vacío.
     */
    fun createNotebook(name: String): String? {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return null
        val existing = (_notebookIcons.value.keys + _notes.value.mapNotNull { it.notebook })
            .firstOrNull { it.equals(clean, ignoreCase = true) }
        val result = existing ?: clean
        // Uno que está en la papelera no se vuelve a crear: se restaura desde ahí.
        if (result in _notebooksTrashed.value) return null
        synchronized(iconLock) {
            _notebookIcons.update { if (result in it) it else it + (result to "") }
        }
        scheduleSave()
        return result
    }

    /**
     * Una nota nueva que se cerró sin escribir nada no queda como "Sin título".
     * Solo si no tiene título, texto, adjuntos ni enlaces y no está dentro de otra.
     */
    fun discardIfEmpty(noteId: String): Boolean {
        val notes = _notes.value
        val note = notes.firstOrNull { it.id == noteId } ?: return false
        val empty = isEmpty(note) && !note.trashed
        if (!empty) {
            // Ya tiene algo: si después se vacía, es una nota del usuario, no se descarta.
            freshIds.remove(noteId)
            return false
        }
        if (noteId !in freshIds || busyNotes.containsKey(noteId)) return false
        freshIds.remove(noteId)
        // Una nota creada adentro de otra y dejada vacía: también se saca su tarjeta
        // de la nota de afuera, para que no quede una "Sin título".
        notes.filter { !it.trashed && it.id != noteId && noteId in it.noteLinkIds }.forEach { holder ->
            update(holder.id) { it.copy(body = NoteHtml.removeNoteLinkBlocks(it.body, noteId)) }
        }
        deletePermanently(noteId)
        return true
    }

    /**
     * Si [noteId] es una nota nueva de esta sesión que se descartaría al salir si no tuviera
     * adjuntos ni tarjetas: sin título, texto ni ícono, y sin nada cargándose.
     */
    fun isFreshAndBlank(noteId: String): Boolean {
        if (noteId !in freshIds || busyNotes.containsKey(noteId)) return false
        val note = findNote(noteId) ?: return false
        return !note.trashed && note.title.isBlank() && NoteHtml.isBlank(note.body) && note.icon == null
    }

    /** Sin título, texto, adjuntos, enlaces ni ícono. */
    private fun isEmpty(note: Note): Boolean =
        note.title.isBlank() && NoteHtml.isBlank(note.body) &&
            note.attachments.isEmpty() && note.bookmarks.isEmpty() && note.icon == null

    /**
     * Mueve una nota al lugar que ocupa [targetId] en la lista (sin papelera).
     * Se ubica por id y no por posición porque la pantalla no muestra todas
     * las notas (las que están dentro de otra quedan ocultas).
     * Las fijadas y las no fijadas no se mezclan: el reorder solo cambia el
     * orden dentro del mismo grupo.
     */
    fun moveNote(noteId: String, targetId: String) {
        // Atómico: si mientras tanto se agrega un adjunto o un enlace en segundo
        // plano, ese cambio no se pisa con una copia vieja de la lista.
        var moved = false
        _notes.update { current ->
            val visible = current.filterNot { it.trashed }.sortedWith(ORDER)
            val fromIndex = visible.indexOfFirst { it.id == noteId }
            val toIndex = visible.indexOfFirst { it.id == targetId }
            val moving = visible.getOrNull(fromIndex)
            val target = visible.getOrNull(toIndex)
            if (moving == null || target == null || moving.pinned != target.pinned) {
                moved = false
                return@update current
            }
            val reordered = visible.toMutableList().apply {
                removeAt(fromIndex)
                add(toIndex.coerceIn(0, size), moving)
            }
            val trashed = current.filter { it.trashed }.sortedWith(ORDER)
            moved = true
            (reordered + trashed).mapIndexed { index, note ->
                note.copy(sortOrder = index.toLong())
            }
        }
        if (moved) scheduleSave()
    }

    // ---------------------------------------------------------------- portada

    /** Archivo de la portada de imagen de la nota, o null si es un color, un degradado o no tiene. */
    fun coverFile(note: Note): File? {
        val cover = note.cover ?: return null
        if (!cover.startsWith(COVER_FILE)) return null
        return File(coversRoot, cover.removePrefix(COVER_FILE))
    }

    /** Pone un color o degradado de portada (o ninguna, con null); borra la imagen anterior. */
    fun setCoverPreset(noteId: String, value: String?) {
        findNote(noteId)?.let { coverFile(it)?.delete() }
        update(noteId) { it.copy(cover = value, coverX = 0.5f, coverY = 0.5f, coverScale = 1f) }
    }

    /**
     * Copia una imagen del aparato como portada (JPG, PNG, HEIC…), achicada a [MAX_COVER_SIDE] px
     * de lado y derecha según su orientación. El encuadre vuelve al centro.
     */
    suspend fun setCoverFromUri(noteId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val destination = File(coversRoot, "${noteId}_${UUID.randomUUID()}.jpg")
        val ok = runCatching { writeCoverImage(uri, destination) }.getOrDefault(false)
        if (!ok || findNote(noteId) == null) {
            destination.delete()
            return@withContext false
        }
        findNote(noteId)?.let { coverFile(it)?.delete() }
        update(noteId) { it.copy(cover = COVER_FILE + destination.name, coverX = 0.5f, coverY = 0.5f, coverScale = 1f) }
        true
    }

    /** Guarda el encuadre de la portada de imagen. */
    fun setCoverCrop(noteId: String, x: Float, y: Float, scale: Float) = update(noteId) {
        it.copy(
            coverX = x.coerceIn(0f, 1f),
            coverY = y.coerceIn(0f, 1f),
            coverScale = scale.coerceIn(1f, MAX_COVER_ZOOM)
        )
    }

    private fun writeCoverImage(uri: Uri, destination: File): Boolean {
        val resolver = appContext.contentResolver
        val bitmap: Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = android.graphics.ImageDecoder.createSource(resolver, uri)
            // ImageDecoder ya respeta la orientación de las fotos de la cámara.
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val factor = MAX_COVER_SIDE.toFloat() / maxOf(w, h)
                if (factor < 1f) {
                    decoder.setTargetSize((w * factor).toInt().coerceAtLeast(1), (h * factor).toInt().coerceAtLeast(1))
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_SIDE) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false
        }
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return destination.length() > 0
    }

    // ---------------------------------------------------------------- íconos de cuaderno

    /** Archivo del ícono propio de un cuaderno ([icon] es el valor guardado), o null si es un emoji. */
    fun notebookIconFile(icon: String?): File? {
        if (icon == null || !icon.startsWith(ICON_FILE)) return null
        return File(iconsRoot, icon.removePrefix(ICON_FILE))
    }

    /** Cuenta, por cuaderno, cuántas veces se eligió un ícono (como [iconRequests]). */
    private val notebookIconRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun hasNotebook(name: String) = name in _notebookIcons.value || _notes.value.any { it.notebook == name }

    /** Pone un emoji como ícono del cuaderno (o ninguno, con null) y borra el archivo propio anterior. */
    fun setNotebookEmojiIcon(name: String, emoji: String?) {
        synchronized(iconLock) {
            notebookIconRequests.merge(name, 1L) { a, b -> a + b }
            notebookIconFile(_notebookIcons.value[name])?.delete()
            _notebookIcons.update { icons ->
                when {
                    !hasNotebook(name) -> icons
                    // Sin ícono, el cuaderno sigue existiendo.
                    emoji == null -> icons + (name to "")
                    else -> icons + (name to ICON_EMOJI + emoji)
                }
            }
        }
        scheduleSave()
    }

    /** Copia una imagen (o un SVG) elegida del aparato y la usa como ícono del cuaderno. */
    suspend fun setNotebookIconFromUri(name: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val request = synchronized(iconLock) { notebookIconRequests.merge(name, 1L) { a, b -> a + b } ?: 0L }
        val destination = copyIcon(uri, "nb") ?: return@withContext false
        synchronized(iconLock) {
            if (!hasNotebook(name) || notebookIconRequests[name] != request) {
                destination.delete()
                return@withContext hasNotebook(name)
            }
            notebookIconFile(_notebookIcons.value[name])?.delete()
            _notebookIcons.update { it + (name to ICON_FILE + destination.name) }
        }
        scheduleSave()
        true
    }

    /**
     * Anota como cuaderno todo nombre que tenga alguna nota (también en la papelera): así el
     * cuaderno sigue existiendo aunque después se quede vacío.
     */
    private fun registerNotebooks() {
        if (!writable) return
        synchronized(iconLock) {
            val icons = _notebookIcons.value
            val missing = _notes.value.mapNotNullTo(LinkedHashSet()) { it.notebook?.takeIf { n -> n.isNotBlank() } } - icons.keys
            if (missing.isEmpty()) return
            _notebookIcons.value = icons + missing.associateWith { "" }
        }
    }

    // ---------------------------------------------------------------- íconos

    /** Archivo del ícono propio de la nota, o null si usa uno incluido o ninguno. */
    fun iconFile(note: Note): File? {
        val icon = note.icon ?: return null
        if (!icon.startsWith(ICON_FILE)) return null
        return File(iconsRoot, icon.removePrefix(ICON_FILE))
    }

    /** Cuenta, por nota, cuántas veces se eligió un ícono: la copia de una imagen lenta sabe así si la pisó una elección posterior. */
    private val iconRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Pone un emoji como ícono (o ninguno, con null) y borra el archivo propio anterior. */
    fun setEmojiIcon(noteId: String, emoji: String?) {
        synchronized(iconLock) {
            iconRequests.merge(noteId, 1L) { a, b -> a + b }
            findNote(noteId)?.let { iconFile(it)?.delete() }
            update(noteId) { it.copy(icon = emoji?.let { e -> ICON_EMOJI + e }) }
        }
    }

    /** Copia una imagen (o un SVG) elegida del aparato y la usa como ícono de la nota. */
    suspend fun setIconFromUri(noteId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Si mientras se copia se elige otro ícono, gana el último elegido.
        val request = synchronized(iconLock) { iconRequests.merge(noteId, 1L) { a, b -> a + b } ?: 0L }
        // Archivo inválido o enorme: no se usa.
        val destination = copyIcon(uri, noteId) ?: return@withContext false
        // La nota se borró mientras se copiaba: no tiene que quedar un archivo suelto en
        // la carpeta de íconos.
        synchronized(iconLock) {
            if (findNote(noteId) == null || iconRequests[noteId] != request) {
                destination.delete()
                return@withContext findNote(noteId) != null
            }
            findNote(noteId)?.let { iconFile(it)?.delete() }
            update(noteId) { it.copy(icon = ICON_FILE + destination.name) }
        }
        true
    }

    /**
     * Copia una imagen a la carpeta de íconos como `<prefix>_<uuid>.<ext>`, tal cual (tamaño y
     * formato originales): al tocar el ícono se ve la imagen real. Null si no es una imagen válida
     * o no se pudo leer (y entonces no deja ningún archivo).
     */
    private fun copyIcon(uri: Uri, prefix: String): File? {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val name = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        val extension = when {
            mime.contains("svg", true) || name.endsWith(".svg", true) -> "svg"
            else -> imageExtension(mime, name)
        }
        val destination = File(iconsRoot, "${prefix}_${UUID.randomUUID()}.$extension")
        val ok = try {
            resolver.openInputStream(uri)?.use { input ->
                if (extension == "svg") writeSvgIcon(input, destination) else writeImageIcon(input, destination)
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (!ok) destination.delete()
        return destination.takeIf { ok }
    }

    /** SVG de hasta 512 KB que por lo menos parezca un SVG; si no, se rechaza. */
    private fun writeSvgIcon(input: java.io.InputStream, destination: File): Boolean {
        val bytes = input.readNBytesCompat(MAX_SVG_ICON_BYTES + 1)
        if (bytes.size > MAX_SVG_ICON_BYTES) return false
        if (!String(bytes, Charsets.UTF_8).contains("<svg", ignoreCase = true)) return false
        destination.writeBytes(bytes)
        return true
    }

    /** Extensión de una imagen según su tipo o su nombre; "img" si no se sabe (se lee igual). */
    private fun imageExtension(mime: String, name: String): String {
        val known = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")
        val fromMime = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.lowercase())
        val fromName = name.substringAfterLast('.', "").lowercase()
        return listOf(fromMime, fromName).firstOrNull { it != null && it in known } ?: "img"
    }

    /**
     * La imagen tal cual, si se puede decodificar y no pasa de [MAX_IMAGE_ICON_BYTES]. La nota y
     * la lista la dibujan achicada al tamaño de la cajita; el visor la muestra entera.
     */
    private fun writeImageIcon(input: java.io.InputStream, destination: File): Boolean {
        val temp = File(iconsRoot, "${destination.name}.tmp")
        try {
            var copied = 0L
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_IMAGE_ICON_BYTES) return false
                    output.write(buffer, 0, read)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            return temp.renameTo(destination) || run { temp.copyTo(destination, overwrite = true); true }
        } finally {
            temp.delete()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- adjuntos

    fun attachmentFile(attachment: Attachment): File =
        File(File(attachmentsRoot, attachment.noteId), attachment.storedName)

    fun bookmarkImageFile(bookmark: Bookmark): File? =
        bookmark.imageFile?.let { File(bookmarksRoot, it) }

    fun bookmarkFaviconFile(bookmark: Bookmark): File? =
        bookmark.faviconFile?.let { File(bookmarksRoot, it) }

    /**
     * Agrega a la nota una tarjeta cuyos datos ya se conocen (por ejemplo, al importar):
     * copia la imagen y el ícono del sitio a la carpeta de tarjetas y conserva el id.
     * Una imagen que no se puede copiar queda en null; la tarjeta se agrega igual.
     */
    suspend fun addKnownBookmark(noteId: String, bookmark: Bookmark, image: Uri?, favicon: Uri?): Bookmark =
        withContext(Dispatchers.IO) {
            // Si ya hay imágenes con este id (de otra tarjeta), la importada recibe un id nuevo
            // para no pisarlas; quien llama lo lee del resultado.
            val taken = (image != null || favicon != null) && bookmarksRoot.listFiles()?.any {
                it.name.startsWith(bookmark.id + ".") || it.name.startsWith(bookmark.id + "_icon.")
            } == true
            val id = if (taken) UUID.randomUUID().toString() else bookmark.id
            val imageFile = image?.let { copyBookmarkImage(it, id, "") }
            val faviconFile = favicon?.let { copyBookmarkImage(it, id, "_icon") }
            val saved = bookmark.copy(id = id, imageFile = imageFile, faviconFile = faviconFile)
            if (findNote(noteId) == null) {
                imageFile?.let { File(bookmarksRoot, it).delete() }
                faviconFile?.let { File(bookmarksRoot, it).delete() }
            } else {
                update(noteId) { note -> note.copy(bookmarks = note.bookmarks + saved) }
            }
            saved
        }

    /** Copia [uri] a `bookmarks/<id><suffix>.<ext>` y devuelve el nombre, o null si falla. */
    private fun copyBookmarkImage(uri: Uri, id: String, suffix: String): String? {
        val extension = (uri.lastPathSegment ?: "").substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
        val destination = File(bookmarksRoot, "$id$suffix.$extension")
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: return null
            destination.name
        } catch (e: Exception) {
            destination.delete()
            null
        }
    }

    private suspend fun <T> withinSeconds(seconds: Long, block: suspend () -> T): T? {
        val job = scope.async { runCatching { block() }.getOrNull() }
        return withTimeoutOrNull(seconds * 1000) { job.await() } ?: run {
            job.cancel()
            null
        }
    }

    suspend fun addBookmark(noteId: String, rawUrl: String): Bookmark? =
        withContext(Dispatchers.IO) {
            if (rawUrl.isBlank()) return@withContext null
            beginWork(noteId)
            try {
                addBookmarkNow(noteId, rawUrl)
            } finally {
                endWork(noteId)
            }
        }

    private suspend fun addBookmarkNow(noteId: String, rawUrl: String): Bookmark? =
        withContext(Dispatchers.IO) {
            val cleanUrl = BookmarkFetcher.normalize(rawUrl)

            // Cada paso tiene un tope de tiempo: "Procesando…" no queda colgado si la
            // página o la imagen tardan.
            val metadata = withinSeconds(15) { BookmarkFetcher.fetch(cleanUrl) }
                ?: BookmarkMetadata(
                    title = cleanUrl,
                    description = "",
                    imageUrl = null,
                    siteName = null
                )

            val id = UUID.randomUUID().toString()
            val imageFile = metadata.imageUrl?.let { imageUrl ->
                val extension = when {
                    imageUrl.contains(".png", true) -> "png"
                    imageUrl.contains(".webp", true) -> "webp"
                    imageUrl.contains(".gif", true) -> "gif"
                    else -> "jpg"
                }
                val destination = File(bookmarksRoot, "$id.$extension")
                if (withinSeconds(15) { BookmarkFetcher.downloadImage(imageUrl, destination) } == true) {
                    destination.name
                } else {
                    null
                }
            }

            val bookmark = Bookmark(
                id = id,
                url = cleanUrl,
                title = metadata.title.ifBlank { cleanUrl },
                description = metadata.description,
                imageUrl = metadata.imageUrl,
                imageFile = imageFile,
                siteName = metadata.siteName
            )
            if (findNote(noteId) == null) {
                // La nota se borró mientras se cargaba el enlace.
                imageFile?.let { File(bookmarksRoot, it).delete() }
                return@withContext null
            }
            update(noteId) { note ->
                note.copy(bookmarks = note.bookmarks + bookmark)
            }
            bookmark
        }

    suspend fun addAttachment(
        noteId: String,
        uri: Uri,
        knownName: String? = null,
        knownMime: String? = null
    ): Attachment? = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        // Una copia empezada se termina y se registra aunque se salga de la nota.
        if (findNote(noteId) == null) return@withContext null
        beginWork(noteId)
        try {
            copyAttachment(noteId, uri, knownName, knownMime)
        } finally {
            endWork(noteId)
        }
    }

    private fun copyAttachment(
        noteId: String,
        uri: Uri,
        knownName: String?,
        knownMime: String?
    ): Attachment? {
        val resolver = appContext.contentResolver
        val mime = knownMime ?: resolver.getType(uri) ?: "application/octet-stream"
        val name = (knownName ?: queryDisplayName(uri) ?: "archivo").ifBlank { "archivo" }
        val id = UUID.randomUUID().toString()
        val storedName = "${id}_${sanitizeFileName(name)}"
        val dir = File(attachmentsRoot, noteId).apply { mkdirs() }
        val destination = File(dir, storedName)

        try {
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: return null
        } catch (e: Exception) {
            destination.delete()
            if (e is java.io.IOException && dir.usableSpace < 1024 * 1024) {
                report("No hay lugar en el almacenamiento para «$name».")
            }
            return null
        }
        if (findNote(noteId) == null) {
            destination.delete()
            return null
        }

        val attachment = Attachment(
            id = id,
            noteId = noteId,
            name = name,
            mime = mime,
            size = destination.length(),
            storedName = storedName,
            createdAt = System.currentTimeMillis()
        )

        update(noteId) { note ->
            note.copy(
                attachments = note.attachments + attachment,
                updatedAt = System.currentTimeMillis()
            )
        }
        return attachment
    }

    /**
     * Al cerrar la nota en el editor: los adjuntos y tarjetas que su cuerpo final ya no usa
     * ([usedAttachmentIds], [usedBookmarkIds]) salen de la nota y sus archivos se apartan en
     * "huerfanos", que se borra a los 7 días. Mientras la nota estaba abierta seguían, para que
     * deshacer la quita los devuelva. Con un adjunto o un enlace cargándose no se toca nada: el
     * que llega todavía no está en el cuerpo.
     */
    fun releaseUnusedContents(noteId: String, usedAttachmentIds: Set<String>, usedBookmarkIds: Set<String>) {
        if (busyNotes.containsKey(noteId)) return
        val note = findNote(noteId) ?: return
        val attachments = note.attachments.filterNot { it.id in usedAttachmentIds }
        val bookmarks = note.bookmarks.filterNot { it.id in usedBookmarkIds }
        if (attachments.isEmpty() && bookmarks.isEmpty()) return
        attachments.map { attachmentFile(it) }.filter { it.exists() }.forEach { quarantine(it) }
        bookmarks.flatMap { listOfNotNull(bookmarkImageFile(it), bookmarkFaviconFile(it)) }.filter { it.exists() }.forEach { quarantine(it) }
        val attachmentIds = attachments.map { it.id }.toSet()
        val bookmarkIds = bookmarks.map { it.id }.toSet()
        update(noteId) { current ->
            current.copy(
                attachments = current.attachments.filterNot { it.id in attachmentIds },
                bookmarks = current.bookmarks.filterNot { it.id in bookmarkIds },
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Las notas cuyo título, texto o enlaces contienen [query], sin importar mayúsculas: lo mismo
     * que [Note.matchesSearch], buscado en Room. Null si la base no sirve para esta búsqueda (la
     * consulta no se puede buscar en la columna, la base es de solo lectura o el último guardado
     * falló): entonces se busca en la lista, como antes.
     */
    suspend fun searchIds(query: String): Set<String>? {
        if (!SearchKey.canSearch(query)) return null
        loaded.await()
        if (!writable || lastSaveFailed) return null
        return withContext(Dispatchers.IO) {
            runCatching { dao.searchIds(SearchKey.fold(query)).toHashSet() }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- persistencia

    private fun scheduleSave() {
        // Todo cambio pasa por acá: un nombre de cuaderno nuevo queda anotado como cuaderno.
        registerNotebooks()
        reconcileNotebookOrder()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(250)
            persist()
        }
    }

    /**
     * Guarda ya. Corre en el scope del almacenamiento, que vive con el proceso: si
     * se cierra la pantalla en ese momento, el guardado no se corta.
     */
    fun flush() {
        saveJob?.cancel()
        saveJob = scope.launch { persist() }
    }

    suspend fun flushAndWait() {
        saveJob?.cancel()
        scope.launch { persist() }.join()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        loaded.await()
        if (!writable) return@withContext
        saveMutex.withLock {
            // Un guardado empezado se termina aunque se pida otro (flush cancela el anterior).
            withContext(NonCancellable) { saveChanges() }
        }
    }

    /**
     * Guarda en Room lo que cambió desde el último guardado. Si cambió el cuerpo, el anterior
     * va al historial y sube `revision`. La nota nueva que sigue vacía no se guarda,
     * salvo que ya esté dentro de otra nota.
     */
    private suspend fun saveChanges() {
        saveNotebookIcons()
        val current = _notes.value
        val now = System.currentTimeMillis()
        val saved = HashMap<String, Note>(current.size)
        val upserts = ArrayList<Note>()
        val history = ArrayList<NoteHistoryEntity>()
        // Una nota nueva vacía que ya está dentro de otra se guarda igual: si la app
        // muere antes de descartarla, la tarjeta de la otra no queda rota.
        val linked = current.flatMapTo(HashSet()) { holder -> holder.noteLinkIds.filter { it != holder.id } }
        for (note in current) {
            val before = lastPersisted[note.id]
            if (before == null && note.id in freshIds && isEmpty(note) && note.id !in linked) continue
            val bodyChanged = before != null && before.body != note.body
            val revision = when {
                before == null -> note.revision
                bodyChanged -> before.revision + 1
                else -> before.revision
            }
            val next = note.copy(revision = revision)
            saved[note.id] = next
            if (next == before) continue
            if (before != null && bodyChanged) {
                history.add(
                    NoteHistoryEntity(
                        noteId = note.id,
                        revision = before.revision,
                        savedAt = now,
                        html = HistoryCodec.pack(before.body)
                    )
                )
            }
            upserts.add(next)
        }
        val deletes = lastPersisted.keys.filterNot { it in saved }
        if (upserts.isEmpty() && deletes.isEmpty()) return

        val result = runCatching {
            dao.save(upserts.map { it.toEntity() }, deletes, history, keep = HISTORY_KEEP)
        }
        if (result.isFailure) {
            lastSaveFailed = true
            report(
                if (appContext.filesDir.usableSpace < 1024 * 1024) {
                    "No se pudieron guardar los cambios: no hay lugar en el almacenamiento."
                } else {
                    "No se pudieron guardar los cambios."
                }
            )
            return
        }
        lastPersisted = saved
        lastSaveFailed = false
        _savedCount.update { it + 1 }
        val revisions = upserts.associate { it.id to it.revision }
        _notes.update { list ->
            list.map { note ->
                val revision = revisions[note.id]
                if (revision != null && revision != note.revision) note.copy(revision = revision) else note
            }
        }
    }

    /** Guarda en Room los íconos de cuaderno que cambiaron desde el último guardado. */
    private suspend fun saveNotebookIcons() {
        val icons = _notebookIcons.value
        val trashed = _notebooksTrashed.value
        val order = _notebookOrder.value.filter { it in icons } + icons.keys.filterNot { it in _notebookOrder.value }
        // También cuenta el orden (se ordena arrastrando) y quién está en la papelera.
        if (icons == lastPersistedNotebooks && order == lastPersistedOrder && trashed == lastPersistedTrashed) return
        val oldPositions = lastPersistedOrder.withIndex().associate { (i, k) -> k to i }
        val changedTrash = (trashed - lastPersistedTrashed) + (lastPersistedTrashed - trashed)
        val upserts = order.mapIndexedNotNull { index, name ->
            val icon = icons.getValue(name)
            if (lastPersistedNotebooks[name] == icon && oldPositions[name] == index && name !in changedTrash) null
            else NotebookEntity(name, icon, index, trashed = name in trashed)
        }
        val deletes = lastPersistedNotebooks.keys.filterNot { it in icons }
        val ok = runCatching { dao.saveNotebooks(upserts, deletes) }.isSuccess
        if (ok) {
            lastPersistedNotebooks = icons
            lastPersistedOrder = order
            lastPersistedTrashed = trashed
        } else {
            report("No se pudieron guardar los cambios.")
        }
    }

    companion object {
        @Volatile
        private var instance: NotesRepository? = null

        /** Uno solo por proceso: dos copias guardando se pisaban. */
        fun get(context: Context): NotesRepository =
            instance ?: synchronized(this) {
                instance ?: run {
                    // Antes de abrir la base: Room crea el archivo al abrirla.
                    val existed = context.getDatabasePath(NotesDatabase.NAME).exists()
                    NotesRepository(context.applicationContext, NotesDatabase.get(context), existed)
                }.also { instance = it }
            }

        /** Versiones viejas del cuerpo que se guardan por nota. */
        private const val HISTORY_KEEP = 50

        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")

        /** Un id que sirve como nombre de carpeta (nada de "." ni ".."). */
        fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

        const val ICON_BUILTIN = "builtin:"
        const val ICON_FILE = "file:"
        const val ICON_EMOJI = "emoji:"
        const val COVER_FILE = "file:"
        const val COVER_COLOR = "color:"
        const val COVER_GRADIENT = "gradient:"
        /** Lado mayor de una portada copiada: alcanza para el zoom sin pesar de más. */
        private const val MAX_COVER_SIDE = 2400
        const val MAX_COVER_ZOOM = 5f
        private const val MAX_SVG_ICON_BYTES = 512 * 1024
        private const val MAX_IMAGE_ICON_BYTES = 30L * 1024 * 1024

        private val ORDER: Comparator<Note> =
            compareByDescending<Note> { it.pinned }
                .thenBy { it.sortOrder }
                .thenByDescending { it.updatedAt }

        private val TAG_REGEX = Regex("(?<![\\p{L}\\p{N}_&/])#([\\p{L}\\p{N}_-]+)")

        // Lo que no es texto escrito: bloques y fragmentos de código, comentarios
        // internos, adjuntos y enlaces (con su dirección) y direcciones sueltas.
        private val NOT_TAG_TEXT = listOf(
            Regex("(?m)^```[^\\n]*\\n[\\s\\S]*?(^```[^\\n]*$|\\z)"),
            Regex("`[^`\\n]*`"),
            Regex("<!--[\\s\\S]*?-->"),
            Regex("\\[[^\\]\\n]*]\\(jot://[^)\\s]*\\)"),
            Regex("]\\([^)\\s]*\\)"),
            Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+"),
            Regex("www\\.\\S+")
        )
        private val HEX_COLOR = Regex("^(?=.*\\d)([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

        /**
         * Etiquetas "#nombre" del texto. "#Tag" y "#tag" son la misma (queda la
         * primera forma escrita). No cuentan los colores (#ff00aa) ni lo que esté
         * en código, en un adjunto o en una dirección web.
         */
        fun parseTags(body: String): List<String> {
            var text = body
            NOT_TAG_TEXT.forEach { regex -> text = regex.replace(text, " ") }
            return TAG_REGEX.findAll(text)
                .map { it.groupValues[1] }
                .filterNot { HEX_COLOR.matches(it) }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
                .toList()
        }

        private fun sanitizeFileName(name: String): String {
            val clean = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
            return clean.take(90).ifBlank { "archivo" }
        }
    }
}
