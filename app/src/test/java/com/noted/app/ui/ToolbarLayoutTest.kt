package com.noted.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {

    @Test
    fun `sin nada guardado quedan todos los botones a la vista`() {
        val layout = ToolbarLayout.parse(null, null)
        assertEquals(ToolbarLayout.DEFAULT, layout)
        assertEquals(18, layout.visible.size)
        assertEquals(listOf(ToolbarItem.Undo, ToolbarItem.Redo, ToolbarItem.Bold), layout.visible.take(3))
        assertEquals(ToolbarItem.Attach, layout.visible.last())
    }

    @Test
    fun `mover cambia el orden y vuelve igual al leerlo`() {
        val moved = ToolbarLayout.DEFAULT.moved(0, 3)
        assertEquals(listOf(ToolbarItem.Redo, ToolbarItem.Bold, ToolbarItem.Italic, ToolbarItem.Undo), moved.order.take(4))
        assertEquals(moved, ToolbarLayout.parse(moved.orderKeys(), moved.hiddenKeys()))
    }

    @Test
    fun `ocultar saca el boton de la barra pero conserva su lugar`() {
        val layout = ToolbarLayout.DEFAULT.toggled(ToolbarItem.Code)
        assertTrue(ToolbarItem.Code !in layout.visible)
        assertEquals(ToolbarLayout.DEFAULT.order, layout.order)
        val again = ToolbarLayout.parse(layout.orderKeys(), layout.hiddenKeys())
        assertEquals(layout, again)
        assertEquals(ToolbarLayout.DEFAULT, again.toggled(ToolbarItem.Code))
    }

    @Test
    fun `se pueden ocultar todos`() {
        val all = ToolbarItem.entries.fold(ToolbarLayout.DEFAULT) { l, item -> l.toggled(item) }
        assertEquals(emptyList<ToolbarItem>(), all.visible)
        assertEquals(all, ToolbarLayout.parse(all.orderKeys(), all.hiddenKeys()))
    }

    @Test
    fun `un boton que no estaba guardado entra despues del que lo precede y las claves raras se ignoran`() {
        val layout = ToolbarLayout.parse("bold,italic,viejo,bold", "viejo,italic")
        assertEquals(
            listOf(
                ToolbarItem.Undo, ToolbarItem.Redo, ToolbarItem.Bold, ToolbarItem.Italic,
                ToolbarItem.Strike, ToolbarItem.TextColor, ToolbarItem.Highlight, ToolbarItem.Heading,
            ),
            layout.order.take(8),
        )
        assertEquals(18, layout.order.size)
        assertEquals(setOf(ToolbarItem.Italic), layout.hidden)
    }

    @Test
    fun `color y resaltado entran despues de tachado en una barra ya ordenada`() {
        val old = ToolbarItem.entries.filterNot { it == ToolbarItem.TextColor || it == ToolbarItem.Highlight }
        val custom = listOf(ToolbarItem.Strike) + (old - ToolbarItem.Strike)
        val layout = ToolbarLayout.parse(custom.joinToString(",") { it.key }, "code")
        assertEquals(listOf(ToolbarItem.Strike, ToolbarItem.TextColor, ToolbarItem.Highlight), layout.order.take(3))
        assertEquals(setOf(ToolbarItem.Code), layout.hidden)
    }

    @Test
    fun `mover fuera de rango no cambia nada`() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.DEFAULT.moved(0, 99))
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.DEFAULT.moved(2, 2))
    }
}
