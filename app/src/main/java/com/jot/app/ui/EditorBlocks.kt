package com.jot.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import com.jot.app.data.Attachment
import com.jot.app.data.Bookmark
import com.jot.app.ui.rich.RichHtml
import com.jot.app.ui.rich.RichText
import com.jot.app.ui.rich.TextGeometry
import com.jot.app.ui.rich.dropTrailingNewline
import com.jot.app.ui.rich.joinLines
import com.jot.app.ui.rich.joinRich
import com.jot.app.ui.rich.lineStartOf
import com.jot.app.ui.rich.split
import com.jot.app.ui.rich.withSelection
import java.util.UUID

/**
 * El cuerpo de la nota se edita como una secuencia de bloques: texto con formato,
 * adjuntos, tarjetas, notas y desplegables. Al insertar un archivo en el cursor, el
 * bloque de texto se parte en dos y el adjunto queda exactamente en el medio; el foco
 * pasa al texto de abajo.
 *
 * Se guarda como HTML ([HtmlBlocks]): cada texto con sus renglones y cada
 * bloque propio con su `<div>`.
 */
internal sealed interface EditorBlock {
    val key: String
    val parentKey: String?

    /**
     * [realLine]: el texto salió de un renglón que estaba en la nota, pegado a una checklist
     * ([BlockCommands.normalizeChecklistsAt]); vacío, no es el lugar para escribir de
     * [ensureTextSlots] y se guarda igual ([withoutAutoSlots]).
     */
    data class Text(
        override val key: String,
        val value: RichText,
        override val parentKey: String? = null,
        val realLine: Boolean = false,
    ) : EditorBlock

    data class Media(
        override val key: String,
        val attachmentId: String,
        override val parentKey: String? = null
    ) : EditorBlock

    data class Bookmark(
        override val key: String,
        val bookmarkId: String,
        override val parentKey: String? = null
    ) : EditorBlock

    data class Toggle(
        override val key: String,
        val title: String,
        val isToggled: Boolean = true,
        override val parentKey: String? = null
    ) : EditorBlock

    /** Otra nota dentro de esta: se muestra con su ícono y título y al tocarla se abre. */
    data class NoteLink(
        override val key: String,
        val noteId: String,
        override val parentKey: String? = null
    ) : EditorBlock

    /**
     * Una checklist: los renglones `data-checked` seguidos, en el orden
     * guardado. No se arrastra como bloque, igual que un texto.
     */
    data class Checklist(
        override val key: String,
        val items: List<ChecklistItem>,
        override val parentKey: String? = null
    ) : EditorBlock
}

internal class BlockTree(private val blocks: List<EditorBlock>) {
    fun roots(): List<EditorBlock> = blocks.filter { it.parentKey == null }

    fun childrenOf(key: String): List<EditorBlock> = blocks.filter { it.parentKey == key }

    fun isVisible(block: EditorBlock): Boolean {
        val toggles = blocks.filterIsInstance<EditorBlock.Toggle>().associateBy { it.key }
        var parent = block.parentKey
        while (parent != null) {
            val toggle = toggles[parent] ?: return false
            if (!toggle.isToggled) return false
            parent = toggle.parentKey
        }
        return true
    }

    fun visible(): List<EditorBlock> = blocks.filter { isVisible(it) }

    fun depth(parentKey: String?): Int {
        val toggles = blocks.filterIsInstance<EditorBlock.Toggle>().associateBy { it.key }
        var depth = 0
        var current = parentKey
        while (current != null) {
            val toggle = toggles[current] ?: break
            depth++
            current = toggle.parentKey
        }
        return depth
    }
}

internal data class InsertPoint(val blockKey: String?, val selection: TextRange)

internal sealed interface PendingDrop {
    val lineTop: Float

    data class Text(
        val textKey: String,
        val offset: Int,
        override val lineTop: Float
    ) : PendingDrop

    data class Block(
        val targetIndex: Int,
        val parentKey: String?,
        override val lineTop: Float
    ) : PendingDrop
}

internal class DragDropController(
    private val blocks: MutableList<EditorBlock>,
    private val tree: BlockTree
) {
    fun update(
        fromKey: String,
        fingerY: Float,
        blockBounds: Map<String, Rect>,
        textGeometry: Map<String, TextGeometry>
    ): PendingDrop? {
        val from = blocks.indexOfFirst { it.key == fromKey }
        if (from < 0) return null

        val textTarget = blocks.withIndex().firstOrNull { (_, block) ->
            block is EditorBlock.Text &&
                block.key != fromKey &&
                tree.isVisible(block) &&
                blockBounds[block.key]?.let { fingerY in it.top..it.bottom } == true
        }
        val geometry = textTarget?.let { textGeometry[it.value.key] }
        if (textTarget != null && geometry != null && geometry.lineCount > 0) {
            val bounds = blockBounds[textTarget.value.key]
            if (bounds != null) {
                val localY = (fingerY - bounds.top)
                    .coerceIn(0f, (bounds.height - 1f).coerceAtLeast(0f))
                val text = (textTarget.value as? EditorBlock.Text)?.value?.plain.orEmpty()
                val offset = geometry.offsetAt(0f, localY).coerceIn(0, text.length)
                val lineStart = lineStartOf(text, offset)
                // El bloque cae al principio del párrafo (no se corta un párrafo por la
                // mitad): la línea tiene que marcar ese lugar, no el renglón visual
                // donde está la copia cuando el párrafo ocupa varias líneas.
                val line = geometry.lineForOffset(lineStart)
                return PendingDrop.Text(
                    textKey = textTarget.value.key,
                    offset = lineStart,
                    lineTop = bounds.top + geometry.lineTop(line)
                )
            }
        }

        fun isDescendantOf(block: EditorBlock, ancestorKey: String): Boolean {
            var parentKey = block.parentKey
            while (parentKey != null) {
                if (parentKey == ancestorKey) return true
                parentKey = blocks.firstOrNull { it.key == parentKey }?.parentKey
            }
            return false
        }

        fun subtreeEnd(index: Int): Int {
            val block = blocks.getOrNull(index) ?: return index
            if (block !is EditorBlock.Toggle) return index + 1
            var end = index + 1
            while (end < blocks.size && isDescendantOf(blocks[end], block.key)) end++
            return end
        }

        // Un desplegable cerrado no recibe bloques: se suelta antes o después de él,
        // como cualquier otro bloque. Si no, el bloque quedaría escondido adentro.
        val targetToggle = blocks.filterIsInstance<EditorBlock.Toggle>()
            .lastOrNull { toggle ->
                toggle.isToggled &&
                    tree.isVisible(toggle) &&
                    blockBounds[toggle.key]?.let { fingerY in it.top..it.bottom } == true
            }
        val targetParent = targetToggle?.key

        val siblings = blocks.withIndex().filter { (_, block) ->
            block.key != fromKey &&
                block.parentKey == targetParent &&
                tree.isVisible(block) &&
                blockBounds.containsKey(block.key)
        }

        if (siblings.isEmpty()) {
            if (targetParent == null) return null
            val allChildren = blocks.withIndex().filter { (_, block) ->
                block.key != fromKey && block.parentKey == targetParent
            }
            val toggleIndex = blocks.indexOfFirst { it.key == targetParent }
            val rawTarget = if (allChildren.isEmpty()) {
                toggleIndex + 1
            } else {
                subtreeEnd(allChildren.last().index)
            }
            val targetIndex = if (from < rawTarget) rawTarget - 1 else rawTarget
            val lineTop = blockBounds[targetParent]?.bottom
            return lineTop?.let { PendingDrop.Block(targetIndex, targetParent, it) }
        }

        val before = siblings.firstOrNull { (_, block) ->
            val center = blockBounds[block.key]?.center?.y ?: return@firstOrNull false
            fingerY < center
        }
        val rawTarget = if (before != null) {
            before.index
        } else {
            subtreeEnd(siblings.last().index)
        }
        val targetIndex = if (from < rawTarget) rawTarget - 1 else rawTarget
        val lineTop = if (before != null) {
            blockBounds[before.value.key]?.top
        } else {
            blockBounds[siblings.last().value.key]?.bottom
        }
        return lineTop?.let { PendingDrop.Block(targetIndex, targetParent, it) }
    }

    /**
     * Saca el bloque y une el texto que tenía arriba con el de abajo, como si
     * nunca hubiera estado ahí; si no, quedaría un hueco hasta reabrir la nota.
     */
    private fun removeAndMerge(index: Int): BlockCommands.MergedText? {
        blocks.removeAt(index)
        return BlockCommands.mergeTextAround(blocks, index)
    }

    /** Suelta el bloque en el texto; dos checklists que quedan pegadas se juntan. */
    fun dropText(mediaKey: String, pending: PendingDrop.Text): String? =
        placeInText(mediaKey, pending).also { BlockCommands.normalizeChecklists(blocks, null) }

    private fun placeInText(mediaKey: String, pending: PendingDrop.Text): String? {
        val mediaIndex = blocks.indexOfFirst { it.key == mediaKey }
        val media = blocks.getOrNull(mediaIndex)?.takeIf { it.isMovable() } ?: return null
        if (blocks.none { it.key == pending.textKey && it is EditorBlock.Text }) return null

        // Si el destino era el texto de abajo, ahora vive dentro del de arriba.
        val merged = removeAndMerge(mediaIndex)
        val movedInto = merged?.takeIf { it.removedKey == pending.textKey }
        val textKey = movedInto?.intoKey ?: pending.textKey
        val textIndex = blocks.indexOfFirst { it.key == textKey }
        val text = blocks.getOrNull(textIndex) as? EditorBlock.Text
        if (text == null) {
            blocks.add(media)
            return null
        }

        val offset = (pending.offset + (movedInto?.shift ?: 0)).coerceIn(0, text.value.plain.length)
        // El corte cae al principio de un renglón, así que lo de arriba termina
        // con el salto de ese renglón. Ese salto ya lo pone el bloque al guardar:
        // si se deja, queda un renglón vacío entre el texto y el bloque. Abajo el
        // renglón conserva su formato: un título sigue siendo título.
        val (before, after) = text.value.split(offset, keepBlockBelow = true)

        blocks[textIndex] = text.copy(value = before.dropTrailingNewline())
        var insertAt = textIndex + 1
        blocks.add(insertAt, media.withParent(text.parentKey))
        insertAt++
        if (after.plain.isNotEmpty()) {
            val afterKey = newKey()
            blocks.add(insertAt, EditorBlock.Text(afterKey, after, text.parentKey))
            return afterKey
        }
        return null
    }

    fun dropBlock(mediaKey: String, targetIndex: Int, targetParentKey: String?) {
        val from = blocks.indexOfFirst { it.key == mediaKey }
        if (from < 0) return
        val media = blocks[from].takeIf { it.isMovable() } ?: return
        // targetIndex ya descuenta el bloque sacado: igual a from es el mismo lugar.
        if (targetIndex == from && media.parentKey == targetParentKey) return
        val merged = removeAndMerge(from)
        // Al unir los textos desaparece un bloque más en la posición from.
        val target = if (merged != null && targetIndex > from) targetIndex - 1 else targetIndex
        blocks.add(
            target.coerceIn(0, blocks.size),
            media.withParent(targetParentKey)
        )
        BlockCommands.normalizeChecklists(blocks, null)
    }
}

/** Bloques que se pueden arrastrar a otro lugar: adjuntos, enlaces y notas. */
internal fun EditorBlock.isMovable(): Boolean =
    this is EditorBlock.Media || this is EditorBlock.Bookmark || this is EditorBlock.NoteLink

internal fun EditorBlock.withParent(parentKey: String?): EditorBlock = when (this) {
    is EditorBlock.Text -> copy(parentKey = parentKey)
    is EditorBlock.Media -> copy(parentKey = parentKey)
    is EditorBlock.Bookmark -> copy(parentKey = parentKey)
    is EditorBlock.Toggle -> copy(parentKey = parentKey)
    is EditorBlock.NoteLink -> copy(parentKey = parentKey)
    is EditorBlock.Checklist -> copy(parentKey = parentKey)
}

/** Dónde va el foco cuando el renglón que lo tenía pasó a ser un ítem: bloque, ítem y cursor. */
internal data class ChecklistFocus(val blockKey: String, val itemId: String, val cursor: Int)

/**
 * Dónde está o adónde va el foco del cuerpo: un texto ([itemId] `null`) o un ítem del bloque
 * de checklist [blockKey], con su selección.
 */
internal data class EditorFocus(val blockKey: String, val itemId: String?, val selection: TextRange)

/**
 * Los botones de la barra que transforman el texto: con el foco en un ítem, el editor
 * tiene que saber cuál se tocó, no solo qué hace.
 */
enum class BarAction { Bold, Italic, Strike, Heading, Bullet, Ordered, Check, Quote, Code, Divider }

internal object BlockCommands {
    /**
     * Deja los bloques como se leen al abrir la nota: cada texto con renglones de
     * checklist se parte en textos y checklists ([splitChecklists]); las checklists hermanas
     * pegadas se juntan en una (la primera conserva su clave); una checklist sin ítems se saca
     * y se unen los textos que quedan pegados ([mergeTextAround]). El primer tramo de texto
     * conserva la clave del texto partido.
     *
     * Si [focus] caía en un renglón que pasó a ser ítem, devuelve dónde poner el foco.
     */
    fun normalizeChecklists(blocks: MutableList<EditorBlock>, focus: InsertPoint?): ChecklistFocus? =
        normalizeChecklistsAt(blocks, focus)?.let { moved ->
            moved.itemId?.let { ChecklistFocus(moved.blockKey, it, moved.selection.start) }
        }

    /**
     * Como [normalizeChecklists], pero si el texto de [focus] se partió devuelve también dónde
     * quedó el cursor cuando no cae en un ítem: el tramo de texto que lo tiene y el cursor
     * dentro de él (no la selección vieja, que era del texto entero). `null` si el texto de
     * [focus] no se partió: su clave y su selección siguen valiendo.
     */
    fun normalizeChecklistsAt(blocks: MutableList<EditorBlock>, focus: InsertPoint?): EditorFocus? {
        var moved: EditorFocus? = null

        var index = 0
        while (index < blocks.size) {
            val text = blocks[index] as? EditorBlock.Text
            val pieces = text?.let { splitChecklists(it.value) }
            if (text == null || pieces == null || pieces.none { it is TextPiece.Items }) {
                index++
                continue
            }
            val cursor = focus?.takeIf { it.blockKey == text.key }?.selection?.start
            var keyKept = false
            // Dónde empieza cada tramo en el texto partido: los separa un `\n`.
            var pieceStart = 0
            val replacement = pieces.map { piece ->
                when (piece) {
                    is TextPiece.Plain -> {
                        val key = if (keyKept) newKey() else text.key.also { keyKept = true }
                        val length = piece.value.plain.length
                        var value = piece.value
                        if (cursor != null && moved == null && cursor - pieceStart in 0..length) {
                            moved = EditorFocus(key, null, TextRange(cursor - pieceStart))
                            value = value.withSelection(TextRange(cursor - pieceStart))
                        }
                        pieceStart += length + 1
                        EditorBlock.Text(key, value, text.parentKey, realLine = RichHtml.isBlank(value.text))
                    }
                    is TextPiece.Items -> {
                        val block = EditorBlock.Checklist(newKey(), piece.items, text.parentKey)
                        if (cursor != null && moved == null) {
                            piece.starts.indices.firstOrNull { i ->
                                cursor - piece.starts[i] in 0..piece.items[i].value.plain.length
                            }?.let { i ->
                                moved = EditorFocus(block.key, piece.items[i].id, TextRange(cursor - piece.starts[i]))
                            }
                        }
                        pieceStart += piece.items.sumOf { it.value.plain.length } + piece.items.size
                        block
                    }
                }
            }
            blocks.removeAt(index)
            blocks.addAll(index, replacement)
            index += replacement.size
        }

        index = 0
        while (index < blocks.size) {
            val block = blocks[index]
            if (block is EditorBlock.Checklist && block.items.isEmpty()) {
                blocks.removeAt(index)
                mergeTextAround(blocks, index)
            } else {
                index++
            }
        }

        index = 1
        while (index < blocks.size) {
            val above = blocks[index - 1] as? EditorBlock.Checklist
            val below = blocks[index] as? EditorBlock.Checklist
            if (above != null && below != null && above.parentKey == below.parentKey) {
                blocks[index - 1] = above.copy(items = ChecklistOps.normalize(above.items + below.items))
                blocks.removeAt(index)
                moved = moved?.let { if (it.blockKey == below.key) it.copy(blockKey = above.key) else it }
            } else {
                index++
            }
        }
        return moved
    }

    /**
     * El ítem [itemId] sale del bloque [blockKey] a un renglón de texto sin marca:
     * el bloque se parte en los ítems de arriba y los de abajo (el primer tramo conserva la
     * clave) y el texto queda entre los dos. Si no quedan ítems de un lado, el texto se une con
     * el texto hermano de ese lado; un texto vacío de al lado (el lugar para escribir de
     * [ensureTextSlots]) se reemplaza, y un ítem vacío igual deja su renglón. Devuelve la clave
     * del texto donde quedó y el cursor en su renglón ([cursor] corrido lo que corresponda).
     */
    fun itemToText(blocks: MutableList<EditorBlock>, blockKey: String, itemId: String, cursor: Int): InsertPoint {
        val index = blocks.indexOfFirst { it.key == blockKey }
        val list = blocks.getOrNull(index) as? EditorBlock.Checklist ?: return InsertPoint(blockKey, TextRange(cursor))
        val items = ChecklistOps.canonical(list.items)
        val at = items.indexOfFirst { it.id == itemId }
        if (at < 0) return InsertPoint(blockKey, TextRange(cursor))
        val item = items[at]
        val parentKey = list.parentKey
        val above = items.subList(0, at).toList()
        val below = ChecklistOps.normalize(items.subList(at + 1, items.size))

        var textKey = newKey()
        var value = RichText(item.value.text)
        var selection = cursor.coerceIn(0, value.plain.length)
        val replacement = buildList {
            if (above.isNotEmpty()) add(list.copy(items = above))
            add(EditorBlock.Text(textKey, value, parentKey))
            if (below.isNotEmpty()) add(EditorBlock.Checklist(if (above.isEmpty()) list.key else newKey(), below, parentKey))
        }
        blocks.removeAt(index)
        blocks.addAll(index, replacement)
        var textIndex = if (above.isNotEmpty()) index + 1 else index

        val upper = blocks.getOrNull(textIndex - 1) as? EditorBlock.Text
        if (above.isEmpty() && upper != null && upper.parentKey == parentKey) {
            if (!upper.isBlank()) {
                selection += upper.value.plain.length + 1
                value = joinLines(upper.value, value)
            }
            blocks.removeAt(textIndex)
            textIndex--
            textKey = upper.key
        }
        val lower = blocks.getOrNull(textIndex + 1) as? EditorBlock.Text
        if (below.isEmpty() && lower != null && lower.parentKey == parentKey) {
            if (!lower.isBlank()) value = joinLines(value, lower.value)
            blocks.removeAt(textIndex + 1)
        }
        blocks[textIndex] = EditorBlock.Text(textKey, value.withSelection(TextRange(selection)), parentKey)
        return InsertPoint(textKey, TextRange(selection))
    }

    /**
     * Un botón de la barra sobre el foco. En un texto aplica [transform] y sigue como
     * cualquier cambio del texto ([textChanged]): con Checklist, o con Título sobre un renglón
     * casilla + título, los renglones que quedan como ítems se llevan a un bloque.
     * En un ítem, Negrita, Cursiva, Tachado y Código cambian su texto; Checklist lo saca a un
     * renglón de texto ([itemToText]) y los demás además le aplican [transform]. Devuelve
     * dónde queda el foco, o `null` si no había dónde aplicarlo. Quien llama después llama a
     * [ensureTextSlots].
     */
    fun formatFocused(
        blocks: MutableList<EditorBlock>,
        focus: EditorFocus,
        action: BarAction,
        transform: (RichText) -> RichText,
    ): EditorFocus? {
        val index = blocks.indexOfFirst { it.key == focus.blockKey }
        return when (val block = blocks.getOrNull(index)) {
            is EditorBlock.Text -> {
                val value = transform(block.value.withSelection(focus.selection))
                blocks[index] = block.copy(value = value)
                textChanged(blocks, block.key) ?: EditorFocus(block.key, null, value.selection)
            }

            is EditorBlock.Checklist -> {
                val itemId = focus.itemId ?: return null
                val item = block.items.firstOrNull { it.id == itemId } ?: return null
                when (action) {
                    BarAction.Bold, BarAction.Italic, BarAction.Strike, BarAction.Code -> {
                        val value = transform(item.value.withSelection(focus.selection))
                        blocks[index] = block.copy(items = itemValueEdit(block.items, item, value).items)
                        EditorFocus(block.key, itemId, value.selection)
                    }

                    else -> {
                        val point = itemToText(blocks, block.key, itemId, focus.selection.start)
                        val textKey = point.blockKey ?: return null
                        if (action == BarAction.Check) return EditorFocus(textKey, null, point.selection)
                        val textIndex = blocks.indexOfFirst { it.key == textKey }
                        val text = blocks[textIndex] as EditorBlock.Text
                        val value = transform(text.value.withSelection(point.selection))
                        blocks[textIndex] = text.copy(value = value)
                        EditorFocus(textKey, null, value.selection)
                    }
                }
            }

            else -> null
        }
    }

    /**
     * Un atajo que no es un botón de la barra ([EditorShortcut]: subrayado, colores, quitar
     * formato, sangría, mover ítem…) sobre el foco: aplica [transform] al texto, que sigue como
     * cualquier cambio del texto ([textChanged]), o al texto del ítem, que se queda en su ítem.
     * Devuelve dónde queda el foco, o `null` si no había dónde aplicarlo.
     */
    fun transformText(
        blocks: MutableList<EditorBlock>,
        focus: EditorFocus,
        transform: (RichText) -> RichText,
    ): EditorFocus? {
        val index = blocks.indexOfFirst { it.key == focus.blockKey }
        return when (val block = blocks.getOrNull(index)) {
            is EditorBlock.Text -> {
                val value = transform(block.value.withSelection(focus.selection))
                blocks[index] = block.copy(value = value)
                textChanged(blocks, block.key) ?: EditorFocus(block.key, null, value.selection)
            }

            is EditorBlock.Checklist -> {
                val itemId = focus.itemId ?: return null
                val item = block.items.firstOrNull { it.id == itemId } ?: return null
                val value = transform(item.value.withSelection(focus.selection))
                blocks[index] = block.copy(items = itemValueEdit(block.items, item, value).items)
                EditorFocus(block.key, itemId, value.selection)
            }

            else -> null
        }
    }

    /**
     * Pone [inserted] justo después del bloque de checklist del lugar [index], sin partirlo
     *. Si lo que sigue es un texto hermano, el foco va a ese texto en 0; si no, se crea
     * uno vacío. Devuelve la clave del texto.
     */
    private fun insertAfterChecklist(blocks: MutableList<EditorBlock>, index: Int, inserted: List<EditorBlock>): String {
        val parentKey = blocks[index].parentKey
        var at = index + 1
        inserted.forEach { blocks.add(at++, it.withParent(parentKey)) }
        val next = blocks.getOrNull(at) as? EditorBlock.Text
        if (next != null && next.parentKey == parentKey) {
            blocks[at] = next.copy(value = next.value.withSelection(TextRange(0)))
            return next.key
        }
        val key = newKey()
        blocks.add(at, EditorBlock.Text(key, RichText.Empty, parentKey))
        return key
    }

    fun insertToggle(
        blocks: MutableList<EditorBlock>,
        tree: BlockTree,
        point: InsertPoint?
    ): String? {
        var index = point?.blockKey?.let { key -> blocks.indexOfFirst { it.key == key } } ?: -1
        if (index < 0) {
            index = blocks.indexOfLast { it is EditorBlock.Text && tree.isVisible(it) }
        }

        val toggleKey = newKey()
        val textKey = newKey()
        val checklist = blocks.getOrNull(index) as? EditorBlock.Checklist
        if (checklist != null) {
            // Justo después del bloque de checklist, sin partirlo.
            blocks.add(index + 1, EditorBlock.Toggle(toggleKey, "Desplegable", true, checklist.parentKey))
            blocks.add(index + 2, EditorBlock.Text(textKey, RichText.Empty, parentKey = toggleKey))
            return textKey
        }
        val target = blocks.getOrNull(index) as? EditorBlock.Text

        if (target == null) {
            blocks.add(EditorBlock.Toggle(toggleKey, "Desplegable", true))
            blocks.add(EditorBlock.Text(textKey, RichText.Empty, parentKey = toggleKey))
        } else {
            val parentKey = target.parentKey
            val (before, after) = splitAt(target.value, point)

            blocks[index] = target.copy(value = before)
            blocks.add(
                index + 1,
                EditorBlock.Toggle(toggleKey, "Desplegable", true, parentKey)
            )
            blocks.add(
                index + 2,
                EditorBlock.Text(textKey, RichText.Empty, parentKey = toggleKey)
            )
            if (after.plain.isNotEmpty()) {
                blocks.add(index + 3, EditorBlock.Text(newKey(), after, parentKey))
            }
        }
        return textKey
    }

    fun insertAttachments(
        blocks: MutableList<EditorBlock>,
        tree: BlockTree,
        attachmentsById: MutableMap<String, Attachment>,
        point: InsertPoint?,
        created: List<Attachment>
    ): String? {
        if (created.isEmpty()) return null
        created.forEach { attachmentsById[it.id] = it }

        var index = point?.blockKey?.let { key -> blocks.indexOfFirst { it.key == key } } ?: -1
        if (index < 0) {
            index = blocks.indexOfLast { it is EditorBlock.Text && tree.isVisible(it) }
        }

        if (blocks.getOrNull(index) is EditorBlock.Checklist) {
            return insertAfterChecklist(blocks, index, created.map { EditorBlock.Media("media-${it.id}", it.id) })
        }
        val target = blocks.getOrNull(index) as? EditorBlock.Text
        if (target == null) {
            created.forEach { blocks.add(EditorBlock.Media("media-${it.id}", it.id)) }
            val key = newKey()
            blocks.add(EditorBlock.Text(key, RichText.Empty))
            return key
        }

        val parentKey = target.parentKey
        val (before, after) = splitAt(target.value, point)

        blocks[index] = target.copy(value = before)

        var insertAt = index + 1
        created.forEach { attachment ->
            blocks.add(
                insertAt,
                EditorBlock.Media("media-${attachment.id}", attachment.id, parentKey)
            )
            insertAt++
        }

        val nextKey = newKey()
        blocks.add(
            insertAt,
            EditorBlock.Text(nextKey, after, parentKey)
        )
        return nextKey
    }

    fun insertBookmark(
        blocks: MutableList<EditorBlock>,
        tree: BlockTree,
        bookmarksById: MutableMap<String, Bookmark>,
        point: InsertPoint?,
        bookmark: Bookmark
    ): String? {
        bookmarksById[bookmark.id] = bookmark

        var index = point?.blockKey?.let { key -> blocks.indexOfFirst { it.key == key } } ?: -1
        if (index < 0) {
            index = blocks.indexOfLast { it is EditorBlock.Text && tree.isVisible(it) }
        }

        if (blocks.getOrNull(index) is EditorBlock.Checklist) {
            return insertAfterChecklist(blocks, index, listOf(EditorBlock.Bookmark("bookmark-${bookmark.id}", bookmark.id)))
        }
        val target = blocks.getOrNull(index) as? EditorBlock.Text
        if (target == null) {
            blocks.add(EditorBlock.Bookmark("bookmark-${bookmark.id}", bookmark.id))
            val key = newKey()
            blocks.add(EditorBlock.Text(key, RichText.Empty))
            return key
        }

        val parentKey = target.parentKey
        val (before, after) = splitAt(target.value, point)

        blocks[index] = target.copy(value = before)
        blocks.add(
            index + 1,
            EditorBlock.Bookmark("bookmark-${bookmark.id}", bookmark.id, parentKey)
        )
        val nextKey = newKey()
        blocks.add(
            index + 2,
            EditorBlock.Text(nextKey, after, parentKey)
        )
        return nextKey
    }

    fun removeMedia(blocks: MutableList<EditorBlock>, block: EditorBlock.Media) {
        val index = blocks.indexOfFirst { it.key == block.key }
        if (index < 0) return
        blocks.removeAt(index)
        mergeTextAround(blocks, index)
        normalizeChecklists(blocks, null)
    }

    fun removeBookmark(blocks: MutableList<EditorBlock>, block: EditorBlock.Bookmark) {
        val index = blocks.indexOfFirst { it.key == block.key }
        if (index < 0) return
        blocks.removeAt(index)
        mergeTextAround(blocks, index)
        normalizeChecklists(blocks, null)
    }

    /** Inserta el bloque de otra nota en el cursor, partiendo el texto como los adjuntos. */
    fun insertNoteLink(
        blocks: MutableList<EditorBlock>,
        tree: BlockTree,
        point: InsertPoint?,
        noteId: String
    ): String? {
        var index = point?.blockKey?.let { key -> blocks.indexOfFirst { it.key == key } } ?: -1
        if (index < 0) {
            index = blocks.indexOfLast { it is EditorBlock.Text && tree.isVisible(it) }
        }

        if (blocks.getOrNull(index) is EditorBlock.Checklist) {
            return insertAfterChecklist(blocks, index, listOf(EditorBlock.NoteLink(newKey(), noteId)))
        }
        val target = blocks.getOrNull(index) as? EditorBlock.Text
        if (target == null) {
            blocks.add(EditorBlock.NoteLink(newKey(), noteId))
            val key = newKey()
            blocks.add(EditorBlock.Text(key, RichText.Empty))
            return key
        }

        val parentKey = target.parentKey
        val (before, after) = splitAt(target.value, point)

        blocks[index] = target.copy(value = before)
        blocks.add(index + 1, EditorBlock.NoteLink(newKey(), noteId, parentKey))
        val nextKey = newKey()
        blocks.add(
            index + 2,
            EditorBlock.Text(nextKey, after, parentKey)
        )
        return nextKey
    }

    fun removeNoteLink(blocks: MutableList<EditorBlock>, block: EditorBlock.NoteLink) {
        val index = blocks.indexOfFirst { it.key == block.key }
        if (index < 0) return
        blocks.removeAt(index)
        mergeTextAround(blocks, index)
        normalizeChecklists(blocks, null)
    }

    fun removeToggle(blocks: MutableList<EditorBlock>, block: EditorBlock.Toggle) {
        val toRemove = mutableSetOf(block.key)
        var changed = true
        while (changed) {
            changed = false
            blocks.forEach { candidate ->
                val parent = candidate.parentKey
                if (parent != null && parent in toRemove && candidate.key !in toRemove) {
                    toRemove.add(candidate.key)
                    changed = true
                }
            }
        }
        blocks.removeAll { it.key in toRemove }
        normalizeChecklists(blocks, null)
    }

    /**
     * El único camino de todo cambio de un texto (escribir, pegar, Enter, la barra), con
     * el valor nuevo ya en el texto [key]. Si le quedó algún renglón de checklist sin título (Enter
     * en un renglón casilla + título, Título que se lo saca, pegar), se lleva a un bloque
     * ([normalizeChecklistsAt], D1, D10) y se ponen los lugares para escribir
     * ([ensureTextSlots]). Devuelve dónde queda el foco, o `null` si el texto no se partió (su
     * clave y su selección siguen valiendo).
     */
    fun textChanged(blocks: MutableList<EditorBlock>, key: String): EditorFocus? {
        val value = (blocks.firstOrNull { it.key == key } as? EditorBlock.Text)?.value ?: return null
        if (!hasChecklistLines(value)) return null
        val focus = normalizeChecklistsAt(blocks, InsertPoint(key, value.selection))
        ensureTextSlots(blocks)
        return focus
    }

    fun updateText(
        blocks: MutableList<EditorBlock>,
        key: String,
        value: RichText
    ): Boolean {
        val index = blocks.indexOfFirst { it.key == key }
        if (index < 0) return false
        val current = blocks[index] as? EditorBlock.Text ?: return false
        blocks[index] = current.copy(value = value)
        return true
    }

    fun mediaContextIds(
        blocks: List<EditorBlock>,
        attachmentsById: Map<String, Attachment>,
        block: EditorBlock.Media
    ): List<String> {
        val parent = block.parentKey
        return blocks.filterIsInstance<EditorBlock.Media>()
            .filter { it.parentKey == parent }
            .map { it.attachmentId }
            .filter { id ->
                val attachment = attachmentsById[id]
                attachment != null && (attachment.isImage || attachment.isVideo)
            }
    }

    /** Texto de abajo que se unió al de arriba al sacar un bloque del medio. */
    data class MergedText(val removedKey: String, val intoKey: String, val shift: Int)

    /** Une el texto de arriba con el de abajo del lugar [index], si son hermanos. */
    fun mergeTextAround(blocks: MutableList<EditorBlock>, index: Int): MergedText? {
        val previous = blocks.getOrNull(index - 1)
        val next = blocks.getOrNull(index)
        if (previous is EditorBlock.Text && next is EditorBlock.Text &&
            previous.parentKey == next.parentKey
        ) {
            // joinRich deja el cursor en la unión: ahí empieza el texto de abajo.
            val merged = joinRich(previous.value, next.value)
            blocks[index - 1] = previous.copy(value = merged)
            blocks.removeAt(index)
            return MergedText(next.key, previous.key, merged.selection.start)
        }
        return null
    }
}

/**
 * Parte [value] en el cursor o la selección de [point] (sin cursor, al final), como los
 * botones de insertar: lo de abajo empieza sin la marca de bloque del renglón (decisión 5).
 */
private fun splitAt(value: RichText, point: InsertPoint?): Pair<RichText, RichText> {
    val length = value.plain.length
    val start = (point?.selection?.min ?: length).coerceIn(0, length)
    val end = (point?.selection?.max ?: length).coerceIn(start, length)
    return value.split(start, end)
}

internal fun newKey(): String = UUID.randomUUID().toString()

/**
 * Siempre tiene que haber un texto donde escribir: al principio y al final de la
 * nota, y al final de cada desplegable. Se usa al abrir la nota y después de mover
 * bloques, así lo que se ve al editar es lo mismo que se ve al reabrir.
 */
internal fun ensureTextSlots(blocks: MutableList<EditorBlock>) {
    if (blocks.firstOrNull() !is EditorBlock.Text) {
        blocks.add(0, EditorBlock.Text(newKey(), RichText.Empty))
    }
    // El último bloque de la raíz (un desplegable va seguido de sus hijos en la lista).
    if (blocks.lastOrNull { it.parentKey == null } !is EditorBlock.Text) {
        blocks.add(EditorBlock.Text(newKey(), RichText.Empty))
    }
    blocks.filterIsInstance<EditorBlock.Toggle>().forEach { toggle ->
        val lastChild = blocks.lastOrNull { it.parentKey == toggle.key }
        if (lastChild is EditorBlock.Text) return@forEach
        val start = blocks.indexOfFirst { it.key == toggle.key }
        var end = start + 1
        while (end < blocks.size && isInside(blocks, blocks[end], toggle.key)) end++
        blocks.add(end, EditorBlock.Text(newKey(), RichText.Empty, toggle.key))
    }
}

internal fun isInside(blocks: List<EditorBlock>, block: EditorBlock, toggleKey: String): Boolean {
    var parent = block.parentKey
    while (parent != null) {
        if (parent == toggleKey) return true
        parent = blocks.firstOrNull { it.key == parent }?.parentKey
    }
    return false
}

/**
 * Saca los textos vacíos que [ensureTextSlots] vuelve a poner al abrir (el del
 * principio de la nota y el del final de la nota o del desplegable). Así abrir y
 * guardar sin tocar nada deja el cuerpo exactamente igual. Un texto sin letras pero con
 * marca de bloque (una viñeta vacía) no es un lugar vacío: se guarda; tampoco un renglón
 * vacío pegado a una checklist ([EditorBlock.Text.realLine]).
 */
internal fun List<EditorBlock>.withoutAutoSlots(leading: Boolean): List<EditorBlock> {
    var list = this
    if (leading && (list.firstOrNull() as? EditorBlock.Text)?.isAutoSlot() == true) {
        list = list.drop(1)
    }
    if ((list.lastOrNull() as? EditorBlock.Text)?.isAutoSlot() == true) {
        list = list.dropLast(1)
    }
    return list
}

/** Un texto vacío que no venía de la nota ([EditorBlock.Text.realLine]): el lugar para escribir. */
private fun EditorBlock.Text.isAutoSlot(): Boolean = !realLine && isBlank()

/** Sin texto ni marca de bloque: un renglón vacío. */
internal fun EditorBlock.Text.isBlank(): Boolean = RichHtml.isBlank(value.text)
