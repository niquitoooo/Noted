package com.noted.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Route {
    data object List : Route
    /** [parents]: notas desde las que se entró a esta, para volver de a una. */
    data class Editor(
        val noteId: String,
        val parents: kotlin.collections.List<String> = emptyList()
    ) : Route
    data class Viewer(
        val noteId: String,
        val attachmentId: String,
        val mediaIds: kotlin.collections.List<String>? = null,
        val parents: kotlin.collections.List<String> = emptyList()
    ) : Route
}

/**
 * Guarda la pantalla actual para que, si Android cierra la app en segundo plano,
 * al volver se abra la misma nota o visor (y la cadena de notas para volver atrás).
 */
private val RouteSaver = Saver<Route, ArrayList<String>>(
    save = { route ->
        when (route) {
            Route.List -> arrayListOf("list")
            is Route.Editor -> arrayListOf("editor", route.noteId, route.parents.joinToString("\n"))
            is Route.Viewer -> arrayListOf(
                "viewer",
                route.noteId,
                route.attachmentId,
                route.mediaIds?.joinToString("\n") ?: NO_MEDIA_IDS,
                route.parents.joinToString("\n")
            )
        }
    },
    restore = { saved ->
        fun ids(value: String) = value.split("\n").filter { it.isNotEmpty() }
        when (saved.firstOrNull()) {
            "editor" -> Route.Editor(saved[1], ids(saved[2]))
            "viewer" -> Route.Viewer(
                noteId = saved[1],
                attachmentId = saved[2],
                mediaIds = saved[3].takeIf { it != NO_MEDIA_IDS }?.let { ids(it) },
                parents = ids(saved[4])
            )
            else -> Route.List
        }
    }
)

private const val NO_MEDIA_IDS = "\u0000"

@Composable
fun NotedApp(vm: NotesViewModel) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val pending by vm.pendingNote.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(Route.List) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(pending) {
        val noteId = pending ?: return@LaunchedEffect
        route = Route.Editor(noteId)
        vm.consumePending()
    }

    // Al llegar a la lista se corta cualquier cadena de "volver": las posiciones
    // guardadas ya no corresponden y una nota abierta desde la lista arranca arriba.
    LaunchedEffect(route) {
        if (route is Route.List) vm.clearEditorScrolls()
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbar.show(message)
        }
    }

    BackHandler(enabled = route is Route.Viewer) {
        val viewer = route as? Route.Viewer
        if (viewer != null) {
            vm.flush()
            route = Route.Editor(viewer.noteId, viewer.parents)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = route) {
            Route.List -> NotesListScreen(
                vm = vm,
                notes = notes,
                snackbar = snackbar,
                onOpenNote = { route = Route.Editor(it) }
            )

            is Route.Editor -> {
                val note = notes.firstOrNull { it.id == current.noteId }
                if (note == null) {
                    // Una nota recién creada (por ejemplo, dentro de otra) puede
                    // tardar un instante en llegar a la lista: esperar antes de salir.
                    // Al volver después de que Android cerró la app, las notas se
                    // están leyendo del disco: se espera hasta 2 s.
                    LaunchedEffect(current.noteId) {
                        val found = withTimeoutOrNull(2000) {
                            vm.notes.first { list -> list.any { it.id == current.noteId } }
                        }
                        if (found == null) {
                            // Si no existe (por ejemplo, se borró definitivamente),
                            // volver a la nota de donde se vino, no a la lista.
                            route = current.parents.lastOrNull()
                                ?.let { Route.Editor(it, current.parents.dropLast(1)) }
                                ?: Route.List
                        }
                    }
                } else {
                    // Cada nota arma su propio editor: al pasar de una nota a otra
                    // no se arrastra el scroll ni los menús abiertos.
                    key(note.id) {
                        NoteEditorScreen(
                            vm = vm,
                            note = note,
                            snackbar = snackbar,
                            onBack = {
                                route = current.parents.lastOrNull()
                                    ?.let { Route.Editor(it, current.parents.dropLast(1)) }
                                    ?: Route.List
                            },
                            onOpenAttachment = { attachment, mediaIds ->
                                route = Route.Viewer(note.id, attachment.id, mediaIds, current.parents)
                            },
                            onOpenNote = { linkedId ->
                                route = Route.Editor(linkedId, current.parents + note.id)
                            }
                        )
                    }
                }
            }

            is Route.Viewer -> {
                val note = notes.firstOrNull { it.id == current.noteId }
                val attachment = note?.attachments?.firstOrNull { it.id == current.attachmentId }
                if (note == null || attachment == null) {
                    LaunchedEffect(current.attachmentId) {
                        // Igual que en el editor: esperar a que se lean las notas.
                        val found = withTimeoutOrNull(2000) {
                            vm.notes.first { list ->
                                list.any { n ->
                                    n.id == current.noteId &&
                                        n.attachments.any { it.id == current.attachmentId }
                                }
                            }
                        }
                        if (found == null) route = Route.Editor(current.noteId, current.parents)
                    }
                } else if (attachment.isImage || attachment.isVideo) {
                    val orderedMedia = current.mediaIds
                        ?.mapNotNull { id -> note.attachments.firstOrNull { it.id == id } }
                        ?.filter { it.isImage || it.isVideo }
                        ?: note.attachments.filter { it.isImage || it.isVideo }
                    MediaGalleryScreen(
                        attachments = orderedMedia,
                        initialAttachmentId = attachment.id,
                        fileFor = vm::attachmentFile,
                        onBack = { route = Route.Editor(note.id, current.parents) }
                    )
                } else {
                    AttachmentViewerScreen(
                        attachment = attachment,
                        file = vm.attachmentFile(attachment),
                        onBack = { route = Route.Editor(note.id, current.parents) }
                    )
                }
            }
        }

        if (vm.busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 6.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "Procesando…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
