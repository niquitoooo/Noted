package com.noted.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.noted.app.data.Attachment
import com.noted.app.data.Bookmark
import com.noted.app.data.Note
import com.noted.app.data.NotesRepository
import com.noted.app.data.html.NoteHtml
import com.noted.app.ui.emoji.EmojiPickerSheet
import com.noted.app.ui.rich.ARROW_EDGE_END
import com.noted.app.ui.rich.DIVIDER_CHAR
import com.noted.app.ui.rich.InlineFormat
import com.noted.app.ui.rich.LocalReceiveMedia
import com.noted.app.ui.rich.LineFormat
import com.noted.app.ui.rich.BarFormat
import com.noted.app.ui.rich.RichCommands
import com.noted.app.ui.rich.RichText
import com.noted.app.ui.rich.RichTextField
import com.noted.app.ui.rich.TextGeometry
import com.noted.app.ui.rich.releaseRichField
import com.noted.app.ui.rich.rememberRichStyle
import com.noted.app.ui.rich.withEmptyLineAtEnd
import com.noted.app.ui.rich.withSelection
import com.noted.app.util.formatDate
import com.noted.app.util.humanSize
import com.noted.app.util.mimeIcon
import com.noted.app.util.shareText
import com.noted.app.util.typeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(
    vm: NotesViewModel,
    note: Note,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenAttachment: (Attachment, List<String>) -> Unit,
    onOpenNote: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // «Deshacer» de un aviso tocado después de salir de la nota no hace nada: este editor ya no está.
    val editorOpen = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { editorOpen.set(false) } }
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hasPhysicalKeyboard = rememberPhysicalKeyboard()
    val imeVisible = WindowInsets.isImeVisible
    val view = LocalView.current
    val imeController = remember(view) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
    }
    var keyboardRequest by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    var title by remember(note.id) { mutableStateOf(note.title) }
    val blocks = remember(note.id) {
        mutableStateListOf<EditorBlock>().apply {
            // Lo que se quitó y solo nombra la pila de deshacer no vuelve al final al reabrir.
            val stack = vm.undoFor(note.id)
            addAll(HtmlBlocks.parse(note.body, note.attachments, note.bookmarks, stack.removedAttachmentIds(), stack.removedBookmarkIds()))
        }
    }
    val tree = BlockTree(blocks)
    val dragDrop = DragDropController(blocks, tree)
    // Copia local de los adjuntos: no depende de que la nota se haya
    // recompuesto después de copiar un archivo. Así nunca se pierde el token
    // del cuerpo por una lista de adjuntos momentáneamente desactualizada.
    val attachmentsById = remember(note.id) {
        mutableStateMapOf<String, Attachment>().apply {
            note.attachments.forEach { put(it.id, it) }
        }
    }
    val bookmarksById = remember(note.id) {
        mutableStateMapOf<String, Bookmark>().apply {
            note.bookmarks.forEach { put(it.id, it) }
        }
    }
    var revision by remember(note.id) { mutableIntStateOf(0) }
    // Al volver de una nota de adentro, retomar donde estaba esta; al abrirla
    // desde la lista arranca arriba. Se espera a que el contenido (imágenes,
    // videos) tenga su alto real: si no, el scroll quedaría recortado más arriba.
    // Mientras tanto el contenido queda invisible, para que no se vea el
    // principio de la nota antes de saltar.
    val restoreScroll = remember(note.id) { vm.takeEditorScroll(note.id) }
    val blockScrollState = rememberScrollState(restoreScroll)
    // Los márgenes se arrastran con su propio estado, que le pasa el movimiento a la columna.
    // Con el mismo estado en los dos nodos, un segundo gesto durante la inercia se perdía:
    // los dos arrancaban a la vez y uno cancelaba al otro.
    val marginScrollState = remember(blockScrollState) {
        val own = ScrollableState { delta -> blockScrollState.dispatchRawDelta(delta) }
        object : ScrollableState by own {
            override suspend fun scroll(
                scrollPriority: MutatePriority,
                block: suspend ScrollScope.() -> Unit,
            ) {
                blockScrollState.stopScroll()
                own.scroll(scrollPriority, block)
            }
        }
    }
    var restoringScroll by remember(note.id) { mutableStateOf(restoreScroll > 0) }
    LaunchedEffect(note.id) {
        if (restoreScroll <= 0) return@LaunchedEffect
        // Antes del primer layout maxValue vale Int.MAX_VALUE: eso no es el alto real.
        withTimeoutOrNull(800) {
            snapshotFlow { blockScrollState.maxValue }
                .first { it != Int.MAX_VALUE && it >= restoreScroll }
        }
        blockScrollState.scrollTo(restoreScroll)
        restoringScroll = false
    }

    var menu by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    val iconFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setIconFromUri(note.id, uri)
    }
    val openIconPicker: () -> Unit = {
        releaseRichField(view)
        keyboard?.hide()
        focusManager.clearFocus()
        showIconPicker = true
    }
    var showCoverPicker by remember { mutableStateOf(false) }
    var adjustingCover by remember { mutableStateOf(false) }
    // Foto abierta a pantalla completa (portada o ícono) y su título en el visor.
    var viewingPhoto by remember { mutableStateOf<Pair<java.io.File, String>?>(null) }
    // «Editar» al final de la barra abre «Organizar».
    var arrangingToolbar by remember { mutableStateOf(false) }
    // Después de elegir la foto se pasa directo a acomodarla.
    val coverFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setCoverFromUri(note.id, uri) { adjustingCover = true }
    }
    var showInfo by remember { mutableStateOf(false) }
    var showNotebook by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    var bookmarkInsertPoint by remember(note.id) { mutableStateOf<InsertPoint?>(null) }

    var focusedKey by remember(note.id) { mutableStateOf<String?>(null) }
    // El ítem de checklist con el foco y el que tiene que tomarlo (ítem y cursor).
    var focusedItemId by remember(note.id) { mutableStateOf<String?>(null) }
    var itemFocusRequest by remember(note.id) { mutableStateOf<Pair<String, Int>?>(null) }
    // La sección de marcados plegada, por nota. Es un estado: el encabezado se redibuja.
    var checklistClosed by remember(note.id) { mutableStateOf(vm.isChecklistClosed(note.id)) }
    val lastFocusedKey = remember(note.id) { arrayOfNulls<String>(1) }
    // Geometría de cada texto: para soltar bloques y para las flechas entre bloques.
    val textGeometry = remember { mutableMapOf<String, TextGeometry>() }
    var titleFocused by remember { mutableStateOf(false) }
    var focusRequestKey by remember(note.id) { mutableStateOf<String?>(null) }
    var insertPoint by remember(note.id) { mutableStateOf<InsertPoint?>(null) }
    // El deshacer de toda la nota: la pila es del ViewModel, así sigue al volver a abrirla.
    val editorUndo = remember(note.id) {
        // El registro real de la nota, no las copias locales, que solo crecen.
        val hasRecord = { block: EditorBlock ->
            val current = vm.currentNote(note.id)
            when (block) {
                is EditorBlock.Media -> current?.attachments?.any { it.id == block.attachmentId } == true
                is EditorBlock.Bookmark -> current?.bookmarks?.any { it.id == block.bookmarkId } == true
                // Una nota de adentro que se descartó o se borró del todo no vuelve con Ctrl+Z.
                is EditorBlock.NoteLink -> vm.currentNote(block.noteId) != null
                else -> true
            }
        }
        // Para comparar la foto de ahora de la pila con lo leído al abrir: sin títulos, igual en los dos lados.
        val serialize = { list: List<EditorBlock> ->
            val current = vm.currentNote(note.id)
            HtmlBlocks.serialize(list, current?.attachments ?: note.attachments, current?.bookmarks ?: note.bookmarks, emptyMap())
        }
        EditorUndo(blocks, vm.undoFor(note.id), hasRecord, serialize, SystemClock::uptimeMillis).apply {
            start(UndoFocus(null, null, TextRange.Zero))
        }
    }

    val titleFocus = remember { FocusRequester() }
    val latestTitle by rememberUpdatedState(title)
    val allNotes by vm.notes.collectAsStateWithLifecycle()
    val notebookIcons by vm.notebookIcons.collectAsStateWithLifecycle()

    var lastSavedBody by remember(note.id) { mutableStateOf(note.body) }
    var lastSavedTitle by remember(note.id) { mutableStateOf(note.title) }

    // Si la nota cambia por otra vía (importación, otro editor), suma lo nuevo
    // sin borrar los adjuntos recién creados que todavía no llegaron al estado.
    LaunchedEffect(note.attachments) {
        note.attachments.forEach { attachmentsById[it.id] = it }
    }
    LaunchedEffect(note.bookmarks) {
        note.bookmarks.forEach { bookmarksById[it.id] = it }
    }

    // Títulos actuales de las notas enlazadas, para que el HTML guardado
    // muestre el nombre vigente aunque la otra nota se haya renombrado.
    fun noteTitles(): Map<String, String> = allNotes.associate { it.id to it.title }

    // El cuerpo en HTML, a partir de los bloques.
    fun bodyHtml(): String {
        val attachments = attachmentsById.values.toList()
        val bookmarks = bookmarksById.values.toList()
        return HtmlBlocks.serialize(blocks, attachments, bookmarks, noteTitles())
    }

    // El HTML de la nota tal como se abrió. Puede no ser exactamente el cuerpo guardado
    // (una nota recién migrada, por ejemplo): mientras los bloques den lo mismo, queda
    // el cuerpo guardado, así abrir y cerrar sin tocar no crea una versión nueva.
    val openedBody = remember(note.id) { note.body }
    val openedHtml = remember(note.id) { bodyHtml() }

    fun saveNow() {
        val html = bodyHtml()
        val body = bodyToSave(html, openedHtml, openedBody)
        if (body == lastSavedBody && latestTitle == lastSavedTitle) return
        lastSavedBody = body
        lastSavedTitle = latestTitle
        vm.updateNote(note.id) {
            it.copy(title = latestTitle, body = body, updatedAt = System.currentTimeMillis())
        }
    }

    fun persist() = saveNow()

    /**
     * Al cerrar la nota, los adjuntos y tarjetas que ya no usan ni los bloques ni la pila de
     * deshacer se apartan. Se cuentan los bloques, no el cuerpo guardado: una nota vieja
     * sin tocar guarda su cuerpo de antes, que puede no nombrar sus adjuntos aunque se vean al final.
     */
    fun releaseUnused() {
        vm.releaseUnusedContents(
            note.id,
            editorUndo.attachmentIdsInUse(),
            editorUndo.bookmarkIdsInUse(),
            // Lo que solo nombra la pila: si la app se cierra, al volver se aparta.
            editorUndo.attachmentIdsInUse() - editorUndo.blockAttachmentIds(),
            editorUndo.bookmarkIdsInUse() - editorUndo.blockBookmarkIds(),
        )
    }

    fun leave() {
        persist()
        // Aparta antes de descartar: una nota nueva a la que se le quitó su único adjunto o
        // tarjeta, o se le deshizo la inserción, queda vacía y se descarta con su pila.
        vm.releaseAndDiscard(note.id, editorUndo)
        vm.flush()
        // Antes de salir, con el campo todavía en la ventana.
        releaseRichField(view)
        onBack()
    }

    fun isBlockVisible(block: EditorBlock): Boolean = tree.isVisible(block)

    /** El cursor del cuerpo ahora, para la foto del deshacer. */
    fun undoFocus(): UndoFocus {
        val key = focusedKey
        return when (val block = blocks.firstOrNull { it.key == key }) {
            is EditorBlock.Text -> UndoFocus(key, null, block.value.selection)
            is EditorBlock.Checklist -> {
                val item = block.items.firstOrNull { it.id == focusedItemId }
                UndoFocus(key, item?.id, item?.value?.selection ?: TextRange.Zero)
            }
            else -> UndoFocus(key, null, TextRange.Zero)
        }
    }

    /**
     * Cambió el cuerpo: se guarda a los 3 s y queda como paso del deshacer; [typing] si fue
     * escribir en un texto o un ítem (se agrupa). Plegar no pasa por acá.
     */
    fun bodyChanged(typing: Boolean) {
        revision++
        editorUndo.bodyChanged(typing, undoFocus())
    }

    /**
     * Pide el foco en el ítem [itemId] del bloque [blockKey], con el cursor en [cursor], y
     * abre el teclado de pantalla como [focusLastText]. El foco del editor pasa ya al ítem,
     * así el campo que lo pierde no lo borra.
     */
    fun requestItemFocus(blockKey: String, itemId: String, cursor: Int) {
        val index = blocks.indexOfFirst { it.key == blockKey }
        val block = blocks.getOrNull(index) as? EditorBlock.Checklist ?: return
        blocks[index] = block.copy(items = block.items.map {
            if (it.id == itemId) it.copy(value = it.value.withCursor(cursor)) else it
        })
        itemFocusRequest = itemId to cursor
        focusedItemId = itemId
        focusedKey = blockKey
        lastFocusedKey[0] = blockKey
        keyboardRequest++
    }

    /** El texto visible más cercano arriba del bloque [blockKey] y su largo (el final). */
    fun textAboveEnd(blockKey: String): Pair<String, Int>? {
        val visible = tree.visible()
        val from = visible.indexOfFirst { it.key == blockKey }
        for (i in from - 1 downTo 0) {
            val text = visible[i] as? EditorBlock.Text ?: continue
            return text.key to text.value.plain.length
        }
        return null
    }

    /** Lleva el foco al texto [key] con [selection]; sin texto, al título. */
    fun focusTextAt(key: String?, selection: TextRange) {
        focusedItemId = null
        itemFocusRequest = null
        val index = key?.let { k -> blocks.indexOfFirst { it.key == k } } ?: -1
        val text = blocks.getOrNull(index) as? EditorBlock.Text
        if (text == null) {
            focusedKey = null
            runCatching { titleFocus.requestFocus() }
            return
        }
        val length = text.value.plain.length
        val range = TextRange(selection.start.coerceIn(0, length), selection.end.coerceIn(0, length))
        blocks[index] = text.copy(value = text.value.withSelection(range))
        focusRequestKey = text.key
        focusedKey = text.key
        keyboardRequest++
    }

    /**
     * Los ítems nuevos del bloque [blockKey] y el foco: si [ChecklistEdit.focusId]
     * es otro ítem que [source] (el que mandó el cambio), el foco va ahí; `null` sale al final
     * del texto de arriba. Con [ChecklistEdit.keepFocus] el id ya no estaba: no cambia nada
     *, ni la lista, que se ordenaría sin que nadie la haya editado.
     */
    fun updateChecklist(blockKey: String, edit: ChecklistEdit, source: String?, change: ItemChange) {
        if (edit.keepFocus) return
        val index = blocks.indexOfFirst { it.key == blockKey }
        val block = blocks.getOrNull(index) as? EditorBlock.Checklist ?: return
        val above = if (edit.focusId == null) textAboveEnd(blockKey) else null
        blocks[index] = block.copy(items = edit.items)
        if (edit.items.isEmpty()) {
            // Sin ítems el bloque se va y se unen los textos que quedan pegados.
            BlockCommands.normalizeChecklists(blocks, null)
            ensureTextSlots(blocks)
        }
        when {
            edit.focusId == null -> focusTextAt(above?.first, TextRange(above?.second ?: 0))
            edit.focusId != source -> requestItemFocus(blockKey, edit.focusId, edit.cursor)
        }
        when (change) {
            ItemChange.Cursor -> revision++
            ItemChange.Typing -> bodyChanged(typing = true)
            ItemChange.Edit -> bodyChanged(typing = false)
        }
    }

    /**
     * Marca o desmarca el ítem y guarda. Si tenía el foco, lo sigue teniendo; con la
     * sección plegada pasa a la fila de al lado o al texto de arriba.
     */
    fun toggleChecklistItem(blockKey: String, itemId: String) {
        val index = blocks.indexOfFirst { it.key == blockKey }
        val block = blocks.getOrNull(index) as? EditorBlock.Checklist ?: return
        val above = textAboveEnd(blockKey)
        val toggled = ChecklistOps.toggle(block.items, itemId)
        blocks[index] = block.copy(items = toggled)
        // El tocado tenía el foco, o el ítem con foco se fue con él a la sección plegada.
        val focused = focusedItemId?.takeIf { id -> block.items.any { it.id == id } }
        if (focused != null && focusLeavesAfterToggle(toggled, checklistClosed, focused, itemId)) {
            val cursor = block.items.firstOrNull { it.id == focused }?.value?.selection?.start ?: 0
            when (val target = focusAfterToggle(block.items, toggled, checklistClosed, focused, cursor)) {
                null -> focusTextAt(above?.first, TextRange(above?.second ?: 0))
                else -> requestItemFocus(blockKey, target.first, target.second)
            }
        }
        // Marcar entra en el deshacer, con el foco ya en su lugar.
        bodyChanged(typing = false)
    }

    /** El cursor de un campo al entrar con la x de la flecha, en su primera o última línea. */
    fun cursorFromArrow(key: String, length: Int, direction: Int, x: Float): Int {
        val geometry = textGeometry[key]
        return if (geometry != null && geometry.lineCount > 0) {
            val line = if (direction < 0) geometry.lineCount - 1 else 0
            val y = (geometry.lineTop(line) + geometry.lineBottom(line)) / 2f
            geometry.offsetAt(x, y).coerceIn(0, length)
        } else if (direction < 0) {
            length
        } else {
            0
        }
    }

    /** Entra al ítem [itemId] con la flecha; [x] es la del cursor desde el borde de la fila. */
    fun focusItemFromArrow(block: EditorBlock.Checklist, itemId: String, direction: Int, x: Float) {
        val item = block.items.firstOrNull { it.id == itemId } ?: return
        val start = with(density) { checklistTextStart(item.nested).toPx() }
        requestItemFocus(block.key, itemId, cursorFromArrow(itemId, item.value.plain.length, direction, x - start))
    }

    fun mediaContextIds(block: EditorBlock.Media): List<String> =
        BlockCommands.mediaContextIds(blocks, attachmentsById, block)

    fun currentInsertPoint(): InsertPoint {
        // Si el texto perdió el foco (por ejemplo, al contraer el desplegable donde
        // estaba el cursor), se usa el último lugar donde estuvo el cursor.
        // Un bloque de checklist también es un lugar: lo insertado va justo después.
        val focused = (focusedKey ?: lastFocusedKey[0])
            ?.let { key -> blocks.firstOrNull { it.key == key } }
            ?.takeIf { it is EditorBlock.Text || it is EditorBlock.Checklist }
        if (focused != null && !isBlockVisible(focused)) {
            // Lo que se inserte ahí tiene que verse: se abren los desplegables
            // cerrados que lo contienen.
            var parent = focused.parentKey
            while (parent != null) {
                val index = blocks.indexOfFirst { it.key == parent }
                val toggle = blocks.getOrNull(index) as? EditorBlock.Toggle ?: break
                if (!toggle.isToggled) blocks[index] = toggle.copy(isToggled = true)
                parent = toggle.parentKey
            }
            revision++
        }
        return when (val block = focused ?: blocks.lastOrNull { it is EditorBlock.Text && isBlockVisible(it) }) {
            is EditorBlock.Text -> InsertPoint(block.key, block.value.selection)
            is EditorBlock.Checklist -> InsertPoint(block.key, TextRange.Zero)
            else -> InsertPoint(null, TextRange.Zero)
        }
    }

    fun insertAttachments(point: InsertPoint?, created: List<Attachment>) {
        val key = BlockCommands.insertAttachments(
            blocks,
            tree,
            attachmentsById,
            point,
            created
        )
        if (key != null) focusRequestKey = key
        bodyChanged(typing = false)
        saveNow()
    }

    /**
     * Aplica [apply] al texto o el ítem con el foco y lleva el foco adonde queda; después, si
     * [record], es un paso del deshacer. Sin foco en el cuerpo (por ejemplo, con el cursor en el
     * título) no hace nada: antes cambiaba el último texto.
     */
    fun applyToFocus(record: () -> Boolean = { true }, apply: (EditorFocus) -> EditorFocus?) {
        val key = focusedKey ?: return
        val itemId = focusedItemId
        val selection = when (val block = blocks.firstOrNull { it.key == key }) {
            is EditorBlock.Text -> block.value.selection
            is EditorBlock.Checklist -> block.items.firstOrNull { it.id == itemId }?.value?.selection ?: return
            else -> return
        }
        val focus = apply(EditorFocus(key, itemId, selection)) ?: return
        ensureTextSlots(blocks)
        val focusItem = focus.itemId
        if (focusItem == null && itemId == null && focus.blockKey == key) {
            // Sigue en el mismo texto: como antes, sin volver a subir el teclado minimizado.
            focusRequestKey = key
        } else if (focusItem == null) {
            focusTextAt(focus.blockKey, focus.selection)
        } else if (focusItem == itemId) {
            // El mismo ítem (Negrita, Cursiva…): el valor ya tiene la selección.
            itemFocusRequest = focusItem to focus.selection.start
        } else {
            requestItemFocus(focus.blockKey, focusItem, focus.selection.start)
        }
        if (record()) bodyChanged(typing = false)
    }

    /** Un botón de la barra sobre el texto o el ítem con el foco. */
    fun transformFocused(action: BarAction, transform: (RichText) -> RichText) {
        applyToFocus { focus -> BlockCommands.formatFocused(blocks, focus, action, transform) }
    }

    /**
     * Un atajo que no es un botón de la barra sobre el texto o el ítem con el foco
     * ([BlockCommands.transformText]). Si no cambia el cuerpo (un formato que queda pendiente,
     * mover un ítem en el borde) no es un paso del deshacer ni se guarda.
     */
    fun transformFocusedText(transform: (RichText) -> RichText) {
        var changed = false
        val tracked = { value: RichText -> transform(value).also { if (contentChanged(value, it)) changed = true } }
        applyToFocus(record = { changed }) { focus -> BlockCommands.transformText(blocks, focus, tracked) }
    }

    /**
     * El foco después de que el texto [key] se partió al cambiar ([BlockCommands.textChanged]):
     * al ítem donde cayó el cursor, o al tramo de texto que lo tiene.
     */
    fun focusAfterTextChange(key: String, focus: EditorFocus) {
        val itemId = focus.itemId
        when {
            itemId != null -> requestItemFocus(focus.blockKey, itemId, focus.selection.start)
            // Sigue en el mismo texto, que ya tiene la selección: sin volver a subir el teclado.
            focus.blockKey == key -> focusRequestKey = key
            else -> focusTextAt(focus.blockKey, focus.selection)
        }
    }

    /** Después de deshacer o rehacer, el cursor vuelve a [focus] (un texto, un ítem o un título de desplegable). */
    fun showUndoFocus(focus: UndoFocus) {
        val key = focus.blockKey ?: return
        val itemId = focus.itemId
        when (blocks.firstOrNull { it.key == key }) {
            is EditorBlock.Text -> focusTextAt(key, focus.selection)
            is EditorBlock.Checklist -> if (itemId != null) requestItemFocus(key, itemId, focus.selection.start)
            is EditorBlock.Toggle -> {
                focusRequestKey = key
                focusedKey = key
            }
            else -> Unit
        }
    }

    /** Deshace o rehace toda la nota: no es un paso nuevo, pero se guarda como cualquier cambio. */
    fun undoBody(redo: Boolean) {
        if (!editorOpen.get()) return
        val current = undoFocus()
        val focus = (if (redo) editorUndo.redo(current) else editorUndo.undo(current)) ?: return
        showUndoFocus(focus)
        revision++
    }

    /** El «Deshacer» del aviso al quitar un adjunto o un enlace: deshace el último cambio de la nota. */
    val undoIfOpen: () -> Unit = { undoBody(redo = false) }

    /**
     * Los atajos que el campo de texto le pasa a la nota. Los que son un botón de la
     * barra hacen lo mismo que el botón; los demás cambian el texto o el ítem con el foco. En un
     * ítem, Ctrl+Shift+9 y Ctrl+Alt+↑/↓ los atiende el ítem ([itemEditorShortcut]).
     */
    fun editorShortcut(shortcut: EditorShortcut): Boolean {
        when (shortcut) {
            EditorShortcut.Undo -> undoBody(redo = false)
            EditorShortcut.Redo -> undoBody(redo = true)
            is EditorShortcut.Inline -> transformFocusedText { RichCommands.toggleInline(it, shortcut.format) }
            is EditorShortcut.Heading -> transformFocused(BarAction.Heading) { RichCommands.setHeading(it, shortcut.level) }
            is EditorShortcut.Bar -> {
                // Ctrl+Shift+Alt+C es el bloque de código; en un ítem, lo que hace ahí el botón.
                val code = shortcut.action == BarAction.Code && focusedItemId == null
                val transform: (RichText) -> RichText = if (code) RichCommands::codeBlock else barTransform(shortcut.action)
                transformFocused(shortcut.action, transform)
            }
            EditorShortcut.ToggleChecked -> transformFocusedText { RichCommands.toggleCheck(it, it.selection.start) }
            is EditorShortcut.Indent -> transformFocusedText { RichCommands.indent(it, shortcut.outdent) }
            is EditorShortcut.TextColor -> transformFocusedText { RichCommands.setTextColor(it, shortcut.color) }
            is EditorShortcut.Highlight -> transformFocusedText { RichCommands.setHighlight(it, shortcut.color) }
            EditorShortcut.RemoveFormat -> transformFocusedText { RichCommands.removeFormat(it) }
            is EditorShortcut.MoveListItem -> transformFocusedText { RichCommands.moveListItem(it, shortcut.up) }
        }
        return true
    }

    fun focusLastText() {
        val lastVisible = tree.visible().lastOrNull()

        if (lastVisible is EditorBlock.Text && lastVisible.parentKey == null) {
            val index = blocks.indexOfFirst { it.key == lastVisible.key }
            val value = lastVisible.value.withEmptyLineAtEnd()
            if (index >= 0) blocks[index] = lastVisible.copy(value = value)
            focusRequestKey = lastVisible.key
            focusedKey = lastVisible.key
            if (value.plain != lastVisible.value.plain) bodyChanged(typing = false)
        } else {
            val key = newKey()
            blocks.add(EditorBlock.Text(key, RichText.Empty))
            focusRequestKey = key
            focusedKey = key
            bodyChanged(typing = false)
        }
        keyboardRequest++
    }

    /** Desde el título, al principio del primer texto del cuerpo. */
    fun focusFirstText() {
        val index = blocks.indexOfFirst { it is EditorBlock.Text && isBlockVisible(it) }
        val firstText = blocks.getOrNull(index) as? EditorBlock.Text ?: return
        blocks[index] = firstText.copy(value = firstText.value.withSelection(TextRange(0)))
        focusRequestKey = firstText.key
        focusedKey = firstText.key
    }

    // Flecha arriba o abajo en el borde de un bloque: pasa al texto o al título de
    // desplegable siguiente, en el renglón de pantalla más cercano y a la misma
    // altura horizontal ([x], en píxeles del texto).
    fun focusSiblingText(fromKey: String, direction: Int, x: Float) {
        val visible = tree.visible()
        val fromIndex = visible.indexOfFirst { it.key == fromKey }
        if (fromIndex < 0) return

        var i = fromIndex + direction
        while (i in visible.indices) {
            val candidate = visible[i]
            if (candidate is EditorBlock.Toggle) {
                focusRequestKey = candidate.key
                focusedKey = candidate.key
                return
            }
            if (candidate is EditorBlock.Text) {
                val index = blocks.indexOfFirst { it.key == candidate.key }
                if (index < 0) return
                val cursor = cursorFromArrow(candidate.key, candidate.value.plain.length, direction, x)
                blocks[index] = candidate.copy(value = candidate.value.withSelection(TextRange(cursor)))
                focusedItemId = null
                focusRequestKey = candidate.key
                focusedKey = candidate.key
                return
            }
            // A una checklist se entra por su primera fila bajando y por la última subiendo.
            if (candidate is EditorBlock.Checklist) {
                val itemId = checklistEdgeItem(candidate.items, checklistClosed, direction)
                if (itemId != null) {
                    focusItemFromArrow(candidate, itemId, direction, x)
                    return
                }
            }
            i += direction
        }
        // La flecha izquierda al principio de la nota no sale al título.
        if (direction < 0 && x != ARROW_EDGE_END) runCatching { titleFocus.requestFocus() }
    }

    fun insertToggle() {
        val key = BlockCommands.insertToggle(blocks, tree, currentInsertPoint())
        if (key != null) {
            focusRequestKey = key
            focusedKey = key
        }
        bodyChanged(typing = false)
        saveNow()
    }

    fun removeToggle(block: EditorBlock.Toggle) {
        // El desplegable se va con todo su contenido. Los adjuntos y enlaces de adentro siguen
        // registrados hasta cerrar la nota, así deshacer los devuelve.
        BlockCommands.removeToggle(blocks, block)
        bodyChanged(typing = false)
    }

    fun openLinkDialog() {
        bookmarkInsertPoint = currentInsertPoint()
        linkUrl = ""
        showLinkDialog = true
    }

    fun insertBookmark(point: InsertPoint?, bookmark: Bookmark) {
        val key = BlockCommands.insertBookmark(
            blocks,
            tree,
            bookmarksById,
            point,
            bookmark
        )
        if (key != null) focusRequestKey = key
        bodyChanged(typing = false)
        saveNow()
    }

    fun insertBookmarkFromDialog() {
        val url = linkUrl.trim()
        if (url.isBlank()) return
        val point = bookmarkInsertPoint
        showLinkDialog = false
        scope.launch {
            // Ocupada hasta que la tarjeta tiene su bloque: si no, ir al fondo la apartaría.
            val bookmark = vm.whileBusy(note.id) {
                vm.addBookmark(note.id, url)?.also { insertBookmark(point, it) }
            }
            if (bookmark != null) vm.flush()
        }
    }

    fun removeBookmark(block: EditorBlock.Bookmark) {
        vm.bookmarkRemoved(undo = undoIfOpen)
        BlockCommands.removeBookmark(blocks, block)
        bodyChanged(typing = false)
    }

    fun insertNoteLink(point: InsertPoint?, noteId: String) {
        val key = BlockCommands.insertNoteLink(blocks, tree, point, noteId)
        if (key != null) focusRequestKey = key
        bodyChanged(typing = false)
        saveNow()
    }

    fun removeNoteLink(block: EditorBlock.NoteLink) {
        BlockCommands.removeNoteLink(blocks, block)
        bodyChanged(typing = false)
    }

    // Guarda antes de salir: al volver, esta nota se reconstruye desde lo guardado.
    fun openLinkedNote(noteId: String) {
        persist()
        vm.flush()
        vm.saveEditorScroll(note.id, blockScrollState.value)
        releaseRichField(view)
        onOpenNote(noteId)
    }

    // Crea la nota hija en el cursor y la abre para escribirla. Queda en el
    // mismo cuaderno que esta.
    fun createChildNote() {
        // Una nota en la papelera no esconde a sus hijas: una nota nueva creada
        // acá aparecería suelta en la lista.
        if (note.trashed) {
            scope.launch { snackbar.showSnackbar("Restaurá la nota para agregarle notas adentro") }
            return
        }
        // Antes de crear la nota de adentro, esta queda guardada con lo último escrito.
        persist()
        // Un doble toque crearía dos notas: el segundo cae en la nota recién abierta
        // y crea otra adentro. Se ignora todo pedido muy seguido, venga de donde venga.
        val created = vm.createLinkedNoteOnce(note.notebook) ?: return
        insertNoteLink(currentInsertPoint(), created.id)
        openLinkedNote(created.id)
    }

    fun openBookmark(url: String) {
        runCatching {
            // Los enlaces viejos pueden no tener "https://" ("www.google.com").
            val target = com.noted.app.data.BookmarkFetcher.normalize(url)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }

    // Sin teclado físico, mostrar el táctil al enfocar un campo o tocar la
    // sangría. No depende de imeVisible, así el usuario puede minimizarlo.
    LaunchedEffect(hasPhysicalKeyboard, focusedKey, titleFocused, keyboardRequest) {
        val focused = focusedKey != null || titleFocused
        if (!hasPhysicalKeyboard && focused) {
            delay(100)
            imeController?.show(WindowInsetsCompat.Type.ime())
            keyboard?.show()
        }
    }

    fun removeMedia(block: EditorBlock.Media) {
        // El registro y el archivo siguen hasta cerrar la nota: deshacer devuelve el adjunto.
        vm.attachmentRemoved(undo = undoIfOpen)
        BlockCommands.removeMedia(blocks, block)
        bodyChanged(typing = false)
    }

    /** Copia [uris] como adjuntos y los pone en [point]; al terminar llama a [done]. */
    fun attachAt(point: InsertPoint?, uris: List<Uri>, done: () -> Unit = {}) {
        scope.launch {
            // Todo el lote ocupado hasta insertar los bloques: entre una copia y la siguiente,
            // ir al fondo apartaría los ya copiados.
            val created = try {
                vm.whileBusy(note.id) {
                    vm.attachFiles(note.id, uris).also { if (it.isNotEmpty()) insertAttachments(point, it) }
                }
            } finally {
                done()
            }
            if (created.isNotEmpty()) vm.flush()
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attachAt(insertPoint, uris)
    }

    // Del teclado o del portapapeles (RichEditText.onMedia): en el lugar del cursor.
    val receiveMedia = remember(note.id) {
        { uris: List<Uri>, done: () -> Unit -> attachAt(currentInsertPoint(), uris, done) }
    }

    fun launchPicker() {
        insertPoint = currentInsertPoint()
        attachmentPicker.launch(arrayOf("*/*"))
    }

    BackHandler { leave() }

    LaunchedEffect(note.id) {
        if (note.title.isBlank() && NoteHtml.isBlank(note.body) && note.attachments.isEmpty()) {
            delay(140)
            titleFocus.requestFocus()
        }
    }

    // Se guarda a los 3 s del último cambio. La revisión se mira con snapshotFlow y no como
    // clave del efecto: leerla acá recompondría toda la pantalla en cada tecla.
    val persistLater by rememberUpdatedState { persist() }
    LaunchedEffect(title, note.id) {
        snapshotFlow { revision }.collectLatest {
            delay(3000)
            persistLater()
        }
    }

    DisposableEffect(note.id) {
        onDispose {
            persist()
            releaseUnused()
        }
    }

    // Al pasar a segundo plano (Inicio, cambiar de app, cerrar desde recientes) se
    // guarda ya, sin esperar el retardo del guardado: si Android cierra la app
    // enseguida, no se pierde lo último escrito.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, note.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                persist()
                // Al ir al fondo la app puede morir sin onDispose: lo quitado se aparta ya.
                if (event == Lifecycle.Event.ON_STOP) releaseUnused()
                vm.flush()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Los cuadernos existen aunque estén vacíos: los guardados más los que tienen notas.
    val notebookOrder by vm.notebookOrder.collectAsStateWithLifecycle()
    val trashedNotebooks by vm.notebooksTrashed.collectAsStateWithLifecycle()
    val notebooks = remember(allNotes, notebookIcons, notebookOrder, trashedNotebooks) {
        (notebookOrder + notebookIcons.keys + allNotes.filterNot { it.trashed }.mapNotNull { it.notebook?.takeIf { name -> name.isNotBlank() } })
            .distinct()
            .filterNot { it in trashedNotebooks }
    }

    // Sobre el texto sin el carácter del separador.
    fun countedText(block: EditorBlock.Text): String = block.value.plain.replace(DIVIDER_CHAR.toString(), "")
    // Cada ítem es un renglón: sus palabras cuentan, y sus caracteres con un `\n` entre
    // ítems, como cuentan los renglones de un texto.
    fun checklistText(block: EditorBlock.Checklist): String = block.items.joinToString("\n") { it.value.plain }

    var columnTop by remember { mutableFloatStateOf(0f) }
    var columnBottom by remember { mutableFloatStateOf(0f) }
    /** Borde inferior de la cabecera de la nota, en la ventana (sube al desplazar). */
    var headerBottom by remember { mutableFloatStateOf(0f) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragMoved by remember { mutableStateOf(false) }
    var pendingDrop by remember { mutableStateOf<PendingDrop?>(null) }
    val dragActive = remember { booleanArrayOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var dragStartTop by remember { mutableFloatStateOf(0f) }
    var dragStartGrab by remember { mutableFloatStateOf(0f) }
    var dragStartHeight by remember { mutableFloatStateOf(0f) }
    var overlayHeight by remember { mutableIntStateOf(0) }
    var dragDirection by remember { mutableFloatStateOf(0f) }
    val blockBounds = remember { mutableStateMapOf<String, Rect>() }
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val autoScrollThreshold = with(LocalDensity.current) { 40.dp.toPx() }
    // Velocidad del auto-scroll, en px por segundo: arranca suave al entrar en
    // la zona del borde y llega al máximo cuando la copia lo pasa.
    val autoScrollMinSpeed = with(LocalDensity.current) { 250.dp.toPx() }
    val autoScrollMaxSpeed = with(LocalDensity.current) { 1600.dp.toPx() }

    // Borde superior de la copia flotante, en coordenadas de ventana. El dedo
    // queda en la misma proporción de la copia que en el bloque agarrado.
    fun dragPreviewTop(): Float {
        val grabFraction = if (dragStartHeight > 0f) {
            (dragStartGrab / dragStartHeight).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        return fingerY - overlayHeight * grabFraction
    }

    // Auto-scroll según lo que se ve: la copia toca el borde hacia el que se
    // la viene moviendo. Exigir esa dirección evita que una copia que ya nace
    // pegada a un borde arrastre la nota para el lado contrario. Devuelve la
    // velocidad con signo (px/s), mayor cuanto más se mete la copia en el borde.
    fun dragAutoScrollSpeed(): Float {
        val top = dragPreviewTop()
        val depth = when {
            dragDirection < 0f -> -(columnTop + edgePx - top)
            dragDirection > 0f -> top + overlayHeight - (columnBottom - edgePx)
            else -> 0f
        }
        if (depth == 0f || (depth < 0f) != (dragDirection < 0f)) return 0f
        val intensity = (abs(depth) / edgePx).coerceIn(0f, 1f)
        val speed = autoScrollMinSpeed +
            (autoScrollMaxSpeed - autoScrollMinSpeed) * intensity * intensity
        return if (depth < 0f) -speed else speed
    }

    fun updateBlockDragTarget() {
        // draggingKey sigue puesto un instante después de soltar; si el auto-scroll
        // corre una vuelta más en ese instante, no tiene que volver a dibujar la línea.
        if (!dragActive[0]) return
        val key = draggingKey ?: return
        pendingDrop = dragDrop.update(
            fromKey = key,
            // El destino lo marca el borde superior de la copia, no el dedo:
            // ahí es donde va a quedar el adjunto al soltarlo.
            fingerY = dragPreviewTop(),
            blockBounds = blockBounds,
            textGeometry = textGeometry
        )
    }

    // Después de soltar no se enfoca ningún texto: enfocar abría el teclado y
    // movía la vista hasta ese texto, dejando el bloque recién movido fuera de pantalla.
    fun applyTextDrop(mediaKey: String, pending: PendingDrop.Text) {
        dragDrop.dropText(mediaKey, pending)
        ensureTextSlots(blocks)
        bodyChanged(typing = false)
        saveNow()
    }

    fun applyBlockDrop(
        mediaKey: String,
        targetIndex: Int,
        targetParentKey: String?
    ) {
        dragDrop.dropBlock(mediaKey, targetIndex, targetParentKey)
        ensureTextSlots(blocks)
        bodyChanged(typing = false)
        saveNow()
    }

    fun finishBlockDrag(commit: Boolean) {
        dragActive[0] = false
        val key = draggingKey
        val pending = pendingDrop
        if (commit && key != null && pending != null) {
            when (pending) {
                is PendingDrop.Text -> applyTextDrop(key, pending)
                is PendingDrop.Block -> applyBlockDrop(
                    key,
                    pending.targetIndex,
                    pending.parentKey
                )
            }
        }
        pendingDrop = null
        dragOffsetY = 0f
        dragMoved = false
        if (key == null) return
        // Mantener el estado un instante para que el click que sigue al long
        // press no abra el visor del adjunto.
        scope.launch {
            delay(150)
            if (draggingKey == key) draggingKey = null
        }
    }

    LaunchedEffect(draggingKey, dragMoved) {
        if (draggingKey == null || !dragMoved) return@LaunchedEffect
        // Un paso por cuadro, proporcional al tiempo transcurrido, para que el
        // desplazamiento sea continuo y no a saltos.
        var lastFrame = 0L
        var edgeSince = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            val speed = dragAutoScrollSpeed()
            if (speed == 0f) {
                edgeSince = 0L
                continue
            }
            if (edgeSince == 0L) edgeSince = now
            // Esperar un momento antes de auto-scrollear: un movimiento corto
            // cerca del borde no debe convertir un renglón en un salto.
            if (now - edgeSince < 200_000_000L) continue
            if (blockScrollState.scrollBy(speed * dt.coerceAtMost(0.05f)) != 0f) {
                updateBlockDragTarget()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) { AppSnackbar(it, notebookIcons, vm::notebookIconFile) } },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                title = {
                    Text(
                        text = "Nota",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                actions = {
                    IconButton(onClick = { launchPicker() }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Adjuntar archivo")
                    }
                    IconButton(onClick = { vm.togglePin(note.id) }) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = if (note.pinned) "Desfijar" else "Fijar",
                            tint = if (note.pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Más")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (note.pinned) "Desfijar" else "Fijar arriba") },
                                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    vm.togglePin(note.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Ícono") },
                                leadingIcon = { Icon(Icons.Outlined.EmojiEmotions, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    openIconPicker()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Portada") },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    releaseRichField(view)
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                    showCoverPicker = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Mover nota") },
                                leadingIcon = { Icon(Icons.Filled.AutoStories, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    showNotebook = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Compartir como texto") },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    persist()
                                    val text = HtmlBlocks.shareText(
                                        blocks,
                                        attachmentsById.values.toList(),
                                        bookmarksById.values.toList(),
                                        noteTitles()
                                    )
                                    shareText(
                                        context,
                                        "# ${note.displayTitle}\n\n$text",
                                        note.displayTitle
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Información") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    showInfo = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar nota") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    confirmDelete = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                // Los botones apretados salen del texto con el foco; sin uno (por ejemplo,
                // con el cursor en el título de la nota) no se marca ninguno.
                // En un ítem, sus formatos en línea y Checklist.
                val activeNow = when (val focused = focusedKey?.let { key -> blocks.firstOrNull { it.key == key } }) {
                    is EditorBlock.Text -> RichCommands.activeFormats(focused.value)
                    is EditorBlock.Checklist -> focused.items.firstOrNull { it.id == focusedItemId }
                        ?.let { RichCommands.activeFormats(it.value) + BarFormat.Check }
                        ?: emptySet()
                    else -> emptySet()
                }
                // El mismo conjunto mientras no cambie: así la barra no se recompone en cada tecla.
                val active = remember(activeNow) { activeNow }
                val toolbarLayout by vm.toolbarLayout.collectAsStateWithLifecycle()
                EditorToolbar(
                    items = toolbarLayout.visible,
                    active = active,
                    onFormat = { action, transform -> transformFocused(action, transform) },
                    onUndo = { undoBody(redo = false) },
                    onRedo = { undoBody(redo = true) },
                    onAttach = { launchPicker() },
                    onLink = { openLinkDialog() },
                    onToggle = { insertToggle() },
                    onNote = { createChildNote() },
                    onArrange = { arrangingToolbar = true }
                )
                if (arrangingToolbar) {
                    ArrangeToolbarDialog(
                        layout = toolbarLayout,
                        onChange = vm::setToolbarLayout,
                        onDismiss = { arrangingToolbar = false }
                    )
                }
            }
        }
    ) { padding ->
    // Las imágenes que manda el teclado o que se pegan van a esta nota, donde está el cursor.
    CompositionLocalProvider(LocalReceiveMedia provides receiveMedia) {
        // En tablet la nota ocupa una columna centrada: arrastrar en los márgenes
        // también la desplaza (mismo estado que el scroll de la columna).
        PageColumn(
            Modifier
                .padding(padding)
                // Un toque en el margen que nadie usa frena la inercia, como en la columna.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        if (!down.isConsumed && blockScrollState.isScrollInProgress) {
                            scope.launch { blockScrollState.stopScroll() }
                        }
                    }
                }
                .scrollable(marginScrollState, Orientation.Vertical, reverseDirection = true)
        ) {
            Box(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(Unit) {
                            // Tocar un lugar vacío lleva al final de la nota, salvo en la
                            // cabecera y en el espacio entre la cabecera y el primer bloque:
                            // ahí no hace nada (pedido del usuario, 9/11/2026).
                            detectTapGestures { offset ->
                                if (columnTop + offset.y > headerBottom + 10.dp.toPx()) focusLastText()
                            }
                        }
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
                                val bounds = coords.boundsInWindow()
                                columnTop = bounds.top
                                columnBottom = bounds.bottom
                            }
                            .alpha(if (restoringScroll) 0f else 1f)
                            .verticalScroll(blockScrollState)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // La cabecera (portada, ícono, título, cuaderno y la línea): dónde termina
                        // sirve para que un toque en sus huecos no lleve al final de la nota.
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    headerBottom = coords.positionInWindow().y + coords.size.height
                                }
                        ) {
                        val cover = note.cover
                        if (cover != null) {
                            CoverHeader(
                                note = note,
                                cover = cover,
                                vm = vm,
                                onCoverClick = {
                                    vm.coverFile(note)?.takeIf { it.exists() }?.let { viewingPhoto = it to "Portada" }
                                },
                                onIconClick = {
                                    vm.iconFile(note)?.takeIf { it.exists() }?.let { viewingPhoto = it to "Ícono" }
                                }
                            )
                        } else run {
                            val headEmoji = emojiOf(note.icon)
                            val headFile = if (headEmoji == null) vm.iconFile(note) else null
                            val hasBuiltin = headEmoji == null && headFile == null &&
                                note.icon?.startsWith(NotesRepository.ICON_BUILTIN) == true
                            val screenBg = MaterialTheme.colorScheme.background
                            val darkScreen = screenBg.luminance() < 0.5f
                            val boxBg = if (darkScreen) Color(0xFF1F1F1F) else Color(0xFFF2F2F2)
                            // Si el archivo falta o no se puede dibujar, no queda un hueco.
                            var fileFailed by remember(headFile) {
                                mutableStateOf(headFile?.exists() == false)
                            }
                            if (headEmoji != null || hasBuiltin) {
                                // Caja visible de 80 dp (88 con el borde de 4 dp del color del fondo).
                                Box(
                                    Modifier
                                        .padding(start = 4.dp, top = 4.dp, bottom = 5.dp)
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(boxBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (headEmoji != null) {
                                        Text(
                                            headEmoji,
                                            fontSize = with(density) { 40.dp.toSp() }
                                        )
                                    } else {
                                        NoteIcon(note = note, iconFile = null, size = 40.dp)
                                    }
                                }
                            } else if (headFile != null && !fileFailed) {
                                Box(
                                    Modifier
                                        .padding(start = 4.dp, top = 4.dp, bottom = 5.dp)
                                        .size(88.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(boxBg)
                                ) {
                                    AsyncImage(
                                        model = headFile,
                                        imageLoader = remember(context) { NoteIconLoader.get(context) },
                                        contentDescription = "Ícono de la nota",
                                        contentScale = ContentScale.Crop,
                                        onError = { fileFailed = true },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable(interactionSource = null, indication = null) {
                                                viewingPhoto = headFile to "Ícono"
                                            }
                                    )
                                }
                            }
                        }
                        BasicTextField(
                            value = title,
                            // Enter en el título no escribe un salto: pasa al cuerpo,
                            // como en cualquier editor de notas.
                            onValueChange = { value ->
                                if (value.contains('\n')) {
                                    title = value.replace("\n", "")
                                    focusFirstText()
                                } else {
                                    title = value
                                }
                            },
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(titleFocus)
                                .onFocusChanged { titleFocused = it.isFocused }
                                .onPreviewKeyEvent { event ->
                                    // El título queda afuera del deshacer: Ctrl+Z, Ctrl+Y y
                                    // Ctrl+Shift+Z se consumen sin hacer nada, así el deshacer propio
                                    // del campo tampoco lo cambia.
                                    if (editorShortcutOf(event) != null) {
                                        true
                                    } else if (event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.DirectionDown ||
                                            event.key == Key.Enter ||
                                            event.key == Key.NumPadEnter)
                                    ) {
                                        focusFirstText()
                                        true
                                    } else {
                                        false
                                    }
                                },
                            decorationBox = { inner ->
                                Box {
                                    if (title.isEmpty()) {
                                        Text(
                                            "Título",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )

                        note.notebook?.let { notebook ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val icon = notebookIcons[notebook]
                                NotebookIcon(
                                    icon,
                                    vm.notebookIconFile(icon),
                                    size = 14.dp,
                                    tint = MaterialTheme.colorScheme.primary,
                                    name = notebook
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = notebook,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        }

                        // Mantener apretado un bloque (adjunto, enlace o nota) y
                        // arrastrarlo lo mueve a otro lugar de la nota.
                        fun blockDragModifier(key: String): Modifier =
                            Modifier.pointerInput(key) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        // Arrastrar deja de editar texto: sin foco no se
                                        // abre el teclado ni se persigue el cursor al soltar.
                                        focusManager.clearFocus()
                                        dragActive[0] = true
                                        dragStartTop = blockBounds[key]?.top ?: 0f
                                        dragStartHeight = blockBounds[key]?.height ?: 0f
                                        dragStartGrab = offset.y
                                        dragOffsetY = 0f
                                        dragDirection = 0f
                                        fingerY = dragStartTop + offset.y
                                        dragMoved = false
                                        pendingDrop = null
                                        draggingKey = key
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        if (dragAmount.y != 0f) {
                                            dragDirection = dragAmount.y
                                        }
                                        fingerY = dragStartTop + dragStartGrab + dragOffsetY
                                        if (abs(dragOffsetY) >= autoScrollThreshold) {
                                            dragMoved = true
                                        }
                                        updateBlockDragTarget()
                                    },
                                    onDragEnd = { finishBlockDrag(commit = true) },
                                    onDragCancel = { finishBlockDrag(commit = false) }
                                )
                            }

                        @Composable
                        fun RenderBlock(block: EditorBlock) {
                            val isDragging = draggingKey == block.key
                            val canDrag = when (block) {
                                is EditorBlock.Media -> attachmentsById[block.attachmentId] != null
                                is EditorBlock.Bookmark -> bookmarksById[block.bookmarkId] != null
                                is EditorBlock.NoteLink -> true
                                else -> false
                            }
                            val dragModifier =
                                if (canDrag) blockDragModifier(block.key) else Modifier
                            Box(
                                Modifier
                                    .onGloballyPositioned { coords ->
                                        // Bounds completos, sin recortar al área visible:
                                        // el punto de agarre se mide desde el borde real
                                        // del bloque aunque esté cortado por el scroll.
                                        blockBounds[block.key] = Rect(
                                            coords.positionInWindow(),
                                            coords.size.toSize()
                                        )
                                    }
                                    .alpha(if (isDragging) 0.2f else 1f)
                            ) {
                                when (block) {
                                    is EditorBlock.Text -> TextBlockEditor(
                                        block = block,
                                        focusRequested = focusRequestKey == block.key,
                                        onFocusRequestConsumed = { focusRequestKey = null },
                                        onFocus = {
                                            focusedKey = block.key
                                            lastFocusedKey[0] = block.key
                                        },
                                        onFocusLost = {
                                            if (focusedKey == block.key) focusedKey = null
                                        },
                                        onFocusPrevious = { x ->
                                            focusSiblingText(block.key, -1, x)
                                        },
                                        onFocusNext = { x ->
                                            focusSiblingText(block.key, 1, x)
                                        },
                                        // Sincrónico: si el campo recibe en update un valor
                                        // viejo, reescribe el texto y corta la composición.
                                        onValueChange = { value ->
                                            val before = (blocks.firstOrNull { it.key == block.key } as? EditorBlock.Text)?.value
                                            if (BlockCommands.updateText(blocks, block.key, value)) {
                                                BlockCommands.textChanged(blocks, block.key)
                                                    ?.let { focusAfterTextChange(block.key, it) }
                                                // Solo mover el cursor no es un paso del deshacer; lo
                                                // que convierte un atajo de Markdown es un paso aparte.
                                                if (before != null && contentChanged(before, value)) bodyChanged(typing = !value.autoFormatted)
                                                else revision++
                                            }
                                        },
                                        onShortcut = ::editorShortcut,
                                        onGeometry = { geometry -> textGeometry[block.key] = geometry },
                                        // Flecha arriba al principio de una cita o un
                                        // código agrega un renglón solo si no hay nada arriba.
                                        isFirstInNote = { tree.visible().firstOrNull()?.key == block.key }
                                    )

                                    is EditorBlock.Media -> {
                                        val attachment = attachmentsById[block.attachmentId]
                                        if (attachment != null) {
                                            MediaInlineCard(
                                                attachment = attachment,
                                                file = vm.attachmentFile(attachment),
                                                modifier = dragModifier,
                                                onOpen = {
                                                    if (draggingKey == null) {
                                                        // Al volver del visor, retomar
                                                        // la nota en el mismo lugar.
                                                        vm.saveEditorScroll(
                                                            note.id,
                                                            blockScrollState.value
                                                        )
                                                        onOpenAttachment(
                                                            attachment,
                                                            mediaContextIds(block)
                                                        )
                                                    }
                                                },
                                                onRemove = { removeMedia(block) }
                                            )
                                        } else {
                                            MissingAttachmentCard()
                                        }
                                    }

                                    is EditorBlock.Bookmark -> {
                                        val bookmark = bookmarksById[block.bookmarkId]
                                        if (bookmark != null) {
                                            BookmarkCard(
                                                bookmark = bookmark,
                                                imageFile = vm.bookmarkImageFile(bookmark),
                                                faviconFile = vm.bookmarkFaviconFile(bookmark),
                                                modifier = dragModifier,
                                                onOpen = {
                                                    if (draggingKey == null) openBookmark(bookmark.url)
                                                },
                                                onRemove = { removeBookmark(block) }
                                            )
                                        } else {
                                            MissingAttachmentCard()
                                        }
                                    }

                                    is EditorBlock.NoteLink -> {
                                        val linked = allNotes.firstOrNull { it.id == block.noteId }
                                        NoteLinkCard(
                                            note = linked,
                                            iconFile = linked?.let { vm.iconFile(it) },
                                            modifier = dragModifier,
                                            onOpen = {
                                                if (draggingKey == null) openLinkedNote(block.noteId)
                                            },
                                            onRemove = { removeNoteLink(block) }
                                        )
                                    }

                                    is EditorBlock.Toggle -> {
                                        val children = tree.childrenOf(block.key)
                                        ToggleBlock(
                                            toggle = block,
                                            depth = tree.depth(block.parentKey) + 1,
                                            children = children,
                                            onToggleExpanded = {
                                                val idx = blocks.indexOfFirst { it.key == block.key }
                                                if (idx >= 0) {
                                                    blocks[idx] = block.copy(
                                                        isToggled = !block.isToggled
                                                    )
                                                    revision++
                                                }
                                            },
                                            onTitleChange = { newTitle ->
                                                val idx = blocks.indexOfFirst { it.key == block.key }
                                                if (idx >= 0) {
                                                    blocks[idx] = block.copy(title = newTitle)
                                                    bodyChanged(typing = true)
                                                }
                                            },
                                            onRemove = { removeToggle(block) },
                                            onShortcut = ::editorShortcut,
                                            onTitleUp = { focusSiblingText(block.key, -1, 0f) },
                                            onTitleDown = { focusSiblingText(block.key, 1, 0f) },
                                            focusRequested = focusRequestKey == block.key,
                                            onFocusRequestConsumed = { focusRequestKey = null },
                                            onTitleFocusChanged = { focused ->
                                                if (focused) {
                                                    focusedKey = block.key
                                                } else if (focusedKey == block.key) {
                                                    focusedKey = null
                                                }
                                            },
                                            childContent = { child -> RenderBlock(child) }
                                        )
                                    }

                                    is EditorBlock.Checklist -> ChecklistBlock(
                                        block = block,
                                        collapsed = checklistClosed,
                                        focusedItemId = focusedItemId,
                                        focusRequest = itemFocusRequest,
                                        onFocusRequestConsumed = { itemFocusRequest = null },
                                        onItemsChange = { edit, source, change -> updateChecklist(block.key, edit, source, change) },
                                        onShortcut = ::editorShortcut,
                                        // Mientras se arrastra una fila, cerca del borde la nota se desplaza sola.
                                        viewport = { columnTop to columnBottom },
                                        scrollBy = { delta -> blockScrollState.scrollBy(delta) },
                                        onToggle = { itemId -> toggleChecklistItem(block.key, itemId) },
                                        onCollapsedChange = { closed ->
                                            checklistClosed = closed
                                            vm.setChecklistClosed(note.id, closed)
                                            // Plegar con el foco en la sección lo saca de ahí.
                                            val focused = focusedItemId
                                            if (closed && focused != null) {
                                                when (val target = focusAfterCollapse(block.items, focused)) {
                                                    CollapseFocus.Stay -> Unit
                                                    is CollapseFocus.Item -> requestItemFocus(block.key, target.itemId, target.cursor)
                                                    CollapseFocus.TextAbove -> textAboveEnd(block.key).let { above ->
                                                        focusTextAt(above?.first, TextRange(above?.second ?: 0))
                                                    }
                                                }
                                            }
                                        },
                                        onItemFocus = { itemId, focused ->
                                            if (focused) {
                                                focusedItemId = itemId
                                                focusedKey = block.key
                                                lastFocusedKey[0] = block.key
                                            } else if (focusedItemId == itemId) {
                                                focusedItemId = null
                                                if (focusedKey == block.key) focusedKey = null
                                            }
                                        },
                                        // Flechas: a la fila de arriba o de abajo; desde la
                                        // primera o la última, al bloque vecino.
                                        onArrow = { itemId, direction, x ->
                                            val next = checklistNeighbor(block.items, checklistClosed, itemId, direction)
                                            if (next != null) {
                                                focusItemFromArrow(block, next, direction, x)
                                            } else {
                                                focusSiblingText(block.key, direction, x)
                                            }
                                        },
                                        onGeometry = { itemId, geometry -> textGeometry[itemId] = geometry }
                                    )
                                }
                            }
                        }

                        tree.roots().forEach { block ->
                            key(block.key) { RenderBlock(block) }
                        }

                        val hintVisible = !imeVisible && !titleFocused && focusedKey == null
                        Text(
                            text = "Toca para seguir escribiendo…",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontStyle = FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (hintVisible) 1f else 0f)
                                .padding(vertical = 6.dp)
                        )
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        )
                    }

                    pendingDrop?.let { drop ->
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.primary,
                            thickness = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset {
                                    IntOffset(
                                        0,
                                        (drop.lineTop - columnTop).roundToInt()
                                    )
                                }
                        )
                    }

                    val draggingBlock = blocks.firstOrNull { it.key == draggingKey }
                    if (draggingBlock != null && draggingBlock.isMovable()) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset {
                                    IntOffset(
                                        0,
                                        (dragPreviewTop() - columnTop).roundToInt()
                                    )
                                }
                                .width(300.dp)
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .alpha(0.8f)
                                .onSizeChanged { overlayHeight = it.height }
                        ) {
                            when (draggingBlock) {
                                is EditorBlock.Media -> attachmentsById[draggingBlock.attachmentId]
                                    ?.let { attachment ->
                                        MediaInlineCard(
                                            attachment = attachment,
                                            file = vm.attachmentFile(attachment),
                                            onOpen = {},
                                            onRemove = null
                                        )
                                    }

                                is EditorBlock.Bookmark -> bookmarksById[draggingBlock.bookmarkId]
                                    ?.let { bookmark ->
                                        BookmarkCard(
                                            bookmark = bookmark,
                                            imageFile = vm.bookmarkImageFile(bookmark),
                                            faviconFile = vm.bookmarkFaviconFile(bookmark),
                                            onOpen = {},
                                            onRemove = null
                                        )
                                    }

                                is EditorBlock.NoteLink -> {
                                    val linked = allNotes.firstOrNull { it.id == draggingBlock.noteId }
                                    NoteLinkCard(
                                        note = linked,
                                        iconFile = linked?.let { vm.iconFile(it) },
                                        onOpen = {},
                                        onRemove = null
                                    )
                                }

                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
    }

    if (showInfo) {
        // También el texto de los ítems de las checklists.
        val characters = blocks.filterIsInstance<EditorBlock.Text>().sumOf { countedText(it).length } +
            blocks.filterIsInstance<EditorBlock.Checklist>().sumOf { checklistText(it).length }
        val totalSize = attachmentsById.values.sumOf { it.size }
        // Solo con el diálogo abierto: contarlas acá arriba leería los bloques en cada tecla.
        val wordCount = blocks.sumOf { block ->
            when (block) {
                is EditorBlock.Text -> countWords(countedText(block))
                is EditorBlock.Checklist -> block.items.sumOf { countWords(it.value.plain) }
                else -> 0
            }
        }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Información") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("Creada", formatDate(note.createdAt))
                    InfoRow("Modificada", formatDate(note.updatedAt))
                    InfoRow("Palabras", wordCount.toString())
                    InfoRow("Caracteres", characters.toString())
                    InfoRow("Adjuntos", "${attachmentsById.size} (${humanSize(totalSize)})")
                    InfoRow("Cuaderno", note.notebook ?: "—")
                    if (note.tags.isNotEmpty()) {
                        InfoRow("Etiquetas", note.tags.joinToString(", ") { "#$it" })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("Cerrar") }
            }
        )
    }

    if (showNotebook) {
        MoveToNotebookDialog(
            current = note.notebook,
            notebooks = notebooks,
            icons = notebookIcons,
            iconFile = vm::notebookIconFile,
            onMove = { name ->
                vm.setNotebook(note.id, name)
                showNotebook = false
            },
            onDismiss = { showNotebook = false }
        )
    }

    if (showCoverPicker) {
        CoverPickerSheet(
            canAdjust = note.cover?.startsWith(NotesRepository.COVER_FILE) == true && vm.coverFile(note)?.exists() == true,
            onPick = { value ->
                vm.setCoverPreset(note.id, value)
                showCoverPicker = false
            },
            onRemove = {
                vm.setCoverPreset(note.id, null)
                showCoverPicker = false
            },
            onUpload = {
                showCoverPicker = false
                coverFileLauncher.launch(arrayOf("image/*"))
            },
            onAdjust = {
                showCoverPicker = false
                adjustingCover = true
            },
            onDismiss = { showCoverPicker = false }
        )
    }

    viewingPhoto?.let { (file, photoTitle) ->
        if (!file.exists()) {
            viewingPhoto = null
        } else {
            PhotoViewer(file = file, title = photoTitle, onBack = { viewingPhoto = null })
        }
    }

    if (adjustingCover) {
        val file = vm.coverFile(note)
        if (file == null) {
            adjustingCover = false
        } else {
            CoverAdjustDialog(
                file = file,
                x = note.coverX,
                y = note.coverY,
                scale = note.coverScale,
                onDone = { x, y, scale ->
                    vm.setCoverCrop(note.id, x, y, scale)
                    adjustingCover = false
                },
                onCancel = { adjustingCover = false }
            )
        }
    }

    if (showIconPicker) {
        EmojiPickerSheet(
            onPick = { emoji ->
                vm.setEmojiIcon(note.id, emoji)
                showIconPicker = false
            },
            onRemove = {
                vm.setEmojiIcon(note.id, null)
                showIconPicker = false
            },
            onUpload = {
                showIconPicker = false
                iconFileLauncher.launch(arrayOf("image/*"))
            },
            onDismiss = { showIconPicker = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar nota") },
            text = { Text("«${note.displayTitle}» se moverá a la papelera.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.moveToTrash(note.id)
                    releaseRichField(view)
                    onBack()
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            }
        )
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Insertar enlace") },
            text = {
                Column {
                    OutlinedTextField(
                        value = linkUrl,
                        onValueChange = { linkUrl = it },
                        label = { Text("Link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipText = clipboard?.primaryClip
                                    ?.getItemAt(0)
                                    ?.text
                                    ?.toString()
                                    .orEmpty()
                                if (clipText.isNotBlank()) linkUrl = clipText
                            }) {
                                Icon(
                                    Icons.Filled.ContentPaste,
                                    contentDescription = "Pegar link"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Se va a crear una tarjeta con el título, la descripción y la imagen del sitio.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { insertBookmarkFromDialog() },
                    enabled = linkUrl.isNotBlank()
                ) {
                    Text("Insertar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

// ------------------------------------------------------------------- bloques

/**
 * Un bloque de texto: el campo con formato, con el foco, las flechas entre bloques y la
 * geometría para soltar bloques.
 */
@Composable
private fun TextBlockEditor(
    block: EditorBlock.Text,
    focusRequested: Boolean,
    onFocusRequestConsumed: () -> Unit,
    onFocus: () -> Unit,
    onFocusLost: () -> Unit,
    onFocusPrevious: (Float) -> Unit,
    onFocusNext: (Float) -> Unit,
    onValueChange: (RichText) -> Unit,
    onGeometry: (TextGeometry) -> Unit,
    onShortcut: (EditorShortcut) -> Boolean,
    isFirstInNote: () -> Boolean,
) {
    RichTextField(
        value = block.value,
        onValueChange = onValueChange,
        style = rememberRichStyle(),
        focusRequested = focusRequested,
        onFocusRequestConsumed = onFocusRequestConsumed,
        onFocusChanged = { focused -> if (focused) onFocus() else onFocusLost() },
        onFocusPrevious = onFocusPrevious,
        onFocusNext = onFocusNext,
        onGeometry = onGeometry,
        onShortcut = onShortcut,
        isFirstInNote = isFirstInNote,
    )
}

/** Lo que hace el botón [action] de la barra sobre el texto, para el atajo [EditorShortcut.Bar]. */
private fun barTransform(action: BarAction): (RichText) -> RichText = when (action) {
    BarAction.Bold -> { value -> RichCommands.toggleInline(value, InlineFormat.Bold) }
    BarAction.Italic -> { value -> RichCommands.toggleInline(value, InlineFormat.Italic) }
    BarAction.Strike -> { value -> RichCommands.toggleInline(value, InlineFormat.Strike) }
    BarAction.Heading -> { value -> RichCommands.toggleLine(value, LineFormat.Heading2) }
    BarAction.Bullet -> { value -> RichCommands.toggleLine(value, LineFormat.Bullet) }
    BarAction.Ordered -> { value -> RichCommands.toggleLine(value, LineFormat.Ordered) }
    BarAction.Check -> { value -> RichCommands.toggleLine(value, LineFormat.Check) }
    BarAction.Quote -> { value -> RichCommands.toggleLine(value, LineFormat.Quote) }
    BarAction.Code -> RichCommands::code
    BarAction.Divider -> RichCommands::insertDivider
}

/** Ctrl+Z deshace y Ctrl+Shift+Z y Ctrl+Y rehacen, como en [com.noted.app.ui.rich.RichEditText]. */
private fun editorShortcutOf(event: KeyEvent): EditorShortcut? = when {
    !event.isCtrlPressed || event.isAltPressed -> null
    event.key == Key.Z && !event.isShiftPressed -> EditorShortcut.Undo
    event.key == Key.Z || event.key == Key.Y -> EditorShortcut.Redo
    else -> null
}

@Composable
private fun ToggleBlock(
    toggle: EditorBlock.Toggle,
    depth: Int,
    children: List<EditorBlock>,
    onToggleExpanded: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTitleUp: () -> Unit,
    onTitleDown: () -> Unit,
    focusRequested: Boolean,
    onFocusRequestConsumed: () -> Unit,
    onTitleFocusChanged: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onShortcut: (EditorShortcut) -> Boolean,
    childContent: @Composable (EditorBlock) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val contentStart = if (depth <= 1) 36.dp else 16.dp
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            titleFocus.requestFocus()
            onFocusRequestConsumed()
        }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .heightIn(min = 60.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier
                        .size(34.dp)
                        .focusProperties { canFocus = false }
                ) {
                    Icon(
                        imageVector = if (toggle.isToggled) {
                            Icons.Filled.ExpandMore
                        } else {
                            Icons.Filled.ChevronRight
                        },
                        contentDescription = if (toggle.isToggled) "Contraer" else "Expandir",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = toggle.title,
                    // Un título largo pasa de renglón en vez de cortarse; sigue siendo un solo
                    // renglón de texto: Enter baja al contenido (como la flecha abajo) y un salto
                    // que llegue del teclado o al pegar se vuelve espacio.
                    onValueChange = { onTitleChange(it.replace("\n", " ")) },
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { onTitleDown() }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp)
                        .focusRequester(titleFocus)
                        .onPreviewKeyEvent { event ->
                            // Ctrl+Z y los de rehacer son de la nota: el deshacer propio del
                            // campo cambiaría el título sin pasar por la pila y borraría lo rehacible.
                            val shortcut = editorShortcutOf(event)
                            if (shortcut != null) {
                                if (event.type == KeyEventType.KeyDown) onShortcut(shortcut)
                                true
                            } else if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                            ) {
                                if (event.key == Key.DirectionUp) onTitleUp() else onTitleDown()
                                true
                            } else if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.type == KeyEventType.KeyDown) onTitleDown()
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { onTitleFocusChanged(it.isFocused) },
                    decorationBox = { inner ->
                        Box {
                            if (toggle.title.isEmpty()) {
                                Text(
                                    "Desplegable",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                            }
                            inner()
                        }
                    }
                )
                Box {
                    IconButton(
                        onClick = { menu = true },
                        modifier = Modifier
                            .size(34.dp)
                            .focusProperties { canFocus = false }
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Opciones del desplegable",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Quitar desplegable") },
                            onClick = {
                                menu = false
                                onRemove()
                            }
                        )
                    }
                }
            }

            if (toggle.isToggled) {
                Column(
                    modifier = Modifier.padding(start = contentStart, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    children.forEach { child ->
                        key(child.key) {
                            childContent(child)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    imageFile: File?,
    faviconFile: File?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Medidas de la tarjeta de enlace.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(16.dp)
    val cardColor = if (dark) Color(0xFF121212) else Color(0xFFFFFFFF)
    val borderColor = if (dark) Color(0xFF3A3A3A) else Color(0xFFE3E3E3)
    val primaryText = if (dark) Color(0xFFF3F3F3) else Color(0xFF252525)
    val secondaryText = if (dark) Color(0xFF909090) else Color(0xFF949494)
    val tertiaryText = if (dark) Color(0xFF646464) else Color(0xFFBFBFBF)
    // 0,5 dp: un píxel físico en la tablet.
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    val imageModel = imageFile ?: bookmark.imageUrl
    val title = bookmark.title.trim()
    // Sin el relleno de la letra y con el renglón centrado, el alto sale justo.
    val tight = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    )
    // El lugar del ícono (16 + 4 dp) solo aparece si el ícono cargó de verdad.
    var iconLoaded by remember(faviconFile) { mutableStateOf(false) }
    var imageLoaded by remember(imageModel) { mutableStateOf(false) }

    Surface(
        shape = shape,
        color = cardColor,
        border = BorderStroke(hairline, borderColor),
        // Mantener apretado arrastra el enlace; las opciones van en los tres puntos.
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen
            )
    ) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = if (imageModel != null) 12.dp else 16.dp)
            ) {
                // El botón de opciones tiene su lugar arriba a la derecha: título y descripción se cortan antes.
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title.ifEmpty { "Sin título" },
                            style = tight,
                            color = if (title.isEmpty()) tertiaryText else primaryText,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.016).em,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        // Sin descripción el renglón no se colapsa: queda vacío.
                        Text(
                            text = bookmark.description.trim(),
                            style = tight,
                            color = primaryText,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            minLines = 1,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (onRemove != null) {
                        Spacer(Modifier.width(4.dp))
                        Box {
                            CardMenuButton("Opciones del enlace") { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Abrir") },
                                    onClick = {
                                        menu = false
                                        onOpen()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Quitar de la nota") },
                                    onClick = {
                                        menu = false
                                        onRemove()
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (faviconFile != null) {
                        Box(Modifier.width(if (iconLoaded) 20.dp else 0.dp).height(16.dp)) {
                            AsyncImage(
                                model = faviconFile,
                                imageLoader = remember(context) { NoteIconLoader.get(context) },
                                contentDescription = null,
                                onSuccess = { iconLoaded = true },
                                onError = { iconLoaded = false },
                                modifier = Modifier
                                    .requiredSize(16.dp)
                                    .alpha(if (iconLoaded) 1f else 0f)
                            )
                        }
                    }
                    Text(
                        text = bookmark.url,
                        style = tight,
                        color = secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (imageModel != null) {
                // El lugar de 82 dp (+ 16 de margen) solo se reserva si la imagen cargó de verdad:
                // con un archivo que falta o que no se dibuja, la tarjeta se ve «sin imagen».
                Box(
                    Modifier
                        .padding(end = if (imageLoaded) 16.dp else 0.dp)
                        .width(if (imageLoaded) 82.dp else 0.dp)
                        .height(82.dp)
                        .align(Alignment.Top)
                ) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { imageLoaded = true },
                        onError = { imageLoaded = false },
                        modifier = Modifier
                            .requiredSize(82.dp)
                            .alpha(if (imageLoaded) 1f else 0f)
                    )
                    if (imageLoaded) Box(Modifier.requiredSize(82.dp).background(Color(0x0D000000)))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaInlineCard(
    attachment: Attachment,
    file: File,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    val isPhotoOrVideo = attachment.isImage || attachment.isVideo

    Surface(
        shape = shape,
        color = if (isPhotoOrVideo) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .focusProperties { canFocus = false }
            .clickable(onClick = onOpen)
    ) {
        Box {
            Column {
                when {
                    attachment.isVideo -> VideoInlinePreview(attachment, file)

                    // La foto se muestra entera: el ancho lo define el bloque y el
                    // alto sale de su relación de aspecto. Sin recorte y sin caja.
                    // Se decodifica a lo sumo a 2048 px de lado: alcanza para el
                    // ancho del bloque y evita bitmaps de decenas de MB por foto.
                    attachment.isImage -> AsyncImage(
                        model = remember(file) {
                            ImageRequest.Builder(context)
                                .data(file)
                                // Solo se limita el ancho: con una caja de 2048×2048,
                                // una captura larga se achicaba a lo alto y se veía borrosa.
                                .size(Size(Dimension.Pixels(2048), Dimension.Undefined))
                                .build()
                        },
                        contentDescription = attachment.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .background(MaterialTheme.colorScheme.surface)
                    )

                    else -> FileInlineRow(attachment, file, onOpen)
                }
            }

            if (onRemove != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    CardMenuButton("Opciones del adjunto") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Abrir") },
                            onClick = {
                                menu = false
                                onOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Quitar de la nota") },
                            onClick = {
                                menu = false
                                onRemove()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Video inline: el frame real ocupa todo el ancho y el alto
 * sale de la relación de aspecto del video, así se ve completo y sin recorte.
 * Tocar el bloque abre la galería de medios en ese video.
 */
@Composable
private fun VideoInlinePreview(attachment: Attachment, file: File) {
    val preview = rememberVideoPreview(file)
    val frame = preview?.frame
    val ratio = preview?.let { item ->
        val width = item.frame?.width ?: item.width
        val height = item.frame?.height ?: item.height
        if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
    } ?: (16f / 9f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = mimeIcon(attachment.mime),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(36.dp)
            )
        }
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.size(54.dp)
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Reproducir",
                tint = Color.White,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Tarjeta compacta para PDF y otros archivos. El PDF muestra la primera
 * página como miniatura; el resto, el ícono de su tipo.
 */
@Composable
private fun FileInlineRow(
    attachment: Attachment,
    file: File,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (attachment.isPdf) {
            PdfThumb(file, Modifier.width(54.dp).height(72.dp))
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(54.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = mimeIcon(attachment.mime),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${typeLabel(attachment.mime)} · ${humanSize(attachment.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpen, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = if (attachment.isAudio) Icons.Filled.PlayArrow
                else Icons.Filled.OpenInNew,
                contentDescription = "Abrir",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PdfThumb(file: File, modifier: Modifier = Modifier) {
    val preview = rememberPdfPreview(file)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.White,
        modifier = modifier
    ) {
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun MissingAttachmentCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Adjunto no disponible",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Bloque de otra nota dentro de esta: ícono y título de la nota enlazada; al
 * tocarlo se abre. Si la nota ya no existe o está en la papelera, lo indica.
 */
@Composable
private fun NoteLinkCard(
    note: Note?,
    iconFile: File?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val available = note != null && !note.trashed
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .focusProperties { canFocus = false }
            .clickable(enabled = available, onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (note != null) {
                NoteIcon(note = note, iconFile = iconFile)
            } else {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = note?.displayTitle ?: "Nota eliminada",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (available) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (note?.trashed == true) {
                    Text(
                        text = "En la papelera",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (available) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onRemove != null) Box {
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier
                        .size(36.dp)
                        .focusProperties { canFocus = false }
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Opciones de la nota",
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (available) {
                        DropdownMenuItem(
                            text = { Text("Abrir") },
                            onClick = {
                                menu = false
                                onOpen()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Quitar de esta nota") },
                        onClick = {
                            menu = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Tres puntos sobre una foto, un video o un enlace. No es un IconButton: ese
 * botón se agranda solo a 48 dp para el toque y el círculo oscuro se salía de la
 * tarjeta. Este mide 32 dp de verdad.
 */
@Composable
private fun CardMenuButton(description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
    ) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

// ------------------------------------------------------------------ toolbar

@Composable
private fun EditorToolbar(
    items: List<ToolbarItem>,
    active: Set<BarFormat>,
    onFormat: (BarAction, (RichText) -> RichText) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAttach: () -> Unit,
    onLink: () -> Unit,
    onToggle: () -> Unit,
    onNote: () -> Unit,
    onArrange: () -> Unit
) {
    fun format(action: BarAction) = onFormat(action, barTransform(action))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        val scrollState = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // En tablet entran todos los botones: se centran. En teléfono se
            // mantiene el scroll horizontal desde el primer botón.
            val centered = maxWidth >= 700.dp
            val rowModifier = if (centered) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.horizontalScroll(scrollState)
            }
            Row(
                modifier = rowModifier.padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (item in items) {
                    val format = item.barFormat()
                    ToolbarButton(item.label, item.icon(), active = format != null && format in active) {
                        when (item) {
                            ToolbarItem.Undo -> onUndo()
                            ToolbarItem.Redo -> onRedo()
                            ToolbarItem.Bold -> format(BarAction.Bold)
                            ToolbarItem.Italic -> format(BarAction.Italic)
                            ToolbarItem.Strike -> format(BarAction.Strike)
                            ToolbarItem.Heading -> format(BarAction.Heading)
                            ToolbarItem.Bullet -> format(BarAction.Bullet)
                            ToolbarItem.Ordered -> format(BarAction.Ordered)
                            ToolbarItem.Check -> format(BarAction.Check)
                            ToolbarItem.Quote -> format(BarAction.Quote)
                            ToolbarItem.Toggle -> onToggle()
                            ToolbarItem.Code -> format(BarAction.Code)
                            ToolbarItem.Link -> onLink()
                            ToolbarItem.ChildNote -> onNote()
                            ToolbarItem.Divider -> format(BarAction.Divider)
                            ToolbarItem.Attach -> onAttach()
                        }
                    }
                }
                // Último ítem de la barra: texto en el primario, 60 x 58dp, 16sp.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(48.dp)
                        .widthIn(min = 60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .focusProperties { canFocus = false }
                        .clickable(onClick = onArrange)
                        .padding(horizontal = 8.dp)
                ) {
                    Text("Editar", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                }
            }
        }
    }
}

// Mantener apretado un botón muestra un cartelito con lo que hace; el toque
// normal sigue aplicando la función.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarButton(
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // Apretado: ícono en el primario sobre una tarjeta de 44 x 44dp con
    // esquinas de 6dp; en oscuro #232323, en claro el primario al 10 % sobre la barra.
    // Un borde de 1dp para que se note más.
    val activeBackground = if (colors.surfaceContainer.luminance() < 0.5f) {
        Color(0xFF232323)
    } else {
        colors.primary.copy(alpha = 0.10f).compositeOver(colors.surfaceContainer)
    }
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = androidx.compose.material3.rememberTooltipState(),
        // Sin tomar el foco: si no, el texto lo pierde y el teclado se esconde
        // mientras se ve el cartelito.
        focusable = false
    ) {
        // La tarjeta va detrás del botón: el IconButton recorta lo de adentro a un círculo.
        Box(contentAlignment = Alignment.Center) {
            if (active) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(activeBackground, RoundedCornerShape(6.dp))
                        .border(1.dp, colors.primary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                )
            }
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = if (active) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun countWords(text: String): Int =
    text.split(Regex("\\s+")).count { it.isNotBlank() }
