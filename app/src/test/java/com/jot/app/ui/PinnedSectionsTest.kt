package com.jot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedSectionsTest {

    private fun pinnedOf(vararg ids: String): (String) -> Boolean {
        val set = ids.toSet()
        return { it in set }
    }

    // ------------------------------------------------------------ filas

    @Test
    fun withoutPinnedTheListHasNoHeaders() {
        val rows = PinnedSections.rows(listOf("a", "b", "c"), pinnedOf())
        assertEquals(listOf("a", "b", "c").map { PinnedSections.Row.Item(it) }, rows)
    }

    @Test
    fun withPinnedTheListHasBothSections() {
        val rows = PinnedSections.rows(listOf("p1", "p2", "a", "b"), pinnedOf("p1", "p2"))
        assertEquals(
            listOf(
                PinnedSections.Row.Header(pinned = true),
                PinnedSections.Row.Item("p1"),
                PinnedSections.Row.Item("p2"),
                PinnedSections.Row.Header(pinned = false),
                PinnedSections.Row.Item("a"),
                PinnedSections.Row.Item("b")
            ),
            rows
        )
    }

    @Test
    fun whenAllArePinnedThereIsNoSecondHeader() {
        val rows = PinnedSections.rows(listOf("p1", "p2"), pinnedOf("p1", "p2"))
        assertEquals(
            listOf(
                PinnedSections.Row.Header(pinned = true),
                PinnedSections.Row.Item("p1"),
                PinnedSections.Row.Item("p2")
            ),
            rows
        )
    }

    // ------------------------------------------------------------ soltar

    @Test
    fun movingInsideTheUnpinnedSectionKeepsTheDropPlace() {
        val drop = listOf("p1", "b", "c", "a", "d")
        val result = PinnedSections.settle(drop, "a", pinnedOf("p1"))
        assertEquals(drop, result)
    }

    @Test
    fun movingInsideThePinnedSectionKeepsTheDropPlace() {
        val drop = listOf("p2", "p1", "p3", "a")
        val result = PinnedSections.settle(drop, "p1", pinnedOf("p1", "p2", "p3"))
        assertEquals(drop, result)
    }

    @Test
    fun pinnedDroppedAmongUnpinnedGoesBackToTheEndOfThePinned() {
        val drop = listOf("p2", "p3", "a", "b", "p1", "c")
        val result = PinnedSections.settle(drop, "p1", pinnedOf("p1", "p2", "p3"))
        assertEquals(listOf("p2", "p3", "p1", "a", "b", "c"), result)
    }

    @Test
    fun pinnedDroppedAtTheVeryBottomGoesBackToTheEndOfThePinned() {
        val drop = listOf("p2", "a", "b", "p1")
        val result = PinnedSections.settle(drop, "p1", pinnedOf("p1", "p2"))
        assertEquals(listOf("p2", "p1", "a", "b"), result)
    }

    @Test
    fun unpinnedDroppedAmongPinnedGoesBackToTheStartOfTheUnpinned() {
        val drop = listOf("p1", "c", "p2", "a", "b")
        val result = PinnedSections.settle(drop, "c", pinnedOf("p1", "p2"))
        assertEquals(listOf("p1", "p2", "c", "a", "b"), result)
    }

    @Test
    fun unpinnedDroppedAtTheVeryTopGoesBackToTheStartOfTheUnpinned() {
        val drop = listOf("c", "p1", "a", "b")
        val result = PinnedSections.settle(drop, "c", pinnedOf("p1"))
        assertEquals(listOf("p1", "c", "a", "b"), result)
    }

    @Test
    fun theOnlyPinnedNoteAlwaysComesBackFirst() {
        val drop = listOf("a", "b", "p1", "c")
        val result = PinnedSections.settle(drop, "p1", pinnedOf("p1"))
        assertEquals(listOf("p1", "a", "b", "c"), result)
    }

    @Test
    fun withoutPinnedTheDropPlaceIsKept() {
        val drop = listOf("b", "a", "c")
        val result = PinnedSections.settle(drop, "a", pinnedOf())
        assertEquals(drop, result)
    }

    @Test
    fun movingToTheFirstAndLastPlaceOfTheSection() {
        val pinned = pinnedOf("p1")
        assertEquals(
            listOf("p1", "c", "a", "b"),
            PinnedSections.settle(listOf("p1", "c", "a", "b"), "c", pinned)
        )
        assertEquals(
            listOf("p1", "b", "c", "a"),
            PinnedSections.settle(listOf("p1", "b", "c", "a"), "a", pinned)
        )
    }

    // ------------------------------------------------------------ destino

    @Test
    fun theTargetIsTheNoteThatHeldTheFinalPlace() {
        val original = listOf("p1", "a", "b", "c")
        assertEquals("c", PinnedSections.moveTarget(original, listOf("p1", "b", "c", "a"), "a"))
        assertEquals("a", PinnedSections.moveTarget(original, listOf("p1", "c", "a", "b"), "c"))
    }

    @Test
    fun noTargetWhenTheNoteEndsWhereItWas() {
        val original = listOf("p1", "p2", "a")
        // Se arrastró a las no fijadas y volvió al final de las fijadas, su lugar.
        val final = PinnedSections.settle(listOf("p1", "a", "p2"), "p2", pinnedOf("p1", "p2"))
        assertNull(PinnedSections.moveTarget(original, final, "p2"))
    }
}
