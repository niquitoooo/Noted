package com.noted.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noted.app.data.Note
import com.noted.app.data.NoteFilter
import com.noted.app.data.label
import java.io.File

/** Nombre del lugar «Notas» (las notas sin cuaderno) dentro de [SearchScope.places]. */
const val NOTAS_PLACE = ""

/**
 * Dónde busca la lupa. [all]: en todas las notas. [places]: los cuadernos marcados
 * ([NOTAS_PLACE] es «Notas»); null en «Fijadas» y en una etiqueta, que buscan en su lista.
 */
data class SearchScope(val all: Boolean, val places: Set<String>?)

/** Lo que viene marcado al abrir la lupa: el lugar donde se está. */
fun defaultSearchScope(filter: NoteFilter): SearchScope = when (filter) {
    NoteFilter.All -> SearchScope(false, setOf(NOTAS_PLACE))
    is NoteFilter.Notebook -> SearchScope(false, setOf(filter.name))
    else -> SearchScope(false, null)
}

/** El lugar de la nota: su cuaderno, o [NOTAS_PLACE] si no tiene. */
fun placeOf(note: Note): String = note.notebook?.takeIf { it.isNotBlank() } ?: NOTAS_PLACE

/**
 * Si la nota entra en la búsqueda (sin mirar el texto buscado). En la papelera se busca
 * solo ahí; si no, nunca aparecen notas de la papelera.
 */
fun inSearchScope(note: Note, filter: NoteFilter, scope: SearchScope): Boolean {
    if (filter == NoteFilter.Trash) return note.trashed
    if (note.trashed) return false
    if (scope.all) return true
    scope.places?.let { return placeOf(note) in it }
    return when (filter) {
        NoteFilter.Pinned -> note.pinned
        is NoteFilter.Tag -> note.tags.any { it.equals(filter.name, true) }
        else -> true
    }
}

/** Si los resultados vienen de más de un lugar y cada uno tiene que decir de qué cuaderno es. */
fun showsPlace(scope: SearchScope): Boolean = scope.all || (scope.places?.size ?: 0) > 1

/** Lo que dice el botón del lugar: el nombre si es uno solo, o cuántos. */
fun scopeLabel(places: Set<String>): String = when {
    places.size == 1 -> places.first().ifEmpty { "Notas" }
    else -> "${places.size} cuadernos"
}

/**
 * La fila de abajo del campo de búsqueda: el lugar donde se busca (con la lista de
 * cuadernos para marcar varios) y «Todas las notas». En la papelera no se muestra.
 */
@Composable
fun SearchScopeBar(
    filter: NoteFilter,
    scope: SearchScope,
    notebooks: List<String>,
    icons: Map<String, String>,
    iconFile: (String?) -> File?,
    onChange: (SearchScope) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val places = scope.places
        Box {
            FilterChip(
                selected = !scope.all,
                onClick = {
                    if (scope.all) onChange(scope.copy(all = false))
                    if (places != null) open = !open || scope.all
                },
                label = {
                    Text(
                        when {
                            places != null -> scopeLabel(places)
                            else -> filter.label()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp)
                    )
                },
                leadingIcon = places?.singleOrNull()?.let { place ->
                    {
                        PlaceIcon(place, icons, iconFile, size = 18.dp)
                    }
                },
                trailingIcon = if (places != null) {
                    {
                        Icon(
                            if (open) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                            contentDescription = "Elegir dónde buscar"
                        )
                    }
                } else {
                    null
                }
            )
            if (places != null) {
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    val all = listOf(NOTAS_PLACE) + notebooks
                    all.forEachIndexed { index, place ->
                        val checked = place in places
                        DropdownMenuItem(
                            text = {
                                Text(
                                    place.ifEmpty { "Notas" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = { PlaceIcon(place, icons, iconFile, size = 22.dp) },
                            trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                            // El último marcado no se desmarca: siempre se busca en algún lugar.
                            onClick = {
                                val next = if (checked) places - place else places + place
                                if (next.isNotEmpty()) onChange(SearchScope(false, next))
                            }
                        )
                        if (index == 0 && notebooks.isNotEmpty()) HorizontalDivider()
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = scope.all,
            onClick = {
                open = false
                onChange(scope.copy(all = true))
            },
            label = { Text("Todas las notas") }
        )
    }
}

/** Ícono de un lugar: el de «Notas» o el del cuaderno (emoji, imagen o el libro). */
@Composable
fun PlaceIcon(
    place: String,
    icons: Map<String, String>,
    iconFile: (String?) -> File?,
    size: Dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (place == NOTAS_PLACE) {
        Icon(Icons.Filled.Notes, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    } else {
        val icon = icons[place]
        NotebookIcon(icon, iconFile(icon), size = size, tint = tint, name = place)
    }
}
