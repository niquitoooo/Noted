package com.noted.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.io.File
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Un aviso de abajo; con [undo], lleva el botón «Deshacer». Con [place] (un cuaderno, o
 * [NOTAS_PLACE]), después de [text] van el ícono y el nombre de ese lugar.
 */
data class UiMessage(val text: String, val undo: (() -> Unit)? = null, val place: String? = null)

/** Lo que se le pasa al aviso: el texto completo (para TalkBack) y, aparte, el lugar. */
private class AppVisuals(
    val text: String,
    val place: String?,
    override val actionLabel: String?,
    override val duration: SnackbarDuration,
) : SnackbarVisuals {
    override val message: String = if (place == null) text else "$text ${place.ifEmpty { "Notas" }}"
    override val withDismissAction: Boolean = false
}

/** Cuánto dura un aviso con «Deshacer»: lo justo para tocarlo. */
private const val UNDO_MS = 5000L

/** Muestra [message] y, si se tocó «Deshacer» a tiempo, lo deshace. */
suspend fun SnackbarHostState.show(message: UiMessage) {
    val undo = message.undo
    if (undo == null) {
        showSnackbar(AppVisuals(message.text, message.place, null, SnackbarDuration.Short))
        return
    }
    val result = withTimeoutOrNull(UNDO_MS) {
        showSnackbar(AppVisuals(message.text, message.place, "Deshacer", SnackbarDuration.Indefinite))
    }
    if (result == SnackbarResult.ActionPerformed) undo()
}

/** El aviso de la app: «Deshacer» va en rojo, con contorno y letra chica. */
@Composable
fun AppSnackbar(
    data: SnackbarData,
    icons: Map<String, String> = emptyMap(),
    iconFile: (String?) -> File? = { null },
) {
    val background = SnackbarDefaults.color
    // Rojo que se lee sobre el fondo del aviso (claro u oscuro según el tema).
    val red = if (background.luminance() > 0.5f) Color(0xFFD32F2F) else Color(0xFFFF6B6B)
    val label = data.visuals.actionLabel
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = label?.let {
            {
                OutlinedButton(
                    onClick = { data.performAction() },
                    border = BorderStroke(1.dp, red),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = red),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.padding(end = 4.dp).heightIn(min = 30.dp)
                ) {
                    Text(it.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) {
        val visuals = data.visuals as? AppVisuals
        val place = visuals?.place
        if (place == null) {
            Text(data.visuals.message)
        } else {
            // «Movida a» + ícono + nombre del cuaderno, como se ve en el menú. Un solo texto con
            // el ícono adentro: así el aviso mide lo mismo que uno de un renglón.
            val tint = LocalContentColor.current
            Text(
                buildAnnotatedString {
                    append(visuals.text)
                    append(" ")
                    appendInlineContent("lugar")
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(place.ifEmpty { "Notas" }) }
                },
                inlineContent = mapOf(
                    "lugar" to InlineTextContent(
                        Placeholder(18.sp, 18.sp, PlaceholderVerticalAlign.TextCenter)
                    ) { PlaceIcon(place, icons, iconFile, size = 18.dp, tint = tint) }
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
