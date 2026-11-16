package com.noted.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Los colores de la grilla, en orden: la clave que se guarda en la nota, el nombre y el tono del círculo. */
private data class GridColor(val key: String, val name: String, val text: Color, val highlight: Color)

private val GRID_COLORS = listOf(
    GridColor("red", "Rojo", Color(0xFFF9241C), Color(0xFFFF918D)),
    GridColor("orange", "Naranja", Color(0xFFF98700), Color(0xFFFEC382)),
    GridColor("yellow", "Amarillo", Color(0xFFFFCB56), Color(0xFFFFEC87)),
    GridColor("green", "Verde", Color(0xFF4EAE21), Color(0xFF9FEB80)),
    GridColor("blue", "Azul", Color(0xFF2070F7), Color(0xFF8BC9FD)),
    GridColor("pink", "Rosa", Color(0xFFFF7BFD), Color(0xFFEFA7D9)),
    GridColor("purple", "Violeta", Color(0xFF8F58FF), Color(0xFFD2A7EF)),
    GridColor("gray", "Gris", Color(0xFF7D7D7D), Color(0xFFC2C2C2)),
)

private val NO_COLOR = Color(0xFFB5B5B5)

/**
 * «Color del texto» y «Resaltar»: una grilla de 3 x 3 sin textos con los ocho colores y «sin
 * color» al final. No marca el color actual. Elegir uno llama a [onPick] (`null` es «sin
 * color») y cierra. Mide 579.8 dp de ancho, con celdas de 49.9 dp de alto y círculos de 26 dp;
 * en una pantalla más angosta ocupa el ancho menos 24 dp de cada lado.
 */
@Composable
fun ColorGridDialog(highlight: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.32f) }
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val background = if (dark) Color(0xFF1E1E1E) else Color.White
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val width = minOf(579.8.dp, maxWidth - 48.dp)
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(6.4.dp))
                    .background(background)
            ) {
                val cells = GRID_COLORS.map<GridColor, GridColor?> { it } + null
                for (row in cells.chunked(3)) {
                    Row(Modifier.height(49.9.dp)) {
                        for (cell in row) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(49.9.dp)
                                    .clickable { onPick(cell?.key) }
                                    .semantics { contentDescription = cell?.name ?: "Sin color" }
                            ) {
                                if (cell != null) {
                                    Box(
                                        Modifier
                                            .size(26.dp)
                                            .background(if (highlight) cell.highlight else cell.text, CircleShape)
                                    )
                                } else {
                                    NoColorIcon(background)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** «Sin color»: un círculo gris cruzado por una barra, separada del círculo por el color del fondo. */
@Composable
private fun NoColorIcon(background: Color) {
    Canvas(Modifier.size(27.dp)) {
        val radius = 11.dp.toPx()
        drawCircle(NO_COLOR, radius)
        val reach = size.minDimension / 2 - 1.dp.toPx()
        val a = Offset(center.x - reach * 0.82f, center.y - reach * 0.82f)
        val b = Offset(center.x + reach * 0.82f, center.y + reach * 0.82f)
        drawLine(background, a, b, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(NO_COLOR, a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}
