package com.noted.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.noted.app.data.NotesRepository
import java.io.File

/** Alto de la franja de portada, 188 dp. */
val COVER_HEIGHT = 188.dp

/** Degradados de portada, de arriba a abajo. */
val COVER_GRADIENTS: List<Pair<String, List<Color>>> = listOf(
    "sky" to listOf(Color(0xFF6EB6E4), Color(0xFFA4CFEC), Color(0xFFDAEAF3)),
    "pinkOrange" to listOf(Color(0xFFD8A4E1), Color(0xFFFDD0CD), Color(0xFFFFCC81)),
    "greenOrange" to listOf(Color(0xFF63B3CB), Color(0xFFC5D3AC), Color(0xFFF6C47A)),
    "bluePink" to listOf(Color(0xFF73B7F0), Color(0xFFABB6ED), Color(0xFFF3BFAC)),
    "yellow" to listOf(Color(0xFFFFB522), Color(0xFFECD91B)),
    "red" to listOf(Color(0xFFF55522), Color(0xFFE51CA0)),
    "blue" to listOf(Color(0xFFAB50CC), Color(0xFF3E58EB)),
    "teal" to listOf(Color(0xFF2AA7EE), Color(0xFF0FC8BA)),
)

/** Colores sólidos de portada. */
val COVER_COLORS: List<Color> = listOf(
    Color(0xFFFBE885), Color(0xFFF5B748), Color(0xFFE46036), Color(0xFFE6B1A4),
    Color(0xFF611A36), Color(0xFF376BE1), Color(0xFF97CCEF), Color(0xFF9FB0B6),
    Color(0xFF336C45), Color(0xFFE9E9E9), Color(0xFFB5B5B5), Color(0xFF2C2B28),
)

private fun colorValue(color: Color): String =
    NotesRepository.COVER_COLOR + "#%06X".format(0xFFFFFF and (color.value shr 32).toInt())

private fun parseColor(value: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(value.removePrefix(NotesRepository.COVER_COLOR)))
}.getOrNull()

/**
 * La portada: color, degradado o imagen. La imagen cubre la franja y se encuadra con
 * [x]/[y] (qué parte de lo que sobra queda a la izquierda / arriba) y [scale] (zoom).
 */
@Composable
fun CoverView(
    cover: String,
    file: File?,
    x: Float,
    y: Float,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier.clipToBounds()) {
        when {
            cover.startsWith(NotesRepository.COVER_GRADIENT) -> {
                val colors = COVER_GRADIENTS.firstOrNull { it.first == cover.removePrefix(NotesRepository.COVER_GRADIENT) }?.second
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors ?: listOf(Color.Gray, Color.DarkGray))))
            }
            cover.startsWith(NotesRepository.COVER_COLOR) ->
                Box(Modifier.fillMaxSize().background(parseColor(cover) ?: Color.Gray))
            file != null -> AsyncImage(
                model = file,
                contentDescription = "Portada",
                contentScale = ContentScale.Crop,
                // Con Crop + este alineado, la imagen ya queda corrida según x/y; el zoom se hace
                // alrededor de ese mismo punto, así lo que sobra se reparte igual.
                alignment = BiasAlignment(x * 2 - 1, y * 2 - 1),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(x, y)
                    }
            )
        }
    }
}

/**
 * «Cambiar portada», una hoja con «Borrar», degradados, colores
 * sólidos y abajo «Cargar» (y «Ajustar» si la portada es una imagen).
 */
@Composable
fun CoverPickerSheet(
    canAdjust: Boolean,
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
    onUpload: () -> Unit,
    onAdjust: () -> Unit,
    onDismiss: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val fondo = if (dark) Color(0xFF1F1F1F) else Color(0xFFFFFFFF)
    val texto = if (dark) Color(0xFFF3F3F3) else Color(0xFF252525)
    val secundario = if (dark) Color(0xFF909090) else Color(0xFF949494)
    val glifo = if (dark) Color(0xFF7B7B7B) else Color(0xFFA7A7A7)
    val agarradera = if (dark) Color(0xFF3A3A3A) else Color(0xFFE3E3E3)
    val (barraArriba, barraAbajo) = activityBars()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        val arriba = maxOf(barraArriba, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
        val abajo = maxOf(barraAbajo, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = arriba + 64.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(fondo)
                    .clickable(interactionSource = null, indication = null, onClick = {})
                    .padding(bottom = abajo)
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 48.dp, height = 4.dp).clip(RoundedCornerShape(6.dp)).background(agarradera))
                }
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    Text(
                        "Cambiar portada",
                        color = texto,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Box(
                        Modifier.align(Alignment.CenterEnd).height(48.dp).clickable { onRemove() }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Borrar", color = Color(0xFFF55522), fontSize = 17.sp) }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                ) {
                    item(span = { GridItemSpan(2) }) { SectionHeader("Degradados", secundario) }
                    items(COVER_GRADIENTS) { (key, colors) ->
                        Box(
                            Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(4.dp))
                                .background(Brush.verticalGradient(colors))
                                .clickable { onPick(NotesRepository.COVER_GRADIENT + key) }
                        )
                    }
                    item(span = { GridItemSpan(2) }) { SectionHeader("Colores sólidos", secundario) }
                    items(COVER_COLORS) { color ->
                        Box(
                            Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(4.dp))
                                .background(color)
                                .clickable { onPick(colorValue(color)) }
                        )
                    }
                    item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(8.dp)) }
                }
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    BarButton("Cargar", glifo, Modifier.weight(1f), onUpload)
                    if (canAdjust) BarButton("Ajustar", glifo, Modifier.weight(1f), onAdjust)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp)
    )
}

@Composable
private fun BarButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(48.dp).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 17.sp)
    }
}

/**
 * Ajustar la portada de imagen: se mueve con un dedo y se agranda o achica pellizcando,
 * siempre cubriendo la franja. «Listo» devuelve el encuadre; «Cancelar» no cambia nada.
 */
@Composable
fun CoverAdjustDialog(
    file: File,
    x: Float,
    y: Float,
    scale: Float,
    onDone: (x: Float, y: Float, scale: Float) -> Unit,
    onCancel: () -> Unit
) {
    var cx by remember { mutableFloatStateOf(x) }
    var cy by remember { mutableFloatStateOf(y) }
    var cs by remember { mutableFloatStateOf(scale) }
    var imageSize by remember { mutableStateOf<Size?>(null) }
    val context = LocalContext.current
    val (barraArriba, _) = activityBars()
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        val arriba = maxOf(barraArriba, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
        Column(Modifier.fillMaxSize().background(Color(0xCC000000)).padding(top = arriba)) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF131313)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.clickable { onCancel() }.padding(12.dp)) { Text("Cancelar", color = Color(0xFFE6E1E5), fontSize = 16.sp) }
                Text(
                    "Ajustar portada",
                    color = Color(0xFFCAC4D0),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.clickable { onDone(cx, cy, cs) }.padding(12.dp)) {
                    Text("Listo", color = Color(0xFFD0BCFF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(COVER_HEIGHT)) {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(imageSize) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val size = imageSize ?: return@detectTransformGestures
                                cs = (cs * zoom).coerceIn(1f, NotesRepository.MAX_COVER_ZOOM)
                                // Tamaño de la imagen en pantalla: lo justo para cubrir, por el zoom.
                                val fit = maxOf(boxW / size.width, boxH / size.height)
                                val extraW = size.width * fit * cs - boxW
                                val extraH = size.height * fit * cs - boxH
                                if (extraW > 1f) cx = (cx - pan.x / extraW).coerceIn(0f, 1f)
                                if (extraH > 1f) cy = (cy - pan.y / extraH).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(context).data(file).build(),
                            contentDescription = "Portada",
                            contentScale = ContentScale.Crop,
                            alignment = BiasAlignment(cx * 2 - 1, cy * 2 - 1),
                            onSuccess = { state -> imageSize = state.painter.intrinsicSize },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = cs
                                    scaleY = cs
                                    transformOrigin = TransformOrigin(cx, cy)
                                }
                        )
                    }
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.OpenWith, contentDescription = null, tint = Color.White)
                        Text(
                            "  Mové con un dedo · pellizcá para el zoom",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                    // Marco blanco de la franja.
                    Box(Modifier.fillMaxSize().background(Color.Transparent).padding(0.dp)) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White).align(Alignment.TopCenter))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White).align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

/**
 * Encabezado de una nota con portada: la franja a todo el ancho de la página (sale del margen
 * de la columna) y el ícono, sin marco ni caja, con su centro justo en el borde de abajo.
 */
@Composable
fun CoverHeader(
    note: com.noted.app.data.Note,
    cover: String,
    vm: NotesViewModel,
    onCoverClick: () -> Unit,
    onIconClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconSize = 80.dp
    val emoji = emojiOf(note.icon)
    val iconFile = if (emoji == null) vm.iconFile(note) else null
    val hasIcon = emoji != null || iconFile != null || note.icon?.startsWith(NotesRepository.ICON_BUILTIN) == true
    Box(
        Modifier
            .fillMaxWidth()
            // La columna tiene 20 dp de margen a los lados y 12 arriba: la franja los ocupa.
            .layout { measurable, constraints ->
                val side = 20.dp.roundToPx()
                val top = 12.dp.roundToPx()
                val width = constraints.maxWidth + side * 2
                val placeable = measurable.measure(
                    constraints.copy(minWidth = width, maxWidth = width)
                )
                layout(constraints.maxWidth, placeable.height - top) {
                    placeable.place(-side, -top)
                }
            }
            .height(COVER_HEIGHT + if (hasIcon) iconSize / 2 else 0.dp)
    ) {
        CoverView(
            cover = cover,
            file = vm.coverFile(note),
            x = note.coverX,
            y = note.coverY,
            scale = note.coverScale,
            // El toque en la portada es suyo: abre la foto (y no manda al final de la nota).
            modifier = Modifier
                .fillMaxWidth()
                .height(COVER_HEIGHT)
                .clickable(interactionSource = null, indication = null, onClick = onCoverClick)
        )
        if (hasIcon) {
            Box(
                Modifier
                    .padding(start = 20.dp + 4.dp, top = COVER_HEIGHT - iconSize / 2)
                    .size(iconSize),
                contentAlignment = Alignment.Center
            ) {
                when {
                    emoji != null -> Text(emoji, fontSize = with(density) { (iconSize * 0.8f).toSp() }, lineHeight = with(density) { iconSize.toSp() })
                    iconFile != null -> AsyncImage(
                        model = iconFile,
                        imageLoader = remember(context) { NoteIconLoader.get(context) },
                        contentDescription = "Ícono de la nota",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(interactionSource = null, indication = null, onClick = onIconClick)
                    )
                    else -> NoteIcon(note = note, iconFile = null, size = iconSize * 0.7f)
                }
            }
        }
    }
}

/** Alto de la barra de estado y de la de navegación, medidos en la actividad (fuera del Dialog). */
@Composable
private fun activityBars(): Pair<Dp, Dp> =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() to
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * La ventana del Dialog medía toda la pantalla pero Android la centraba entre las barras del
 * sistema: en el Pixel quedaba corrida hacia abajo y lo de abajo se cortaba (igual que pasó con el
 * selector de emojis). Se ancla arriba y ocupa la pantalla entera.
 */
@Composable
private fun FullScreenDialogWindow() {
    val vista = LocalView.current
    DisposableEffect(vista) {
        (vista.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            w.attributes = w.attributes.apply {
                gravity = android.view.Gravity.TOP
                if (android.os.Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        onDispose { }
    }
}

/** Una foto (portada o ícono) a pantalla completa, con el mismo visor de las fotos adjuntas. */
@Composable
fun PhotoViewer(file: File, title: String, onBack: () -> Unit) {
    val (barraArriba, barraAbajo) = activityBars()
    val attachment = remember(file) {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        com.noted.app.data.Attachment(
            id = "foto",
            noteId = "",
            name = title,
            mime = mime,
            size = file.length(),
            storedName = file.name,
            createdAt = file.lastModified()
        )
    }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        // Lo que la ventana del Dialog no informa de las barras (en el Pixel), se completa a mano.
        val faltaArriba = (barraArriba - WindowInsets.statusBars.asPaddingValues().calculateTopPadding()).coerceAtLeast(0.dp)
        val faltaAbajo = (barraAbajo - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).coerceAtLeast(0.dp)
        Box(Modifier.fillMaxSize().background(Color.Black).padding(top = faltaArriba, bottom = faltaAbajo)) {
            AttachmentViewerScreen(attachment = attachment, file = file, onBack = onBack)
        }
    }
}
