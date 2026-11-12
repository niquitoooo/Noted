package com.jot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {

    @Test
    fun `sin nada guardado quedan todos los botones a la vista`() {
        val layout = ToolbarLayout.parse(null, null)
        assertEquals(ToolbarLayout.DEFAULT, layout)
        assertEquals(16, layout.visible.size)
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
    fun `un boton que no estaba guardado aparece primero y las claves raras se ignoran`() {
        val layout = ToolbarLayout.parse("bold,italic,viejo,bold", "viejo,italic")
        assertEquals(ToolbarItem.Undo, layout.order.first())
        assertEquals(listOf(ToolbarItem.Bold, ToolbarItem.Italic), layout.order.takeLast(2))
        assertEquals(16, layout.order.size)
        assertEquals(setOf(ToolbarItem.Italic), layout.hidden)
    }

    @Test
    fun `mover fuera de rango no cambia nada`() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.DEFAULT.moved(0, 99))
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.DEFAULT.moved(2, 2))
    }
}
