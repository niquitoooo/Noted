package com.jot.app.ui.rich

import android.text.SpannableStringBuilder
import android.text.Spanned

/** Ayuda de tests: el texto con marcas como texto legible. */
object RichDebug {

    /**
     * Simula al `EditText`: [RichEditRules.before], reemplaza `[start, end)` por [typed] y
     * [RichEditRules.after]. El cursor del resultado es el que da `after`, o después de lo
     * escrito.
     */
    fun editResult(
        text: SpannableStringBuilder,
        start: Int,
        end: Int,
        typed: String,
        pending: Map<InlineFormat, Boolean> = emptyMap(),
        forwardDelete: Boolean = false,
    ): RichEditRules.Result {
        val change = RichEditRules.before(text, start, end - start, typed.length, forwardDelete)
        text.replace(start, end, typed)
        val result = RichEditRules.after(text, change, pending)
        return RichEditRules.Result(result.cursor ?: (start + typed.length), result.pending)
    }

    /** Como [editResult], y devuelve solo el cursor. */
    fun edit(
        text: SpannableStringBuilder,
        start: Int,
        end: Int,
        typed: String,
        pending: Map<InlineFormat, Boolean> = emptyMap(),
        forwardDelete: Boolean = false,
    ): Int = editResult(text, start, end, typed, pending, forwardDelete).cursor!!

    fun type(
        text: SpannableStringBuilder,
        cursor: Int,
        typed: String,
        pending: Map<InlineFormat, Boolean> = emptyMap(),
    ): Int = edit(text, cursor, cursor, typed, pending)

    /**
     * Un renglón por renglón del texto, `<tipo>|<contenido>`. El tipo es `p`, `h2`,
     * `bullet0`, `ordered0@3` (con `start`), `check0` / `check0x`, `quote`, `code:kotlin`
     * (`code` sin lenguaje) o `hr`; un título dentro de un ítem suma `+h2`. Las marcas en
     * línea van como `{b}…{/b}`, en el orden de anidado en que las escribe [RichHtml.toHtml].
     */
    fun describe(text: Spanned): String {
        if (RichHtml.isBlank(text)) return ""
        val lines = ArrayList<String>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val out = StringBuilder(kind(RichHtml.blockMarksAt(text, start))).append('|')
            RichHtml.writeInline(text, start, newline, DescribeSink(out))
            lines.add(out.toString())
            start = newline + 1
        }
        return lines.joinToString("\n")
    }

    private fun kind(blocks: List<BlockMark>): String {
        val parts = ArrayList<String>()
        var heading: HeadingMark? = null
        for (mark in blocks) {
            when (mark) {
                is HeadingMark -> heading = mark
                is ListMark -> parts.add(
                    when (mark.kind) {
                        ListKind.Bullet -> "bullet"
                        ListKind.Ordered -> "ordered"
                        ListKind.Check -> "check"
                    } + mark.depth + (if (mark.checked) "x" else "") + (mark.start?.let { "@$it" } ?: "")
                )
                is QuoteMark -> parts.add("quote")
                is CodeBlockMark -> parts.add("code" + (mark.language?.let { ":$it" } ?: ""))
                is DividerMark -> parts.add("hr")
            }
        }
        heading?.let { parts.add("h${it.level}") }
        return if (parts.isEmpty()) "p" else parts.joinToString("+")
    }

    private class DescribeSink(private val out: StringBuilder) : RichHtml.InlineSink {
        override fun open(mark: InlineMark) {
            out.append('{').append(openName(mark)).append('}')
        }

        override fun close(mark: InlineMark) {
            out.append("{/").append(name(mark)).append('}')
        }

        override fun text(value: String) {
            out.append(value)
        }
    }

    private fun name(mark: InlineMark): String = when (mark) {
        is BoldMark -> "b"
        is ItalicMark -> "i"
        is UnderlineMark -> "u"
        is StrikeMark -> mark.tag
        is SubscriptMark -> "sub"
        is SuperscriptMark -> "sup"
        is CodeMark -> "code"
        is LinkMark -> "a"
        is TextColorMark -> "color"
        is HighlightMark -> "hl"
    }

    private fun openName(mark: InlineMark): String = when (mark) {
        is LinkMark -> if (mark.href == null) "a" else "a=${mark.href}"
        is TextColorMark -> "color=${mark.color}"
        is HighlightMark -> "hl=${mark.color ?: "green"}"
        else -> name(mark)
    }
}
