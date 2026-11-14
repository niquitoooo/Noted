package com.noted.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import coil.compose.AsyncImage
import com.noted.app.ui.emoji.EmojiPickerSheet

/**
 * «Nuevo cuaderno»: título con ✕, ícono grande (la inicial
 * del nombre, o el emoji o la imagen elegidos) con su lápiz, campo «Nombre» y «Crear», que se
 * prende al escribir. El ícono se elige con nuestro selector (Emoji / Cargar).
 *
 * Siempre queda entero a la vista: se centra en el espacio que dejan libres las barras del
 * sistema y el teclado, así el teclado nunca tapa el campo ni «Crear».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CreateNotebookDialog(
    onCreate: (name: String, emoji: String?, image: Uri?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<Uri?>(null) }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val fieldArea = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    // Al abrirse el teclado (o al escribir) el campo vuelve a la vista.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom, name) { if (imeBottom > 0) fieldArea.bringIntoView() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            image = uri
            emoji = null
        }
    }
    val colors = MaterialTheme.colorScheme
    val canCreate = name.isNotBlank()
    val create = { if (canCreate) onCreate(name, emoji, image) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // Toda la pantalla: tocar afuera cierra; el cuadro va centrado en lo que deja el teclado.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .systemBarsPadding()
                .imePadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    // Traga los toques del cuadro para que no lo cierren.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                // Si no entra (apaisado con el teclado abierto), se desplaza; el campo y «Crear»
                // se traen a la vista al escribir.
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Nuevo cuaderno",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                            Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = colors.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Ícono de 96 dp con el lápiz de 32 dp abajo a la derecha, corrido 6 dp.
                    Box(Modifier.size(96.dp)) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { picking = true },
                            contentAlignment = Alignment.Center
                        ) {
                            val current = image
                            when {
                                current != null -> AsyncImage(
                                    model = current,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                emoji != null -> Box(
                                    Modifier.fillMaxSize().background(colors.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(emoji!!, fontSize = with(LocalDensity.current) { 56.dp.toSp() })
                                }
                                else -> LetterIcon(name, 96.dp)
                            }
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 6.dp, y = 6.dp)
                                .size(32.dp)
                                .shadow(8.dp, CircleShape)
                                .background(colors.surfaceContainerHighest, CircleShape)
                                .clickable { picking = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Elegir ícono",
                                tint = colors.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                    // Campo «Nombre» en forma de píldora.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(colors.surfaceContainerHighest)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (name.isEmpty()) {
                            Text("Nombre", fontSize = 16.sp, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 16.sp, color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { create() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onFocusEvent { if (it.isFocused) scope.launch { delay(300); fieldArea.bringIntoView() } }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // «Crear»: apagado hasta que haya un nombre. Es lo que se trae a la vista al
                    // escribir: queda abajo de todo y el campo, justo arriba.
                    Box(
                        Modifier
                            .bringIntoViewRequester(fieldArea)
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (canCreate) colors.onSurface else colors.surfaceContainerHigh)
                            .border(1.dp, Color.Transparent, RoundedCornerShape(24.dp))
                            .clickable(enabled = canCreate) { create() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Crear",
                            fontSize = 15.sp,
                            fontWeight = if (canCreate) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (canCreate) colors.surface else colors.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (picking) {
        EmojiPickerSheet(
            onPick = { picked ->
                emoji = picked
                image = null
                picking = false
            },
            onRemove = {
                emoji = null
                image = null
                picking = false
            },
            onUpload = {
                picking = false
                imagePicker.launch(arrayOf("image/*"))
            },
            onDismiss = { picking = false }
        )
    }
}
