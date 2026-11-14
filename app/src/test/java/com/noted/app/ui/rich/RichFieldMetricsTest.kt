package com.noted.app.ui.rich

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.Typography
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * El campo con formato mide como el `BasicTextField` de antes: `bodyLarge` con
 * `lineHeight = 28.sp` y los títulos con su tamaño en negrita (MarkdownVisualTransformation).
 * El de antes se arma acá con un `Paragraph` de Compose con ese mismo estilo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichFieldMetricsTest {

    private val width = 1000
    private val body = Typography().bodyLarge.copy(lineHeight = 28.sp)
    private val headingSp = floatArrayOf(25f, 22f, 19f, 17f)

    private lateinit var activity: Activity
    private var dpi = 1f

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        dpi = activity.resources.displayMetrics.density
    }

    private fun style() = RichStyle(
        primary = 0, onSurfaceVariant = 0, surfaceVariant = 0, checkedText = 0, onBackground = 0, dark = false,
        textSizePx = body.fontSize.value * dpi,
        typeface = Typeface.DEFAULT,
        letterSpacingEm = body.letterSpacing.value / body.fontSize.value,
        lineHeightPx = 28f * dpi,
        density = dpi,
    )

    private fun field(html: String): RichEditText {
        val field = RichEditText(activity)
        field.style = style()
        val value = RichText.fromHtml(html)
        field.setValue(value.withSelection(TextRange(0)))
        activity.setContentView(field, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        field.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        field.layout(0, 0, field.measuredWidth, field.measuredHeight)
        return field
    }

    /** El campo de antes: el mismo texto, sin las marcas de Markdown, con los títulos en su tamaño. */
    private fun before(html: String): Paragraph {
        val text = RichText.fromHtml(html).text
        val styles = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var start = 0
        while (start <= text.length) {
            val end = lineEndOf(text, start)
            val heading = RichHtml.blockMarksAt(text, start).firstOrNull { it is HeadingMark } as HeadingMark?
            if (heading != null && end > start) {
                val size = headingSp[(heading.level - 1).coerceIn(0, headingSp.lastIndex)]
                styles.add(AnnotatedString.Range(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.Bold), start, end))
            }
            start = end + 1
        }
        return Paragraph(
            text = text.toString(),
            style = body,
            constraints = Constraints(maxWidth = width),
            density = Density(dpi, 1f),
            fontFamilyResolver = createFontFamilyResolver(activity),
            spanStyles = styles,
        )
    }

    /** Alto total y base de cada renglón con texto, sin el margen de 2 dp que tienen los dos. */
    private fun assertSameAsBefore(html: String) {
        val field = field(html)
        val old = before(html)
        val margin = (2 * dpi).roundToInt()
        assertEquals("$html: alto", old.height.roundToInt(), field.measuredHeight - 2 * margin)
        val layout = field.layout
        assertEquals("$html: renglones", old.lineCount, layout.lineCount)
        for (line in 0 until old.lineCount) {
            if (layout.getLineStart(line) == layout.getLineEnd(line)) continue // el último vacío: lo mide el alto
            assertEquals(
                "$html: base del renglón $line",
                old.getLineBaseline(line).roundToInt(),
                field.totalPaddingTop - margin + layout.getLineBaseline(line),
            )
        }
    }

    @Test
    fun oneLineHasTheOldMargins() = assertSameAsBefore("<div>hola</div>")

    @Test
    fun emptyFieldIsOneFullLine() = assertSameAsBefore("")

    @Test
    fun emptyLineAtTheEndIsAFullLine() {
        assertSameAsBefore("<div>a</div><div><br></div>")
        assertSameAsBefore("<ul><li>a</li></ul><div><br></div>")
    }

    @Test
    fun severalLines() = assertSameAsBefore("<div>a</div><div>b</div><blockquote>c</blockquote><div>d</div>")

    @Test
    fun headingInTheMiddleKeepsTheLineBox() {
        assertSameAsBefore("<div>a</div><h2>Título</h2><div>b</div>")
        assertSameAsBefore("<div>a</div><h3>Título</h3><div>b</div><h4>x</h4>")
    }

    @Test
    fun headingFirstSetsTheLineBox() {
        assertSameAsBefore("<h2>Título</h2><div>b</div>")
        // Un título 1 no entra en 28 sp: el campo suma lo que sobresale.
        assertSameAsBefore("<h1>Título</h1><div>b</div>")
        assertSameAsBefore("<h1>Título</h1>")
    }

    @Test
    fun turningTheFirstLineIntoHeadingRelaysEveryLine() {
        val field = field("<div>a</div><div>b</div><div>c</div>")
        field.setValue(RichCommands.toggleLine(field.value.withSelection(TextRange(0)), LineFormat.Heading2))
        field.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        field.layout(0, 0, field.measuredWidth, field.measuredHeight)
        val old = before("<h2>a</h2><div>b</div><div>c</div>")
        val margin = (2 * dpi).roundToInt()
        for (line in 0 until old.lineCount) {
            assertEquals(old.getLineBaseline(line).roundToInt(), field.totalPaddingTop - margin + field.layout.getLineBaseline(line))
        }
    }
}
