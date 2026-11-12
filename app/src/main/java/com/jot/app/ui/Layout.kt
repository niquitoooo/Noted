package com.jot.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Columna de página centrada con un ancho máximo legible.
 *
 * En teléfonos el ancho máximo no se alcanza y no cambia nada. En tablets
 * (sobre todo en horizontal) evita que el texto y los adjuntos se estiren de
 * borde a borde y quedan con márgenes cómodos.
 */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 900.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .fillMaxHeight(),
            content = content
        )
    }
}
