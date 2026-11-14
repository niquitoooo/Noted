package com.jot.app.ui

import android.text.SpannableStringBuilder
import androidx.compose.ui.text.TextRange
import com.jot.app.ui.rich.BLOCK_MARK_FLAGS
import com.jot.app.ui.rich.HeadingMark
import com.jot.app.ui.rich.InlineFormat
import com.jot.app.ui.rich.RichMark
import com.jot.app.ui.rich.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChecklistOpsTest {

    private fun item(id: String, checked: Boolean = false, nested: Boolean = false) =
        ChecklistItem(id, RichText(id), checked, nested)

    /** `x:` marcado en la sección, `-` anidado, `ghost:` el padre fantasma. */
    private fun ids(rows: List<ChecklistRow>): List<String> = rows.map { row ->
        when (row) {
            is ChecklistRow.Item ->
                (if (row.item.checked) "x:" else "") + (if (row.item.nested) "-" else "") + row.item.id
            is ChecklistRow.Ghost -> "ghost:" + row.parent.id
        }
    }

    /** La lista guardada: `-` anidado, `✓` marcado. */
    private fun desc(items: List<ChecklistItem>): List<String> = items.map {
        (if (it.nested) "-" else "") + it.id + (if (it.checked) "✓" else "")
    }

    @Test
    fun rowsGroupsCheckedAtBottom() {
        val rows = ChecklistOps.rows(listOf(item("A"), item("B", checked = true), item("C")))
        assertEquals(listOf("A", "C"), ids(rows.unchecked))
        assertEquals(listOf("x:B"), ids(rows.checked))
        assertEquals(1, rows.checkedCount)
    }

    @Test
    fun rowsGhostParent() {
        val rows = ChecklistOps.rows(listOf(item("A"), item("B", checked = true, nested = true), item("C")))
        assertEquals(listOf("A", "C"), ids(rows.unchecked))
        assertEquals(listOf("ghost:A", "x:-B"), ids(rows.checked))
        assertEquals(1, rows.checkedCount)
        assertEquals("ghost-A", rows.checked[0].id)
        assertEquals("B", rows.checked[1].id)
    }

    @Test
    fun rowsCheckedParentWithUncheckedChild() {
        val items = listOf(item("A"), item("B", checked = true), item("b1", nested = true))
        val rows = ChecklistOps.rows(items)
        assertEquals(listOf("A"), ids(rows.unchecked))
        assertEquals(listOf("x:B", "-b1"), ids(rows.checked))
        assertEquals(1, rows.checkedCount)

        assertEquals(listOf("A", "B✓", "-b1✓"), desc(ChecklistOps.toggle(items, "b1")))
    }

    @Test
    fun checkTopLevelMovesGroupToEnd() {
        val items = listOf(item("A"), item("B"), item("b1", nested = true), item("C"))
        assertEquals(listOf("A", "C", "B✓", "-b1✓"), desc(ChecklistOps.toggle(items, "B")))
    }

    @Test
    fun uncheckTopLevelGoesAboveCheckedGroups() {
        val items = listOf(item("A"), item("C"), item("B", checked = true), item("D", checked = true))
        assertEquals(listOf("A", "C", "D", "B✓"), desc(ChecklistOps.toggle(items, "D")))
    }

    @Test
    fun checkChildStaysUnderParent() {
        val items = listOf(item("A"), item("a1", nested = true))
        val checked = ChecklistOps.toggle(items, "a1")
        assertEquals(listOf("A", "-a1✓"), desc(checked))
        assertEquals(listOf("A", "-a1"), desc(ChecklistOps.toggle(checked, "a1")))
    }

    @Test
    fun uncheckChildOfCheckedParentUnchecksParent() {
        val items = listOf(
            item("A"),
            item("B", checked = true),
            item("b1", checked = true, nested = true),
            item("b2", checked = true, nested = true),
        )
        assertEquals(listOf("A", "B", "-b1", "-b2✓"), desc(ChecklistOps.toggle(items, "b1")))
    }

    @Test
    fun uncheckChildOfCheckedParentMovesGroupAboveCheckedGroups() {
        val items = listOf(
            item("A"),
            item("B", checked = true),
            item("b1", checked = true, nested = true),
            item("C", checked = true),
        )
        assertEquals(listOf("A", "B", "-b1", "C✓"), desc(ChecklistOps.toggle(items, "b1")))
    }

    @Test
    fun toggleCanonicalizesLegacyOrder() {
        val items = listOf(item("A"), item("B", checked = true), item("C"), item("D"))
        assertEquals(listOf("A", "C", "B✓", "D✓"), desc(ChecklistOps.toggle(items, "D")))
    }

    @Test
    fun canonicalKeepsOrderInsideEachPart() {
        val items = listOf(item("A"), item("B", checked = true), item("C"), item("D", checked = true), item("E"))
        val canonical = ChecklistOps.canonical(items)
        assertEquals(listOf("A", "C", "E", "B✓", "D✓"), desc(canonical))
        assertEquals(desc(canonical), desc(ChecklistOps.canonical(canonical)))
        // No cambia ningún ítem: son los mismos objetos.
        assertEquals(items.toSet(), canonical.toSet())
    }

    @Test
    fun canonicalMovesWholeGroups() {
        val items = listOf(
            item("A", checked = true),
            item("a1", nested = true),
            item("B"),
            item("b1", checked = true, nested = true),
        )
        assertEquals(listOf("B", "-b1✓", "A✓", "-a1"), desc(ChecklistOps.canonical(items)))
    }

    @Test
    fun toggleKeepsItemIds() {
        val items = listOf(item("A"), item("B"), item("b1", nested = true), item("C", checked = true))
        val out = ChecklistOps.toggle(items, "B")
        assertEquals(items.map { it.id }.sorted(), out.map { it.id }.sorted())
        assertNotEquals(items.map { it.id }, out.map { it.id })
    }

    @Test
    fun toggleUnknownIdOnlyCanonicalizes() {
        val items = listOf(item("A", checked = true), item("B"))
        assertEquals(listOf("B", "A✓"), desc(ChecklistOps.toggle(items, "zz")))
    }

    @Test
    fun normalizeUnnestsFirst() {
        val items = listOf(item("A", nested = true), item("B"))
        assertEquals(listOf("A", "B"), desc(ChecklistOps.normalize(items)))
    }

    @Test
    fun newItemIdIsUnique() {
        assertNotEquals(ChecklistOps.newItemId(), ChecklistOps.newItemId())
    }

    // ---- editar ----

    private fun text(id: String, value: String, checked: Boolean = false, nested: Boolean = false) =
        ChecklistItem(id, RichText(value), checked, nested)

    /** La lista guardada con los textos: `-` anidado, `✓` marcado, el texto entre comillas. */
    private fun texts(items: List<ChecklistItem>): List<String> = items.map {
        (if (it.nested) "-" else "") + "'" + it.value.plain + "'" + (if (it.checked) "✓" else "")
    }

    @Test
    fun replaceTextWithoutNewlineChangesText() {
        val items = listOf(text("A", "Alfa"), text("B", "Be"))
        val edit = ChecklistOps.replaceText(items, "B", RichText("Bet", TextRange(3)))
        assertEquals(listOf("'Alfa'", "'Bet'"), texts(edit.items))
        assertEquals("B", edit.focusId)
        assertEquals(3, edit.cursor)
    }

    @Test
    fun replaceTextWithNewlinesSplits() {
        val items = listOf(text("A", "Alfa"), text("B", "Beta", nested = true))
        val edit = ChecklistOps.replaceText(items, "B", RichText("Be\nta", TextRange(3)))
        assertEquals(listOf("'Alfa'", "-'Be'", "-'ta'"), texts(edit.items))
        assertEquals("B", edit.items[1].id)
        val created = edit.items[2]
        assertNotEquals("B", created.id)
        assertFalse(created.checked)
        assertEquals(created.id, edit.focusId)
        assertEquals(0, edit.cursor)

        val three = ChecklistOps.replaceText(items, "B", RichText("a\nb\nc", TextRange(5)))
        assertEquals(listOf("'Alfa'", "-'a'", "-'b'", "-'c'"), texts(three.items))
        assertTrue(three.items.none { it.value.plain.contains('\n') })
        assertEquals(three.items[3].id, three.focusId)
        assertEquals(1, three.cursor)
        assertEquals(4, three.items.map { it.id }.toSet().size)
    }

    @Test
    fun replaceTextDropsBlockMarks() {
        val value = SpannableStringBuilder("a\nb").apply { setSpan(HeadingMark(2), 0, 2, BLOCK_MARK_FLAGS) }
        val edit = ChecklistOps.replaceText(listOf(text("A", "")), "A", RichText(value, TextRange(2)))
        assertEquals(listOf("'a'", "'b'"), texts(edit.items))
        for (item in edit.items) {
            assertTrue(item.value.text.getSpans(0, item.value.plain.length, RichMark::class.java).isEmpty())
        }
    }

    @Test
    fun enterKeepsPending() {
        val pending = mapOf(InlineFormat.Bold to true)
        val edit = ChecklistOps.replaceText(listOf(text("A", "A")), "A", RichText("A\n", TextRange(2), pending))
        assertEquals(2, edit.items.size)
        assertEquals(pending, edit.items[1].value.pending)
        assertEquals(edit.items[1].id, edit.focusId)
    }

    @Test
    fun enterOnEmptyItemCreatesAnother() {
        val items = listOf(text("A", "Alfa"), text("B", "", nested = true))
        val edit = ChecklistOps.replaceText(items, "B", RichText("\n", TextRange(1)))
        assertEquals(listOf("'Alfa'", "-''", "-''"), texts(edit.items))
        val top = ChecklistOps.replaceText(listOf(text("A", "")), "A", RichText("\n", TextRange(1)))
        assertEquals(listOf("''", "''"), texts(top.items))
    }

    @Test
    fun enterOnCheckedItemAddsUncheckedAtEnd() {
        val items = listOf(text("A", "A"), text("B", "B", checked = true))
        val edit = ChecklistOps.replaceText(items, "B", RichText("B\n", TextRange(2)))
        assertEquals(listOf("'A'", "''", "'B'✓"), texts(edit.items))
        assertEquals(edit.items[1].id, edit.focusId)
        assertFalse(edit.items[1].nested)
        assertEquals(0, edit.cursor)
    }

    @Test
    fun enterOnCheckedChildAddsUnderParent() {
        val items = listOf(text("A", "A"), text("a1", "a1", checked = true, nested = true), text("C", "C"))
        val edit = ChecklistOps.replaceText(items, "a1", RichText("a1\n", TextRange(3)))
        assertEquals(listOf("'A'", "-'a1'✓", "-''", "'C'"), texts(edit.items))
        assertEquals(edit.items[2].id, edit.focusId)

        val checkedParent = listOf(text("A", "A", checked = true), text("a1", "a1", checked = true, nested = true))
        val out = ChecklistOps.replaceText(checkedParent, "a1", RichText("a1\n", TextRange(3)))
        assertEquals(listOf("''", "'A'✓", "-'a1'✓"), texts(out.items))
        assertFalse(out.items[0].nested)
        assertEquals(out.items[0].id, out.focusId)
    }

    @Test
    fun enterAtEndOfParentAddsFirstChild() {
        val items = listOf(
            text("A", "Alfa"),
            text("a1", "a1", nested = true),
            text("a2", "a2", nested = true),
        )
        val edit = ChecklistOps.replaceText(items, "A", RichText("Alfa\n", TextRange(5)))
        assertEquals(listOf("'Alfa'", "-''", "-'a1'", "-'a2'"), texts(edit.items))
        assertEquals("A", edit.items[0].id)
        assertEquals(edit.items[1].id, edit.focusId)
        assertEquals(0, edit.cursor)
    }

    @Test
    fun enterInMiddleOfParentMovesTailToFirstChild() {
        val items = listOf(
            text("A", "Alfa"),
            text("a1", "a1", nested = true),
            text("a2", "a2", nested = true),
        )
        val edit = ChecklistOps.replaceText(items, "A", RichText("A\nlfa", TextRange(2)))
        assertEquals(listOf("'A'", "-'lfa'", "-'a1'", "-'a2'"), texts(edit.items))
        assertEquals(edit.items[1].id, edit.focusId)
        assertEquals(0, edit.cursor)
    }

    @Test
    fun backspaceOnEmptyDeletesAndFocusesAboveEnd() {
        val edit = ChecklistOps.backspaceAtStart(listOf(text("A", "Alfa"), text("B", "")), "B")!!
        assertEquals(listOf("'Alfa'"), texts(edit.items))
        assertEquals("A", edit.focusId)
        assertEquals(4, edit.cursor)
    }

    @Test
    fun backspaceWithTextMergesKeepingUpperLevel() {
        val items = listOf(text("A", "Alfa"), text("B", "Beta", nested = true), text("C", "Gamma"))
        val edit = ChecklistOps.backspaceAtStart(items, "C")!!
        assertEquals(listOf("'Alfa'", "-'BetaGamma'"), texts(edit.items))
        assertEquals("B", edit.focusId)
        assertEquals(4, edit.cursor)
    }

    @Test
    fun backspaceMergeKeepsEachLevel() {
        // B (con hijo b1) se une a a2; b1 queda hijo de A.
        val items = listOf(
            text("A", "Alfa"),
            text("a1", "a1", nested = true),
            text("a2", "a2", nested = true),
            text("B", "Beta"),
            text("b1", "b1", nested = true),
        )
        val edit = ChecklistOps.backspaceAtStart(items, "B")!!
        assertEquals(listOf("'Alfa'", "-'a1'", "-'a2Beta'", "-'b1'"), texts(edit.items))
        assertEquals("a2", edit.focusId)
        assertEquals(2, edit.cursor)

        // A (con hijos a1 y a2) se une a Z; a1 y a2 quedan hijos de "ZetaAlfa".
        val withZ = listOf(
            text("Z", "Zeta"),
            text("A", "Alfa"),
            text("a1", "a1", nested = true),
            text("a2", "a2", nested = true),
        )
        val merged = ChecklistOps.backspaceAtStart(withZ, "A")!!
        assertEquals(listOf("'ZetaAlfa'", "-'a1'", "-'a2'"), texts(merged.items))
        assertEquals("Z", merged.focusId)
        assertEquals(4, merged.cursor)
    }

    @Test
    fun unknownIdKeepsFocus() {
        val items = listOf(item("A"), item("B"))
        val edits = listOf(
            ChecklistOps.delete(items, "zz"),
            ChecklistOps.replaceText(items, "zz", RichText("x\ny")),
            ChecklistOps.move(items, "zz", toRow = 0),
            ChecklistOps.moveByOne(items, "zz", up = true),
        )
        for (edit in edits) {
            assertEquals(items, edit.items)
            assertTrue(edit.keepFocus)
        }
        assertFalse(ChecklistOps.delete(items, "B").keepFocus)
        assertFalse(ChecklistOps.delete(items, "A").keepFocus)
    }

    @Test
    fun backspaceAtFirstItemWithTextDoesNothing() {
        assertNull(ChecklistOps.backspaceAtStart(listOf(text("A", "Alfa"), text("B", "")), "A"))
    }

    @Test
    fun backspaceAtFirstEmptyItemLeavesBlock() {
        val edit = ChecklistOps.backspaceAtStart(listOf(text("A", ""), text("B", "Beta")), "A")!!
        assertEquals(listOf("'Beta'"), texts(edit.items))
        assertNull(edit.focusId)
    }

    @Test
    fun backspaceInSectionSkipsGhost() {
        val items = listOf(
            text("A", "Alfa"),
            text("a1", "a1", checked = true, nested = true),
            text("B", "Beta", checked = true),
        )
        val edit = ChecklistOps.backspaceAtStart(items, "B")!!
        assertEquals(listOf("'Alfa'", "-'a1Beta'✓"), texts(edit.items))
        assertEquals("a1", edit.focusId)
        assertEquals(2, edit.cursor)

        assertNull(ChecklistOps.backspaceAtStart(items, "a1"))
    }

    @Test
    fun backspaceFirstEmptyInSection() {
        val edit = ChecklistOps.backspaceAtStart(listOf(text("A", "Alfa"), text("B", "", checked = true)), "B")!!
        assertEquals(listOf("'Alfa'"), texts(edit.items))
        assertEquals("A", edit.focusId)
        assertEquals(4, edit.cursor)

        val alone = ChecklistOps.backspaceAtStart(listOf(text("B", "", checked = true)), "B")!!
        assertTrue(alone.items.isEmpty())
        assertNull(alone.focusId)
    }

    @Test
    fun deleteFirstItemLeavesFocus() {
        val items = listOf(item("A"), item("B"))
        val first = ChecklistOps.delete(items, "A")
        assertEquals(listOf("B"), desc(first.items))
        assertNull(first.focusId)

        val second = ChecklistOps.delete(items, "B")
        assertEquals(listOf("A"), desc(second.items))
        assertEquals("A", second.focusId)
        assertEquals(1, second.cursor)
    }

    @Test
    fun deleteParentGivesChildrenToUpperGroup() {
        val items = listOf(item("A"), item("B"), item("b1", nested = true))
        assertEquals(listOf("A", "-b1"), desc(ChecklistOps.delete(items, "B").items))
        // Si el hijo queda primero, sube a primer nivel.
        val alone = listOf(item("B"), item("b1", nested = true))
        assertEquals(listOf("b1"), desc(ChecklistOps.delete(alone, "B").items))
    }

    @Test
    fun deletingLastItemEmptiesList() {
        val edit = ChecklistOps.delete(listOf(item("A")), "A")
        assertTrue(edit.items.isEmpty())
        assertNull(edit.focusId)
    }

    @Test
    fun addAtEndGoesAfterUnchecked() {
        val items = listOf(item("A"), item("a1", checked = true, nested = true), item("B", checked = true))
        val edit = ChecklistOps.addAtEnd(items)
        assertEquals(4, edit.items.size)
        val added = edit.items[2]
        assertEquals("", added.value.plain)
        assertFalse(added.checked)
        assertFalse(added.nested)
        assertEquals(added.id, edit.focusId)
        assertEquals(0, edit.cursor)
        assertEquals(listOf("A", "-a1✓"), desc(edit.items.take(2)))
        assertEquals(listOf("B✓"), desc(edit.items.drop(3)))
    }

    @Test
    fun nestRules() {
        val items = listOf(item("A"), item("B"))
        assertNull(ChecklistOps.setNested(items, "A", true))
        val nested = ChecklistOps.setNested(items, "B", true, cursor = 1)!!
        assertEquals(listOf("A", "-B"), desc(nested.items))
        assertEquals("B", nested.focusId)
        assertEquals(1, nested.cursor)
        assertNull(ChecklistOps.setNested(items, "B", false))
        assertNull(ChecklistOps.setNested(nested.items, "B", true))

        val group = listOf(item("D"), item("A"), item("b", nested = true), item("c", nested = true))
        val under = ChecklistOps.setNested(group, "A", true)!!
        assertEquals(listOf("D", "-A", "-b", "-c"), desc(under.items))
        assertEquals(listOf("D", "A", "-b", "-c"), desc(ChecklistOps.setNested(under.items, "A", false)!!.items))
    }

    @Test
    fun nestNotInCheckedSection() {
        val items = listOf(item("A"), item("B", checked = true))
        assertNull(ChecklistOps.setNested(items, "B", true))
    }

    @Test
    fun nestAfterLegacyCheckedInMiddle() {
        val items = listOf(item("A"), item("B", checked = true), item("C"))
        assertEquals(listOf("A", "-C", "B✓"), desc(ChecklistOps.setNested(items, "C", true)!!.items))
    }

    @Test
    fun moveCarriesHiddenCheckedChildren() {
        val items = listOf(item("A"), item("a1", checked = true, nested = true), item("B"), item("C"))
        val edit = ChecklistOps.move(items, "A", toRow = 2, cursor = 1)
        assertEquals(listOf("B", "C", "A", "-a1✓"), desc(edit.items))
        assertEquals("A", edit.focusId)
        assertEquals(1, edit.cursor)
    }

    @Test
    fun dropRowSnapsToGroupEnd() {
        val items = listOf(item("A"), item("a1", nested = true), item("B"), item("C"))
        assertEquals(2, ChecklistOps.dropRow(items, "C", rawRow = 1))
        assertEquals(0, ChecklistOps.dropRow(items, "C", rawRow = 0))
        assertEquals(3, ChecklistOps.dropRow(items, "C", rawRow = 3))
        assertEquals(2, ChecklistOps.dropRow(items, "a1", rawRow = 2))
    }

    @Test
    fun moveTopLevelOnlyBetweenGroups() {
        val items = listOf(item("A"), item("a1", nested = true), item("B"), item("C"))
        assertEquals(listOf("A", "-a1", "C", "B"), desc(ChecklistOps.move(items, "C", toRow = 1).items))
    }

    @Test
    fun moveByOneParentSkipsGroup() {
        val items = listOf(item("A"), item("a1", nested = true), item("B"))
        assertEquals(listOf("B", "A", "-a1"), desc(ChecklistOps.moveByOne(items, "B", up = true).items))
        assertEquals(listOf("B", "A", "-a1"), desc(ChecklistOps.moveByOne(items, "A", up = false).items))
        // En el borde no cambia nada.
        assertEquals(items, ChecklistOps.moveByOne(items, "A", up = true).items)
        assertEquals(items, ChecklistOps.moveByOne(items, "B", up = false).items)
    }

    @Test
    fun moveByOneChildPassesNeighbourRow() {
        val items = listOf(item("A"), item("a1", nested = true), item("a2", nested = true), item("B"))
        assertEquals(listOf("A", "-a2", "-a1", "B"), desc(ChecklistOps.moveByOne(items, "a1", up = false).items))
        assertEquals(listOf("A", "-a1", "B", "-a2"), desc(ChecklistOps.moveByOne(items, "a2", up = false).items))
        assertEquals(listOf("a1", "A", "-a2", "B"), desc(ChecklistOps.moveByOne(items, "a1", up = true).items))
    }

    @Test
    fun moveChildToTopBecomesTopLevel() {
        val items = listOf(item("A"), item("a1", nested = true))
        assertEquals(listOf("a1", "A"), desc(ChecklistOps.move(items, "a1", toRow = 0).items))
    }
}
