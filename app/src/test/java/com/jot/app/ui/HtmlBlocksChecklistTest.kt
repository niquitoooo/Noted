package com.jot.app.ui

import android.text.SpannableString
import com.jot.app.data.Attachment
import com.jot.app.data.html.NoteMarkdown
import com.jot.app.data.html.TestBodies
import com.jot.app.ui.rich.BLOCK_MARK_FLAGS
import com.jot.app.ui.rich.BlockMark
import com.jot.app.ui.rich.INLINE_MARK_FLAGS
import com.jot.app.ui.rich.RichMark
import com.jot.app.ui.rich.RichText
import com.jot.app.ui.rich.lineStartOf
import org.json.JSONArray
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HtmlBlocksChecklistTest {

    private fun parse(html: String, attachments: List<Attachment> = emptyList()) =
        HtmlBlocks.parse(html, attachments, emptyList())

    private fun save(blocks: List<EditorBlock>, attachments: List<Attachment> = emptyList()) =
        HtmlBlocks.serialize(blocks, attachments, emptyList(), emptyMap())

    private fun item(text: String, checked: Boolean = false, nested: Boolean = false) =
        ChecklistItem(ChecklistOps.newItemId(), RichText(text), checked, nested)

    @Test
    fun checklistBetweenTexts() {
        val html = "<div>a</div><ul><li data-checked=\"false\">A</li><li data-checked=\"true\">B</li>" +
            "<ul><li data-checked=\"false\">C</li></ul></ul><div>b</div>"
        val blocks = parse(html)
        assertEquals(listOf("text:p|a", "checklist:A,x:B,-C", "text:p|b"), blockShape(blocks))
        assertEquals(html, save(blocks))
    }

    /**
     * Review Focus 2: los cuerpos de Markdown con casillas (`- [ ]` / `- [x]`) vuelven iguales.
     * Usa [TestBodies.canonicalMarkdown] (Pendientes: padre marcado con un hijo sin marcar), el
     * cuerpo de desplegables anidados de `HtmlBlocksTest.openAndSaveIsIdentity` (`- [x] cuatro`
     * dentro de un desplegable cerrado) y una checklist con anidado dentro de un desplegable.
     */
    @Test
    fun copyHtmlRoundTrips() {
        val bodies = listOf(
            TestBodies.canonicalMarkdown,
            listOf(
                "antes",
                "<details open>",
                "<summary>Afuera</summary>",
                "- uno",
                "<details>",
                "<summary>Adentro</summary>",
                "- [x] cuatro",
                "</details>",
                "seis",
                "</details>",
                "después",
            ).joinToString("\n"),
            listOf(
                "<details open>",
                "<summary>T</summary>",
                "- [ ] a",
                "- [x] b",
                "  - [ ] c",
                "</details>",
            ).joinToString("\n"),
        )
        for (markdown in bodies) {
            val html = NoteMarkdown.toHtml(markdown, emptyList())
            val blocks = parse(html)
            assertTrue(markdown, blocks.any { it is EditorBlock.Checklist })
            assertEquals(markdown, html, save(blocks))
        }
    }

    @Test
    fun checklistInsideToggleIsChild() {
        val html = NoteMarkdown.toHtml("<details open>\n<summary>T</summary>\n- [ ] a\n  - [x] b\n</details>", emptyList())
        assertEquals(
            listOf("text:", "toggle:T:true", "checklist:a,-x:b (adentro)", "text: (adentro)", "text:"),
            blockShape(parse(html)),
        )
    }

    @Test
    fun inlineFormatInItem() {
        val html = "<ul><li data-checked=\"false\"><b>A</b> <a href=\"https://a.com\">x</a></li></ul>"
        val blocks = parse(html)
        assertEquals(listOf("text:", "checklist:A x", "text:"), blockShape(blocks))
        assertEquals(html, save(blocks))
    }

    @Test
    fun legacyListsKeepEveryText() {
        val html = "<ul><li>v</li><li data-checked=\"false\">A</li><ul><ul><li data-checked=\"true\">B</li></ul></ul>" +
            "<li data-checked=\"false\"><h2>T</h2></li></ul>"
        val blocks = parse(html)
        val all = blocks.joinToString("\n") { block ->
            when (block) {
                is EditorBlock.Text -> block.value.plain
                is EditorBlock.Checklist -> block.items.joinToString("\n") { it.value.plain }
                else -> ""
            }
        }
        for (text in listOf("v", "A", "B", "T")) assertTrue(all, text in all.lines())
        assertEquals(listOf("text:bullet0|v", "checklist:A,-x:B", "text:check0+h2|T"), blockShape(blocks))
        // Y lo guardado se vuelve a leer igual.
        assertEquals(blockShape(blocks), blockShape(parse(save(blocks))))
    }

    @Test
    fun emptyChecklistIsNotWritten() {
        val blocks = listOf(
            EditorBlock.Text("a", RichText("a")),
            EditorBlock.Checklist("c", emptyList()),
            EditorBlock.Text("b", RichText("b")),
        )
        assertEquals("<div>a</div><div>b</div>", save(blocks))
    }

    @Test
    fun blankLineNextToChecklist() {
        val html = "<div>a</div><div><br></div><ul><li data-checked=\"false\">A</li></ul><div><br></div><div>b</div>"
        val blocks = parse(html)
        assertEquals(listOf("text:p|a\np|", "checklist:A", "text:p|\np|b"), blockShape(blocks))
        assertEquals(html, save(blocks))
    }

    /** Un renglón vacío pegado a la checklist en el borde de la nota o de un desplegable no es el lugar para escribir: se guarda. */
    @Test
    fun blankLineAtTheEdgeNextToChecklist() {
        val bodies = listOf(
            "<ul><li data-checked=\"false\">A</li></ul><div><br></div>",
            "<div><br></div><ul><li data-checked=\"false\">A</li></ul>",
            "<div><br></div><ul><li data-checked=\"false\">A</li></ul><div><br></div>",
        )
        for (html in bodies) assertEquals(html, save(parse(html)))

        // Al final de un desplegable.
        val toggle = NoteMarkdown.toHtml("<details open>\n<summary>T</summary>\n- [ ] a\n</details>", emptyList())
        val withBlank = toggle.replace("</ul>", "</ul><div><br></div>")
        assertTrue(withBlank != toggle)
        assertEquals(withBlank, save(parse(withBlank)))
    }

    @Test
    fun emptyItemsRoundTrip() {
        val html = "<ul><li data-checked=\"false\"><br></li><li data-checked=\"true\">a</li>" +
            "<li data-checked=\"false\"><br></li></ul>"
        val blocks = parse(html)
        assertEquals(listOf("text:", "checklist:,x:a,", "text:"), blockShape(blocks))
        assertEquals(html, save(blocks))
    }

    @Test
    fun shareTextLists() {
        val html = "<div>a</div><ul><li data-checked=\"false\">A</li><li data-checked=\"true\">B</li>" +
            "<ul><li data-checked=\"false\">C</li></ul></ul><div>b</div>"
        assertEquals("a\nA\nB\nC\nb", HtmlBlocks.shareText(parse(html), emptyList(), emptyList(), emptyMap()))
    }

    @Test
    fun toRichTextWritesOneLinePerItem() {
        val items = listOf(item("A"), item("B", checked = true), item("C", nested = true))
        assertEquals(
            "<ul><li data-checked=\"false\">A</li><li data-checked=\"true\">B</li>" +
                "<ul><li data-checked=\"false\">C</li></ul></ul>",
            save(listOf(EditorBlock.Checklist("c", items))),
        )
    }

    /**
     * Las notas con checklist que había en la tablet antes de este cambio: abrir y
     * guardar sin tocar da lo mismo que daba el editor de antes, que las guardaba como un solo
     * texto, y no se pierde ningún texto.
     */
    @Test
    fun tabletNotesRoundTrip() {
        val json = javaClass.classLoader!!.getResource("checklist/checklist-notas.json")!!.readText()
        val notes = JSONArray(json)
        assertEquals(6, notes.length())
        for (i in 0 until notes.length()) {
            val note = notes.getJSONObject(i)
            val title = note.getString("title")
            val html = note.getString("html")
            val attachments = Regex("jot://att/([0-9a-f-]+)").findAll(html).map {
                Attachment(it.groupValues[1], "n", "foto.jpg", "image/jpeg", 1, "x.jpg", 0)
            }.toList()
            val blocks = parse(html, attachments)
            assertTrue(title, blocks.any { it is EditorBlock.Checklist })
            val saved = save(blocks, attachments)
            assertEquals(title, asOneText(blocks, attachments), saved)
            assertEquals(title, Jsoup.parse(html).text(), Jsoup.parse(saved).text())
        }
    }

    /**
     * Lo que guardaba el editor de antes: cada checklist vuelve a ser renglones del texto de al
     * lado, y un texto vacío del medio es un renglón vacío. Antes se sacan los lugares para
     * escribir de la raíz que pone `ensureTextSlots` (las notas de la tablet no tienen
     * checklists dentro de un desplegable).
     */
    private fun asOneText(blocks: List<EditorBlock>, attachments: List<Attachment>): String {
        val list = blocks.toMutableList()
        if ((list.first() as? EditorBlock.Text)?.isBlank() == true) list.removeAt(0)
        val lastRoot = list.indexOfLast { it.parentKey == null }
        if ((list[lastRoot] as? EditorBlock.Text)?.isBlank() == true) list.removeAt(lastRoot)

        val joined = mutableListOf<EditorBlock>()
        var previousWasChecklist = false
        for (block in list) {
            val isChecklist = block is EditorBlock.Checklist
            val asText = (block as? EditorBlock.Checklist)
                ?.let { EditorBlock.Text(it.key, ChecklistOps.toRichText(it.items), it.parentKey) }
                ?: block
            val previous = joined.lastOrNull()
            if (asText is EditorBlock.Text && previous is EditorBlock.Text &&
                previous.parentKey == asText.parentKey && (isChecklist || previousWasChecklist)
            ) {
                joined[joined.lastIndex] = previous.copy(value = joinLines(previous.value, asText.value))
            } else {
                joined += asText
            }
            previousWasChecklist = isChecklist
        }
        return save(joined, attachments)
    }

    /** Los renglones de [top] y después los de [bottom], cada uno con sus marcas. */
    private fun joinLines(top: RichText, bottom: RichText): RichText {
        val length = top.plain.length
        val text = SpannableString(top.plain + "\n" + bottom.plain)
        val lastLine = lineStartOf(top.plain, length)
        for (mark in top.text.getSpans(0, length, RichMark::class.java)) {
            val start = top.text.getSpanStart(mark)
            val end = top.text.getSpanEnd(mark)
            if (mark is BlockMark) {
                text.setSpan(mark, start, if (start == lastLine) length + 1 else end, BLOCK_MARK_FLAGS)
            } else {
                text.setSpan(mark, start, end, INLINE_MARK_FLAGS)
            }
        }
        for (mark in bottom.text.getSpans(0, bottom.plain.length, RichMark::class.java)) {
            val flags = if (mark is BlockMark) BLOCK_MARK_FLAGS else INLINE_MARK_FLAGS
            text.setSpan(mark, bottom.text.getSpanStart(mark) + length + 1, bottom.text.getSpanEnd(mark) + length + 1, flags)
        }
        return RichText(text)
    }
}
