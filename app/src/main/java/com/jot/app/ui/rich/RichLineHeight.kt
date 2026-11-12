package com.jot.app.ui.rich

import android.graphics.Paint
import android.text.style.LineHeightSpan
import kotlin.math.ceil

/**
 * El renglón del campo de antes: un `BasicTextField` de Compose 1.7 con `bodyLarge`,
 * `lineHeight = 28.sp` y el `LineHeightStyle` de Material 3 (centrado, sin recortar). Así
 * medía cada renglón:
 *
 * - Todos miden `ceil(lineHeight)`, también el primero y el último: lo que sobra a la letra
 *   va mitad arriba y mitad abajo (con un `EditText.setLineHeight` falta arriba del primero y
 *   abajo del último).
 * - La caja (subida y bajada) sale del primer renglón y vale para todos: un título en el medio
 *   queda con la base donde la tendría el texto común, y su letra más alta sobresale arriba.
 * - Si la letra del primer renglón no entra (un título 1 arriba de todo), el campo suma lo que
 *   sobresale como relleno arriba y abajo.
 * - Un último renglón vacío (texto vacío o terminado en `\n`) no pasa por el span: mide lo que
 *   mediría un renglón de texto común, con su caja propia.
 */
internal data class LineBox(val ascent: Int, val descent: Int) {

    companion object {
        /** La caja de un renglón de letra [fm] con [lineHeightPx] de alto, centrada. */
        fun of(fm: Paint.FontMetricsInt, lineHeightPx: Float): LineBox {
            val height = ceil(lineHeightPx).toInt()
            val diff = height - (fm.descent - fm.ascent)
            // La mitad de lo que sobra (o falta) va abajo, redondeado hacia arriba.
            val descent = fm.descent + ceil(diff * 0.5f).toInt()
            return LineBox(descent - height, descent)
        }
    }
}

/**
 * Pone a cada renglón la caja del primero, como el campo de antes. La caja se calcula al pasar
 * por el primer renglón; si cambia (por ejemplo, el primer renglón pasa a título), los demás
 * renglones ya armados quedan viejos: el campo compara [box] con la de su último armado y, si
 * cambió, rearma todo. Con [frozen] se usa la caja que hay sin recalcularla: así no la cambian
 * los rearmados de paso mientras se sacan y se vuelven a poner los spans de dibujo.
 */
internal class LineHeightRender(val lineHeightPx: Float) : LineHeightSpan {

    /** La caja de todos los renglones, o `null` si todavía no se armó el primero. */
    var box: LineBox? = null
        private set

    /** La letra del primer renglón, con la que se miden los rellenos. */
    private var firstAscent = 0
    private var firstDescent = 0

    /** No recalcular la caja al pasar por el primer renglón. */
    var frozen = false

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        if (fm.descent - fm.ascent <= 0) return
        if ((start == 0 && !frozen) || box == null) {
            box = LineBox.of(fm, lineHeightPx)
            firstAscent = fm.ascent
            firstDescent = fm.descent
        }
        val box = box!!
        fm.ascent = box.ascent
        fm.descent = box.descent
    }

    /** Lo que la letra del primer renglón sobresale de la caja arriba, o 0. */
    val topOverflow: Int get() = box?.let { (it.ascent - firstAscent).coerceAtLeast(0) } ?: 0

    /** Lo que la letra del primer renglón sobresale de la caja abajo, o 0. */
    val bottomOverflow: Int get() = box?.let { (firstDescent - it.descent).coerceAtLeast(0) } ?: 0
}
