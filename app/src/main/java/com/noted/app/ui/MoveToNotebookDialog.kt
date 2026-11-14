package com.noted.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Nombre con el que se guarda un cuaderno escrito a mano: sin espacios de más y,
 * si ya hay uno que se llama igual salvo mayúsculas, el de ese cuaderno (así no
 * quedan dos «Casa» y «casa»). Vacío → null.
 */
fun resolveNotebookName(input: String, existing: List<String>): String? {
    val name = input.trim().takeIf { it.isNotEmpty() } ?: return null
    return existing.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
}

/**
 * «Mover a cuaderno»: «Nuevo cuaderno», «Notas» (sin cuaderno) y los
 * cuadernos que ya hay. Tocar una fila mueve la nota y cierra el cuadro;
 * [onMove] recibe null para «Notas».
 */
@Composable
fun MoveToNotebookDialog(
    current: String?,
    notebooks: List<String>,
    icons: Map<String, String>,
    iconFile: (String?) -> File?,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NewNotebookDialog(
            notebooks = notebooks,
            onCreate = onMove,
            onDismiss = { creating = false }
        )
        return
    }
    val currentName = current?.takeIf { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover a cuaderno") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    NotebookRow(
                        icon = Icons.Filled.Add,
                        label = "Nuevo cuaderno",
                        selected = false,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { creating = true }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    NotebookRow(
                        icon = Icons.Filled.Notes,
                        label = "Notas",
                        selected = currentName == null,
                        onClick = { onMove(null) }
                    )
                }
                items(notebooks) { name ->
                    NotebookRow(
                        label = name,
                        selected = name == currentName,
                        onClick = { onMove(name) },
                        leading = { tint ->
                            NotebookIcon(icons[name], iconFile(icons[name]), tint = tint, name = name)
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun NotebookRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color? = null
) {
    NotebookRow(label, selected, onClick, accent) { tint ->
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
private fun NotebookRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color? = null,
    leading: @Composable (tint: Color) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        selected -> colors.onSecondaryContainer
        accent != null -> accent
        else -> colors.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.background(colors.secondaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        leading(accent ?: content.copy(alpha = 0.8f))
        Text(
            label,
            color = content,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Cuaderno actual", tint = content)
        } else {
            Spacer(Modifier.width(24.dp))
        }
    }
}

/**
 * Ícono de un cuaderno: su emoji, su imagen cargada o, si no tiene (o la imagen no se puede
 * dibujar), la inicial de [name] en un cuadrado gris. Sin [name], el libro.
 */
@Composable
fun NotebookIcon(
    icon: String?,
    file: File?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    name: String? = null
) {
    val context = LocalContext.current
    val emoji = emojiOf(icon)
    var fileFailed by remember(file) { mutableStateOf(file?.exists() == false) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            file != null && !fileFailed -> AsyncImage(
                model = file,
                imageLoader = remember(context) { NoteIconLoader.get(context) },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { fileFailed = true },
                modifier = Modifier.size(size)
            )
            emoji != null -> Text(
                emoji,
                fontSize = with(LocalDensity.current) { (size * 0.85f).toSp() },
                lineHeight = with(LocalDensity.current) { size.toSp() }
            )
            name != null -> LetterIcon(name, size)
            else -> Icon(
                Icons.Filled.AutoStories,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size)
            )
        }
    }
}

/** La inicial de [name] (o «S» de «Sin título») en un cuadrado gris. */
@Composable
fun LetterIcon(name: String, size: Dp) {
    val letter = name.trim().firstOrNull()?.uppercase() ?: "S"
    val density = LocalDensity.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 5))
            .background(LETTER_BACKGROUND),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            color = LETTER_COLOR,
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { (size * 0.6f).toSp() },
            lineHeight = with(density) { (size * 0.6f).toSp() }
        )
    }
}

private val LETTER_BACKGROUND = Color(0xFF8A8A8A)
private val LETTER_COLOR = Color(0xFFE9E9E9)

@Composable
internal fun NewNotebookDialog(
    notebooks: List<String>,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Crear y mover"
) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val create = {
        resolveNotebookName(name, notebooks)?.let(onCreate)
        Unit
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo cuaderno") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nombre") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = create, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
