package com.noted.app.ui.rich

import android.graphics.Paint
import android.text.DynamicLayout
import android.text.Editable
import android.text.Layout
import android.text.SpanWatcher
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [RichEditable] solo, con un `DynamicLayout` propio y sin [RichEditText]: después de cada
 * cambio, cada renglón tiene que ser el de un `StaticLayout` armado de cero con el mismo texto.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 30, 34, 35])
class RichEditableTest {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f }
    private val width = 260
    private val color = 0xFF6750A4.toInt()

    private lateinit var text: RichEditable
    private lateinit var layout: DynamicLayout

    /** Un tamaño grande sobre las tres primeras palabras del párrafo largo: cambia sus cortes. */
    private lateinit var big: RichRender.SizeSpan

    /** Las tres últimas palabras del párrafo largo. */
    private val lastWords get() = text.lastIndexOf("palabra palabra palabra").let { it until it + 23 }

    /**
     * Un título (más grande), un ítem con rótulo, un separador, un párrafo que corta en varios
     * renglones (con [big] al principio) y otro título abajo, con el interlineado de punta a
     * punta. Con el interlineado todos los renglones miden lo mismo: lo que cambia un tamaño
     * son los cortes.
     */
    private fun build() {
        text = RichEditable("")
        text.append("titulo\nitem uno\n─\n" + "palabra ".repeat(10).trim() + "\nabajo")
        span(RichRender.SizeSpan(26f), "titulo")
        val item = text.indexOf("item uno")
        text.setSpan(RichRender.MarkerSpan("• ", 20, color), item, item + "item uno\n".length, Spanned.SPAN_PARAGRAPH)
        span(RichRender.DividerSpan(color), "─")
        span(RichRender.SizeSpan(22f), "abajo")
        big = RichRender.SizeSpan(40f)
        span(big, "palabra palabra palabra")
        text.setSpan(LineHeightRender(28f), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        @Suppress("DEPRECATION")
        layout = DynamicLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        // El párrafo largo corta en varios renglones.
        assertTrue(layout.lineCount > 6)
        assertSameAsFresh("al armar")
    }

    private fun span(what: Any, word: String) {
        val at = text.indexOf(word)
        text.setSpan(what, at, at + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Cada renglón: cortes, alto, base, margen del párrafo y ancho. */
    private fun lines(layout: Layout): List<String> = (0 until layout.lineCount).map { line ->
        "${layout.getLineStart(line)}-${layout.getLineEnd(line)} top=${layout.getLineTop(line)} " +
            "bottom=${layout.getLineBottom(line)} base=${layout.getLineBaseline(line)} " +
            "margin=${layout.getParagraphLeft(line)} width=${layout.getLineWidth(line)}"
    }

    private fun assertSameAsFresh(step: String) {
        val copy = SpannableString(text)
        val fresh = StaticLayout.Builder.obtain(copy, 0, copy.length, paint, width)
            .setIncludePad(false)
            .build()
        assertEquals(step, lines(fresh), lines(layout))
    }

    @Test
    fun typingBeforeASizeSpan() {
        build()
        text.insert(text.indexOf("item uno") + 4, "zz")
        assertSameAsFresh("en el ítem, arriba del título de abajo")
        text.insert(text.indexOf("palabra"), "más ")
        assertSameAsFresh("en el párrafo largo")
    }

    @Test
    fun typingBeforeADividerSpan() {
        build()
        text.insert(text.indexOf("titulo") + 6, "s")
        assertSameAsFresh("al final del título")
        text.insert(text.indexOf("─"), "x")
        assertSameAsFresh("pegado al separador")
    }

    @Test
    fun replaceWithASpannedThatBringsASizeSpan() {
        build()
        val inserted = SpannableString("nu\nevo")
        inserted.setSpan(RichRender.SizeSpan(30f), 0, inserted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val at = text.indexOf("palabra")
        text.replace(at, at, inserted)
        assertSameAsFresh("lo pegado con su tamaño")
    }

    @Test
    fun deletingANewlineBetweenParagraphsOfDifferentKinds() {
        build()
        // El título y el ítem con rótulo.
        val newline = text.indexOf("\nitem")
        text.delete(newline, newline + 1)
        assertSameAsFresh("título + ítem")
        // El separador y el párrafo largo.
        val second = text.indexOf("─\n") + 1
        text.delete(second, second + 1)
        assertSameAsFresh("separador + párrafo")
    }

    @Test
    fun movingASpanOutsideAChangeStillRelayouts() {
        // Fuera de replace el DynamicLayout tiene que enterarse de todo: si el corte por la
        // etapa de replace no anduviera, este corrimiento no lo rearmaría.
        build()
        val before = lines(layout)
        val at = text.indexOf("abajo")
        text.setSpan(big, at, at + "abajo".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertTrue(before != lines(layout))
        assertSameAsFresh("el tamaño grande, movido al título de abajo")
    }

    /** Un `SpanWatcher` que, la primera vez que ve correrse el separador, hace [action]. */
    private fun onDividerShift(action: (Spannable) -> Unit): () -> Boolean {
        var done = false
        text.setSpan(
            object : SpanWatcher {
                override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = Unit
                override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) = Unit
                override fun onSpanChanged(text: Spannable, what: Any, ost: Int, oen: Int, nst: Int, nen: Int) {
                    if (done || what !is RichRender.DividerSpan) return
                    done = true
                    action(text)
                }
            },
            0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return { done }
    }

    @Test
    fun spanAddedWhileShiftingReachesTheLayout() {
        // Lo que se pone en la etapa de los corrimientos le tiene que llegar al DynamicLayout.
        build()
        val done = onDividerShift { text ->
            text.setSpan(RichRender.SizeSpan(40f), lastWords.first, lastWords.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.insert(text.indexOf("item uno"), "y")
        assertTrue(done())
        assertSameAsFresh("el tamaño puesto al correrse el separador")
    }

    @Test
    fun spanRemovedWhileShiftingReachesTheLayout() {
        // Lo que se saca en la etapa de los corrimientos también.
        build()
        val done = onDividerShift { text -> text.removeSpan(big) }
        text.insert(text.indexOf("item uno"), "y")
        assertTrue(done())
        assertSameAsFresh("el tamaño sacado al correrse el separador")
    }

    @Test
    fun lastWatcherStaysAfterTextWatchersAddedLater() {
        // Un TextWatcher de prioridad 0 que se pone después: el de RichEditable tiene que seguir
        // siendo el último, así los spans que este ponga en afterTextChanged no pasan por el
        // intermediario.
        build()
        val order = ArrayList<String>()
        text.setSpan(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    order.add("otro")
                }
            },
            0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        text.insert(0, "a")
        val watchers = text.getSpans(0, text.length, TextWatcher::class.java)
        assertEquals(listOf("otro"), order)
        assertEquals("LastWatcher", watchers.last().javaClass.simpleName)
    }
}
