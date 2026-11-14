package com.jot.app.ui

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteUndoTest {

    /** Un estado reconocible por su nombre: un desplegable con ese título. */
    private fun state(name: String) =
        UndoState(listOf(EditorBlock.Toggle("t", name)), UndoFocus("t", null, TextRange(name.length)))

    private fun UndoState?.name(): String? = (this?.blocks?.single() as? EditorBlock.Toggle)?.title

    @Test
    fun typingWithin300msIsOneStep() {
        val undo = NoteUndo()
        undo.reset(state(""))
        undo.record(state("a"), typing = true, now = 0)
        undo.record(state("ab"), typing = true, now = 100)
        undo.record(state("abc"), typing = true, now = 250)

        assertEquals("", undo.undo().name())
        assertFalse(undo.canUndo)
    }

    @Test
    fun pauseOf300msSplitsSteps() {
        val undo = NoteUndo()
        undo.reset(state(""))
        undo.record(state("a"), typing = true, now = 0)
        undo.record(state("ab"), typing = true, now = 300)

        assertEquals("a", undo.undo().name())
        assertEquals("", undo.undo().name())
        assertNull(undo.undo())
    }

    @Test
    fun nonTypingIsAlwaysOwnStep() {
        val undo = NoteUndo()
        undo.reset(state(""))
        undo.record(state("a"), typing = true, now = 0)
        undo.record(state("a+foto"), typing = false, now = 10)
        // Después de un paso que no es escribir, lo que se escribe abre un grupo nuevo.
        undo.record(state("a+foto+b"), typing = true, now = 20)

        assertEquals("a+foto", undo.undo().name())
        assertEquals("a", undo.undo().name())
        assertEquals("", undo.undo().name())
        assertNull(undo.undo())
    }

    @Test
    fun limitKeeps100() {
        val undo = NoteUndo()
        undo.reset(state("0"))
        for (i in 1..101) undo.record(state("$i"), typing = false, now = i * 1000L)

        repeat(100) { assertNotNull(undo.undo()) }
        assertNull(undo.undo())
    }

    @Test
    fun recordClearsRedo() {
        val undo = NoteUndo()
        undo.reset(state(""))
        undo.record(state("a"), typing = false, now = 0)
        undo.undo()
        assertTrue(undo.canRedo)

        undo.record(state("b"), typing = false, now = 10)

        assertFalse(undo.canRedo)
        assertNull(undo.redo())
        assertEquals("", undo.undo().name())
    }

    @Test
    fun undoThenRedoReturnsSameState() {
        val undo = NoteUndo()
        undo.reset(state(""))
        val after = state("a")
        undo.record(after, typing = false, now = 0)

        assertEquals("", undo.undo().name())
        assertEquals(after, undo.redo())
        assertFalse(undo.canRedo)
        assertTrue(undo.canUndo)
    }
}
