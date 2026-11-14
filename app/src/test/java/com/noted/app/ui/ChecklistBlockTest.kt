package com.jot.app.ui

import androidx.compose.ui.text.TextRange
import com.jot.app.ui.rich.RichText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChecklistBlockTest {

    private fun item(id: String, checked: Boolean = false, nested: Boolean = false) =
        ChecklistItem(id, RichText(id), checked, nested)

    /** Cada línea del bloque: `+` la fila de agregar, `#N` el encabezado, `x:` la sección. */
    private fun desc(lines: List<ChecklistLine>): List<String> = lines.map { line ->
        when (line) {
            is ChecklistLine.Row -> {
                val row = line.row
                val name = when (row) {
                    is ChecklistRow.Item -> row.item.id
                    is ChecklistRow.Ghost -> "ghost:" + row.parent.id
                }
                (if (line.inSection) "x:" else "") + name
            }
            ChecklistLine.Add -> "+"
            is ChecklistLine.Header -> "#" + line.count
        }
    }

    @Test
    fun linesWithoutCheckedHaveNoHeader() {
        val lines = checklistLines(ChecklistOps.rows(listOf(item("A"), item("B"))), collapsed = false)
        assertEquals(listOf("A", "B", "+"), desc(lines))
    }

    @Test
    fun linesPutAddHeaderAndSectionInOneList() {
        val rows = ChecklistOps.rows(listOf(item("A"), item("B", checked = true), item("C")))
        assertEquals(listOf("A", "C", "+", "#1", "x:B"), desc(checklistLines(rows, collapsed = false)))
    }

    @Test
    fun collapsedHidesSectionButKeepsHeader() {
        val rows = ChecklistOps.rows(listOf(item("A"), item("B", checked = true)))
        assertEquals(listOf("A", "+", "#1"), desc(checklistLines(rows, collapsed = true)))
    }

    @Test
    fun ghostGoesInSection() {
        val rows = ChecklistOps.rows(listOf(item("A"), item("B", checked = true, nested = true)))
        assertEquals(listOf("A", "+", "#1", "x:ghost:A", "x:B"), desc(checklistLines(rows, collapsed = false)))
    }

    @Test
    fun lineKeysAreStableAndUnique() {
        val rows = ChecklistOps.rows(listOf(item("A"), item("B", checked = true, nested = true)))
        val keys = checklistLines(rows, collapsed = false).map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        // El ítem conserva la misma clave arriba y en la sección.
        val before = checklistLines(ChecklistOps.rows(listOf(item("A"), item("B"))), false).map { it.key }
        assertEquals(true, "B" in before && "B" in keys)
    }

    @Test
    fun cursorMoveDoesNotReorderLegacyList() {
        // Nota vieja: un marcado en el medio. Mover el cursor en C no la ordena.
        val items = listOf(item("A"), item("B", checked = true), item("C"))
        val moved = RichText("C", TextRange(0))
        val edit = itemValueEdit(items, items[2], moved)
        assertEquals(listOf("A", "B", "C"), edit.items.map { it.id })
        assertEquals(0, edit.items[2].value.selection.start)
        assertEquals("C", edit.focusId)
    }

    @Test
    fun typingReplacesTextInOrder() {
        val items = listOf(item("A"), item("B", checked = true), item("C"))
        val edit = itemValueEdit(items, items[2], RichText("Cx"))
        assertEquals(listOf("A", "C", "B"), edit.items.map { it.id })
        assertEquals("Cx", edit.items[1].value.plain)
    }

    @Test
    fun headerText() {
        assertEquals("1 elemento marcado", checkedHeaderText(1))
        assertEquals("3 elementos marcados", checkedHeaderText(3))
    }

    @Test
    fun arrowsSkipAddHeaderGhostAndCollapsedSection() {
        val items = listOf(item("A"), item("a1", nested = true), item("a2", checked = true, nested = true), item("B", checked = true))
        // Desplegado: A, a1, +, encabezado, fantasma de A, a2, B.
        assertEquals("a2", checklistNeighbor(items, collapsed = false, "a1", 1))
        assertEquals("a1", checklistNeighbor(items, collapsed = false, "a2", -1))
        assertEquals(null, checklistNeighbor(items, collapsed = false, "B", 1))
        assertEquals(null, checklistNeighbor(items, collapsed = false, "A", -1))
        // Plegado, desde la última fila de arriba se sale del bloque.
        assertEquals(null, checklistNeighbor(items, collapsed = true, "a1", 1))
        assertEquals("A", checklistEdgeItem(items, collapsed = true, direction = 1))
        assertEquals("a1", checklistEdgeItem(items, collapsed = true, direction = -1))
        assertEquals("B", checklistEdgeItem(items, collapsed = false, direction = -1))
    }

    @Test
    fun togglingParentMovesFocusOffHiddenChild() {
        // Camino A: el foco en a1 y se marca su padre A con la sección plegada.
        val items = listOf(item("A"), item("a1", nested = true), item("B"))
        val toggled = ChecklistOps.toggle(items, "A")
        assertEquals(true, focusLeavesAfterToggle(toggled, collapsed = true, focusedId = "a1", toggledId = "A"))
        assertEquals("B" to 0, focusAfterToggle(items, toggled, collapsed = true, "a1", cursor = 1))
        // Desplegado, a1 se sigue viendo en la sección: el foco no se mueve.
        assertEquals(false, focusLeavesAfterToggle(toggled, collapsed = false, focusedId = "a1", toggledId = "A"))
        // Otro ítem con foco que sigue a la vista: tampoco.
        assertEquals(false, focusLeavesAfterToggle(ChecklistOps.toggle(items, "B"), collapsed = true, focusedId = "A", toggledId = "B"))
    }

    @Test
    fun collapsingMovesFocusOutOfSection() {
        // Camino B: foco en un ítem de la sección y se pliega el encabezado.
        val items = listOf(ChecklistItem("A", RichText("Alfa")), item("B", checked = true))
        assertEquals(CollapseFocus.Item("A", 4), focusAfterCollapse(items, "B"))
        assertEquals(CollapseFocus.Stay, focusAfterCollapse(items, "A"))
        val allChecked = listOf(item("B", checked = true))
        assertEquals(CollapseFocus.TextAbove, focusAfterCollapse(allChecked, "B"))
    }

    @Test
    fun toggleFocusFollowsItemOrGoesToNeighborWhenCollapsed() {
        val items = listOf(ChecklistItem("A", RichText("Alfa")), item("B"), item("C"))
        val toggledB = ChecklistOps.toggle(items, "B")
        // Desplegado: el foco sigue al ítem.
        assertEquals("B" to 1, focusAfterToggle(items, toggledB, collapsed = false, "B", cursor = 1))
        // Plegado: al final de la fila de arriba.
        assertEquals("A" to 4, focusAfterToggle(items, toggledB, collapsed = true, "B", cursor = 1))
        // Era la primera: al principio de la de abajo.
        val toggledA = ChecklistOps.toggle(items, "A")
        assertEquals("B" to 0, focusAfterToggle(items, toggledA, collapsed = true, "A", cursor = 2))
        // No queda ninguna: sale del bloque.
        val only = listOf(item("A"))
        assertEquals(null, focusAfterToggle(only, ChecklistOps.toggle(only, "A"), collapsed = true, "A", cursor = 0))
    }

    // ---- arrastre y anidado ----

    @Test
    fun dragOthersLeaveOutTheDraggedGroup() {
        val items = listOf(item("A"), item("a1", nested = true), item("B"), item("b1", nested = true), item("C", checked = true))
        // Un padre se lleva a sus hijos; un subítem va solo; los marcados no cuentan.
        assertEquals(listOf("B", "b1"), checklistDragOthers(items, "A"))
        assertEquals(listOf("A", "a1", "B"), checklistDragOthers(items, "b1"))
    }

    @Test
    fun dragSlotIsTheNearestPlace() {
        // Tres filas de 48 sin la arrastrada: los lugares empiezan en 0, 48, 96 y 144.
        val heights = listOf(48f, 48f, 48f)
        assertEquals(0, checklistDragSlot(heights, copyTop = 23f))
        // Pasó la mitad de la vecina.
        assertEquals(1, checklistDragSlot(heights, copyTop = 25f))
        assertEquals(3, checklistDragSlot(heights, copyTop = 500f))
        assertEquals(0, checklistDragSlot(heights, copyTop = -300f))
        // Una fila alta corre los lugares de abajo.
        assertEquals(1, checklistDragSlot(listOf(100f, 48f), copyTop = 60f))
    }

    @Test
    fun dragViewShowsTheGapWhereTheDropLands() {
        // B (padre) sobre el lugar entre A y a1: el hueco se ve al final del grupo de A.
        val items = listOf(item("A"), item("a1", nested = true), item("B"), item("b1", nested = true))
        val view = checklistDragView(items, "B", rawRow = 1)
        assertEquals(listOf("A", "a1", "B", "b1"), view.items.map { it.id })
        // Los hijos del arrastrado no se ven.
        assertEquals(setOf("b1"), view.hidden)
        // B sigue siendo de primer nivel y b1 su hijo.
        assertEquals(listOf(false, true, false, true), view.items.map { it.nested })
        // Entre A y a1 con un subítem sí cae ahí, y conserva su nivel.
        val child = checklistDragView(items, "b1", rawRow = 1).items
        assertEquals(listOf("A", "b1", "a1", "B"), child.map { it.id })
        assertEquals(listOf(false, true, true, false), child.map { it.nested })
        // Arriba de todo, con los hijos.
        assertEquals(listOf("B", "b1", "A", "a1"), checklistDragView(items, "B", rawRow = 0).items.map { it.id })
        // Un subítem no esconde nada.
        assertEquals(emptySet<String>(), checklistDragView(items, "a1", rawRow = 2).hidden)
    }

    @Test
    fun dragViewHidesOnlyUncheckedChildren() {
        val items = listOf(item("A"), item("a1", nested = true), item("a2", checked = true, nested = true), item("B"))
        assertEquals(setOf("a1"), checklistDragView(items, "A", rawRow = 1).hidden)
    }

    @Test
    fun swipeFollowsFingerWithinLimits() {
        // Sigue al dedo hasta ±38 dp.
        assertEquals(20f, checklistSwipeOffset(20f, limit = 38f, canNest = true, canUnnest = true))
        assertEquals(38f, checklistSwipeOffset(89f, limit = 38f, canNest = true, canUnnest = true))
        assertEquals(-38f, checklistSwipeOffset(-89f, limit = 38f, canNest = true, canUnnest = true))
        // Para el lado que no se puede (el primer ítem, uno de nivel 0 a la izquierda), no se mueve.
        assertEquals(0f, checklistSwipeOffset(89f, limit = 38f, canNest = false, canUnnest = true))
        assertEquals(0f, checklistSwipeOffset(-89f, limit = 38f, canNest = true, canUnnest = false))
    }

    @Test
    fun swipeNestsPastThirtyDp() {
        // Más de 30 dp anida o desanida; menos, vuelve.
        assertEquals(true, checklistSwipeNested(31f, threshold = 30f))
        assertEquals(false, checklistSwipeNested(-31f, threshold = 30f))
        assertEquals(null, checklistSwipeNested(29f, threshold = 30f))
        assertEquals(null, checklistSwipeNested(-29f, threshold = 30f))
    }

    @Test
    fun edgeScrollIsQuadraticNearTheBorders() {
        // A menos de 56 del borde, más rápido cuanto más cerca, tope 2000.
        assertEquals(0f, checklistEdgeSpeed(300f, top = 0f, bottom = 1000f, edge = 56f, max = 2000f))
        assertEquals(0f, checklistEdgeSpeed(56f, top = 0f, bottom = 1000f, edge = 56f, max = 2000f))
        assertEquals(-500f, checklistEdgeSpeed(28f, top = 0f, bottom = 1000f, edge = 56f, max = 2000f))
        assertEquals(-2000f, checklistEdgeSpeed(-10f, top = 0f, bottom = 1000f, edge = 56f, max = 2000f))
        assertEquals(500f, checklistEdgeSpeed(972f, top = 0f, bottom = 1000f, edge = 56f, max = 2000f))
        assertEquals(2000f, checklistEdgeSpeed(1000f, top = 0f, bottom = 1000f, edge = 56f, max = 2000f))
    }

    @Test
    fun horizontalSwipeAlwaysClaimsEvenWhereItCannotNest() {
        // De costado se queda con el gesto (también hacia un lado no permitido: no selecciona).
        assertEquals(SwipeStart.Claim, checklistSwipeStart(20f, 5f, slop = 8f, elapsed = 100, longPress = 400, secondTap = false))
        assertEquals(SwipeStart.Claim, checklistSwipeStart(-20f, 5f, slop = 8f, elapsed = 100, longPress = 400, secondTap = false))
        // Vertical: desplaza la nota.
        assertEquals(SwipeStart.Release, checklistSwipeStart(5f, 20f, slop = 8f, elapsed = 100, longPress = 400, secondTap = false))
        // Dentro del margen de toque: espera.
        assertEquals(SwipeStart.Wait, checklistSwipeStart(3f, 3f, slop = 8f, elapsed = 100, longPress = 400, secondTap = false))
        // Toque largo o doble toque: seleccionar en el ítem.
        assertEquals(SwipeStart.Release, checklistSwipeStart(20f, 0f, slop = 8f, elapsed = 500, longPress = 400, secondTap = false))
        assertEquals(SwipeStart.Release, checklistSwipeStart(20f, 0f, slop = 8f, elapsed = 100, longPress = 400, secondTap = true))
    }
}
