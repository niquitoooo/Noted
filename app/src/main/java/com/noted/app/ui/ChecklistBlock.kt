package com.noted.app.ui

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.noted.app.ui.rich.ItemShortcut
import com.noted.app.ui.rich.TextGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noted.app.ui.rich.ItemCallbacks
import com.noted.app.ui.rich.RichText
import com.noted.app.ui.rich.RichTextField
import com.noted.app.ui.rich.rememberRichStyle

/** Alto de cada fila. */
internal val CHECKLIST_ROW = 48.dp

/** Sangría de un subítem: corre la fila entera. */
internal val CHECKLIST_INDENT = 38.dp

/** Dónde empieza el texto del ítem, desde el borde izquierdo de la fila. */
internal val CHECKLIST_TEXT_START = 94.dp

/** El margen de la fila a los dos lados. */
private val ROW_PADDING = 6.dp

/** Dónde empieza la vista de la casilla: se superpone 8 dp con la agarradera. */
private val BOX_START = 46.dp

internal const val CHECKLIST_ADD_TEXT = "Elemento de la lista"
internal const val CHECKLIST_ADD_DESCRIPTION = "Agregar elemento de la lista"
internal const val CHECKLIST_COLLAPSE_DESCRIPTION = "Contraer elementos marcados"
internal const val CHECKLIST_EXPAND_DESCRIPTION = "Ampliar elementos marcados"
internal const val CHECKLIST_UNCHECKED_DESCRIPTION = "No marcados"
internal const val CHECKLIST_CHECKED_DESCRIPTION = "Marcados"

/** Opacidades de la casilla y del texto marcados y del padre fantasma. */
private const val CHECKED_ALPHA = 0.38f
private const val GHOST_ALPHA = 0.12f

/**
 * Medidos en la tablet: el "+" es un cuadrado de 16 dp, y el de Material, de
 * 14 dp en un dibujo de 24; el chevron mide 15,5 × 9 dp, y el de Material,
 * 11,6 × 7 dp. Los íconos de Material se agrandan hasta esas medidas.
 */
private const val ADD_SCALE = 16f / 14f
private const val CHEVRON_SCALE_X = 15.5f / 11.65f
private const val CHEVRON_SCALE_Y = 9f / 7f

/** El texto del encabezado de la sección. */
internal fun checkedHeaderText(count: Int): String =
    if (count == 1) "1 elemento marcado" else "$count elementos marcados"

/**
 * Lo que se dibuja en el bloque, de arriba abajo y en una sola `Column`: las filas no
 * marcadas, "+ Elemento de la lista", el encabezado y la sección. La [key] de un ítem es su
 * id en los dos lados, así marcar el ítem con foco mueve su campo sin recrearlo.
 */
internal sealed interface ChecklistLine {
    val key: String

    data class Row(val row: ChecklistRow, val inSection: Boolean) : ChecklistLine {
        override val key: String get() = row.id
    }

    data object Add : ChecklistLine {
        override val key: String get() = "+add"
    }

    data class Header(val count: Int) : ChecklistLine {
        override val key: String get() = "+header"
    }
}

/** Las líneas del bloque: sin marcados no hay encabezado; plegado, la sección no se ve. */
internal fun checklistLines(rows: ChecklistRows, collapsed: Boolean): List<ChecklistLine> = buildList {
    rows.unchecked.mapTo(this) { ChecklistLine.Row(it, inSection = false) }
    add(ChecklistLine.Add)
    if (rows.checkedCount > 0) {
        add(ChecklistLine.Header(rows.checkedCount))
        if (!collapsed) rows.checked.mapTo(this) { ChecklistLine.Row(it, inSection = true) }
    }
}

/** Duración del fundido de la fila marcada y del deslizamiento de las de abajo. */
private const val TOGGLE_MS = 250

/** `DecelerateInterpolator`. */
private val Decelerate = Easing { 1f - (1f - it) * (1f - it) }

/**
 * El fundido de `animate().alpha(0f)` sin interpolador propio: el de la plataforma por
 * defecto, `AccelerateDecelerateInterpolator`.
 */
private val AccelerateDecelerate = Easing { (cos((it + 1f) * PI) / 2.0 + 0.5).toFloat() }

/** La imagen de la fila marcada que se desvanece en su lugar viejo. */
private class FadingRow(val image: ImageBitmap, val top: Float)

/** Sombra de la fila arrastrada (V24, V26: `editor_list_item_drag_elevation`). */
private val DRAG_ELEVATION = 2.dp

/** Hasta dónde sigue al dedo la fila que se desliza en horizontal. */
private val SWIPE_LIMIT = CHECKLIST_INDENT

/** Desde cuánto anida o desanida al soltar (H5: `list_item_indent_drag_limit`). */
private val SWIPE_THRESHOLD = 30.dp

/** Autodesplazamiento cerca del borde de la nota (H15: `edgeScrollThreshold`, `edgeScrollPerSecond`). */
private val EDGE_SCROLL_ZONE = 56.dp
private val EDGE_SCROLL_MAX = 2000.dp

/**
 * Un arrastre por la agarradera en curso: el ítem, dónde estaba su fila en el bloque, dónde
 * estaba el dedo en la ventana, y las alturas de las demás filas no marcadas desde [othersTop]
 * (las de [checklistDragOthers], medidas al empezar).
 */
private class RowDrag(
    val id: String,
    val startTop: Float,
    val fingerStart: Float,
    val othersTop: Float,
    val heights: List<Float>,
)

/**
 * Un bloque de checklist. Tocar la casilla llama a [onToggle] y el
 * encabezado a [onCollapsedChange]; lo que se escribe, ✕, "+ Elemento de la lista",
 * Backspace al principio, los atajos, el arrastre por la agarradera y el deslizamiento para
 * anidar mandan su [ChecklistEdit] por [onItemsChange], con el ítem que lo mandó (`null` si el
 * foco tiene que ir igual al ítem de [ChecklistEdit.focusId]) y qué fue ([ItemChange]).
 * [onShortcut] recibe los atajos del editor desde el texto de un ítem (Ctrl+Z…), menos
 * Ctrl+Shift+9, que marca o desmarca el ítem como su casilla, y Ctrl+Alt+↑/↓, que lo mueven
 * como Ctrl+Shift+P/O ([itemEditorShortcut]).
 * [focusRequest] es el ítem que tiene que tomar el foco (el cursor ya está en su valor).
 * [onArrow] es una flecha en la primera o la última línea de un ítem, con la x del cursor
 * desde el borde de la fila. [viewport] da el borde de arriba y de abajo de la nota en la
 * ventana y [scrollBy] la desplaza, para el autodesplazamiento mientras se arrastra.
 *
 * Al marcar o desmarcar, la fila se desvanece en su lugar en 250 ms y las de abajo se
 * deslizan a su lugar nuevo en 250 ms, desacelerando. Al arrastrar, la fila se dibuja
 * como copia que sigue al dedo y deja su lugar vacío donde caería; las demás se corren igual.
 */
@Composable
internal fun ChecklistBlock(
    block: EditorBlock.Checklist,
    collapsed: Boolean,
    focusedItemId: String?,
    focusRequest: Pair<String, Int>?,
    onFocusRequestConsumed: () -> Unit,
    onItemsChange: (edit: ChecklistEdit, sourceItemId: String?, change: ItemChange) -> Unit,
    onToggle: (itemId: String) -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
    onItemFocus: (itemId: String, focused: Boolean) -> Unit,
    onArrow: (itemId: String, direction: Int, x: Float) -> Unit,
    onGeometry: (itemId: String, geometry: TextGeometry) -> Unit,
    viewport: () -> Pair<Float, Float>,
    scrollBy: suspend (Float) -> Float,
    onShortcut: (EditorShortcut) -> Boolean,
) {
    val style = rememberRichStyle()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val background = MaterialTheme.colorScheme.background
    val rowLayers = remember { mutableMapOf<String, GraphicsLayer>() }
    val rowTops = remember { mutableMapOf<String, Float>() }
    val rowHeights = remember { mutableMapOf<String, Float>() }
    val blockWindowTop = remember { floatArrayOf(0f) }
    var sliding by remember { mutableStateOf(false) }
    var toggledId by remember { mutableStateOf<String?>(null) }
    var fading by remember { mutableStateOf<FadingRow?>(null) }
    val fadeAlpha = remember { Animatable(1f) }
    var drag by remember { mutableStateOf<RowDrag?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var dragScrolled by remember { mutableFloatStateOf(0f) }
    val currentItems by rememberUpdatedState(block.items)
    val currentOnItemsChange by rememberUpdatedState(onItemsChange)
    val currentViewport by rememberUpdatedState(viewport)
    val currentScrollBy by rememberUpdatedState(scrollBy)

    fun toggle(itemId: String) {
        scope.launch {
            // La imagen de la fila antes de moverla, para desvanecerla en su lugar.
            val image = runCatching { rowLayers[itemId]?.toImageBitmap() }
                .onFailure { Log.w("ChecklistBlock", "No se pudo tomar la imagen de la fila; se marca sin fundido", it) }
                .getOrNull()
            val top = rowTops[itemId]
            toggledId = itemId
            sliding = true
            onToggle(itemId)
            if (image != null && top != null) {
                fading = FadingRow(image, top)
                fadeAlpha.snapTo(1f)
                launch {
                    fadeAlpha.animateTo(0f, tween(TOGGLE_MS, easing = AccelerateDecelerate))
                    fading = null
                }
            }
            // Un poco más que la animación: si no, la última la cortaría al saltar.
            delay(TOGGLE_MS + 100L)
            sliding = false
            toggledId = null
        }
    }

    // ---- arrastre por la agarradera ----

    /** El borde de arriba de la copia en el bloque: sigue al dedo 1:1, también si la nota se desplaza. */
    fun copyTop(d: RowDrag) = d.startTop + dragDy + dragScrolled

    fun rawRow(d: RowDrag) = checklistDragSlot(d.heights, copyTop(d) - d.othersTop)

    fun startDrag(itemId: String, grabY: Float) {
        // Un segundo dedo en otra agarradera no pisa el arrastre en curso.
        if (drag != null) return
        val items = currentItems
        val top = rowTops[itemId] ?: return
        val firstId = ChecklistOps.rows(items).unchecked.firstOrNull()?.id ?: return
        val rowPx = with(density) { CHECKLIST_ROW.toPx() }
        dragDy = 0f
        dragScrolled = 0f
        drag = RowDrag(
            id = itemId,
            startTop = top,
            fingerStart = blockWindowTop[0] + top + grabY,
            othersTop = rowTops[firstId] ?: 0f,
            heights = checklistDragOthers(items, itemId).map { rowHeights[it] ?: rowPx },
        )
    }

    /** Al soltar (o si se cancela) se aplica el orden nuevo, el mismo que mostraba el hueco. */
    fun drop(itemId: String) {
        val d = drag?.takeIf { it.id == itemId } ?: return
        val row = rawRow(d)
        drag = null
        val items = currentItems
        val cursor = items.firstOrNull { it.id == d.id }?.value?.selection?.start ?: 0
        currentOnItemsChange(ChecklistOps.move(items, d.id, toRow = row, cursor = cursor), d.id, ItemChange.Edit)
    }

    val dragId = drag?.id
    LaunchedEffect(dragId) {
        if (dragId == null) return@LaunchedEffect
        val zone = with(density) { EDGE_SCROLL_ZONE.toPx() }
        val max = with(density) { EDGE_SCROLL_MAX.toPx() }
        var lastFrame = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            val d = drag ?: break
            val (top, bottom) = currentViewport()
            val speed = checklistEdgeSpeed(d.fingerStart + dragDy, top, bottom, zone, max)
            if (speed != 0f && dt > 0f) dragScrolled += currentScrollBy(speed * dt.coerceAtMost(0.05f))
        }
    }

    // Mientras se arrastra, el orden en que caería (con el hueco en su lugar) y sin los hijos.
    // El lugar se lee con derivedStateOf: el bloque se recompone solo cuando cambia, no en cada paso del dedo.
    val dragRow by remember { derivedStateOf { drag?.let { rawRow(it) } } }
    val view = drag?.let { d -> dragRow?.let { checklistDragView(block.items, d.id, it) } }
    val rows = ChecklistOps.rows(view?.items ?: block.items)
    val hidden = view?.hidden.orEmpty()

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { blockWindowTop[0] = it.positionInWindow().y }
            .drawWithContent {
                drawContent()
                fading?.let { row -> drawImage(row.image, Offset(0f, row.top), alpha = fadeAlpha.value) }
                // La copia de la fila arrastrada, opaca y con sombra, arriba de todo.
                drag?.let { d -> rowLayers[d.id]?.let { layer -> translate(0f, copyTop(d)) { drawLayer(layer) } } }
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            for (line in checklistLines(rows, collapsed)) {
                key(line.key) {
                    val layer = rememberGraphicsLayer()
                    DisposableEffect(line.key) {
                        rowLayers[line.key] = layer
                        onDispose {
                            rowLayers.remove(line.key)
                            rowTops.remove(line.key)
                            rowHeights.remove(line.key)
                            if (drag?.id == line.key) drag = null
                        }
                    }
                    val dragged = dragId == line.key
                    Box(
                        Modifier
                            .hiddenWhen(line.key in hidden)
                            .slideWhenMoved(active = (sliding && line.key != toggledId) || (dragId != null && !dragged)) { top ->
                                rowTops[line.key] = top
                            }
                            .onSizeChanged { rowHeights[line.key] = it.height.toFloat() }
                            .drawWithContent {
                                // La fila arrastrada no se dibuja en su lugar (queda el hueco): su
                                // imagen, con el fondo de la nota, la dibuja el bloque en el dedo.
                                val isDragged = drag?.id == line.key
                                layer.shadowElevation = if (isDragged) DRAG_ELEVATION.toPx() else 0f
                                layer.record {
                                    if (isDragged) drawRect(background)
                                    this@drawWithContent.drawContent()
                                }
                                if (!isDragged) drawLayer(layer)
                            }
                    ) {
                        when (line) {
                            is ChecklistLine.Row -> when (val row = line.row) {
                                is ChecklistRow.Ghost -> GhostRow(row.parent)
                                is ChecklistRow.Item -> {
                                    val item = row.item
                                    val textStart = with(density) { checklistTextStart(item.nested).toPx() }
                                    ItemRow(
                                        item = item,
                                        inSection = line.inSection,
                                        focused = focusedItemId == item.id,
                                        onToggle = { toggle(item.id) },
                                        onDelete = { currentOnItemsChange(ChecklistOps.delete(currentItems, item.id), item.id, ItemChange.Edit) },
                                        onGrab = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragStart = { grabY -> startDrag(item.id, grabY) },
                                        onDragBy = { dy -> if (drag?.id == item.id) dragDy += dy },
                                        onDragEnd = { drop(item.id) },
                                        canNest = { nested -> ChecklistOps.setNested(currentItems, item.id, nested) != null },
                                        onNest = { nested ->
                                            val items = currentItems
                                            val cursor = items.firstOrNull { it.id == item.id }?.value?.selection?.start ?: 0
                                            ChecklistOps.setNested(items, item.id, nested, cursor)
                                                ?.let { currentOnItemsChange(it, item.id, ItemChange.Edit) }
                                        },
                                        dragging = { drag != null },
                                    ) {
                                        RichTextField(
                                            value = item.value,
                                            onValueChange = { value ->
                                                val items = currentItems
                                                val now = items.firstOrNull { it.id == item.id } ?: item
                                                // Lo que convierte un atajo de Markdown es un paso aparte.
                                                val change = when {
                                                    !contentChanged(now.value, value) -> ItemChange.Cursor
                                                    value.autoFormatted -> ItemChange.Edit
                                                    else -> ItemChange.Typing
                                                }
                                                currentOnItemsChange(itemValueEdit(items, now, value), item.id, change)
                                            },
                                            style = style,
                                            focusRequested = focusRequest?.first == item.id,
                                            onFocusRequestConsumed = onFocusRequestConsumed,
                                            onFocusChanged = { focused -> onItemFocus(item.id, focused) },
                                            onFocusPrevious = { x -> onArrow(item.id, -1, x + textStart) },
                                            onFocusNext = { x -> onArrow(item.id, 1, x + textStart) },
                                            onGeometry = { geometry -> onGeometry(item.id, geometry) },
                                            onShortcut = { shortcut ->
                                                when (val action = itemEditorShortcut(shortcut)) {
                                                    null -> onShortcut(shortcut)
                                                    ItemEditorShortcut.Toggle -> {
                                                        toggle(item.id)
                                                        true
                                                    }
                                                    is ItemEditorShortcut.Item -> {
                                                        val items = currentItems
                                                        val now = items.firstOrNull { it.id == item.id } ?: item
                                                        itemShortcutEdit(items, now, action.shortcut)
                                                            ?.let { currentOnItemsChange(it, null, ItemChange.Edit) }
                                                        true
                                                    }
                                                }
                                            },
                                            item = ItemCallbacks(
                                                checked = item.checked,
                                                // Siempre se consume: si no, el campo
                                                // haría lo suyo (sacar una marca, sangrar).
                                                onBackspaceAtStart = {
                                                    ChecklistOps.backspaceAtStart(currentItems, item.id)
                                                        ?.let { currentOnItemsChange(it, item.id, ItemChange.Edit) }
                                                    true
                                                },
                                                onShortcut = { shortcut ->
                                                    val items = currentItems
                                                    val now = items.firstOrNull { it.id == item.id } ?: item
                                                    itemShortcutEdit(items, now, shortcut)
                                                        ?.let { currentOnItemsChange(it, null, ItemChange.Edit) }
                                                    true
                                                },
                                            ),
                                        )
                                    }
                                }
                            }

                            ChecklistLine.Add -> AddRow { currentOnItemsChange(ChecklistOps.addAtEnd(currentItems), null, ItemChange.Edit) }
                            is ChecklistLine.Header -> HeaderRow(line.count, collapsed) { onCollapsedChange(!collapsed) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Una línea que no se ve mientras se arrastra su padre: sigue compuesta (su campo no se
 * recrea), pero no ocupa lugar ni se dibuja.
 */
private fun Modifier.hiddenWhen(hidden: Boolean): Modifier =
    if (!hidden) this else layout { measurable, constraints ->
        measurable.measure(constraints)
        layout(0, 0) {}
    }

/**
 * Lo que hace un atajo del ítem: Tab / Ctrl+] anida, Shift+Tab / Ctrl+[ desanida,
 * Ctrl+Shift+P / Ctrl+Shift+O suben y bajan. `null` si no se puede (el primer ítem, uno de la
 * sección, ya en ese nivel); el cursor queda donde estaba.
 */
internal fun itemShortcutEdit(items: List<ChecklistItem>, item: ChecklistItem, shortcut: ItemShortcut): ChecklistEdit? {
    val cursor = item.value.selection.start
    return when (shortcut) {
        ItemShortcut.Indent -> ChecklistOps.setNested(items, item.id, nested = true, cursor = cursor)
        ItemShortcut.Outdent -> ChecklistOps.setNested(items, item.id, nested = false, cursor = cursor)
        ItemShortcut.MoveUp -> ChecklistOps.moveByOne(items, item.id, up = true, cursor = cursor)
        ItemShortcut.MoveDown -> ChecklistOps.moveByOne(items, item.id, up = false, cursor = cursor)
    }
}

/** Lo que hace en un ítem un atajo del editor que el ítem atiende él mismo. */
internal sealed interface ItemEditorShortcut {
    /** Marcar o desmarcar, como la casilla (se va a marcados). */
    data object Toggle : ItemEditorShortcut

    /** Lo mismo que el atajo del ítem [shortcut]. */
    data class Item(val shortcut: ItemShortcut) : ItemEditorShortcut
}

/**
 * En un ítem, Ctrl+Shift+9 ([EditorShortcut.ToggleChecked]) lo marca o desmarca y Ctrl+Alt+↑/↓
 * ([EditorShortcut.MoveListItem]) hace lo mismo que Ctrl+Shift+P/O. `null`: el atajo sigue al
 * editor de la nota.
 */
internal fun itemEditorShortcut(shortcut: EditorShortcut): ItemEditorShortcut? = when (shortcut) {
    EditorShortcut.ToggleChecked -> ItemEditorShortcut.Toggle
    is EditorShortcut.MoveListItem -> ItemEditorShortcut.Item(if (shortcut.up) ItemShortcut.MoveUp else ItemShortcut.MoveDown)
    else -> null
}

/**
 * Si la línea cambia de lugar en la columna mientras [active], llega a su lugar nuevo
 * deslizándose en 250 ms con desaceleración; si no, salta. [onTop] recibe su lugar
 * dentro del bloque.
 */
@Composable
private fun Modifier.slideWhenMoved(active: Boolean, onTop: (Float) -> Unit): Modifier {
    // [progress] va de 1 a 0: la línea se dibuja corrida `start * progress`. El corrimiento se
    // fija en onPlaced, antes de ubicar la línea, así no salta un cuadro antes de deslizarse:
    // hasta que arranca la animación ([pending]) se usa el corrimiento entero.
    val progress = remember { Animatable(0f) }
    val last = remember { floatArrayOf(Float.NaN) }
    val start = remember { mutableFloatStateOf(0f) }
    val pending = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentActive by rememberUpdatedState(active)
    return this
        .onPlaced { coords ->
            val top = coords.positionInParent().y
            onTop(top)
            val previous = last[0]
            last[0] = top
            if (previous.isNaN() || previous == top || !currentActive) return@onPlaced
            val shown = start.floatValue * (if (pending.value) 1f else progress.value)
            start.floatValue = shown + previous - top
            pending.value = true
            scope.launch {
                progress.snapTo(1f)
                pending.value = false
                progress.animateTo(0f, tween(TOGGLE_MS, easing = Decelerate))
            }
        }
        .offset {
            val factor = if (pending.value) 1f else progress.value
            IntOffset(0, (start.floatValue * factor).roundToInt())
        }
}

/**
 * Una fila de un ítem: agarradera (solo arriba; en la sección queda el lugar, V15, V23),
 * casilla, texto y ✕ (solo con el foco, V9). Un subítem corre la fila entera.
 *
 * Arriba (no en la sección), la agarradera arrastra la fila ([grabber]) y deslizar la fila en
 * horizontal la anida o desanida ([nestSwipe]): sigue al dedo hasta ±38 dp y al soltar con
 * más de 30 dp llama a [onNest]. En la sección deslizar de costado no hace
 * nada.
 */
@Composable
private fun ItemRow(
    item: ChecklistItem,
    inSection: Boolean,
    focused: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onGrab: () -> Unit,
    onDragStart: (grabY: Float) -> Unit,
    onDragBy: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    canNest: (nested: Boolean) -> Boolean,
    onNest: (nested: Boolean) -> Unit,
    dragging: () -> Boolean,
    text: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var swipe by remember { mutableFloatStateOf(0f) }
    val currentCanNest by rememberUpdatedState(canNest)
    val currentOnNest by rememberUpdatedState(onNest)
    val currentDragging by rememberUpdatedState(dragging)
    val currentInSection by rememberUpdatedState(inSection)
    // Hacia qué lado se puede (anidar, desanidar), calculado una vez al tomar el gesto; ninguno
    // si hay otra fila arrastrándose.
    val allowed = remember { booleanArrayOf(false, false) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CHECKLIST_ROW)
            .semantics {
                contentDescription = if (inSection) CHECKLIST_CHECKED_DESCRIPTION else CHECKLIST_UNCHECKED_DESCRIPTION
            }
            // En la sección también se queda con el deslizamiento de costado, sin ningún lado
            // permitido: la fila no se mueve y el campo no selecciona.
            .nestSwipe(
                onClaim = {
                    val free = !currentInSection && !currentDragging()
                    allowed[0] = free && currentCanNest(true)
                    allowed[1] = free && currentCanNest(false)
                },
                onSwipe = { dx ->
                    val limit = with(density) { SWIPE_LIMIT.toPx() }
                    swipe = checklistSwipeOffset(dx, limit, allowed[0], allowed[1])
                },
                onRelease = {
                    val nested = checklistSwipeNested(swipe, with(density) { SWIPE_THRESHOLD.toPx() })
                    swipe = 0f
                    nested?.let { currentOnNest(it) }
                },
            )
            .padding(start = if (item.nested) CHECKLIST_INDENT else 0.dp)
            .offset { IntOffset(swipe.roundToInt(), 0) }
    ) {
        if (!inSection) {
            Box(
                Modifier
                    .padding(start = ROW_PADDING)
                    .size(CHECKLIST_ROW)
                    .grabber(onGrab = onGrab, onDragStart = onDragStart, onDragBy = onDragBy, onDragEnd = onDragEnd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Box(
            Modifier
                .padding(start = BOX_START)
                .size(CHECKLIST_ROW)
                .toggleable(
                    value = item.checked,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 20.dp),
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
        ) {
            ChecklistBox(checked = item.checked, ghost = false)
        }
        Box(
            Modifier
                .padding(start = CHECKLIST_TEXT_START, end = ROW_PADDING + CHECKLIST_ROW)
                .fillMaxWidth()
                .heightIn(min = CHECKLIST_ROW),
            contentAlignment = Alignment.CenterStart,
        ) {
            text()
        }
        if (focused) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = ROW_PADDING)
                    .size(CHECKLIST_ROW)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 20.dp),
                        onClick = onDelete,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Borrar", tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** El padre fantasma de un subítem marcado: sin marcar, deshabilitado, al 12 %. */
@Composable
private fun GhostRow(parent: ChecklistItem) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CHECKLIST_ROW)
            .semantics { contentDescription = CHECKLIST_CHECKED_DESCRIPTION }
    ) {
        Box(Modifier.padding(start = BOX_START).size(CHECKLIST_ROW)) {
            ChecklistBox(checked = false, ghost = true)
        }
        Box(
            Modifier
                .padding(start = CHECKLIST_TEXT_START, end = ROW_PADDING + CHECKLIST_ROW)
                .fillMaxWidth()
                .heightIn(min = CHECKLIST_ROW),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = parent.value.plain,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                color = colors.onBackground.copy(alpha = GHOST_ALPHA),
            )
        }
    }
}

/** "+ Elemento de la lista": "+" en la columna de la casilla, texto desde 94 + 6 dp. */
@Composable
private fun AddRow(onAdd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CHECKLIST_ROW)
            .clickable(onClick = onAdd)
            .semantics { contentDescription = CHECKLIST_ADD_DESCRIPTION }
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier
                .padding(start = BOX_START)
                .size(CHECKLIST_ROW)
                .wrapContentSize()
                .size(24.dp)
                .graphicsLayer(scaleX = ADD_SCALE, scaleY = ADD_SCALE),
        )
        Text(
            text = CHECKLIST_ADD_TEXT,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = CHECKLIST_TEXT_START + ROW_PADDING),
        )
    }
}

/**
 * El encabezado de la sección, pegado a "+ Elemento de la lista" y sin línea: la flecha
 * en la columna de la agarradera, hacia abajo desplegado y hacia arriba plegado; el
 * texto desde 54 + 6 dp. Toda la fila pliega o despliega.
 */
@Composable
private fun HeaderRow(count: Int, collapsed: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CHECKLIST_ROW)
            .clickable(onClick = onClick)
    ) {
        Icon(
            if (collapsed) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (collapsed) CHECKLIST_EXPAND_DESCRIPTION else CHECKLIST_COLLAPSE_DESCRIPTION,
            tint = colors.onSurfaceVariant,
            modifier = Modifier
                .padding(start = ROW_PADDING)
                .size(CHECKLIST_ROW)
                .wrapContentSize()
                .size(24.dp)
                .graphicsLayer(scaleX = CHEVRON_SCALE_X, scaleY = CHEVRON_SCALE_Y),
        )
        Text(
            text = checkedHeaderText(count),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = ROW_PADDING + CHECKLIST_ROW + ROW_PADDING),
        )
    }
}

/**
 * La casilla dibujada con `Canvas` en una vista de 48×48: recuadro de
 * 18×18 dp, borde de 2 dp y radio exterior de 2 dp con las esquinas de adentro rectas.
 * Marcada, relleno de `onSurfaceVariant` al 38 % sobre el fondo de la nota y el tilde del
 * color del fondo. [ghost] es la casilla del padre fantasma, al 12 %.
 */
@Composable
internal fun ChecklistBox(checked: Boolean, ghost: Boolean) {
    val colors = MaterialTheme.colorScheme
    val background = colors.background
    val boxColor = when {
        ghost -> colors.onSurfaceVariant.copy(alpha = GHOST_ALPHA).compositeOver(background)
        checked -> colors.onSurfaceVariant.copy(alpha = CHECKED_ALPHA).compositeOver(background)
        else -> colors.onSurfaceVariant
    }
    Canvas(Modifier.size(CHECKLIST_ROW)) {
        val side = 18.dp.toPx()
        val border = 2.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        val outer = RoundRect(topLeft.x, topLeft.y, topLeft.x + side, topLeft.y + side, radius)
        if (checked) {
            drawPath(Path().apply { addRoundRect(outer) }, boxColor)
            // El tilde: dos tramos de 2 dp dentro del recuadro.
            val unit = side / 18f
            val tick = Path().apply {
                moveTo(topLeft.x + 3.7f * unit, topLeft.y + 9.3f * unit)
                lineTo(topLeft.x + 7f * unit, topLeft.y + 12.6f * unit)
                lineTo(topLeft.x + 14.3f * unit, topLeft.y + 5.3f * unit)
            }
            drawPath(tick, background, style = Stroke(width = border, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
        } else {
            val ring = Path().apply {
                fillType = PathFillType.EvenOdd
                addRoundRect(outer)
                addRect(
                    androidx.compose.ui.geometry.Rect(
                        Offset(topLeft.x + border, topLeft.y + border),
                        Size(side - 2 * border, side - 2 * border),
                    )
                )
            }
            drawPath(ring, boxColor)
        }
    }
}

/**
 * Qué fue un cambio de la lista, para el deshacer: [Cursor] solo movió el cursor (no es
 * un paso), [Typing] es lo que llega del campo de un ítem con el texto cambiado (Enter también) y
 * [Edit] todo lo demás: ✕, anidar, mover, unir con Backspace y "+ Elemento de la lista".
 */
internal enum class ItemChange { Cursor, Typing, Edit }

/**
 * Lo que manda el campo de un ítem. Si solo se movió el cursor, se guarda la selección sin
 * pasar por [ChecklistOps.replaceText], que ordenaría una nota vieja: abrir y tocar un ítem
 * no es editar la lista.
 */
internal fun itemValueEdit(items: List<ChecklistItem>, item: ChecklistItem, value: RichText): ChecklistEdit {
    if (contentChanged(item.value, value)) {
        return ChecklistOps.replaceText(items, item.id, value)
    }
    val list = items.map { if (it.id == item.id) it.copy(value = value) else it }
    return ChecklistEdit(list, item.id, value.selection.start)
}

/** Los ítems que se ven y se pueden editar, de arriba abajo: sin "+", encabezado ni fantasmas. */
private fun visibleItemIds(items: List<ChecklistItem>, collapsed: Boolean): List<String> =
    checklistLines(ChecklistOps.rows(items), collapsed).mapNotNull { line ->
        ((line as? ChecklistLine.Row)?.row as? ChecklistRow.Item)?.id
    }

/**
 * La fila de arriba ([direction] -1) o de abajo (1) del ítem [itemId] para las flechas, sin
 * contar "+", el encabezado ni los fantasmas, y sin la sección si está plegada. `null` desde la
 * primera o la última: se sale al bloque vecino.
 */
internal fun checklistNeighbor(items: List<ChecklistItem>, collapsed: Boolean, itemId: String, direction: Int): String? {
    val ids = visibleItemIds(items, collapsed)
    val index = ids.indexOf(itemId)
    if (index < 0) return null
    return ids.getOrNull(index + direction)
}

/** Por dónde se entra al bloque con las flechas: la primera fila bajando, la última subiendo. */
internal fun checklistEdgeItem(items: List<ChecklistItem>, collapsed: Boolean, direction: Int): String? {
    val ids = visibleItemIds(items, collapsed)
    return if (direction > 0) ids.firstOrNull() else ids.lastOrNull()
}

/**
 * Adónde va el foco después de marcar o desmarcar el ítem [itemId] que lo tenía:
 * si se sigue viendo, el ítem en [cursor]; con la sección plegada, el final de la fila no
 * marcada de arriba, o el principio de la de abajo si era la primera. `null` si no queda
 * ninguna: el foco sale al texto de arriba.
 */
internal fun focusAfterToggle(
    before: List<ChecklistItem>,
    after: List<ChecklistItem>,
    collapsed: Boolean,
    itemId: String,
    cursor: Int,
): Pair<String, Int>? {
    if (itemId in visibleItemIds(after, collapsed)) return itemId to cursor
    val remaining = ChecklistOps.rows(after).unchecked.map { it.item }
    val old = ChecklistOps.rows(before).unchecked.map { it.item }
    val index = old.indexOfFirst { it.id == itemId }
    val above = old.take(index.coerceAtLeast(0)).lastOrNull { item -> remaining.any { it.id == item.id } }
    if (above != null) return above.id to (remaining.first { it.id == above.id }.value.plain.length)
    return remaining.firstOrNull()?.let { it.id to 0 }
}

/**
 * Si después de marcar o desmarcar [toggledId] el foco tiene que moverse: el ítem tocado
 * tenía el foco, o el ítem con foco [focusedId] dejó de verse (un hijo que se fue a la
 * sección plegada con su padre, H7).
 */
internal fun focusLeavesAfterToggle(after: List<ChecklistItem>, collapsed: Boolean, focusedId: String, toggledId: String): Boolean =
    focusedId == toggledId || focusedId !in visibleItemIds(after, collapsed)

/** Adónde va el foco al plegar la sección ([focusAfterCollapse]). */
internal sealed interface CollapseFocus {
    /** El ítem con foco no está en la sección: no se mueve. */
    data object Stay : CollapseFocus

    data class Item(val itemId: String, val cursor: Int) : CollapseFocus

    /** No hay no marcados: al final del texto de arriba. */
    data object TextAbove : CollapseFocus
}

/**
 * Al plegar la sección con el foco en uno de sus ítems: el foco va al final del último
 * ítem no marcado, o al texto de arriba si no hay ninguno.
 */
internal fun focusAfterCollapse(items: List<ChecklistItem>, focusedId: String): CollapseFocus {
    val rows = ChecklistOps.rows(items)
    if (rows.checked.none { it is ChecklistRow.Item && it.id == focusedId }) return CollapseFocus.Stay
    val last = rows.unchecked.lastOrNull()?.item ?: return CollapseFocus.TextAbove
    return CollapseFocus.Item(last.id, last.value.plain.length)
}

/** Dónde empieza el texto de un ítem desde el borde de la fila, para las flechas. */
internal fun checklistTextStart(nested: Boolean) = CHECKLIST_TEXT_START + if (nested) CHECKLIST_INDENT else 0.dp

/** El valor del ítem con el cursor en [cursor] (para pedir el foco en un lugar). */
internal fun RichText.withCursor(cursor: Int): RichText =
    RichText(text, androidx.compose.ui.text.TextRange(cursor.coerceIn(0, plain.length)), pending, pendingColor)

// ---- arrastre por la agarradera y anidado deslizando ----

/** Lo que se ve mientras se arrastra un ítem: el orden en que caería y las filas que no se ven. */
internal data class ChecklistDragView(val items: List<ChecklistItem>, val hidden: Set<String>)

/**
 * Las filas no marcadas sin el ítem [id] arrastrado ni, si es de primer nivel, sus hijos: entre
 * ellas se busca el lugar más cercano al dedo, el `rawRow` de [ChecklistOps.dropRow].
 */
internal fun checklistDragOthers(items: List<ChecklistItem>, id: String): List<String> {
    val unchecked = ChecklistOps.rows(items).unchecked.map { it.item }
    val hidden = checklistDraggedChildren(unchecked, id)
    return unchecked.filter { it.id != id && it.id !in hidden }.map { it.id }
}

/** Los hijos sin marcar del ítem [id] de primer nivel, que no se ven mientras se lo arrastra. */
private fun checklistDraggedChildren(unchecked: List<ChecklistItem>, id: String): Set<String> {
    val index = unchecked.indexOfFirst { it.id == id }
    if (index < 0 || unchecked[index].nested) return emptySet()
    return unchecked.drop(index + 1).takeWhile { it.nested }.mapTo(mutableSetOf()) { it.id }
}

/**
 * El lugar más cercano a la fila arrastrada: [heights] son las alturas de las filas sin
 * la arrastrada, una debajo de la otra desde 0, y [copyTop] el borde de arriba de la copia en
 * esa misma medida. El lugar k empieza donde terminan las k primeras filas; se cambia de lugar
 * al pasar la mitad de la fila vecina.
 */
internal fun checklistDragSlot(heights: List<Float>, copyTop: Float): Int {
    var best = 0
    var bestDistance = abs(copyTop)
    var top = 0f
    heights.forEachIndexed { i, height ->
        top += height
        val distance = abs(copyTop - top)
        if (distance < bestDistance) {
            best = i + 1
            bestDistance = distance
        }
    }
    return best
}

/**
 * El orden con el ítem [id] en el lugar [rawRow], el mismo que deja [ChecklistOps.move] al
 * soltar (así el hueco y la caída coinciden, P10), y sus hijos escondidos.
 */
internal fun checklistDragView(items: List<ChecklistItem>, id: String, rawRow: Int): ChecklistDragView {
    val hidden = checklistDraggedChildren(ChecklistOps.rows(items).unchecked.map { it.item }, id)
    return ChecklistDragView(ChecklistOps.move(items, id, toRow = rawRow).items, hidden)
}

/**
 * Cuánto se corre la fila que se desliza en horizontal: sigue al dedo [dx] hasta ±[limit],
 * y no se mueve hacia el lado en que no se puede anidar o desanidar.
 */
internal fun checklistSwipeOffset(dx: Float, limit: Float, canNest: Boolean, canUnnest: Boolean): Float =
    dx.coerceIn(if (canUnnest) -limit else 0f, if (canNest) limit else 0f)

/** Al soltar la fila corrida [offset]: más de [threshold] anida (`true`) o desanida (`false`); si no, vuelve. */
internal fun checklistSwipeNested(offset: Float, threshold: Float): Boolean? = when {
    offset > threshold -> true
    offset < -threshold -> false
    else -> null
}

/**
 * La velocidad del autodesplazamiento con el dedo en [y]: a menos de [edge] del borde
 * [top] o [bottom] de la nota, cuadrática con lo que se metió, hasta [max]. Negativa hacia arriba.
 */
internal fun checklistEdgeSpeed(y: Float, top: Float, bottom: Float, edge: Float, max: Float): Float {
    val upper = top + edge - y
    val lower = y - (bottom - edge)
    return when {
        upper > 0f -> -max * (upper / edge).coerceAtMost(1f).let { it * it }
        lower > 0f -> max * (lower / edge).coerceAtMost(1f).let { it * it }
        else -> 0f
    }
}

/**
 * La agarradera: al apoyar el dedo, sin toque largo, [onGrab] (la vibración). El
 * arrastre empieza cuando el dedo pasa el margen de toque yendo más en vertical que en
 * horizontal: [onDragStart] con la y del dedo en la fila, [onDragBy] con cada paso del dedo
 * en la pantalla y [onDragEnd] al levantarlo o si se cancela. Si arranca más
 * horizontal, sigue la fila ([nestSwipe]).
 */
private fun Modifier.grabber(
    onGrab: () -> Unit,
    onDragStart: (grabY: Float) -> Unit,
    onDragBy: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        // Tocar la agarradera no pone el cursor en ningún texto.
        down.consume()
        onGrab()
        var change: PointerInputChange
        while (true) {
            change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            val moved = change.position - down.position
            if (moved.getDistance() > viewConfiguration.touchSlop) {
                if (abs(moved.y) > abs(moved.x)) break else return@awaitEachGesture
            }
        }
        onDragStart(down.position.y)
        try {
            change.consume()
            onDragBy(change.position.y - down.position.y)
            while (true) {
                val next = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                // Los pasos en la pantalla: la fila cambia de lugar en la lista mientras tanto.
                val dy = next.positionChange().y
                next.consume()
                onDragBy(dy)
                if (!next.pressed) break
            }
        } finally {
            onDragEnd()
        }
    }
}

/** Qué hace el deslizamiento de una fila con el dedo todavía cerca de donde bajó ([checklistSwipeStart]). */
internal enum class SwipeStart { Wait, Claim, Release }

/**
 * Si la fila se queda con el gesto: con el dedo corrido ([dx], [dy]) más que [slop] y más
 * en horizontal que en vertical, siempre, aunque para ese lado no se pueda anidar (ahí la fila
 * no se mueve y el campo no selecciona). Lo suelta si es vertical (desplazar la
 * nota), después del toque largo ([elapsed] ≥ [longPress]) o en el segundo toque de un doble
 * toque ([secondTap]): seleccionar en un ítem.
 */
internal fun checklistSwipeStart(dx: Float, dy: Float, slop: Float, elapsed: Long, longPress: Long, secondTap: Boolean): SwipeStart =
    when {
        secondTap || elapsed >= longPress -> SwipeStart.Release
        hypot(dx, dy) <= slop -> SwipeStart.Wait
        abs(dx) > abs(dy) -> SwipeStart.Claim
        else -> SwipeStart.Release
    }

/**
 * Deslizar la fila en horizontal para anidar o desanidar, también sobre el
 * texto: [checklistSwipeStart] decide si se queda con el gesto; entonces [onClaim] avisa,
 * [onSwipe] recibe cuánto se corrió el dedo desde que bajó y [onRelease] avisa al soltar. Con
 * mouse o lápiz no se queda con el gesto.
 */
private fun Modifier.nestSwipe(
    onClaim: () -> Unit,
    onSwipe: (dx: Float) -> Unit,
    onRelease: () -> Unit,
): Modifier = composed {
    val lastUp = remember { LastUp() }
    pointerInput(Unit) {
        val doubleTapSlop = DOUBLE_TAP_SLOP.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            val secondTap = down.uptimeMillis - lastUp.time <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastUp.position).getDistance() <= doubleTapSlop
            var change: PointerInputChange
            while (true) {
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    lastUp.time = change.uptimeMillis
                    lastUp.position = change.position
                    return@awaitEachGesture
                }
                if (change.isConsumed) return@awaitEachGesture
                val moved = change.position - down.position
                val start = checklistSwipeStart(
                    moved.x, moved.y, viewConfiguration.touchSlop,
                    change.uptimeMillis - down.uptimeMillis, viewConfiguration.longPressTimeoutMillis, secondTap,
                )
                if (start == SwipeStart.Release) return@awaitEachGesture
                if (start == SwipeStart.Claim) break
            }
            var dx = change.position.x - down.position.x
            try {
                // Consumido, el campo recibe CANCEL: no selecciona.
                change.consume()
                onClaim()
                onSwipe(dx)
                while (true) {
                    val next = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    dx += next.positionChange().x
                    next.consume()
                    onSwipe(dx)
                    if (!next.pressed) break
                }
            } finally {
                onRelease()
            }
        }
    }
}

/** El último toque que se levantó en la fila, para reconocer el segundo de un doble toque. */
private class LastUp {
    var time = Long.MIN_VALUE / 2
    var position = Offset.Zero
}

/** La distancia de un doble toque de Android (`ViewConfiguration.getScaledDoubleTapSlop`, 100 dp). */
private val DOUBLE_TAP_SLOP = 100.dp
