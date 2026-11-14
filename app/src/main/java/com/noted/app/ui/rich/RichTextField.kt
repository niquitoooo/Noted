package com.noted.app.ui.rich

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.staticCompositionLocalOf
import android.net.Uri
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.noted.app.ui.EditorShortcut

/**
 * Recibe las imágenes o videos que llegan a un campo de la nota abierta ([RichEditText.onMedia]).
 * Sin él, el campo no le ofrece al teclado recibir imágenes.
 */
val LocalReceiveMedia = staticCompositionLocalOf<((List<Uri>, () -> Unit) -> Unit)?> { null }

/**
 * Un bloque de texto con formato: un [RichEditText] dentro de Compose. Como el campo de hoy
 *, lleva el cursor a la vista mientras se escribe, con 32 dp de margen arriba y abajo.
 * Con [item] es el texto de un ítem de checklist ([RichEditText.item]). [onShortcut] recibe los
 * atajos del editor ([RichEditText.onShortcut]). [isFirstInNote] dice si el bloque es lo
 * primero de la nota ([RichEditText.isFirstInNote]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RichTextField(
    value: RichText,
    onValueChange: (RichText) -> Unit,
    style: RichStyle,
    focusRequested: Boolean,
    onFocusRequestConsumed: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onFocusPrevious: (Float) -> Unit,
    onFocusNext: (Float) -> Unit,
    onGeometry: (TextGeometry) -> Unit,
    modifier: Modifier = Modifier,
    item: ItemCallbacks? = null,
    onShortcut: (EditorShortcut) -> Boolean = { false },
    isFirstInNote: () -> Boolean = { false },
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val field = remember { arrayOfNulls<RichEditText>(1) }
    var isFocused by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<Rect?>(null) }
    val cursorMargin = with(LocalDensity.current) { 32.dp.toPx() }
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)
    val onMedia = LocalReceiveMedia.current

    LaunchedEffect(focusRequested) {
        if (!focusRequested) return@LaunchedEffect
        field[0]?.let { view ->
            if (!view.requestFocus()) view.post { view.requestFocus() }
            view.post {
                val keyboard = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                keyboard.showSoftInput(view, 0)
            }
        }
        bringIntoViewRequester.bringIntoView()
        onFocusRequestConsumed()
    }

    // Mientras se escribe (por ejemplo, Enter sostenido) la nota sigue al cursor, con un
    // margen para que no quede pegado al borde ni bajo la barra.
    LaunchedEffect(cursor, isFocused) {
        if (!isFocused) return@LaunchedEffect
        val rect = cursor ?: return@LaunchedEffect
        withFrameNanos { } // esperar el layout con el texto nuevo
        bringIntoViewRequester.bringIntoView(rect.copy(top = rect.top - cursorMargin, bottom = rect.bottom + cursorMargin))
    }

    AndroidView(
        factory = { context ->
            RichEditText(context).also { view ->
                field[0] = view
                view.setOnFocusChangeListener { _, focused ->
                    isFocused = focused
                    currentOnFocusChanged(focused)
                }
                view.onCursorRect = { rect -> cursor = Rect(rect.left, rect.top, rect.right, rect.bottom) }
                view.addOnAttachStateChangeListener(ShowHolderToAccessibility)
            }
        },
        update = { view ->
            view.style = style
            view.onValueChange = onValueChange
            view.onFocusPrevious = onFocusPrevious
            view.onFocusNext = onFocusNext
            view.item = item
            view.onShortcut = onShortcut
            view.onMedia = onMedia
            view.isFirstInNote = isFirstInNote
            view.setValue(value)
            onGeometry(view.geometry)
        },
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
    )
}

/**
 * Compose 1.7 marca como invisible para la accesibilidad la vista que envuelve al campo
 * dentro de `AndroidView` (cuando hay algún servicio de accesibilidad prendido), y con ella el
 * volcado de uiautomator descarta al campo y su texto. El campo de antes aparecía como
 * `EditText` con su texto: el envoltorio se vuelve a mostrar si está a la vista. Compose pone su
 * delegado después de agregar la vista a la ventana: se lo envuelve en el cuadro siguiente.
 */
private object ShowHolderToAccessibility : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(v: View) {
        v.post {
            val holder = v.parent as? View ?: return@post
            val compose = ViewCompat.getAccessibilityDelegate(holder)
            if (compose !is Shown) ViewCompat.setAccessibilityDelegate(holder, Shown(compose))
        }
    }

    override fun onViewDetachedFromWindow(v: View) = Unit

    /** El delegado de Compose, sin el "invisible". */
    private class Shown(private val compose: AccessibilityDelegateCompat?) : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            if (compose != null) compose.onInitializeAccessibilityNodeInfo(host, info)
            else super.onInitializeAccessibilityNodeInfo(host, info)
            info.isVisibleToUser = host.isShown
        }
    }
}

/**
 * Al salir del editor: si el foco está en un campo con formato, lo suelta y esconde el
 * teclado. Es una vista de Android: al sacarla, el teclado no se esconde solo como con el
 * `BasicTextField` de antes, y la vista con el foco se lo pasaría a la próxima nota. Se llama
 * antes de salir: cuando Compose avisa que el editor se fue, las vistas ya no están.
 */
fun releaseRichField(root: View) {
    val field = root.findFocus() as? RichEditText ?: return
    val keyboard = field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    keyboard.hideSoftInputFromWindow(field.windowToken, 0)
    field.clearFocus()
}

/**
 * El estilo del campo desde el tema: `bodyLarge` con interlineado de 28 sp, como hoy. El ítem
 * tildado va en gris según el tema.
 */
@Composable
fun rememberRichStyle(): RichStyle {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val typeface by LocalFontFamilyResolver.current.resolveAsTypeface(
        body.fontFamily,
        body.fontWeight ?: FontWeight.Normal,
        body.fontStyle ?: FontStyle.Normal,
        body.fontSynthesis ?: FontSynthesis.All,
    )
    val dark = colors.background.luminance() < 0.5f
    return remember(colors, body, density, typeface, dark) {
        with(density) {
            RichStyle(
                primary = colors.primary.toArgb(),
                onSurfaceVariant = colors.onSurfaceVariant.toArgb(),
                surfaceVariant = colors.surfaceVariant.toArgb(),
                checkedText = if (dark) CHECKED_DARK else CHECKED_LIGHT,
                onBackground = colors.onBackground.toArgb(),
                dark = dark,
                textSizePx = body.fontSize.toPx(),
                typeface = typeface,
                letterSpacingEm = letterSpacingEm(body),
                lineHeightPx = 28.sp.toPx(),
                // Píxeles por sp, con la escala de letra del aparato.
                density = 1.sp.toPx(),
            )
        }
    }
}

/** El gris del ítem tildado: #909090 en claro y #808080 en oscuro. */
private const val CHECKED_LIGHT = 0xFF909090.toInt()
private const val CHECKED_DARK = 0xFF808080.toInt()

/** El `letterSpacing` de Compose en em, como lo pide `TextView.setLetterSpacing`. */
private fun letterSpacingEm(style: TextStyle): Float {
    val spacing = style.letterSpacing
    return when {
        spacing.isEm -> spacing.value
        spacing.isSp && style.fontSize.isSp && style.fontSize.value > 0f -> spacing.value / style.fontSize.value
        else -> 0f
    }
}
