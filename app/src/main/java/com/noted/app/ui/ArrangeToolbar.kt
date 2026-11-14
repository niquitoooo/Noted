package com.noted.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noted.app.ui.rich.BarFormat
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

fun ToolbarItem.icon(): ImageVector = when (this) {
    ToolbarItem.Undo -> Icons.AutoMirrored.Filled.Undo
    ToolbarItem.Redo -> Icons.AutoMirrored.Filled.Redo
    ToolbarItem.Bold -> Icons.Filled.FormatBold
    ToolbarItem.Italic -> Icons.Filled.FormatItalic
    ToolbarItem.Strike -> Icons.Filled.StrikethroughS
    ToolbarItem.Heading -> Icons.Filled.Title
    ToolbarItem.Bullet -> Icons.AutoMirrored.Filled.FormatListBulleted
    ToolbarItem.Ordered -> Icons.Filled.FormatListNumbered
    ToolbarItem.Check -> Icons.Filled.CheckBox
    ToolbarItem.Quote -> Icons.Filled.FormatQuote
    ToolbarItem.Toggle -> Icons.Filled.ExpandMore
    ToolbarItem.Code -> Icons.Filled.Code
    ToolbarItem.Link -> Icons.Filled.Link
    ToolbarItem.ChildNote -> Icons.AutoMirrored.Filled.NoteAdd
    ToolbarItem.Divider -> Icons.Filled.Remove
    ToolbarItem.Attach -> Icons.Filled.AttachFile
}

/** El formato que marca el botón como apretado, si tiene uno. */
fun ToolbarItem.barFormat(): BarFormat? = when (this) {
    ToolbarItem.Bold -> BarFormat.Bold
    ToolbarItem.Italic -> BarFormat.Italic
    ToolbarItem.Strike -> BarFormat.Strike
    ToolbarItem.Heading -> BarFormat.Heading
    ToolbarItem.Bullet -> BarFormat.Bullet
    ToolbarItem.Ordered -> BarFormat.Ordered
    ToolbarItem.Check -> BarFormat.Check
    ToolbarItem.Quote -> BarFormat.Quote
    ToolbarItem.Code -> BarFormat.Code
    else -> null
}

/**
 * «Organizar»: diálogo centrado del 75 % de la pantalla, «Restablecer»,
 * título y «Hecho» arriba, y una fila por botón con su ícono, su nombre y la agarradera. El
 * arrastre empieza al apoyar el dedo en la agarradera y se guarda al soltar; «Restablecer» vuelve
 * al de fábrica sin preguntar ni cerrar.
 *
 * Un interruptor por fila que muestra u
 * oculta el botón en la barra. Un botón oculto conserva su lugar y se ve atenuado.
 */
@Composable
fun ArrangeToolbarDialog(
    layout: ToolbarLayout,
    onChange: (ToolbarLayout) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val colors = MaterialTheme.colorScheme
            // Mientras se arrastra, el orden es local; al soltar se guarda.
            var order by remember { mutableStateOf(layout.order) }
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(layout.order) { if (!dragging) order = layout.order }
            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
            }
            Surface(
                color = colors.background,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .width(maxWidth * 0.75f)
                    .height(maxHeight * 0.75f)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp)
                    ) {
                        TextButton(onClick = { onChange(ToolbarLayout.DEFAULT) }) {
                            Text("Restablecer", fontSize = 16.sp, color = colors.primary)
                        }
                        Text(
                            "Organizar",
                            fontSize = 18.sp,
                            color = colors.onBackground,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismiss) {
                            Text("Hecho", fontSize = 16.sp, color = colors.primary)
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(order, key = { it.key }) { item ->
                            ReorderableItem(reorderState, key = item.key) { isDragging ->
                                val shown = item !in layout.hidden
                                Column(
                                    Modifier.background(
                                        if (isDragging) colors.surfaceContainerHigh else colors.background
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(40.dp).alpha(if (shown) 1f else 0.4f)
                                        ) {
                                            Icon(
                                                item.icon(),
                                                contentDescription = null,
                                                tint = colors.onSurfaceVariant,
                                                modifier = Modifier.size(21.dp)
                                            )
                                        }
                                        Text(
                                            item.label,
                                            fontSize = 16.sp,
                                            color = colors.onBackground,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 10.dp)
                                                .alpha(if (shown) 1f else 0.4f)
                                        )
                                        Switch(
                                            checked = shown,
                                            onCheckedChange = { onChange(layout.copy(order = order).toggled(item)) }
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .draggableHandle(
                                                    onDragStarted = { dragging = true },
                                                    onDragStopped = {
                                                        dragging = false
                                                        onChange(layout.copy(order = order))
                                                    }
                                                )
                                        ) {
                                            Icon(
                                                Icons.Filled.DragHandle,
                                                contentDescription = "Mover ${item.label}",
                                                tint = colors.outline
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = colors.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
