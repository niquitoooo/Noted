package com.jot.app.ui.emoji

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selector «Cambiar icono»: hoja de abajo con buscador, grilla de emoji por categoría
 * y barra de acciones. Medidas propias.
 *
 * Es un Dialog propio y no un ModalBottomSheet: la hoja de Material mete imePadding y el
 * teclado la achicaría; el teclado tapa la barra de abajo.
 */
@Composable
fun EmojiPickerSheet(
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val fondo = if (dark) Color(0xFF1F1F1F) else Color(0xFFFFFFFF)
    val textoPrimario = if (dark) Color(0xFFF3F3F3) else Color(0xFF252525)
    val textoSecundario = if (dark) Color(0xFF909090) else Color(0xFF949494)
    val textoTerciario = if (dark) Color(0xFF646464) else Color(0xFFBFBFBF)
    val glifo = if (dark) Color(0xFF7B7B7B) else Color(0xFFA7A7A7)
    val rojo = Color(0xFFF55522)
    val cajaBusqueda = if (dark) Color(0x24DAD7CA) else Color(0x14535353)
    val agarradera = if (dark) Color(0xFF3A3A3A) else Color(0xFFE3E3E3)
    val gris = Color(0xFFACA996)

    val catalog by produceState<EmojiCatalog?>(null) {
        value = withContext(Dispatchers.Default) { EmojiCatalog.load(context) }
    }

    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isEmpty()) {
            applied = ""
        } else {
            delay(300)
            applied = query
        }
    }
    // Mientras el catálogo carga o el texto escrito todavía no se aplicó (debounce), se ve la rueda.
    val ocupado = catalog == null || query != applied

    val porCategoria = remember(catalog) { catalog?.byCategory().orEmpty() }
    val resultados = remember(catalog, applied) {
        if (applied.isBlank()) emptyList() else catalog?.search(applied).orEmpty()
    }

    val lleno = query.isEmpty()
    // La ventana del Dialog no recibe los insets de las barras del sistema: se leen de la
    // actividad (con Compat, para API < 30; la ventana llega bajo la barra de navegación, así que la barra de abajo se sube ese alto) y se vuelven a leer al rotar.
    val config = LocalConfiguration.current
    val dens = LocalDensity.current
    val (navDp, estadoDp) = remember(config, dens) {
        val raiz = context.findActivity()?.window?.decorView
        val ins = raiz?.let { ViewCompat.getRootWindowInsets(it) }
        val nav = ins?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val est = ins?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        with(dens) { nav.toDp() to est.toDp() }
    }

    val alcance = rememberCoroutineScope()
    val desplazamiento = remember { Animatable(0f) }
    var alturaHoja by remember { mutableIntStateOf(0) }
    val umbral = with(dens) { 120.dp.toPx() }
    val velocidadCierre = with(dens) { 700.dp.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Ajustes de ventana: una sola vez. Sin dim propio (el scrim es el de abajo, 0.32),
        // sin elevación, a pantalla completa y sin que el teclado la mueva.
        val vista = LocalView.current
        // Alto de la barra de navegación: el mayor entre lo que informa la actividad y lo que
        // recibe la ventana del Dialog (en el Pixel la actividad puede no informarlo).
        val navAbajo = maxOf(navDp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        // Con el teclado abierto (buscando), la hoja se apoya arriba del teclado y ocupa el alto
        // que queda: si no, el teclado tapaba los resultados.
        val tecladoAbajo = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val teclado = tecladoAbajo > 0.dp
        val alto = lleno || teclado
        DisposableEffect(vista) {
            (vista.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                w.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0f)
                // La ventana del Dialog pinta su propio fondo detrás de la barra de navegación
                // (negro): tapaba la barra de abajo en el Pixel. Transparente, como la actividad.
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
                // La ventana medía toda la pantalla pero Android la centraba en el espacio libre
                // entre las barras del sistema: en el Pixel quedaba 50 px corrida hacia abajo y la
                // barra de abajo se cortaba. Se ancla arriba y ocupa la pantalla entera.
                w.attributes = w.attributes.apply {
                    gravity = android.view.Gravity.TOP
                    if (android.os.Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        layoutInDisplayCutoutMode =
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                w.decorView.elevation = 0f
                (vista.parent as? android.view.View)?.elevation = 0f
            }
            onDispose { }
        }
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    paneTitle = "Cambiar icono"
                    onClick(label = "Cerrar") { onDismiss(); true }
                }
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(top = estadoDp, bottom = tecladoAbajo),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .then(if (alto) Modifier.fillMaxHeight() else Modifier.heightIn(min = 482.dp))
                    .offset { IntOffset(0, desplazamiento.value.toInt()) }
                    .onSizeChanged { alturaHoja = it.height }
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(fondo)
                    .semantics { dismiss { onDismiss(); true } }
                    // Traga los toques de la hoja para que no lleguen al scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // Zona de arrastre: agarradera y título (V5/V6). Cierra por distancia o por fling.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            customActions = listOf(CustomAccessibilityAction("Cerrar") { onDismiss(); true })
                        }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                alcance.launch {
                                    desplazamiento.snapTo((desplazamiento.value + delta).coerceAtLeast(0f))
                                }
                            },
                            onDragStopped = { velocidad ->
                                if (desplazamiento.value > umbral || velocidad > velocidadCierre) {
                                    desplazamiento.animateTo(alturaHoja.toFloat(), tween(150))
                                    onDismiss()
                                } else {
                                    desplazamiento.animateTo(0f, tween(180))
                                }
                            },
                        ),
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            Modifier
                                .size(width = 48.dp, height = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(agarradera)
                        )
                    }
                    // V5 / V6: barra de título de 48 dp
                    Box(Modifier.fillMaxWidth().height(48.dp)) {
                        Text(
                            "Cambiar icono",
                            color = textoPrimario,
                            fontSize = 17.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .height(48.dp)
                                .clickable(role = Role.Button) { onRemove() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Borrar", color = rojo, fontSize = 17.sp)
                        }
                    }
                }
                Box(if (alto) Modifier.weight(1f) else Modifier.heightIn(min = 408.dp)) {
                    Column(if (alto) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                        // Buscador
                        Row(
                            Modifier
                                .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cajaBusqueda),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Search, null, tint = glifo,
                                modifier = Modifier.padding(start = 8.dp).size(24.dp),
                            )
                            Box(
                                Modifier.weight(1f).padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (query.isEmpty()) {
                                    Text("Filtrar", color = textoTerciario, fontSize = 17.sp)
                                }
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = textoPrimario, fontSize = 17.sp),
                                    cursorBrush = SolidColor(textoPrimario),
                                    modifier = Modifier.fillMaxWidth()
                                        .semantics { contentDescription = "Filtrar" },
                                )
                            }
                            Box(
                                Modifier.padding(end = 8.dp).size(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    ocupado -> CircularProgressIndicator(
                                        color = gris, strokeWidth = 2.dp, modifier = Modifier.size(20.dp),
                                    )
                                    query.isNotEmpty() -> Icon(
                                        Icons.Filled.Cancel, "Limpiar", tint = gris,
                                        modifier = Modifier.size(24.dp).clickable { query = "" },
                                    )
                                }
                            }
                        }
                        // V12 / V13: grilla
                        val emojiSp = with(dens) { 34.dp.toSp() }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 165.dp),
                            modifier = if (alto) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                        ) {
                            if (applied.isBlank()) {
                                porCategoria.forEach { (cat, lista) ->
                                    item(key = "h$cat", span = { GridItemSpan(maxLineSpan) }) {
                                        Text(
                                            EMOJI_CATEGORIES.getOrElse(cat) { "" },
                                            color = textoSecundario,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            fontWeight = FontWeight.Medium,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        )
                                    }
                                    items(lista, key = { "e$cat${it.emoji}" }) { e ->
                                        CeldaEmoji(e.emoji, emojiSp) { onPick(e.emoji) }
                                    }
                                }
                            } else {
                                items(resultados, key = { "r${it.emoji}" }) { e ->
                                    CeldaEmoji(e.emoji, emojiSp) { onPick(e.emoji) }
                                }
                            }
                        }
                    }
                    // Barra de abajo. Se queda con todos los toques: si no, un toque en
                    // «Emoji» (que no hace nada) pasaba a la grilla de atrás y elegía el emoji
                    // que estaba debajo.
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(fondo)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                                    }
                                }
                            }
                            .padding(bottom = if (teclado) 0.dp else navAbajo)
                            .height(48.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        PestanaActiva("Emoji", textoPrimario, Modifier.weight(1f))
                        BotonBarra("Cargar", glifo, Modifier.weight(1f)) { onUpload() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CeldaEmoji(emoji: String, tamano: TextUnit, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .semantics { contentDescription = emoji }
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = tamano, lineHeight = tamano)
    }
}

@Composable
private fun BotonBarra(texto: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(48.dp).clickable(role = Role.Button) { onClick() }, contentAlignment = Alignment.Center) {
        Text(texto, color = color, fontSize = 17.sp)
    }
}

/** La pestaña activa: no hace nada al tocarla, así que es texto, sin ripple ni acción. */
@Composable
private fun PestanaActiva(texto: String, color: Color, modifier: Modifier) {
    Box(modifier.height(48.dp), contentAlignment = Alignment.Center) {
        Text(texto, color = color, fontSize = 17.sp)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
