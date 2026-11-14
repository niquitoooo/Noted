package com.noted.app.ui

import android.text.SpannableStringBuilder
import androidx.compose.ui.text.TextRange
import com.noted.app.data.Attachment
import com.noted.app.ui.rich.LineFormat
import com.noted.app.ui.rich.RichCommands
import com.noted.app.ui.rich.RichDebug
import com.noted.app.ui.rich.RichText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlockCommandsTest {

    private val photo = Attachment("p1", "n1", "foto.jpg", "image/jpeg", 10, "p1.jpg", 0)

    private fun text(key: String, html: String) = EditorBlock.Text(key, RichText.fromHtml(html))

    private fun shape(blocks: List<EditorBlock>): List<String> = blocks.map { block ->
        when (block) {
            is EditorBlock.Text -> "text:" + RichDebug.describe(block.value.text)
            is EditorBlock.Media -> "media:" + block.attachmentId
            else -> block.toString()
        }
    }

    @Test
    fun insertAttachmentSplitsRichText() {
        val blocks = mutableListOf<EditorBlock>(text("t", "<ul><li>a<b>bc</b></li></ul>"))
        val next = BlockCommands.insertAttachments(
            blocks,
            BlockTree(blocks),
            mutableMapOf(),
            InsertPoint("t", TextRange(2)),
            listOf(photo),
        )
        assertEquals(listOf("text:bullet0|a{b}b{/b}", "media:p1", "text:p|{b}c{/b}"), shape(blocks))
        assertEquals(blocks.last().key, next)
        assertEquals(TextRange(0), (blocks.last() as EditorBlock.Text).value.selection)
    }

    @Test
    fun removeMediaJoinsTexts() {
        val media = EditorBlock.Media("media-p1", "p1")
        val blocks = mutableListOf(text("a", "<div><b>a</b></div>"), media, text("c", "<h2>c</h2>"))
        BlockCommands.removeMedia(blocks, media)
        assertEquals(listOf("text:p|{b}a{/b}\nh2|c"), shape(blocks))
        assertEquals(TextRange(2), (blocks.single() as EditorBlock.Text).value.selection)
    }

    @Test
    fun removeMediaKeepsAnEmptyBullet() {
        val media = EditorBlock.Media("media-p1", "p1")
        val blocks = mutableListOf(text("a", "<ul><li><br></li></ul>"), media, text("c", "<div>c</div>"))
        BlockCommands.removeMedia(blocks, media)
        assertEquals(listOf("text:bullet0|\np|c"), shape(blocks))
    }

    @Test
    fun dropTextCutsAtLineStart() {
        val blocks = mutableListOf(text("t", "<div>a</div><h2>b</h2>"), EditorBlock.Media("media-p1", "p1"))
        val below = DragDropController(blocks, BlockTree(blocks))
            .dropText("media-p1", PendingDrop.Text("t", offset = 2, lineTop = 0f))
        assertEquals(listOf("text:p|a", "media:p1", "text:h2|b"), shape(blocks))
        assertEquals(blocks.last().key, below)
    }

    private fun checklist(key: String, vararg texts: String) =
        EditorBlock.Checklist(key, texts.map { ChecklistItem(ChecklistOps.newItemId(), RichText(it)) })

    @Test
    fun normalizeSplitsAndMerges() {
        val blocks = mutableListOf<EditorBlock>(
            text("t", "<div>x</div><ul><li data-checked=\"false\">A</li></ul>"),
            checklist("c", "B"),
        )
        // El cursor en "A|": después de "x\nA".
        val focus = BlockCommands.normalizeChecklists(blocks, InsertPoint("t", TextRange(3)))
        assertEquals(listOf("text:p|x", "checklist:A,B"), blockShape(blocks))
        assertEquals("t", blocks[0].key)
        val merged = blocks[1] as EditorBlock.Checklist
        assertEquals(ChecklistFocus(merged.key, merged.items[0].id, 1), focus)
        // Sin cursor en un ítem, no hay foco que mover.
        assertEquals(null, BlockCommands.normalizeChecklists(blocks, InsertPoint("t", TextRange(1))))
        assertEquals(listOf("text:p|x", "checklist:A,B"), blockShape(blocks))
    }

    @Test
    fun normalizeRemovesEmptyChecklist() {
        val blocks = mutableListOf(text("a", "<div>a</div>"), EditorBlock.Checklist("c", emptyList()), text("b", "<div>b</div>"))
        assertEquals(null, BlockCommands.normalizeChecklists(blocks, null))
        assertEquals(listOf("text:p|a\np|b"), blockShape(blocks))
    }

    @Test
    fun removeMediaJoinsChecklists() {
        val media = EditorBlock.Media("media-p1", "p1")
        val blocks = mutableListOf(checklist("c1", "A"), media, checklist("c2", "B"))
        BlockCommands.removeMedia(blocks, media)
        assertEquals(listOf("checklist:A,B"), blockShape(blocks))
        assertEquals("c1", blocks.single().key)
    }

    @Test
    fun dropBlockJoinsChecklists() {
        val blocks = mutableListOf(checklist("c1", "A"), EditorBlock.Media("media-p1", "p1"), checklist("c2", "B"), text("t", "<div>t</div>"))
        DragDropController(blocks, BlockTree(blocks)).dropBlock("media-p1", targetIndex = 3, targetParentKey = null)
        assertEquals(listOf("checklist:A,B", "text:p|t", "media:p1"), blockShape(blocks))
    }

    @Test
    fun itemToTextSplitsBlock() {
        val blocks = mutableListOf<EditorBlock>(checklist("c", "A", "Bb", "C"))
        val b = (blocks[0] as EditorBlock.Checklist).items[1]
        val point = BlockCommands.itemToText(blocks, "c", b.id, cursor = 1)
        assertEquals(listOf("checklist:A", "text:p|Bb", "checklist:C"), blockShape(blocks))
        assertEquals("c", blocks[0].key)
        assertEquals(InsertPoint(blocks[1].key, TextRange(1)), point)
    }

    @Test
    fun itemToTextOnOnlyItemLeavesText() {
        val blocks = mutableListOf<EditorBlock>(checklist("c", "B"))
        val b = (blocks[0] as EditorBlock.Checklist).items[0]
        val point = BlockCommands.itemToText(blocks, "c", b.id, cursor = 0)
        assertEquals(listOf("text:p|B"), blockShape(blocks))
        assertEquals(InsertPoint(blocks[0].key, TextRange(0)), point)
    }

    @Test
    fun itemToTextMergesAbove() {
        val blocks = mutableListOf<EditorBlock>(text("t", "<div>x</div>"), checklist("c", "B"))
        val b = (blocks[1] as EditorBlock.Checklist).items[0]
        val point = BlockCommands.itemToText(blocks, "c", b.id, cursor = 1)
        assertEquals(listOf("text:p|x\np|B"), blockShape(blocks))
        // El cursor del ítem corrido "x\n".
        assertEquals(InsertPoint("t", TextRange(3)), point)
    }

    @Test
    fun itemToTextKeepsAnEmptyItemLine() {
        val blocks = mutableListOf<EditorBlock>(text("t", "<div>x</div>"), checklist("c", ""), text("u", "<div>y</div>"))
        val b = (blocks[1] as EditorBlock.Checklist).items[0]
        val point = BlockCommands.itemToText(blocks, "c", b.id, cursor = 0)
        assertEquals("x\n\ny", (blocks.single() as EditorBlock.Text).value.plain)
        assertEquals(InsertPoint("t", TextRange(2)), point)
    }

    @Test
    fun checklistButtonOnItemMakesText() {
        val blocks = mutableListOf<EditorBlock>(checklist("c", "A", "B", "C"))
        val b = (blocks[0] as EditorBlock.Checklist).items[1]
        val focus = BlockCommands.formatFocused(
            blocks,
            EditorFocus("c", b.id, TextRange(1)),
            BarAction.Check,
        ) { RichCommands.toggleLine(it, LineFormat.Check) }
        assertEquals(listOf("checklist:A", "text:p|B", "checklist:C"), blockShape(blocks))
        assertEquals(EditorFocus(blocks[1].key, null, TextRange(1)), focus)
        // La checklist no lo vuelve a tomar.
        BlockCommands.normalizeChecklists(blocks, null)
        assertEquals(listOf("checklist:A", "text:p|B", "checklist:C"), blockShape(blocks))
    }

    @Test
    fun headingButtonOnItemMakesHeading() {
        val blocks = mutableListOf<EditorBlock>(text("t", "<div>x</div>"), checklist("c", "B"))
        val b = (blocks[1] as EditorBlock.Checklist).items[0]
        val focus = BlockCommands.formatFocused(
            blocks,
            EditorFocus("c", b.id, TextRange(0)),
            BarAction.Heading,
        ) { RichCommands.toggleLine(it, LineFormat.Heading2) }
        assertEquals(listOf("text:p|x\nh2|B"), blockShape(blocks))
        assertEquals(EditorFocus("t", null, TextRange(2)), focus)
    }

    @Test
    fun boldButtonOnItemStaysInItem() {
        val blocks = mutableListOf<EditorBlock>(checklist("c", "Bb"))
        val b = (blocks[0] as EditorBlock.Checklist).items[0]
        val list = blocks[0] as EditorBlock.Checklist
        blocks[0] = list.copy(items = listOf(b.copy(value = RichText("Bb", TextRange(0, 2)))))
        val focus = BlockCommands.formatFocused(
            blocks,
            EditorFocus("c", b.id, TextRange(0, 2)),
            BarAction.Bold,
        ) { RichCommands.toggleInline(it, com.noted.app.ui.rich.InlineFormat.Bold) }
        assertEquals(listOf("checklist:Bb"), blockShape(blocks))
        val item = (blocks[0] as EditorBlock.Checklist).items[0]
        assertEquals("p|{b}Bb{/b}", RichDebug.describe(item.value.text))
        assertEquals(EditorFocus("c", b.id, TextRange(0, 2)), focus)
    }

    @Test
    fun checklistButtonOnTextFocusesItem() {
        val blocks = mutableListOf<EditorBlock>(text("t", "<div>x</div><div>ab</div>"))
        val focus = BlockCommands.formatFocused(
            blocks,
            EditorFocus("t", null, TextRange(3)),
            BarAction.Check,
        ) { RichCommands.toggleLine(it, LineFormat.Check) }
        assertEquals(listOf("text:p|x", "checklist:ab", "text:"), blockShape(blocks))
        val list = blocks[1] as EditorBlock.Checklist
        assertEquals(EditorFocus(list.key, list.items[0].id, TextRange(1)), focus)
    }

    /** Un renglón casilla + título se queda en el texto. */
    private val checkedHeading = "<ul><li data-checked=\"false\"><h2>T</h2></li></ul>"

    @Test
    fun enterAtEndOfChecklistHeadingMakesAnItem() {
        val blocks = mutableListOf<EditorBlock>(text("t", checkedHeading))
        val typed = SpannableStringBuilder((blocks[0] as EditorBlock.Text).value.text)
        val cursor = RichDebug.type(typed, 1, "\n")
        BlockCommands.updateText(blocks, "t", RichText(typed, TextRange(cursor)))
        val focus = BlockCommands.textChanged(blocks, "t")
        // El renglón nuevo (casilla sin título) no queda suelto en el texto: es un ítem.
        assertEquals(listOf("text:check0+h2|T", "checklist:", "text:"), blockShape(blocks))
        val list = blocks[1] as EditorBlock.Checklist
        assertEquals(EditorFocus(list.key, list.items[0].id, TextRange(0)), focus)
    }

    @Test
    fun typingInChecklistHeadingStaysInText() {
        val blocks = mutableListOf<EditorBlock>(text("t", checkedHeading))
        val typed = SpannableStringBuilder((blocks[0] as EditorBlock.Text).value.text)
        val cursor = RichDebug.type(typed, 1, "x")
        BlockCommands.updateText(blocks, "t", RichText(typed, TextRange(cursor)))
        assertEquals(null, BlockCommands.textChanged(blocks, "t"))
        assertEquals(listOf("text:check0+h2|Tx"), blockShape(blocks))
    }

    @Test
    fun headingButtonOnChecklistHeadingMakesAnItem() {
        val blocks = mutableListOf<EditorBlock>(text("t", checkedHeading))
        val focus = BlockCommands.formatFocused(
            blocks,
            EditorFocus("t", null, TextRange(1)),
            BarAction.Heading,
        ) { RichCommands.toggleLine(it, LineFormat.Heading2) }
        // Con los lugares para escribir arriba y abajo (ensureTextSlots).
        assertEquals(listOf("text:", "checklist:T", "text:"), blockShape(blocks))
        val list = blocks[1] as EditorBlock.Checklist
        assertEquals(EditorFocus(list.key, list.items[0].id, TextRange(1)), focus)
    }

    @Test
    fun insertAfterChecklist() {
        val blocks = mutableListOf<EditorBlock>(checklist("c", "A", "B"))
        val next = BlockCommands.insertAttachments(blocks, BlockTree(blocks), mutableMapOf(), InsertPoint("c", TextRange.Zero), listOf(photo))
        assertEquals(listOf("checklist:A,B", "media:p1"), blockShape(blocks).take(2))
        assertEquals(3, blocks.size)
        assertEquals(next, blocks[2].key)
        assertEquals("", (blocks[2] as EditorBlock.Text).value.plain)
    }

    @Test
    fun insertAfterChecklistDoesNotDoubleText() {
        val blocks = mutableListOf<EditorBlock>(checklist("c", "A"), text("b", "<div>b</div>"))
        val next = BlockCommands.insertAttachments(blocks, BlockTree(blocks), mutableMapOf(), InsertPoint("c", TextRange.Zero), listOf(photo))
        assertEquals(listOf("checklist:A", "media:p1", "text:p|b"), blockShape(blocks))
        assertEquals("b", next)
        assertEquals(TextRange(0), (blocks[2] as EditorBlock.Text).value.selection)
    }

    @Test
    fun bookmarkNoteAndToggleGoAfterChecklist() {
        val blocks = mutableListOf<EditorBlock>(checklist("c", "A"), text("b", "<div>b</div>"))
        BlockCommands.insertNoteLink(blocks, BlockTree(blocks), InsertPoint("c", TextRange.Zero), "n7")
        BlockCommands.insertBookmark(blocks, BlockTree(blocks), mutableMapOf(), InsertPoint("c", TextRange.Zero),
            com.noted.app.data.Bookmark(id = "b1", url = "https://x.com", title = "x"))
        val inside = BlockCommands.insertToggle(blocks, BlockTree(blocks), InsertPoint("c", TextRange.Zero))
        assertEquals(
            // Después de la tarjeta sigue una nota, no un texto: va un texto vacío.
            listOf("checklist:A", "toggle:Desplegable:true", "text: (adentro)", "bookmark:b1", "text:", "note:n7", "text:p|b"),
            blockShape(blocks),
        )
        assertEquals(blocks[2].key, inside)
    }
}
