package com.jot.app.ui

import androidx.compose.ui.text.TextRange
import com.jot.app.data.NotesRepository
import com.jot.app.ui.rich.RichText
import com.jot.app.ui.rich.toHtml

/**
 * Dónde está el cursor en una foto del cuerpo: el bloque, el ítem de checklist si el foco está
 * en uno, y la selección dentro de ese texto. [blockKey] `null` es sin foco en el cuerpo.
 */
internal data class UndoFocus(val blockKey: String?, val itemId: String?, val selection: TextRange)

/**
 * Si el valor nuevo de un campo cambió el texto o su formato, no solo el cursor: solo eso es
 * escribir para el deshacer.
 */
internal fun contentChanged(before: RichText, after: RichText): Boolean =
    after.plain != before.plain || after.toHtml() != before.toHtml()

/** Una foto del cuerpo de la nota: la lista de bloques entera y el cursor. */
internal data class UndoState(val blocks: List<EditorBlock>, val focus: UndoFocus)

/**
 * La pila de deshacer de una nota: hasta [limit] pasos, y lo que se escribe seguido, con menos
 * de [groupMs] entre cambio y cambio, es un solo paso. Vive en memoria: se pierde al
 * cerrar la app.
 *
 * Cada paso es la foto del cuerpo después del cambio; abajo de todo queda la de [reset].
 */
internal class NoteUndo(val limit: Int = 100, val groupMs: Long = 300) {

    private val steps = ArrayList<UndoState>()

    /** El estado actual en [steps]; lo de arriba es lo que se puede rehacer. */
    private var position = -1

    /** Si el último paso es un grupo de escritura todavía abierto. */
    private var typingOpen = false
    private var lastRecordAt = 0L

    val canUndo: Boolean get() = position > 0

    val canRedo: Boolean get() = position in 0 until steps.lastIndex

    /** El estado de ahora: el último anotado, o al que se llegó deshaciendo o rehaciendo. */
    val current: UndoState? get() = steps.getOrNull(position)

    /** Todas las fotos, de la más vieja a la más nueva: las que se pueden deshacer y rehacer. */
    val states: List<UndoState> get() = steps

    /** Los adjuntos que nombra alguna foto de la pila. */
    fun attachmentIds(): Set<String> = namedIds { (it as? EditorBlock.Media)?.attachmentId }

    /** Las tarjetas que nombra alguna foto de la pila. */
    fun bookmarkIds(): Set<String> = namedIds { (it as? EditorBlock.Bookmark)?.bookmarkId }

    /**
     * Los adjuntos y tarjetas que se quitaron y solo nombran fotos de antes o de después: alguna
     * foto los tiene, la de ahora no. Al reabrir la nota no se agregan al final. Los de la
     * foto de ahora (por ejemplo, el adjunto suelto de una nota vieja sin tocar) se agregan.
     */
    fun removedAttachmentIds(): Set<String> = attachmentIds() - currentIds { (it as? EditorBlock.Media)?.attachmentId }

    /** Como [removedAttachmentIds], para las tarjetas. */
    fun removedBookmarkIds(): Set<String> = bookmarkIds() - currentIds { (it as? EditorBlock.Bookmark)?.bookmarkId }

    private fun namedIds(id: (EditorBlock) -> String?): Set<String> =
        steps.asSequence().flatMap { it.blocks }.mapNotNull(id).toSet()

    private fun currentIds(id: (EditorBlock) -> String?): Set<String> =
        current?.blocks.orEmpty().mapNotNull(id).toSet()

    /** Empieza de cero con [state] como estado de partida, sin nada que deshacer ni rehacer. */
    fun reset(state: UndoState) {
        steps.clear()
        steps.add(state)
        position = 0
        typingOpen = false
    }

    /**
     * Anota el estado después de un cambio. Si es escribir ([typing]) y el paso de arriba también
     * lo era y pasaron menos de [groupMs] desde el último, lo reemplaza; si no, apila un paso
     * nuevo. Borra lo que se podía rehacer y, pasando [limit], el paso más viejo.
     */
    fun record(state: UndoState, typing: Boolean, now: Long) {
        if (position < 0) {
            reset(state)
            return
        }
        while (steps.lastIndex > position) steps.removeAt(steps.lastIndex)
        val sameGroup = typing && typingOpen && position > 0 && now - lastRecordAt < groupMs
        if (sameGroup) {
            steps[position] = state
        } else {
            steps.add(state)
            position++
            if (steps.size > limit + 1) {
                steps.removeAt(0)
                position--
            }
        }
        typingOpen = typing
        lastRecordAt = now
    }

    /** Vuelve al estado anterior y deja el actual para [redo]; `null` si no hay. */
    fun undo(): UndoState? {
        if (!canUndo) return null
        typingOpen = false
        position--
        return steps[position]
    }

    /** Vuelve al estado que se deshizo; `null` si no hay. */
    fun redo(): UndoState? {
        if (!canRedo) return null
        typingOpen = false
        position++
        return steps[position]
    }
}

/**
 * El deshacer de toda la nota sobre la lista de bloques del editor ([blocks]): cada cambio del
 * cuerpo se anota en [undo] con [bodyChanged] y [undo]/[redo] vuelven a poner la foto en la
 * lista. [now] es el reloj para agrupar lo que se escribe.
 *
 * Plegar no es un paso: al poner una foto, cada desplegable que sigue en la nota queda
 * abierto o cerrado como está ahora. El plegado de la sección de marcados de las checklists no
 * está en los bloques (es de la nota), así que no cambia.
 *
 * [hasRecord] dice si un adjunto o una tarjeta todavía está registrado en la nota: al cerrarla
 * se apartan los que ya no usan ni el cuerpo ni la pila, y una foto que nombre uno
 * apartado (la app murió y la pila vino de antes) se pone sin él. Para un bloque de otra nota,
 * si esa nota todavía existe: el de una nota ya borrada tampoco vuelve.
 *
 * [serialize] da el HTML de una lista de bloques: al abrir, compara la foto de ahora de la pila
 * con lo leído.
 */
internal class EditorUndo(
    private val blocks: MutableList<EditorBlock>,
    private val undo: NoteUndo,
    private val hasRecord: (EditorBlock) -> Boolean = { true },
    private val serialize: (List<EditorBlock>) -> String = { HtmlBlocks.serialize(it, emptyList(), emptyList(), emptyMap()) },
    private val now: () -> Long,
) {
    /**
     * Al abrir la nota, la foto de partida, si su pila está vacía: si la nota se vuelve a abrir,
     * lo que había para deshacer sigue. Pero si la nota cambió desde la foto de ahora
     * por otra vía (por ejemplo, se descartó una nota de adentro y su bloque salió del cuerpo),
     * la pila ya no corresponde y empieza de nuevo desde lo leído.
     */
    fun start(focus: UndoFocus) {
        val current = undo.current
        val empty = !undo.canUndo && !undo.canRedo
        if (empty || current == null || comparable(current.blocks) != comparable(blocks)) {
            undo.reset(UndoState(blocks.toList(), focus))
        }
    }

    /** El HTML de [list] sin el plegado de los desplegables, que no es un paso. */
    private fun comparable(list: List<EditorBlock>): String =
        serialize(list.map { if (it is EditorBlock.Toggle) it.copy(isToggled = true) else it })

    /**
     * Los adjuntos que nombran los bloques de ahora o alguna foto de la pila: al cerrar la nota no
     * se apartan, porque deshacer o rehacer los puede volver a poner.
     */
    fun attachmentIdsInUse(): Set<String> = blockAttachmentIds() + undo.attachmentIds()

    /** Como [attachmentIdsInUse], para las tarjetas. */
    fun bookmarkIdsInUse(): Set<String> = blockBookmarkIds() + undo.bookmarkIds()

    /** Los adjuntos que nombran solo los bloques de ahora, sin la pila. */
    fun blockAttachmentIds(): Set<String> =
        blocks.mapNotNull { (it as? EditorBlock.Media)?.attachmentId }.toSet()

    /** Las tarjetas que nombran solo los bloques de ahora, sin la pila. */
    fun blockBookmarkIds(): Set<String> =
        blocks.mapNotNull { (it as? EditorBlock.Bookmark)?.bookmarkId }.toSet()

    /** Anota el cuerpo después de un cambio, con el cursor en [focus]; [typing] si fue escribir. */
    fun bodyChanged(typing: Boolean, focus: UndoFocus) {
        undo.record(UndoState(blocks.toList(), focus), typing, now())
    }

    /**
     * Pone la foto anterior y devuelve dónde va el cursor; `null` si no hay nada que deshacer.
     * El cursor queda donde estaba el cambio que se deshace, dentro del texto de ahora; si
     * ese texto ya no está, el de la foto, y si no, [current], el cursor de antes de deshacer.
     */
    fun undo(current: UndoFocus): UndoFocus? {
        val undone = undo.current ?: return null
        val state = undo.undo() ?: return null
        restore(state)
        return within(undone.focus) ?: within(state.focus) ?: within(current) ?: NO_FOCUS
    }

    /** Vuelve a poner lo deshecho, con el cursor donde quedó ese cambio. */
    fun redo(current: UndoFocus): UndoFocus? {
        val state = undo.redo() ?: return null
        restore(state)
        return within(state.focus) ?: within(current) ?: NO_FOCUS
    }

    private fun restore(state: UndoState) {
        val folded = blocks.filterIsInstance<EditorBlock.Toggle>().associate { it.key to it.isToggled }
        val restored = state.blocks.map { block ->
            if (block !is EditorBlock.Toggle) block
            else folded[block.key]?.let { block.copy(isToggled = it) } ?: block
        }
        blocks.clear()
        blocks.addAll(restored)
        // Lo que ya no está en la nota se saca como si se quitara: los textos de los lados se unen.
        for (block in restored.filterNot(hasRecord)) {
            when (block) {
                is EditorBlock.Media -> BlockCommands.removeMedia(blocks, block)
                is EditorBlock.Bookmark -> BlockCommands.removeBookmark(blocks, block)
                is EditorBlock.NoteLink -> BlockCommands.removeNoteLink(blocks, block)
                else -> Unit
            }
        }
    }

    /** [focus] con la selección dentro de su texto, si ese bloque está en la nota. */
    private fun within(focus: UndoFocus): UndoFocus? {
        val block = blocks.firstOrNull { it.key == focus.blockKey } ?: return null
        val length = when (block) {
            is EditorBlock.Text -> block.value.plain.length
            is EditorBlock.Checklist -> block.items.firstOrNull { it.id == focus.itemId }?.value?.plain?.length ?: return null
            else -> return focus
        }
        val selection = TextRange(focus.selection.start.coerceIn(0, length), focus.selection.end.coerceIn(0, length))
        return focus.copy(selection = selection)
    }

    private companion object {
        val NO_FOCUS = UndoFocus(null, null, TextRange.Zero)
    }
}

/** Las pilas de deshacer de cada nota: viven en memoria mientras dura la app. */
internal class UndoStacks {
    private val stacks = mutableMapOf<String, NoteUndo>()

    /** La pila de [noteId]; la misma cada vez, así volver a abrir la nota no la pierde. */
    fun forNote(noteId: String): NoteUndo = stacks.getOrPut(noteId) { NoteUndo() }

    /** Olvida la pila de [noteId]. */
    fun drop(noteId: String) {
        stacks.remove(noteId)
    }
}

/**
 * Al salir de la nota con la flecha o con Atrás, después de guardar: aparta lo que ya no se usa
 * y descarta la nota si es nueva y quedó vacía. Devuelve `true` si la descartó.
 *
 * Una nota nueva cuyos bloques quedaron sin nada (se quitó su único adjunto o tarjeta, o se
 * deshizo la inserción) se aparta mirando solo los bloques de ahora, sin la pila: si no, lo que
 * nombra la pila seguiría en la nota y no se descartaría. Si se descarta, su pila también
 * se olvida: no queda a qué volver. Para las demás notas se aparta como al cerrar.
 */
internal fun releaseAndDiscard(
    repository: NotesRepository,
    stacks: UndoStacks,
    noteId: String,
    undo: EditorUndo,
): Boolean {
    val attachments = undo.blockAttachmentIds()
    val bookmarks = undo.blockBookmarkIds()
    if (attachments.isEmpty() && bookmarks.isEmpty() && repository.isFreshAndBlank(noteId)) {
        repository.releaseUnusedContents(noteId, attachments, bookmarks)
    } else {
        repository.releaseUnusedContents(noteId, undo.attachmentIdsInUse(), undo.bookmarkIdsInUse())
    }
    val discarded = repository.discardIfEmpty(noteId)
    if (discarded) stacks.drop(noteId)
    return discarded
}
