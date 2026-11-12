package com.jot.app.ui.rich

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlin.math.roundToInt

/**
 * Colores y medidas con que se dibuja el texto con formato, sacados del tema.
 * [onBackground], [typeface], [letterSpacingEm] y [lineHeightPx] los usa el campo;
 * [lineHeightPx] va sin redondear (`28.sp.toPx()`), como el `lineHeight` de Compose.
 *
 * [density] son los píxeles de un sp (`1.sp.toPx()`, con la escala de letra del aparato):
 * con él se pasan a píxeles los tamaños de los títulos, que hoy están en sp.
 *
 * [checkedItemText] es el texto de un ítem marcado del bloque de checklist: el color del texto
 * al 38 %. [checkedText] es otro: el gris de los renglones tildados que
 * quedan en el texto.
 */
data class RichStyle(
    val primary: Int,
    val onSurfaceVariant: Int,
    val surfaceVariant: Int,
    val checkedText: Int,
    val onBackground: Int,
    val dark: Boolean,
    val textSizePx: Float,
    val typeface: Typeface,
    val letterSpacingEm: Float,
    val lineHeightPx: Float,
    val density: Float,
    val checkedItemText: Int = withAlpha(onBackground, CHECKED_ITEM_ALPHA),
)

/** La opacidad del texto de un ítem marcado (V13: `alphaDisabled`, 0,38). */
private const val CHECKED_ITEM_ALPHA = 0.38f

/** [color] con su alfa multiplicado por [alpha]. */
private fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or (((color ushr 24) * alpha).roundToInt() shl 24)

/**
 * El dibujo de las marcas con el aspecto de la app (los estilos que tenía
 * `MarkdownVisualTransformation` en el editor Markdown de antes), más lo
 * que ese editor no tenía: el ítem tildado
 * en gris sin tachar, subrayado, sub y superíndice y los
 * colores de texto y de resaltado.
 */
object RichRender {

    private const val QUOTE_LABEL = "▎ "

    /** Color del separador de línea en oscuro y en claro. */
    private const val DIVIDER_DARK = 0xFF3A3A3A.toInt()
    private const val DIVIDER_LIGHT = 0xFFE3E3E3.toInt()
    private const val CHAR_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    /** Tamaño en sp de los títulos 1 a 3; del 4 al 6, el último. */
    private val HEADING_SP = floatArrayOf(25f, 22f, 19f, 17f)

    /** Sub y superíndice: 14 px sobre los 17 del texto. */
    private const val SCRIPT_SIZE = 14f / 17f

    /**
     * Pone los [RenderSpan] de [text] según las marcas. Compara renglón por renglón: los de un
     * renglón que quedarían iguales no se tocan, así escribir una letra no rearma los párrafos
     * con formato del resto del texto. Los de un renglón que cambia se sacan y se ponen
     * todos, en el orden de siempre (primero los del renglón, después los de las marcas en
     * línea), que es el que decide qué color gana.
     *
     * Con [checkedItem] (el texto de un ítem marcado del bloque de checklist) cada renglón lleva
     * al final un color [RichStyle.checkedItemText] de todo su ancho: es el último y les gana a
     * los colores de texto y de enlace, así se ve todo gris encima del formato.
     *
     * Con [changed] (las posiciones del texto nuevo que tocó un cambio, las dos puntas incluidas:
     * `p..p + n` al agregar `n` caracteres en `p`, `p..p` al borrar) solo se miran los renglones
     * de esas posiciones y, por los números, los ítems seguidos de abajo hasta que corta la lista;
     * los ítems de arriba de la misma lista solo se cuentan. El resto del texto no se recorre.
     * Sin [changed], todo el texto.
     */
    fun refresh(text: Spannable, style: RichStyle, checkedItem: Boolean = false, changed: IntRange? = null) {
        val paint = markerPaint(style)
        val numbers = OrderedNumbers()
        val from = if (changed == null) 0 else touchedFrom(text, changed.first.coerceIn(0, text.length))
        // Los renglones que empiezan hasta acá se miran siempre; después, solo los ítems.
        val touchedEnd = changed?.last?.coerceIn(from, text.length) ?: text.length
        if (changed != null) countItemsAbove(text, from, numbers)
        val lineStarts = ArrayList<Int>()
        val code = ArrayList<Boolean>()
        val wanted = ArrayList<MutableList<Placed>>()
        var start = from
        var lastEnd: Int
        var last = false
        while (true) {
            val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val blockEnd = if (newline < text.length) newline + 1 else newline
            val blocks = RichHtml.blockMarksAt(text, start)
            val out = ArrayList<Placed>()
            Line(out, start, newline, blockEnd).render(blocks, style, paint, numbers)
            lineStarts.add(start)
            code.add(blocks.any { it is CodeBlockMark })
            wanted.add(out)
            lastEnd = newline
            start = newline + 1
            if (start > text.length || last) break
            // Pasado lo tocado va un renglón más, aunque corte la lista: al borrar un `\n`, el
            // `Spannable` corre los spans de párrafo del renglón que se unió al principio del
            // siguiente, y ahí quedan de ese renglón.
            if (start > touchedEnd && !continuesList(RichHtml.blockMarksAt(text, start))) last = true
        }
        // El fin de lo que se mira: después del `\n` del último renglón, o el final del texto.
        val regionEnd = if (lastEnd < text.length) lastEnd + 1 else lastEnd
        // Cada span es del renglón donde empieza: de [from] a [lastEnd], el `\n` incluido.
        fun owned(pos: Int) = pos in from..lastEnd
        for (mark in text.getSpans(from, regionEnd, InlineMark::class.java)) {
            val markStart = text.getSpanStart(mark)
            val markEnd = text.getSpanEnd(mark)
            if (!owned(markStart)) continue
            val line = lineIndex(lineStarts, markStart)
            // El código se guarda en texto plano: el formato en línea ahí no se dibuja.
            if (markEnd > markStart && !code[line]) {
                for (span in inlineSpans(mark, style)) wanted[line].add(Placed(span, markStart, markEnd, CHAR_FLAGS))
            }
        }
        if (checkedItem) {
            for (line in lineStarts.indices) {
                val lineStart = lineStarts[line]
                val lineEnd = if (line < lineStarts.lastIndex) lineStarts[line + 1] - 1 else lastEnd
                if (lineEnd > lineStart) {
                    wanted[line].add(Placed(ForegroundRender(style.checkedItemText), lineStart, lineEnd, CHAR_FLAGS))
                }
            }
        }
        val existing = List(wanted.size) { ArrayList<RenderSpan>() }
        for (span in text.getSpans(from, regionEnd, RenderSpan::class.java)) {
            val spanStart = text.getSpanStart(span)
            if (owned(spanStart)) existing[lineIndex(lineStarts, spanStart)].add(span)
        }
        for (line in wanted.indices) {
            val have = existing[line]
            val want = wanted[line]
            if (have.size == want.size && have.indices.all { same(text, have[it], want[it]) }) continue
            for (span in have) text.removeSpan(span)
            for (placed in want) text.setSpan(placed.span, placed.start, placed.end, placed.flags)
        }
    }

    /**
     * El principio del primer renglón que hay que mirar por un cambio en [pos]: el de [pos], o
     * uno de arriba si un span suyo se metió en ese renglón. Pasa con el rótulo que llega al
     * final del texto: lo que se escribe en el ítem vacío de abajo lo estira.
     */
    private fun touchedFrom(text: Spanned, pos: Int): Int {
        val lineStart = lineStartOf(text, pos)
        var from = lineStart
        for (span in text.getSpans(lineStart, lineStart, RenderSpan::class.java)) {
            val spanStart = text.getSpanStart(span)
            if (spanStart < from && text.getSpanEnd(span) > lineStart) from = spanStart
        }
        return lineStartOf(text, from)
    }

    /**
     * Si el renglón de [blocks] sigue la lista de arriba: es un ítem, y no una cita, un código ni
     * un separador. Cualquier otro renglón corta la lista ([OrderedNumbers.close]) y lo de
     * abajo cuenta de cero, sin importar lo de arriba.
     */
    private fun continuesList(blocks: List<BlockMark>): Boolean =
        blocks.any { it is ListMark } && blocks.none { it is CodeBlockMark || it is DividerMark || it is QuoteMark }

    /**
     * Cuenta en [numbers] los ítems de la lista que sigue hasta el renglón de [lineStart], sin
     * dibujarlos: sube mientras haya ítems seguidos y los cuenta de arriba para abajo.
     */
    private fun countItemsAbove(text: Spanned, lineStart: Int, numbers: OrderedNumbers) {
        val above = ArrayList<ListMark>()
        var start = lineStart
        while (start > 0) {
            start = lineStartOf(text, start - 1)
            val blocks = RichHtml.blockMarksAt(text, start)
            if (!continuesList(blocks)) break
            above.add(blocks.first { it is ListMark } as ListMark)
        }
        for (item in above.asReversed()) numbers.next(item)
    }

    /** Un span de dibujo con el tramo y las banderas con que va. */
    private class Placed(val span: RenderSpan, val start: Int, val end: Int, val flags: Int)

    /** El renglón (por su índice en [lineStarts]) donde está [pos]. */
    private fun lineIndex(lineStarts: List<Int>, pos: Int): Int =
        lineStarts.binarySearch(pos).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)

    /** Si el span que ya está en [text] es el que se pondría: misma clase, valores, tramo y banderas. */
    private fun same(text: Spanned, span: RenderSpan, placed: Placed): Boolean =
        span.javaClass == placed.span.javaClass && valueOf(span) == valueOf(placed.span) &&
            text.getSpanStart(span) == placed.start && text.getSpanEnd(span) == placed.end &&
            // El texto suma banderas propias (dónde quedó el span al editar): solo cuentan las de punto y marca.
            text.getSpanFlags(span) and Spanned.SPAN_POINT_MARK_MASK == placed.flags

    /** Lo que dibuja cada span de dibujo, para compararlos. */
    internal fun valueOf(span: RenderSpan): Any = when (span) {
        is MarkerSpan -> listOf(span.label, span.width, span.color, span.empty)
        is DividerSpan -> listOf(span.color, span.thicknessPx)
        is SizeSpan -> span.px
        is StyleSpan -> span.style
        is ForegroundColorSpan -> span.foregroundColor
        is BackgroundColorSpan -> span.backgroundColor
        is TypefaceSpan -> span.family ?: ""
        is RelativeSizeSpan -> span.sizeChange
        else -> Unit
    }

    /** La letra del texto, con la que se mide el ancho de las viñetas, números y cita. */
    private fun markerPaint(style: RichStyle) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = style.textSizePx
        typeface = style.typeface
        letterSpacing = style.letterSpacingEm
    }

    /**
     * Un renglón: el contenido es `[start, end)` y el bloque llega hasta [blockEnd], con su `\n`.
     * Sus spans de dibujo van a [out], en orden.
     */
    private class Line(val out: MutableList<Placed>, val start: Int, val end: Int, val blockEnd: Int) {

        fun render(blocks: List<BlockMark>, style: RichStyle, paint: TextPaint, numbers: OrderedNumbers) {
            val heading = blocks.firstOrNull { it is HeadingMark } as HeadingMark?
            val item = blocks.firstOrNull { it is ListMark } as ListMark?
            // El mismo orden que RichHtml.appendTo: así el número corta donde corta la lista.
            when {
                blocks.any { it is CodeBlockMark } -> {
                    numbers.close()
                    content(TypefaceRender("monospace"), BackgroundRender(style.surfaceVariant))
                }
                blocks.any { it is DividerMark } -> {
                    numbers.close()
                    // El separador de línea: 1 dp, de borde a borde, #3A3A3A
                    // (oscuro) o #E3E3E3 (claro).
                    content(DividerSpan(if (style.dark) DIVIDER_DARK else DIVIDER_LIGHT, style.density))
                }
                blocks.any { it is QuoteMark } -> {
                    numbers.close()
                    marker(QUOTE_LABEL, style, paint)
                    content(ForegroundRender(style.onSurfaceVariant), StyleRender(Typeface.ITALIC))
                }
                item != null -> {
                    marker("  ".repeat(item.depth) + label(item, numbers), style, paint)
                    if (item.kind == ListKind.Check && item.checked) content(ForegroundRender(style.checkedText))
                    heading?.let { heading(it, style) }
                }
                else -> {
                    numbers.close()
                    heading?.let { heading(it, style) }
                }
            }
        }

        /** Todo ítem cuenta, aunque no sea numerado: corta o sigue la lista de su nivel. */
        private fun label(item: ListMark, numbers: OrderedNumbers): String {
            val number = numbers.next(item)
            return when (item.kind) {
                ListKind.Bullet -> "• "
                ListKind.Ordered -> "$number. "
                ListKind.Check -> if (item.checked) "☑ " else "☐ "
            }
        }

        private fun heading(heading: HeadingMark, style: RichStyle) {
            val sp = HEADING_SP[(heading.level - 1).coerceIn(0, HEADING_SP.lastIndex)]
            content(StyleRender(Typeface.BOLD), SizeSpan(sp * style.density))
        }

        private fun marker(label: String, style: RichStyle, paint: TextPaint) {
            out.add(
                Placed(
                    MarkerSpan(label, paint.measureText(label).roundToInt(), style.primary, empty = start == blockEnd),
                    start, blockEnd, Spanned.SPAN_PARAGRAPH,
                ),
            )
        }

        private fun content(vararg spans: RenderSpan) {
            if (end > start) for (span in spans) out.add(Placed(span, start, end, CHAR_FLAGS))
        }
    }

    private fun inlineSpans(mark: InlineMark, style: RichStyle): List<RenderSpan> = when (mark) {
        is BoldMark -> listOf(StyleRender(Typeface.BOLD))
        is ItalicMark -> listOf(StyleRender(Typeface.ITALIC))
        is UnderlineMark -> listOf(UnderlineRender())
        is StrikeMark -> listOf(StrikeRender())
        is SubscriptMark -> listOf(SubscriptRender(), RelativeSizeRender(SCRIPT_SIZE))
        is SuperscriptMark -> listOf(SuperscriptRender(), RelativeSizeRender(SCRIPT_SIZE))
        is CodeMark -> listOf(TypefaceRender("monospace"), BackgroundRender(style.surfaceVariant))
        // No se abre al tocarlo, como hoy.
        is LinkMark -> listOf(ForegroundRender(style.primary), UnderlineRender())
        is TextColorMark -> listOfNotNull(
            (if (style.dark) TEXT_DARK else TEXT_LIGHT)[mark.color]?.let { ForegroundRender(it) }
        )
        is HighlightMark -> listOfNotNull(
            (if (style.dark) HIGHLIGHT_DARK else HIGHLIGHT_LIGHT)[mark.color ?: "green"]?.let { BackgroundRender(it) }
        )
    }

    /**
     * Los números de las listas numeradas, contados igual que arma las listas `ListBuilder`
     * al guardar: un párrafo, una cita, un código o una lista de otro tipo en el mismo nivel
     * abren una lista nueva, que vuelve a empezar.
     */
    private class OrderedNumbers {
        private class Open(val level: Int, val ordered: Boolean) {
            var start = 1
            var items = 0
        }

        private val open = ArrayList<Open>()

        fun close() = open.clear()

        /** Cuenta el ítem y devuelve su número (vale solo para los numerados). */
        fun next(item: ListMark): Int {
            val ordered = item.kind == ListKind.Ordered
            while (open.isNotEmpty() && open.last().level > item.depth) open.removeAt(open.lastIndex)
            if (open.isNotEmpty() && open.last().level == item.depth && open.last().ordered != ordered) {
                open.removeAt(open.lastIndex)
            }
            while ((open.lastOrNull()?.level ?: -1) < item.depth) {
                open.add(Open((open.lastOrNull()?.level ?: -1) + 1, ordered))
            }
            val list = open.last()
            // Como el `start` del `<ol>`: solo cuenta el del primer ítem.
            if (list.items == 0) list.start = item.start ?: 1
            return list.start + list.items++
        }
    }

    // ---- spans de dibujo ----

    /**
     * Viñeta, número, casillero o cita: [label] en el color primario, en el margen del primer
     * renglón. Los renglones siguientes del mismo párrafo arrancan en el borde, como hoy.
     *
     * El de un ítem vacío al final del texto ([empty], de largo 0) no corre nada: el `Layout` se
     * lo suma al párrafo de arriba, que quedaría con dos márgenes. Lo dibuja el campo.
     */
    internal class MarkerSpan(val label: String, val width: Int, val color: Int, val empty: Boolean = false) :
        LeadingMarginSpan, RenderSpan {

        override fun getLeadingMargin(first: Boolean): Int = if (first && !empty) width else 0

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
        ) {
            if (!first || (text is Spanned && text.getSpanStart(this) != start)) return
            val saved = p.color
            p.color = color
            val left = if (dir < 0) x - p.measureText(label) else x.toFloat()
            c.drawText(label, left, baseline.toFloat(), p)
            p.color = saved
        }
    }

    /**
     * El separador: una línea fina de [thicknessPx] que cruza todo el renglón, a media altura
     * Su carácter no ocupa lugar ni se dibuja.
     */
    internal class DividerSpan(val color: Int, val thicknessPx: Float = 1f) :
        ReplacementSpan(), LineBackgroundSpan, RenderSpan {

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) paint.getFontMetricsInt(fm)
            return 0
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint,
        ) = Unit

        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int,
            bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            val saved = paint.color
            val savedStyle = paint.style
            paint.color = color
            paint.style = Paint.Style.FILL
            val middle = (top + bottom) / 2f
            val half = thicknessPx.coerceAtLeast(1f) / 2f
            canvas.drawRect(left.toFloat(), middle - half, right.toFloat(), middle + half, paint)
            paint.color = saved
            paint.style = savedStyle
        }
    }

    /** Tamaño de letra en píxeles, sin redondear (los títulos). */
    internal class SizeSpan(val px: Float) : MetricAffectingSpan(), RenderSpan {
        override fun updateMeasureState(textPaint: TextPaint) {
            textPaint.textSize = px
        }

        override fun updateDrawState(tp: TextPaint) {
            tp.textSize = px
        }
    }

    private class StyleRender(style: Int) : StyleSpan(style), RenderSpan
    private class ForegroundRender(color: Int) : ForegroundColorSpan(color), RenderSpan
    private class BackgroundRender(color: Int) : BackgroundColorSpan(color), RenderSpan
    private class TypefaceRender(family: String) : TypefaceSpan(family), RenderSpan
    private class UnderlineRender : UnderlineSpan(), RenderSpan
    private class StrikeRender : StrikethroughSpan(), RenderSpan
    private class SubscriptRender : SubscriptSpan(), RenderSpan
    private class SuperscriptRender : SuperscriptSpan(), RenderSpan
    private class RelativeSizeRender(size: Float) : RelativeSizeSpan(size), RenderSpan

    // ---- colores ----

    private val TEXT_LIGHT = colors(
        "red" to 0xFFF9241C, "orange" to 0xFFF98700, "yellow" to 0xFFD3AD53, "green" to 0xFF389F08,
        "blue" to 0xFF2070F7, "pink" to 0xFFE359E1, "purple" to 0xFF8758FF, "gray" to 0xFF757575,
    )
    private val TEXT_DARK = colors(
        "red" to 0xFFFF5959, "orange" to 0xFFFD9E1F, "yellow" to 0xFFEDE508, "green" to 0xFF16DBA5,
        "blue" to 0xFF33A7FF, "pink" to 0xFFFF7BFD, "purple" to 0xFFC0A2FF, "gray" to 0xFF9A9A9A,
    )

    // El gris del resaltado es el fondo de los bloques de código.
    private val HIGHLIGHT_LIGHT = colors(
        "green" to 0xFFE7FFCD, "red" to 0xFFFEDEDE, "orange" to 0xFFFFE4CB, "blue" to 0xFFD8ECFD,
        "yellow" to 0xFFFBFBC3, "purple" to 0xFFEDDDFF, "pink" to 0xFFFFE4FB, "gray" to 0xFFF5F5F5,
    )
    private val HIGHLIGHT_DARK = colors(
        "green" to 0xFF386E53, "red" to 0xFF7B4242, "orange" to 0xFF744B20, "blue" to 0xFF22547D,
        "yellow" to 0xFF757018, "purple" to 0xFF58486F, "pink" to 0xFF713A66, "gray" to 0xFF363A41,
    )

    private fun colors(vararg pairs: Pair<String, Long>): Map<String, Int> = pairs.associate { (name, argb) -> name to argb.toInt() }
}
