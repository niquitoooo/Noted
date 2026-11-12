package com.jot.app.ui.rich

import android.text.Editable
import android.text.NoCopySpan
import android.text.SpanWatcher
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.WrapTogetherSpan

/**
 * El texto del campo ([RichEditText]): un `SpannableStringBuilder` que al cambiar el texto no
 * rehace lo que no cambió.
 *
 * Después de cada cambio, `SpannableStringBuilder.replace` le avisa a cada `SpanWatcher` de
 * cada span que se corrió (todos los que están después de lo escrito):
 *
 * - El `DynamicLayout` rearma dos veces los renglones de cada uno que sea `UpdateLayout`: los
 *   títulos, el código, los separadores, la negrita… En una nota con mucho formato eran unos
 *   2 ms por span, en cada tecla.
 * - El `TextView` vuelve a grabar el dibujo de los renglones de cada span de estilo o de
 *   párrafo; con el interlineado ([LineHeightRender]), que va de punta a punta, toda la nota.
 *
 * Esos avisos no cambian nada: el `DynamicLayout` ya rearmó el párrafo del cambio con los spans
 * en su lugar nuevo (`replace` corre los spans antes de avisar el cambio de texto) y marcó para
 * dibujar de nuevo sus renglones; los demás párrafos tienen el mismo texto y los mismos spans,
 * solo más adelante.
 *
 * Por eso, solo en esa última parte de `replace` (después de todos los `TextWatcher`), a esos
 * dos les llega un intermediario que no pasa los corrimientos de los spans propios
 * ([RenderSpan], [RichMark], [LineHeightRender]). Los demás (la selección, la composición del
 * teclado, la ortografía) pasan como siempre, igual que todo span que se pone o se saca, y al
 * resto de los `SpanWatcher` les llega todo. Se los reconoce por el nombre de su clase
 * ([SKIPPED_WATCHERS]); si en un aparato se llaman de otra forma, todo sigue como siempre, solo
 * que más lento. Un span que se pone, se saca o se mueve fuera de un cambio de texto (las
 * reglas, los spans de dibujo, [RichEditText.relayout]) les llega como siempre.
 *
 * Además, y siempre (no depende de la etapa de `replace` ni de los nombres de clase de AOSP),
 * el interlineado no cuenta como `WrapTogetherSpan` ([withoutLineHeight]).
 */
internal class RichEditable(text: CharSequence) : SpannableStringBuilder(text) {

    /** Si `replace` ya avisó a todos los `TextWatcher` y está avisando los spans corridos. */
    private var shifting = false

    /** El intermediario de cada `SpanWatcher` que no recibe los corrimientos, por el original. */
    private val proxies = HashMap<SpanWatcher, SpanWatcher>(4)

    /**
     * Tiene que ser el último `TextWatcher`: cuando le llega afterTextChanged, lo que sigue en
     * `replace` es avisar los spans corridos. Va con prioridad 0, la más baja, y entre los de la
     * misma prioridad `getSpans` los da en el orden en que se pusieron: si después se pone otro
     * `TextWatcher`, se lo saca y se lo vuelve a poner al empezar el próximo `replace` de afuera
     * ([watcherAfterLast]).
     */
    private val lastWatcher = LastWatcher()

    /** Si se puso un `TextWatcher` después de [lastWatcher]. */
    private var watcherAfterLast = false

    /** Cuántos `replace` hay en curso: uno dentro de otro, las reglas en afterTextChanged. */
    private var depth = 0

    init {
        setSpan(lastWatcher, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        super.setSpan(what, start, end, flags)
        if (what is TextWatcher && what !== lastWatcher) watcherAfterLast = true
    }

    override fun replace(start: Int, end: Int, tb: CharSequence, tbstart: Int, tbend: Int): SpannableStringBuilder {
        if (depth == 0 && watcherAfterLast) {
            watcherAfterLast = false
            removeSpan(lastWatcher)
            setSpan(lastWatcher, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        // Un cambio dentro de otro tiene sus propias etapas.
        val outer = shifting
        shifting = false
        depth++
        try {
            return super.replace(start, end, tb, tbstart, tbend)
        } finally {
            depth--
            shifting = outer
        }
    }

    override fun <T : Any?> getSpans(queryStart: Int, queryEnd: Int, kind: Class<T>?): Array<T> {
        val spans = super.getSpans(queryStart, queryEnd, kind)
        // Siempre, sin mirar nombres de clase ni la etapa de replace.
        if (kind == WrapTogetherSpan::class.java) return withoutLineHeight(spans)
        if (!shifting || kind != SpanWatcher::class.java) return spans
        for (i in spans.indices) {
            val watcher = spans[i] as SpanWatcher
            if (watcher.javaClass.name !in SKIPPED_WATCHERS) continue
            @Suppress("UNCHECKED_CAST")
            spans[i] = proxies.getOrPut(watcher) { ShiftIgnoring(watcher) } as T
        }
        return spans
    }

    /**
     * Sin el interlineado: es un `LineHeightSpan`, y así un `WrapTogetherSpan`, que va de punta a
     * punta. El `DynamicLayout` estira lo que rearma hasta cubrir los `WrapTogetherSpan` que toca:
     * con él, cada tecla rearmaba toda la nota. Su alto no depende de dónde empieza el span (la
     * caja sale del primer renglón, [LineHeightRender]), así que rearmar solo el párrafo del
     * cambio da los mismos renglones. Si la caja cambia, [RichEditText] rearma todo. Vale para
     * cualquiera que pregunte por los `WrapTogetherSpan`, en cualquier momento.
     */
    private fun <T> withoutLineHeight(spans: Array<T>): Array<T> {
        if (spans.none { it is LineHeightRender }) return spans
        var count = 0
        for (span in spans) if (span !is LineHeightRender) spans[count++] = span
        @Suppress("UNCHECKED_CAST")
        return spans.copyOf(count) as Array<T>
    }

    override fun removeSpan(what: Any?) {
        super.removeSpan(what)
        // Un Layout o un TextView que suelta el texto no queda guardado acá.
        if (what is SpanWatcher) proxies.remove(what)
    }

    /** Un `SpanWatcher` sin los corrimientos de los spans propios. */
    private class ShiftIgnoring(private val watcher: SpanWatcher) : SpanWatcher {
        override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) =
            watcher.onSpanAdded(text, what, start, end)

        override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) =
            watcher.onSpanRemoved(text, what, start, end)

        override fun onSpanChanged(text: Spannable, what: Any, ostart: Int, oend: Int, nstart: Int, nend: Int) {
            if (what is RenderSpan || what is RichMark || what is LineHeightRender) return
            watcher.onSpanChanged(text, what, ostart, oend, nstart, nend)
        }
    }

    /** No se copia con el texto ([NoCopySpan]) y solo atiende a su propio texto. */
    private inner class LastWatcher : TextWatcher, NoCopySpan {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            if (s === this@RichEditable) shifting = true
        }
    }

    /** Lo que crea el texto del campo en cada `setText`. */
    object Factory : Editable.Factory() {
        override fun newEditable(source: CharSequence): Editable = RichEditable(source)
    }

    internal companion object {
        /** El del `DynamicLayout` y el del `TextView`. */
        val SKIPPED_WATCHERS = setOf("android.text.DynamicLayout\$ChangeWatcher", "android.widget.TextView\$ChangeWatcher")
    }
}
