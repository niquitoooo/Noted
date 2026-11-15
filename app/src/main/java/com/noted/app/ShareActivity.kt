package com.noted.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noted.app.data.NotesRepository
import com.noted.app.data.SharedLink
import com.noted.app.ui.NotedTheme
import kotlinx.coroutines.delay

/**
 * Compartir texto o un link con Noted: un
 * diálogo encima de la app de origen, sin abrir Noted. «Guardar» crea la nota y muestra el aviso
 * «Se guardó en Noted» con «Ver»; Atrás o tocar afuera cierran sin crear nada. Las
 * Se puede elegir el cuaderno.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Un archivo de texto compartido (sin texto, con un adjunto) va por el camino de siempre.
        if (intent.getCharSequenceExtra(Intent.EXTRA_TEXT).isNullOrBlank() && hasStream(intent)) {
            startActivity(Intent(intent).setClass(this, MainActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        val shared = SharedLink(
            subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString().orEmpty(),
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
        )
        val repository = NotesRepository.get(applicationContext)
        setContent {
            NotedTheme {
                ShareScreen(
                    shared = shared,
                    repository = repository,
                    onClose = { finish() },
                    onView = { noteId ->
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_NOTE, noteId)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        finish()
                    },
                )
            }
        }
    }

    // Sin animación de entrada ni de salida de la ventana: solo se ve el diálogo.
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun hasStream(intent: Intent): Boolean {
        if ((intent.clipData?.itemCount ?: 0) > 0 && intent.clipData?.getItemAt(0)?.uri != null) return true
        @Suppress("DEPRECATION")
        return (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) != null
    }
}

private enum class SharePhase { Editing, Notebooks, Saved }

@Composable
private fun ShareScreen(
    shared: SharedLink,
    repository: NotesRepository,
    onClose: () -> Unit,
    onView: (String) -> Unit,
) {
    var phase by rememberSaveable { mutableStateOf(SharePhase.Editing) }
    var title by rememberSaveable { mutableStateOf(shared.subject) }
    var text by rememberSaveable { mutableStateOf(shared.text) }
    var notebook by rememberSaveable { mutableStateOf<String?>(null) }
    var savedId by rememberSaveable { mutableStateOf<String?>(null) }
    val order by repository.notebookOrder.collectAsStateWithLifecycle()
    val trashed by repository.notebooksTrashed.collectAsStateWithLifecycle()
    val notebooks = order.filterNot { name -> trashed.any { it.equals(name, ignoreCase = true) } }

    BackHandler {
        // Atrás en los cuadernos vuelve sin aplicar; en el diálogo, cierra sin guardar.
        if (phase == SharePhase.Notebooks) phase = SharePhase.Editing else onClose()
    }

    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                // Atenuado al 32 %; tocar afuera cierra o, con el aviso, lo saca.
                if (phase == SharePhase.Saved) Modifier
                else Modifier.background(Color.Black.copy(alpha = 0.32f))
            )
            .clickable(interactionSource = noRipple, indication = null) { onClose() }
            .statusBarsPadding()
            // El aviso va a 32 dp del borde de la pantalla, sin contar la barra de navegación.
            .then(if (phase == SharePhase.Saved) Modifier else Modifier.navigationBarsPadding().imePadding()),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            SharePhase.Editing -> ShareDialog(
                shared = shared,
                title = title,
                onTitle = { title = it },
                text = text,
                onText = { text = it },
                notebook = notebook,
                onNotebooks = { phase = SharePhase.Notebooks },
                onSave = {
                    val note = repository.saveShared(SharedLink(title, text), notebook)
                    savedId = note.id
                    phase = SharePhase.Saved
                },
            )
            SharePhase.Notebooks -> NotebookPicker(
                notebooks = notebooks,
                chosen = notebook,
                onAccept = {
                    notebook = it
                    phase = SharePhase.Editing
                },
            )
            SharePhase.Saved -> SavedNotice(onView = { savedId?.let(onView) ?: onClose() }, onTimeout = onClose)
        }
    }
}

/** El diálogo: encabezado con el título (y el sitio si es un link), texto y botones. */
@Composable
private fun ShareDialog(
    shared: SharedLink,
    title: String,
    onTitle: (String) -> Unit,
    text: String,
    onText: (String) -> Unit,
    notebook: String?,
    onNotebooks: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val titleStyle = TextStyle(color = colors.onSurface, fontSize = 19.5.sp, fontWeight = FontWeight.Bold)
    DialogCard {
        if (shared.host != null) {
            // Variante link: lugar de la imagen (vacío, 64 dp), título y sitio.
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.size(64.dp))
                Column(Modifier.weight(1f)) {
                    TitleField(title, onTitle, titleStyle, Modifier.padding(start = 14.dp, end = 14.dp, top = 21.7.dp))
                    Text(
                        shared.host ?: "",
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        letterSpacing = 0.25.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 18.3.dp, bottom = 1.3.dp),
                    )
                }
            }
        } else {
            // Variante texto: solo el título.
            TitleField(title, onTitle, titleStyle, Modifier.heightIn(min = 60.4.dp).padding(start = 14.dp, end = 14.dp, top = 19.dp, bottom = 9.dp))
        }
        HorizontalDivider(color = colors.outlineVariant)
        BasicTextField(
            value = text,
            onValueChange = onText,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, lineHeight = 1.1.em),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 14.dp, top = 15.5.dp, bottom = 14.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // El ícono abre los cuadernos.
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onNotebooks)
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Folder, contentDescription = "Elegir cuaderno", tint = colors.onSurfaceVariant)
                }
                if (notebook != null) {
                    Text(
                        notebook,
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp).padding(horizontal = 6.dp),
                    )
                }
            }
            // 88 dp de ancho.
            Button(onClick = onSave, contentPadding = PaddingValues(horizontal = 20.dp)) { Text("Guardar", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun TitleField(value: String, onValue: (String) -> Unit, style: TextStyle, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValue,
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text("Título", style = style.copy(color = colors.onSurfaceVariant.copy(alpha = 0.7f)))
                inner()
            }
        },
    )
}

/** Los cuadernos dentro del mismo diálogo. Uno solo. */
@Composable
private fun NotebookPicker(notebooks: List<String>, chosen: String?, onAccept: (String?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var marked by remember { mutableStateOf(chosen) }
    DialogCard {
        Text(
            "Cuadernos",
            color = colors.onSurface,
            fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 25.25.dp),
        )
        HorizontalDivider(color = colors.outlineVariant)
        LazyColumn(Modifier.heightIn(max = 560.dp).fillMaxWidth()) {
            // «Notas»: sin cuaderno, como en «Mover a cuaderno». Es la opción de partida.
            item(key = "__notas__") {
                PickerRow(Icons.AutoMirrored.Filled.Notes, "Notas", checked = marked == null) { marked = null }
            }
            items(notebooks, key = { it }) { name ->
                PickerRow(Icons.Outlined.Folder, name, checked = marked == name) { marked = name }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(end = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { onAccept(marked) }, contentPadding = PaddingValues(horizontal = 20.dp)) { Text("Aceptar", fontSize = 14.sp) }
        }
    }
}

/** Una fila del selector: ícono, nombre y casilla; una sola queda marcada. */
@Composable
private fun PickerRow(icon: ImageVector, name: String, checked: Boolean, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onPick)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        Text(
            name,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 20.dp),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { onPick() },
            colors = CheckboxDefaults.colors(uncheckedColor = colors.onSurfaceVariant),
        )
    }
}

/** El contenedor del diálogo: esquinas de 28 dp, 34,7 dp a los costados. */
@Composable
private fun DialogCard(content: @Composable () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .padding(horizontal = 34.7.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            // Tocar adentro no cierra.
            .clickable(interactionSource = noRipple, indication = null) {},
    ) {
        Column { content() }
    }
}

/** El aviso «Se guardó en Noted» con «Ver»: dura lo de un aviso largo y se cierra. */
@Composable
private fun SavedNotice(onView: () -> Unit, onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2750)
        onTimeout()
    }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = colors.inverseSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 32.dp).fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Se guardó en Noted", color = colors.inverseOnSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onView,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                ) { Text("Ver") }
            }
        }
    }
}
