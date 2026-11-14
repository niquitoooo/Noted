package com.noted.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveNotebookNameTest {
    private val existing = listOf("Ideas", "Viajes")

    @Test
    fun vacioEsSinCuaderno() {
        assertNull(resolveNotebookName("   ", existing))
    }

    @Test
    fun nombreNuevoSinEspaciosDeMas() {
        assertEquals("Casa", resolveNotebookName("  Casa ", existing))
    }

    @Test
    fun mismoNombreConOtrasMayusculasUsaElExistente() {
        assertEquals("Viajes", resolveNotebookName("viajes", existing))
    }
}
