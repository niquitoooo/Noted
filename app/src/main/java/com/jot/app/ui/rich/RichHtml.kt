package com.jot.app.ui.rich

import android.text.SpannableStringBuilder
import android.text.Spanned
import com.jot.app.data.html.HtmlSanitizer
import com.jot.app.data.html.ListBuilder
import com.jot.app.data.html.appendCode
import com.jot.app.data.html.appendLinesWithBr
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Códec entre el texto con marcas del campo con formato y el HTML de la nota. Cada elemento de bloque da uno o más renglones del
 * texto, separados por `\n`; el tipo de renglón es su [BlockMark] (sin marca, un `<div>`).
 *
 * Al leer, las marcas en línea se agregan de afuera hacia adentro; al escribir, va afuera
 * la que empieza antes y, si empiezan juntas, la que se agregó primero. Así
 * `toHtml(fromHtml(x)) == x` para el HTML que escribe la app.
 */
object RichHtml {

    private const val TEXT_COLOR = "jot-text-"
    private const val HIGHLIGHT = "jot-highlight"
    private const val NBSP = '\u00A0'

    /** Las etiquetas que dan renglones; las demás son contenido en línea. */
    private val BLOCK_TAGS = setOf(
        "div", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "pre", "hr",
        "section", "article", "main", "header", "footer", "nav", "aside", "address", "figure",
        "figcaption", "dl", "dt", "dd", "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th",
        "details", "summary",
    )

    private val Node.isBlockElement: Boolean get() = this is Element && normalName() in BLOCK_TAGS

    fun fromHtml(fragment: String): SpannableStringBuilder =
        fromNodes(Jsoup.parseBodyFragment(fragment).body().childNodes())

    fun fromNodes(nodes: List<Node>): SpannableStringBuilder {
        val reader = Reader()
        readBlocks(nodes, Context(), reader)
        return reader.build()
    }

    /** El HTML del texto, saneado. Un texto vacío y sin marca de bloque es un HTML vacío. */
    fun toHtml(text: Spanned): String {
        val document = Document.createShell("")
        document.outputSettings().prettyPrint(false)
        appendTo(document.body(), text)
        return HtmlSanitizer.clean(document.body().html())
    }

    /**
     * Agrega a [parent] los bloques de los renglones de [text]. Un texto vacío sin marca de
     * bloque no agrega nada (como `NoteMarkdown.toHtml` con un cuerpo vacío; `HtmlBlocks`
     * escribe `<div><br></div>` si ese texto queda en el medio); con marca, como un ítem
     * vacío, es ese renglón.
     */
    fun appendTo(parent: Element, text: Spanned) {
        if (isBlank(text)) return
        val lines = lines(text)
        val lists = ListBuilder(parent)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val code = line.mark<CodeBlockMark>()
            val quote = line.mark<QuoteMark>()
            val item = line.mark<ListMark>()
            val heading = line.mark<HeadingMark>()
            when {
                code != null -> {
                    lists.close()
                    val run = runOf(lines, i) { it.mark<CodeBlockMark>()?.group == code.group }
                    appendCode(code.language ?: "", run.map { text.substring(it.start, it.end) }, parent)
                    i += run.size
                }
                line.mark<DividerMark>() != null -> {
                    lists.close()
                    parent.appendElement("hr")
                    i++
                }
                quote != null -> {
                    lists.close()
                    val run = runOf(lines, i) { it.mark<QuoteMark>()?.group == quote.group }
                    appendLinesWithBr(parent.appendElement("blockquote"), run.size, run.last().isEmpty) { index, into ->
                        writeLine(text, run[index], into)
                    }
                    i += run.size
                }
                else -> {
                    val block = if (item != null) {
                        val tag = if (item.kind == ListKind.Ordered) "ol" else "ul"
                        val li = lists.item(item.depth, tag, item.start ?: 1)
                        if (item.kind == ListKind.Check) li.attr("data-checked", item.checked.toString())
                        // El título va dentro del ítem.
                        if (heading != null) li.appendElement(headingTag(heading)) else li
                    } else {
                        lists.close()
                        parent.appendElement(if (heading != null) headingTag(heading) else "div")
                    }
                    appendLinesWithBr(block, 1, line.isEmpty) { _, into -> writeLine(text, line, into) }
                    i++
                }
            }
        }
    }

    /** Sin texto ni marca de bloque: no tiene ningún renglón que escribir. */
    internal fun isBlank(text: Spanned): Boolean = text.isEmpty() && blockMarksAt(text, 0).isEmpty()

    private fun headingTag(heading: HeadingMark) = "h${heading.level.coerceIn(1, 6)}"

    // ---- renglones ----

    /** Un renglón `[start, end)` del texto, sin su `\n`, con sus marcas de bloque. */
    private class Line(val start: Int, val end: Int, val blocks: List<BlockMark>) {
        val isEmpty get() = start == end

        inline fun <reified T : BlockMark> mark(): T? = blocks.firstOrNull { it is T } as T?
    }

    private fun lines(text: Spanned): List<Line> {
        val result = ArrayList<Line>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            result.add(Line(start, newline, blockMarksAt(text, start)))
            start = newline + 1
        }
        return result
    }

    /** Las marcas de bloque del renglón que empieza en [lineStart]. */
    internal fun blockMarksAt(text: Spanned, lineStart: Int): List<BlockMark> =
        text.getSpans(lineStart, lineStart, BlockMark::class.java).filter { text.getSpanStart(it) == lineStart }

    /** Los renglones seguidos desde [from] que cumplen [same]. */
    private fun runOf(lines: List<Line>, from: Int, same: (Line) -> Boolean): List<Line> {
        var to = from + 1
        while (to < lines.size && same(lines[to])) to++
        return lines.subList(from, to)
    }

    private fun writeLine(text: Spanned, line: Line, into: Element) {
        writeInline(text, line.start, line.end, NbspSink(ElementSink(into), line.end - line.start))
    }

    // ---- HTML → marcas ----

    /**
     * Dónde se está leyendo: dentro de un ítem, de un título o de una cita. Adentro de una
     * cita los renglones son solo de la cita.
     */
    private class Context(val item: Item? = null, val heading: Int? = null, val quote: Long? = null) {
        fun withHeading(level: Int) = Context(item, level, quote)

        fun blocks(): List<BlockMark> = when {
            quote != null -> listOf(QuoteMark(quote))
            else -> listOfNotNull(item?.next(), heading?.let { HeadingMark(it) })
        }
    }

    /** Un `<li>`: el `start` del `<ol>` va solo en su primer renglón. */
    private class Item(private val mark: ListMark) {
        val depth get() = mark.depth
        private var used = false

        fun next(): ListMark {
            val start = if (used) null else mark.start
            used = true
            return ListMark(mark.kind, mark.depth, mark.checked, start)
        }
    }

    /** Los bloques de [nodes]; lo suelto entre bloques (texto, marcas) es un renglón más. */
    private fun readBlocks(nodes: List<Node>, context: Context, reader: Reader) {
        val loose = ArrayList<Node>()
        fun flushLoose() {
            if (loose.isNotEmpty()) reader.line(context.blocks(), loose)
            loose.clear()
        }
        for (node in nodes) {
            when {
                node.isBlockElement -> {
                    flushLoose()
                    readBlock(node as Element, context, reader)
                }
                // Espacios entre bloques (HTML con sangría).
                node is TextNode && node.isBlank && loose.isEmpty() -> Unit
                else -> loose.add(node)
            }
        }
        flushLoose()
    }

    private fun readBlock(block: Element, context: Context, reader: Reader) {
        when (val tag = block.normalName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> readFlow(block, context.withHeading(tag[1].digitToInt()), reader)
            "ul", "ol" -> readList(block, context.item?.let { it.depth + 1 } ?: 0, context, reader)
            "li" -> readItem(block, ListMark(ListKind.Bullet, context.item?.let { it.depth + 1 } ?: 0), context, reader)
            "blockquote" -> {
                val quote = Context(quote = context.quote ?: newBlockGroup())
                readContainer(block.childNodes(), quote, reader)
            }
            "pre" -> reader.code(newBlockGroup(), block.attr("data-language").ifEmpty { null }, codeLines(block))
            "hr" -> reader.divider()
            // Un `<div>` que contiene bloques se lee por dentro.
            else -> readFlow(block, context, reader)
        }
    }

    /** Un bloque sin bloques adentro es un renglón; si tiene, se leen sus bloques. */
    private fun readFlow(block: Element, context: Context, reader: Reader) {
        if (block.childNodes().any { it.isBlockElement }) {
            readContainer(block.childNodes(), context, reader)
        } else {
            reader.line(context.blocks(), block.childNodes())
        }
    }

    /** Como [readBlocks], pero sin contenido da un renglón vacío. */
    private fun readContainer(nodes: List<Node>, context: Context, reader: Reader) {
        val before = reader.lineCount
        readBlocks(nodes, context, reader)
        if (reader.lineCount == before) reader.line(context.blocks(), emptyList())
    }

    /** Un nivel de lista; la lista de adentro (en la lista o en un `<li>`) es un nivel más. */
    private fun readList(list: Element, depth: Int, context: Context, reader: Reader) {
        val ordered = list.normalName() == "ol"
        val start = list.attr("start").toIntOrNull() ?: 1
        var first = true
        val loose = ArrayList<Node>()
        fun flushLoose() {
            if (loose.isNotEmpty()) reader.line(context.blocks(), loose)
            loose.clear()
        }
        for (node in list.childNodes()) {
            val element = node as? Element
            when {
                element != null && (element.normalName() == "ul" || element.normalName() == "ol") -> {
                    flushLoose()
                    readList(element, depth + 1, context, reader)
                }
                element != null && element.normalName() == "li" -> {
                    flushLoose()
                    val kind = when {
                        element.hasAttr("data-checked") -> ListKind.Check
                        ordered -> ListKind.Ordered
                        else -> ListKind.Bullet
                    }
                    val itemStart = if (kind == ListKind.Ordered && first && start != 1) start else null
                    first = false
                    readItem(element, ListMark(kind, depth, element.attr("data-checked") == "true", itemStart), context, reader)
                }
                node.isBlockElement -> {
                    flushLoose()
                    readBlock(element!!, context, reader)
                }
                node is TextNode && node.isBlank && loose.isEmpty() -> Unit
                else -> loose.add(node)
            }
        }
        flushLoose()
    }

    private fun readItem(li: Element, mark: ListMark, context: Context, reader: Reader) {
        readContainer(li.childNodes(), Context(item = Item(mark), quote = context.quote), reader)
    }

    /** El texto de un `<pre>` en renglones: un `<br>` es un salto y el último salto no abre renglón. */
    private fun codeLines(pre: Element): List<String> {
        val text = StringBuilder()
        fun collect(node: Node) {
            when {
                node is TextNode -> text.append(node.wholeText)
                node is Element && node.normalName() == "br" -> text.append('\n')
                node is Element -> node.childNodes().forEach { collect(it) }
            }
        }
        pre.childNodes().forEach { collect(it) }
        // Un `<pre>` vacío no se pierde: es un renglón de código vacío.
        if (text.isEmpty()) return listOf("")
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }

    /** Una marca leída, con su tramo; [end] se completa al cerrar su etiqueta. */
    private class ReadMark(val mark: InlineMark, val start: Int) {
        var end = start
    }

    private class Reader {
        val text = StringBuilder()
        val marks = ArrayList<ReadMark>()
        private val lineStarts = ArrayList<Int>()
        private val lineBlocks = ArrayList<List<BlockMark>>()

        val lineCount get() = lineStarts.size

        // Los `<br>` todavía sin escribir: el último de un renglón no abre otro (el
        // navegador no lo dibuja), así que se escriben recién cuando sigue texto.
        private var pendingBreaks = 0

        private fun newLine(blocks: List<BlockMark>) {
            if (lineStarts.isNotEmpty()) text.append('\n')
            lineStarts.add(text.length)
            lineBlocks.add(blocks)
        }

        /** Un `<br>` en el medio abre otro renglón del mismo tipo. */
        private fun continueLine() = newLine(lineBlocks.last().map { continued(it) })

        fun line(blocks: List<BlockMark>, nodes: List<Node>) {
            newLine(blocks)
            pendingBreaks = 0
            nodes.forEach { read(it) }
            repeat((pendingBreaks - 1).coerceAtLeast(0)) { continueLine() }
            pendingBreaks = 0
        }

        fun code(group: Long, language: String?, lines: List<String>) {
            for (line in lines) {
                newLine(listOf(CodeBlockMark(group, language)))
                text.append(line)
            }
        }

        fun divider() {
            newLine(listOf(DividerMark()))
            text.append(DIVIDER_CHAR)
        }

        private fun read(node: Node) {
            when {
                node is TextNode -> appendText(node.wholeText.replace('\n', ' ').replace(NBSP, ' '))
                node !is Element -> Unit
                node.normalName() == "br" -> pendingBreaks++
                else -> {
                    // De afuera hacia adentro: la marca entra a la lista antes que las de adentro.
                    val opened = marksOf(node).map { ReadMark(it, text.length + pendingBreaks).also(marks::add) }
                    node.childNodes().forEach { read(it) }
                    opened.forEach { it.end = text.length }
                }
            }
        }

        private fun appendText(value: String) {
            if (value.isEmpty()) return
            repeat(pendingBreaks) { continueLine() }
            pendingBreaks = 0
            text.append(value)
        }

        // Las marcas se ponen al final: con el texto a medio armar, una marca
        // EXCLUSIVE_INCLUSIVE crecería con lo que se agrega después.
        fun build(): SpannableStringBuilder {
            val result = SpannableStringBuilder(text)
            for (read in marks) {
                if (read.end > read.start) result.setSpan(read.mark, read.start, read.end, INLINE_MARK_FLAGS)
            }
            lineStarts.forEachIndexed { index, start ->
                val end = lineStarts.getOrNull(index + 1) ?: result.length
                for (block in lineBlocks[index]) result.setSpan(block, start, end, BLOCK_MARK_FLAGS)
            }
            return result
        }
    }

    /** La marca del renglón que abre un `<br>`: la misma, sin el `start` del primer ítem. */
    private fun continued(mark: BlockMark): BlockMark = when (mark) {
        is HeadingMark -> HeadingMark(mark.level)
        is ListMark -> ListMark(mark.kind, mark.depth, mark.checked)
        is QuoteMark -> QuoteMark(mark.group)
        is CodeBlockMark -> CodeBlockMark(mark.group, mark.language)
        is DividerMark -> DividerMark()
    }

    /** Las marcas de una etiqueta; lo que no se reconoce no da marca y queda su texto. */
    private fun marksOf(element: Element): List<InlineMark> = when (element.normalName()) {
        "b", "strong" -> listOf(BoldMark())
        "i", "em" -> listOf(ItalicMark())
        "u" -> listOf(UnderlineMark())
        "strike" -> listOf(StrikeMark("strike"))
        "s", "del" -> listOf(StrikeMark("s"))
        "sub" -> listOf(SubscriptMark())
        "sup" -> listOf(SuperscriptMark())
        "code" -> listOf(CodeMark())
        "a" -> listOf(
            LinkMark(
                href = if (element.hasAttr("href")) element.attr("href") else null,
                attrs = element.attributes().filter { it.key != "href" }.associate { it.key to it.value },
            )
        )
        "span" -> element.classNames().mapNotNull { name ->
            when {
                name == HIGHLIGHT -> HighlightMark(null)
                name.startsWith("$HIGHLIGHT-") -> HighlightMark(name.removePrefix("$HIGHLIGHT-"))
                name.startsWith(TEXT_COLOR) -> TextColorMark(name.removePrefix(TEXT_COLOR))
                else -> null
            }
        }
        else -> emptyList()
    }

    // ---- marcas → HTML ----

    /** Dónde se escribe el contenido en línea de un renglón: el HTML o, en los tests, la descripción. */
    internal interface InlineSink {
        fun open(mark: InlineMark)
        fun close(mark: InlineMark)
        fun text(value: String)
    }

    /** Una marca recortada al renglón; [order] es su lugar en `getSpans`. */
    private class LineMark(val mark: InlineMark, val start: Int, var end: Int, val order: Int)

    /**
     * El contenido en línea de `[start, end)`, que no tiene `\n`. Las marcas se recortan al
     * renglón; las de largo 0 no se escriben y las iguales pegadas o encimadas son una sola.
     * Una marca de adentro que sigue después de que se cierra la de afuera se cierra con
     * ella y se vuelve a abrir afuera para el resto.
     */
    internal fun writeInline(text: Spanned, start: Int, end: Int, sink: InlineSink) {
        val marks = lineMarks(text, start, end)
        val open = ArrayList<LineMark>()
        var position = start
        var next = 0
        while (true) {
            // Cierra las que terminan acá y reabre las de adentro que siguen.
            val outermostEnding = open.indexOfFirst { it.end == position }
            if (outermostEnding >= 0) {
                val closed = open.subList(outermostEnding, open.size).toList()
                for (i in closed.indices.reversed()) sink.close(closed[i].mark)
                open.subList(outermostEnding, open.size).clear()
                for (mark in closed) if (mark.end > position) {
                    sink.open(mark.mark)
                    open.add(mark)
                }
            }
            while (next < marks.size && marks[next].start == position) {
                sink.open(marks[next].mark)
                open.add(marks[next])
                next++
            }
            if (position >= end) break
            val stop = minOf(
                marks.getOrNull(next)?.start ?: end,
                open.minOfOrNull { it.end } ?: end,
            )
            sink.text(text.subSequence(position, stop).toString())
            position = stop
        }
    }

    private fun lineMarks(text: Spanned, start: Int, end: Int): List<LineMark> {
        val clipped = text.getSpans(start, end, InlineMark::class.java).mapIndexedNotNull { order, mark ->
            val from = maxOf(text.getSpanStart(mark), start)
            val to = minOf(text.getSpanEnd(mark), end)
            if (to > from) LineMark(mark, from, to, order) else null
        }.sortedWith(compareBy({ it.start }, { it.order }))
        val merged = ArrayList<LineMark>()
        for (mark in clipped) {
            val same = merged.firstOrNull { it.mark.sameFormat(mark.mark) && it.end >= mark.start }
            if (same != null) same.end = maxOf(same.end, mark.end) else merged.add(mark)
        }
        return merged
    }

    private class ElementSink(root: Element) : InlineSink {
        private val stack = arrayListOf(root)

        override fun open(mark: InlineMark) {
            val child = element(mark)
            stack.last().appendChild(child)
            stack.add(child)
        }

        override fun close(mark: InlineMark) {
            stack.removeAt(stack.lastIndex)
        }

        override fun text(value: String) {
            if (value.isNotEmpty()) stack.last().appendText(value)
        }
    }

    /**
     * Los espacios como en HTML editable: U+00A0 si es el primero o el último del
     * renglón de largo [length], o si el anterior se escribió como espacio común. Así el
     * navegador no junta los espacios ni los come en los bordes.
     */
    private class NbspSink(private val inner: InlineSink, private val length: Int) : InlineSink {
        private var index = 0
        private var previousWasSpace = false

        override fun open(mark: InlineMark) = inner.open(mark)

        override fun close(mark: InlineMark) = inner.close(mark)

        override fun text(value: String) {
            val out = StringBuilder(value.length)
            for (char in value) {
                if (char == ' ' && (index == 0 || index == length - 1 || previousWasSpace)) {
                    out.append(NBSP)
                    previousWasSpace = false
                } else {
                    out.append(char)
                    previousWasSpace = char == ' '
                }
                index++
            }
            inner.text(out.toString())
        }
    }

    private fun element(mark: InlineMark): Element = when (mark) {
        is BoldMark -> Element("b")
        is ItalicMark -> Element("i")
        is UnderlineMark -> Element("u")
        is StrikeMark -> Element(mark.tag)
        is SubscriptMark -> Element("sub")
        is SuperscriptMark -> Element("sup")
        is CodeMark -> Element("code").attr("spellcheck", "false")
        is LinkMark -> Element("a").also { a ->
            mark.href?.let { a.attr("href", it) }
            mark.attrs.forEach { (key, value) -> a.attr(key, value) }
        }
        is TextColorMark -> Element("span").addClass(TEXT_COLOR + mark.color)
        is HighlightMark -> Element("span").addClass(mark.color?.let { "$HIGHLIGHT-$it" } ?: HIGHLIGHT)
    }
}
