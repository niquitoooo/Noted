package com.noted.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteIONotebooksTest {

    @Test
    fun `ida y vuelta de notebooks json`() {
        val icons = linkedMapOf("Viajes" to "emoji:🏠", "Trabajo" to "icons/nb_1.png", "Vacío" to "")
        assertEquals(icons, NoteIO.notebooksFromJson(NoteIO.notebooksToJson(icons)))
    }

    @Test
    fun `se ignoran entradas raras y archivos rotos`() {
        val text = """{"notebooks":[
            {"name":"","icon":"emoji:🏠"},
            {"name":"A","icon":"emoji:"},
            {"name":"B","icon":"../secreto.png"},
            {"name":"C","icon":"icons/../x.png"},
            {"name":"D","icon":"builtin:home"},
            {"name":" E ","icon":"icons/e.svg"}
        ]}"""
        assertEquals(mapOf("E" to "icons/e.svg"), NoteIO.notebooksFromJson(text))
        assertTrue(NoteIO.notebooksFromJson("no es json").isEmpty())
    }
}
