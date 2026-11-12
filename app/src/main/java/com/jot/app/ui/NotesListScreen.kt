package com.jot.app.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiSymbols
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jot.app.data.Note
import com.jot.app.data.NoteFilter
import com.jot.app.data.label
import com.jot.app.data.nestedNoteIds
import com.jot.app.data.hiddenWithTrashedNotebooks
import com.jot.app.ui.emoji.EmojiPickerSheet
import com.jot.app.util.excerpt
import com.jot.app.util.formatDate
import com.jot.app.util.humanSize
import com.jot.app.util.typeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn
import androidx.compose.runtime.key
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    vm: NotesViewModel,
    notes: List<Note>,
    snackbar: SnackbarHostState,
    onOpenNote: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val haptic = LocalHapticFeedback.current
    // Atrás con el menú lateral abierto cierra el menú, no la app.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val filter = vm.filter
    val query = vm.query
    // La búsqueda vive en el ViewModel y sobrevive al abrir una nota: si al volver
    // sigue activa, la barra tiene que verse, o parece que se borraron las demás notas.
    var searchVisible by remember { mutableStateOf(vm.query.isNotBlank()) }
    // Dónde busca la lupa: al abrirla (o al cambiar de lugar), el lugar donde se está.
    var searchScope by remember { mutableStateOf(defaultSearchScope(filter)) }
    // Con la búsqueda abierta, «atrás» la cierra en vez de salir de la app (con el menú
    // lateral abierto, primero se cierra el menú).
    BackHandler(enabled = searchVisible && !drawerState.isOpen) {
        searchVisible = false
        vm.query = ""
    }
    // Sin nada abierto: desde un cuaderno (o Fijadas, Papelera, una etiqueta), «atrás»
    // vuelve a «Notas»; en «Notas», el primero avisa y el segundo (antes de 1 s) sale.
    val nothingOpen = !drawerState.isOpen && !searchVisible
    BackHandler(enabled = nothingOpen && filter != NoteFilter.All) {
        vm.filter = NoteFilter.All
    }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = nothingOpen && filter == NoteFilter.All) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            lastBackAt = 0L
            // Como el «atrás» de Android en la pantalla de inicio: la app queda en segundo plano.
            (context as? Activity)?.moveTaskToBack(true)
        } else {
            lastBackAt = now
            val toast = Toast.makeText(context, "Volvé atrás otra vez para salir", Toast.LENGTH_SHORT)
            toast.show()
            // El aviso se va cuando vence el plazo (el corto de Android duraría 2 s).
            scope.launch {
                delay(EXIT_WINDOW_MS)
                toast.cancel()
            }
        }
    }
    LaunchedEffect(filter) { searchScope = defaultSearchScope(filter) }
    var searchFocusRequest by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Note?>(null) }
    var notebookTarget by remember { mutableStateOf<Note?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var iconTarget by remember { mutableStateOf<Note?>(null) }
    var iconFileTarget by remember { mutableStateOf<String?>(null) }
    val notebookIcons by vm.notebookIcons.collectAsState()
    var notebookIconTarget by remember { mutableStateOf<String?>(null) }
    var notebookIconFileTarget by remember { mutableStateOf<String?>(null) }
    val notebookIconLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = notebookIconFileTarget
        notebookIconFileTarget = null
        if (uri != null && name != null) vm.setNotebookIconFromUri(name, uri)
    }

    val iconFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val noteId = iconFileTarget
        iconFileTarget = null
        if (uri != null && noteId != null) vm.setIconFromUri(noteId, uri)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.importNotesTree(uri)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.exportAllTo(uri)
        }
    }

    val trashedNotebooks by vm.notebooksTrashed.collectAsState()
    // Las notas de un cuaderno en la papelera, y las que están adentro de ellas, se van con él.
    val hiddenIds = remember(notes, trashedNotebooks) { hiddenWithTrashedNotebooks(notes, trashedNotebooks) }
    val activeNotes = remember(notes, hiddenIds) {
        notes.filterNot { it.trashed || it.id in hiddenIds }
    }
    // Las notas que están dentro de otra se ven solo desde esa nota en "Notas".
    // Al buscar, en la papelera y en los demás filtros se muestran igual.
    val nestedIds = remember(notes) { nestedNoteIds(notes) }
    // Lo que cuenta el menú lateral es lo que se ve en "Notas": las que no tienen cuaderno.
    val listedCount = remember(activeNotes, nestedIds) {
        activeNotes.count { it.id !in nestedIds && placeOf(it) == NOTAS_PLACE }
    }
    // El encabezado del menú cuenta todas las notas, de cualquier cuaderno.
    val totalCount = remember(activeNotes, nestedIds) { activeNotes.count { it.id !in nestedIds } }
    val trashedIds = remember(notes) { notes.filter { it.trashed }.map { it.id }.toSet() }
    val trashCount = remember(notes, trashedIds, trashedNotebooks) {
        notes.count { it.trashed && it.trashedWith !in trashedIds } + trashedNotebooks.size
    }
    // Los cuadernos existen aunque estén vacíos: los guardados más los que tienen notas.
    val notebookOrder by vm.notebookOrder.collectAsState()
    val notebooks = remember(notes, notebookIcons, notebookOrder, trashedNotebooks) {
        (notebookOrder + notebookIcons.keys + activeNotes.mapNotNull { it.notebook?.takeIf { name -> name.isNotBlank() } })
            .distinct()
            .filterNot { it in trashedNotebooks }
    }
    // Los cuadernos de la papelera, en el orden del menú.
    val trashNotebooks = remember(notebookOrder, notebookIcons, trashedNotebooks) {
        (notebookOrder + notebookIcons.keys).distinct().filter { it in trashedNotebooks }
    }
    var creatingNotebook by remember { mutableStateOf(false) }
    // Orden mientras se arrastra un cuaderno en el menú; al soltar se guarda.
    var notebookDrag by remember(notebooks) { mutableStateOf(notebooks) }
    val tags = remember(notes) {
        activeNotes.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    val drawerColors = NavigationDrawerItemDefaults.colors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedBadgeColor = MaterialTheme.colorScheme.primary,
        unselectedBadgeColor = MaterialTheme.colorScheme.surfaceVariant
    )

    val search by vm.search.collectAsState()
    // Lo que encontró la base para esta misma consulta; mientras no llega, se busca en la lista.
    val foundIds = search?.takeIf { it.query == query }?.ids
    val visibleNotes = remember(notes, filter, query, nestedIds, searchScope, foundIds, trashedNotebooks, hiddenIds) {
        if (query.isNotBlank()) {
            // Al buscar manda el lugar elegido en la lupa (en la papelera, solo la papelera).
            // Lo que se fue con un cuaderno a la papelera no se busca.
            return@remember notes.filter { note ->
                note.id !in hiddenIds &&
                    inSearchScope(note, filter, searchScope) &&
                    (filter != NoteFilter.Trash || note.trashedWith !in trashedIds) &&
                    (if (foundIds != null) note.id in foundIds else note.matchesSearch(query))
            }
        }
        val hideNested = filter == NoteFilter.All
        notes.filter { note ->
            if (hideNested && note.id in nestedIds) return@filter false
            // Lo que se fue con un cuaderno a la papelera solo se ve abriendo ese cuaderno.
            if (note.id in hiddenIds &&
                !(filter is NoteFilter.Notebook && filter.name in trashedNotebooks)
            ) return@filter false
            when (filter) {
                // «Notas» es un cuaderno más: las que no tienen cuaderno.
                NoteFilter.All -> !note.trashed && placeOf(note) == NOTAS_PLACE
                NoteFilter.Pinned -> !note.trashed && note.pinned
                // Las que se fueron con otra nota se ven dentro de ella, no sueltas.
                NoteFilter.Trash -> note.trashed && note.trashedWith !in trashedIds
                is NoteFilter.Notebook -> !note.trashed && note.notebook == filter.name
                is NoteFilter.Tag -> !note.trashed && note.tags.any { it.equals(filter.name, true) }
            }
        }
    }
    val showPlace = query.isNotBlank() && filter != NoteFilter.Trash && showsPlace(searchScope)

    // En «Notas» y dentro de un cuaderno; no en etiquetas, fijadas, papelera ni al buscar.
    val canReorder = (filter == NoteFilter.All || filter is NoteFilter.Notebook) && query.isBlank()
    // Guardada en el ViewModel: al volver de una nota la lista queda donde estaba.
    val lazyListState = vm.listState
    // Al cambiar de cuaderno, etiqueta o filtro, la lista empieza arriba.
    // (Solo si el filtro cambió: al volver de una nota se conserva el lugar.)
    var shownFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != shownFilter) {
            shownFilter = filter
            lazyListState.scrollToItem(0)
        }
    }
    // Con alguna fijada, la lista va en dos secciones con título (donde hoy las
    // fijadas van primero: todas las notas, cuadernos y etiquetas).
    val sectioned = filter == NoteFilter.All || filter is NoteFilter.Notebook || filter is NoteFilter.Tag
    val baseRows = remember(visibleNotes, sectioned) {
        if (sectioned) {
            PinnedSections.rows(visibleNotes) { it.pinned }
        } else {
            visibleNotes.map { PinnedSections.Row.Item(it) }
        }
    }
    // Mientras se arrastra, el orden es local: la nota puede pasar por la otra
    // sección y las demás se corren. Al soltar vuelve a la suya (ver onDragStopped).
    var dragRows by remember { mutableStateOf<List<PinnedSections.Row<Note>>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(baseRows) {
        if (draggingId == null) dragRows = null
    }
    val rows = dragRows ?: baseRows
    // La librería del arrastre se queda con las funciones del primer armado de
    // cada tarjeta: lo que leen al empezar y al soltar tiene que ser lo vigente.
    val latestBaseRows by rememberUpdatedState(baseRows)
    val latestVisibleNotes by rememberUpdatedState(visibleNotes)
    val latestCanReorder by rememberUpdatedState(canReorder)
    // Al aparecer el título de arriba, la lista se queda en la primera tarjeta y
    // el título queda fuera de vista; si se estaba arriba de todo, se lo muestra.
    val topHeader = rows.firstOrNull() is PinnedSections.Row.Header
    LaunchedEffect(topHeader) {
        if (topHeader && lazyListState.firstVisibleItemIndex == 1 &&
            lazyListState.firstVisibleItemScrollOffset == 0
        ) {
            lazyListState.scrollToItem(0)
        }
    }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (latestCanReorder && draggingId != null) {
            val current = dragRows ?: latestBaseRows
            // Nada sube por encima del primer título.
            val aboveFirstHeader = to.index == 0 && current.firstOrNull() is PinnedSections.Row.Header
            if (from.index in current.indices && to.index in current.indices && !aboveFirstHeader) {
                dragRows = current.toMutableList().apply { add(to.index, removeAt(from.index)) }
            }
        }
    }
    fun finishDrag(noteId: String) {
        val current = dragRows
        draggingId = null
        if (current == null) return
        val drop = current.mapNotNull { (it as? PinnedSections.Row.Item)?.value }
        val pinnedIds = drop.filter { it.pinned }.map { it.id }.toSet()
        val finalIds = PinnedSections.settle(drop.map { it.id }, noteId) { it in pinnedIds }
        val target = PinnedSections.moveTarget(latestVisibleNotes.map { it.id }, finalIds, noteId)
        if (target == null) {
            dragRows = null
        } else {
            val byId = drop.associateBy { it.id }
            dragRows = PinnedSections.rows(finalIds.mapNotNull { byId[it] }) { it.pinned }
            vm.moveNote(noteId, target)
        }
    }

    var notebookAction by remember { mutableStateOf<String?>(null) }
    var notebookName by remember { mutableStateOf("") }
    var renamingNotebook by remember { mutableStateOf(false) }
    var removingNotebook by remember { mutableStateOf(false) }
    var permanentlyDeleting by remember { mutableStateOf<String?>(null) }

    if (renamingNotebook) {
        AlertDialog(
            onDismissRequest = { renamingNotebook = false },
            title = { Text("Renombrar cuaderno") },
            text = {
                OutlinedTextField(
                    value = notebookName,
                    onValueChange = { notebookName = it.replace("\n", " ") },
                    singleLine = true,
                    label = { Text("Nombre") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = notebookName.trim().isNotEmpty(),
                    onClick = {
                        renamingNotebook = false
                        notebookAction?.let { vm.renameNotebook(it, notebookName) }
                    }
                ) { Text("Renombrar") }
            },
            dismissButton = {
                TextButton(onClick = { renamingNotebook = false }) { Text("Cancelar") }
            }
        )
    }

    if (removingNotebook) {
        val target = notebookAction.orEmpty()
        val inside = notes.filter { it.notebook == target }
        val attachments = inside.sumOf { it.attachments.size }
        AlertDialog(
            onDismissRequest = { removingNotebook = false },
            title = { Text("Eliminar cuaderno") },
            text = {
                Text(
                    buildString {
                        append("Se elimina el cuaderno «$target» con ")
                        append(if (inside.size == 1) "su nota" else "sus ${inside.size} notas")
                        if (attachments > 0) {
                            append(" y ")
                            append(if (attachments == 1) "su archivo" else "sus $attachments archivos")
                        }
                        append(". Van a la papelera y podés restaurarlos desde ahí.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removingNotebook = false
                    notebookAction?.let { vm.trashNotebook(it) }
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { removingNotebook = false }) { Text("Cancelar") }
            }
        )
    }

    permanentlyDeleting?.let { target ->
        val count = notes.count { it.notebook == target }
        AlertDialog(
            onDismissRequest = { permanentlyDeleting = null },
            title = { Text("Eliminar para siempre") },
            text = {
                Text(
                    "Se borra el cuaderno «$target» con " +
                        (if (count == 1) "su nota" else "sus $count notas") +
                        " y sus archivos. Esto no se puede deshacer."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    permanentlyDeleting = null
                    vm.deleteNotebookPermanently(target)
                }) { Text("Eliminar para siempre") }
            },
            dismissButton = {
                TextButton(onClick = { permanentlyDeleting = null }) { Text("Cancelar") }
            }
        )
    }

    fun chooseFilter(next: NoteFilter) {
        vm.filter = next
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(300.dp)
            ) {
              // Con muchos cuadernos, o en apaisado, el menú no entra: se desplaza. Ocupa todo el
              // alto del menú: si no, un cuaderno arrastrado más abajo de lo último se recortaba.
              Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
                    Text(
                        "Notas",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        plural(totalCount, "nota", "notas") + " · " +
                            plural(activeNotes.sumOf { it.attachments.size }, "adjunto", "adjuntos"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))

                NavigationDrawerItem(
                    label = { Text("Notas") },
                    icon = { Icon(Icons.Filled.Notes, contentDescription = null) },
                    selected = filter == NoteFilter.All,
                    onClick = { chooseFilter(NoteFilter.All) },
                    badge = { Text("$listedCount") },
                    colors = drawerColors,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Fijadas") },
                    icon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                    selected = filter == NoteFilter.Pinned,
                    onClick = { chooseFilter(NoteFilter.Pinned) },
                    colors = drawerColors,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Papelera") },
                    icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    selected = filter == NoteFilter.Trash,
                    onClick = { chooseFilter(NoteFilter.Trash) },
                    badge = { Text("$trashCount") },
                    colors = drawerColors,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )

                run {
                    // «CUADERNOS» con su ＋ para crear uno; sin cuadernos, también una fila que lo dice.
                    NotebooksHeader(onAdd = { creatingNotebook = true })
                    if (notebooks.isEmpty()) {
                        NavigationDrawerItem(
                            label = { Text("Nuevo cuaderno") },
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            selected = false,
                            onClick = { creatingNotebook = true },
                            colors = drawerColors,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                    }
                    // Mantener apretado un cuaderno y arrastrarlo cambia su lugar en el menú.
                    ReorderableColumn(
                        list = notebookDrag,
                        onSettle = { from, to ->
                            val moved = notebookDrag[from]
                            notebookDrag = notebookDrag.toMutableList().apply { add(to, removeAt(from)) }
                            vm.moveNotebook(moved, to)
                        },
                        onMove = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    ) { _, notebook, dragging ->
                      key(notebook) {
                        val lift by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "levantar")
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    notebook,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            icon = {
                                val icon = notebookIcons[notebook]
                                NotebookIcon(icon, vm.notebookIconFile(icon), name = notebook)
                            },
                            selected = filter == NoteFilter.Notebook(notebook),
                            onClick = { chooseFilter(NoteFilter.Notebook(notebook)) },
                            badge = {
                                NotebookMenu(
                                    onIcon = { notebookIconTarget = notebook },
                                    onRename = {
                                        notebookAction = notebook
                                        notebookName = notebook
                                        renamingNotebook = true
                                    },
                                    onRemove = {
                                        notebookAction = notebook
                                        removingNotebook = true
                                    }
                                )
                            },
                            colors = drawerColors,
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .shadow(lift, RoundedCornerShape(28.dp))
                                .longPressDraggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                )
                        )
                      }
                    }
                }

                if (tags.isNotEmpty()) {
                    SectionTitle("ETIQUETAS")
                    tags.forEach { tag ->
                        NavigationDrawerItem(
                            label = { Text("#$tag", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            icon = { Icon(Icons.Filled.Label, contentDescription = null) },
                            selected = filter == NoteFilter.Tag(tag),
                            onClick = { chooseFilter(NoteFilter.Tag(tag)) },
                            colors = drawerColors,
                    modifier = Modifier.padding(horizontal = 10.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))

                NavigationDrawerItem(
                    label = { Text("Exportar todo a Markdown") },
                    icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        exportLauncher.launch(null)
                    },
                    colors = drawerColors,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Importar export de Markdown") },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        importLauncher.launch(null)
                    },
                    colors = drawerColors,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                Spacer(Modifier.height(16.dp))
              }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // La app va de borde a borde: el teclado no achica la ventana, así que
            // la lista y el botón «+» se corren solos por encima de él.
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
            snackbarHost = { SnackbarHost(snackbar) { AppSnackbar(it, notebookIcons, vm::notebookIconFile) } },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menú")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (filter as? NoteFilter.Notebook)?.let { notebook ->
                                val icon = notebookIcons[notebook.name]
                                NotebookIcon(icon, vm.notebookIconFile(icon), size = 28.dp, name = notebook.name)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                text = filter.label(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            searchVisible = !searchVisible
                            searchScope = defaultSearchScope(filter)
                            if (!searchVisible) vm.query = "" else searchFocusRequest++
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "Buscar")
                        }
                        // Exportar e importar están solo en el menú lateral: el ⋮ queda
                        // para la papelera («Vaciar papelera»).
                        if (filter == NoteFilter.Trash) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Más")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Vaciar papelera") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            confirmEmptyTrash = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        vm.createNote(notebook = (filter as? NoteFilter.Notebook)?.name)
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Nueva nota")
                }
            }
        ) { padding ->
            PageColumn(Modifier.padding(padding)) {
                if (searchVisible) {
                    // Al tocar la lupa se puede escribir enseguida. Al volver de una
                    // nota con la búsqueda abierta no se enfoca (no salta el teclado).
                    LaunchedEffect(searchFocusRequest) {
                        if (searchFocusRequest > 0) searchFocus.requestFocus()
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.query = it },
                        singleLine = true,
                        placeholder = { Text("Buscar en título y contenido…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Limpiar")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .focusRequester(searchFocus)
                    )
                    if (filter != NoteFilter.Trash) {
                        SearchScopeBar(
                            filter = filter,
                            scope = searchScope,
                            notebooks = notebooks,
                            icons = notebookIcons,
                            iconFile = vm::notebookIconFile,
                            onChange = { searchScope = it },
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                }

                if (visibleNotes.isEmpty() &&
                    !(filter == NoteFilter.Trash && trashNotebooks.isNotEmpty())
                ) {
                    EmptyState(
                        isTrash = filter == NoteFilter.Trash,
                        hasQuery = query.isNotBlank(),
                        onCreate = { vm.createNote(notebook = (filter as? NoteFilter.Notebook)?.name) }
                    )
                } else {
                    LazyColumn(
                        state = lazyListState,
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (filter == NoteFilter.Trash && trashNotebooks.isNotEmpty()) {
                            item(key = "trash:cuadernos") { ListSectionTitle("Cuadernos") }
                            items(trashNotebooks, key = { "trash:nb:$it" }) { name ->
                                TrashNotebookCard(
                                    name = name,
                                    icon = notebookIcons[name],
                                    iconFile = vm.notebookIconFile(notebookIcons[name]),
                                    count = notes.count { it.notebook == name },
                                    onOpen = { chooseFilter(NoteFilter.Notebook(name)) },
                                    onRestore = { vm.restoreNotebook(name) },
                                    onDelete = { permanentlyDeleting = name }
                                )
                            }
                            if (rows.isNotEmpty()) item(key = "trash:notas") { ListSectionTitle("Notas") }
                        }
                        items(rows, key = { row ->
                            when (row) {
                                is PinnedSections.Row.Header -> if (row.pinned) "header:pinned" else "header:others"
                                is PinnedSections.Row.Item -> row.value.id
                            }
                        }) { row ->
                            if (row is PinnedSections.Row.Header) {
                                // También es destino del arrastre, para que la nota
                                // pase por encima del título y las demás se corran.
                                ReorderableItem(reorderState, key = if (row.pinned) "header:pinned" else "header:others") {
                                    ListSectionTitle(if (row.pinned) "Notas fijadas" else "Notas")
                                }
                                return@items
                            }
                            val note = (row as PinnedSections.Row.Item).value
                            ReorderableItem(reorderState, key = note.id) { isDragging ->
                                NoteCard(
                                    note = note,
                                    modifier = Modifier
                                        .longPressDraggableHandle(
                                            enabled = canReorder,
                                            onDragStarted = {
                                                draggingId = note.id
                                                if (dragRows == null) dragRows = latestBaseRows
                                            },
                                            onDragStopped = { finishDrag(note.id) }
                                        )
                                        .graphicsLayer {
                                            val scale = if (isDragging) 1.015f else 1f
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                    iconFile = vm.iconFile(note),
                                    place = if (showPlace) {
                                        {
                                            val place = placeOf(note)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                PlaceIcon(
                                                    place, notebookIcons, vm::notebookIconFile,
                                                    size = 13.dp, tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    place.ifEmpty { "Notas" },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    onIconClick = { iconTarget = note },
                                    onOpen = { onOpenNote(note.id) },
                                    onTogglePin = { vm.togglePin(note.id) },
                                    onMoveRequest = {
                                        notebookTarget = note
                                    },
                                    onTrash = { vm.moveToTrash(note.id) },
                                    onRestore = { vm.restore(note.id) },
                                    onDelete = { deleteTarget = note }
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    deleteTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminar definitivamente") },
            text = { Text("«${note.displayTitle}» y sus adjuntos se borrarán para siempre.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePermanently(note.id)
                    deleteTarget = null
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }

    if (creatingNotebook) {
        CreateNotebookDialog(
            onCreate = { name, emoji, uri ->
                creatingNotebook = false
                vm.createNotebook(name, emoji, uri)
                scope.launch { drawerState.close() }
            },
            onDismiss = { creatingNotebook = false }
        )
    }

    notebookTarget?.let { note ->
        MoveToNotebookDialog(
            current = note.notebook,
            notebooks = notebooks,
            icons = notebookIcons,
            iconFile = vm::notebookIconFile,
            onMove = { name ->
                vm.setNotebook(note.id, name)
                notebookTarget = null
            },
            onDismiss = { notebookTarget = null }
        )
    }

    notebookIconTarget?.let { name ->
        EmojiPickerSheet(
            onPick = { emoji ->
                vm.setNotebookEmojiIcon(name, emoji)
                notebookIconTarget = null
            },
            onRemove = {
                vm.setNotebookEmojiIcon(name, null)
                notebookIconTarget = null
            },
            onUpload = {
                notebookIconFileTarget = name
                notebookIconTarget = null
                notebookIconLauncher.launch(arrayOf("image/*"))
            },
            onDismiss = { notebookIconTarget = null }
        )
    }

    iconTarget?.let { target ->
        EmojiPickerSheet(
            onPick = { emoji ->
                vm.setEmojiIcon(target.id, emoji)
                iconTarget = null
            },
            onRemove = {
                vm.setEmojiIcon(target.id, null)
                iconTarget = null
            },
            onUpload = {
                iconFileTarget = target.id
                iconTarget = null
                iconFileLauncher.launch(arrayOf("image/*"))
            },
            onDismiss = { iconTarget = null }
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("Vaciar papelera") },
            text = { Text("Se eliminarán todas las notas y adjuntos de la papelera.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.emptyTrash()
                    confirmEmptyTrash = false
                }) { Text("Vaciar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) { Text("Cancelar") }
            }
        )
    }
}

/** Título de sección de la lista de notas ("Notas fijadas", "Notas"). */
@Composable
private fun ListSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
    )
}

/** «CUADERNOS» con un ＋ a la derecha para crear un cuaderno. */
@Composable
private fun NotebooksHeader(onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 8.dp, end = 14.dp)
    ) {
        Text(
            text = "CUADERNOS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onAdd, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Nuevo cuaderno",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCard(
    note: Note,
    modifier: Modifier = Modifier,
    iconFile: java.io.File?,
    place: (@Composable () -> Unit)? = null,
    onIconClick: () -> Unit,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onMoveRequest: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
            ) {
                NoteIcon(note = note, iconFile = iconFile)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = note.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Al buscar en varios lugares, el cuaderno de la nota.
                place?.invoke()
            }

            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Opciones",
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (note.trashed) {
                        DropdownMenuItem(
                            text = { Text("Restaurar") },
                            leadingIcon = { Icon(Icons.Filled.RestoreFromTrash, contentDescription = null) },
                            onClick = {
                                menu = false
                                onRestore()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar definitivamente") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menu = false
                                onDelete()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (note.pinned) "Desfijar" else "Fijar arriba") },
                            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                            onClick = {
                                menu = false
                                onTogglePin()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Cambiar ícono") },
                            leadingIcon = { Icon(Icons.Filled.EmojiSymbols, contentDescription = null) },
                            onClick = {
                                menu = false
                                onIconClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mover nota") },
                            leadingIcon = { Icon(Icons.Filled.AutoStories, contentDescription = null) },
                            onClick = {
                                menu = false
                                onMoveRequest()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Enviar a papelera") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menu = false
                                onTrash()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteThumbnail(
    attachment: com.jot.app.data.Attachment,
    file: java.io.File,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    when {
        attachment.isImage -> AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape)
        )

        attachment.isVideo -> {
            val preview = rememberVideoPreview(file)
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val frame = preview?.frame
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(5.dp)
                    )
                }
            }
        }

        attachment.isPdf -> {
            val preview = rememberPdfPreview(file)
            Surface(
                shape = shape,
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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

        else -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = shape,
            modifier = modifier
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = com.jot.app.util.mimeIcon(attachment.mime),
                    contentDescription = typeLabel(attachment.mime),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(isTrash: Boolean, hasQuery: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = when {
                hasQuery -> "Sin resultados"
                isTrash -> "La papelera está vacía"
                else -> "Todavía no hay notas"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isTrash && !hasQuery) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCreate) {
                Text("Crear la primera nota")
            }
        }
    }
}

@Composable
/** Un cuaderno en la papelera: se abre, se restaura o se borra para siempre. */
private fun TrashNotebookCard(
    name: String,
    icon: String?,
    iconFile: File?,
    count: Int,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NotebookIcon(icon, iconFile, size = 22.dp, name = name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (count == 1) "1 nota" else "$count notas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRestore) { Text("Restaurar") }
            TextButton(onClick = onDelete) { Text("Eliminar") }
        }
    }
}

@Composable
private fun NotebookMenu(onIcon: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Opciones del cuaderno",
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Ícono") },
                onClick = {
                    open = false
                    onIcon()
                }
            )
            DropdownMenuItem(
                text = { Text("Renombrar") },
                onClick = {
                    open = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text("Eliminar cuaderno") },
                onClick = {
                    open = false
                    onRemove()
                }
            )
        }
    }
}

private fun plural(count: Int, one: String, many: String): String =
    "$count ${if (count == 1) one else many}"

/** Tiempo para el segundo «atrás» que sale de la app. */
private const val EXIT_WINDOW_MS = 1000L
