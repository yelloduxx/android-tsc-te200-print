package com.example.tscprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSelectionTest {

    @Test
    fun resetSelectsEveryPage() {
        val selection = PageSelection()

        selection.reset(4)

        assertEquals(PageSelection.Mode.ALL, selection.mode)
        assertEquals(setOf(0, 1, 2, 3), selection.selectedPages())
    }

    @Test
    fun rangeSupportsSinglePagesAndIntervals() {
        val selection = PageSelection()
        selection.reset(10)

        assertTrue(selection.applyRange("1-3, 5, 8-10").isSuccess)

        assertEquals(PageSelection.Mode.RANGE, selection.mode)
        assertEquals("1,2,3,5,8,9,10", selection.expression())
    }

    @Test
    fun invalidRangeDoesNotChangeSelection() {
        val selection = PageSelection()
        selection.reset(4)
        selection.toggle(0)
        val before = selection.selectedPages()

        assertFalse(selection.applyRange("1-5").isSuccess)
        assertEquals(before, selection.selectedPages())
    }

    @Test
    fun restoredManualSelectionCanRemainEmpty() {
        val selection = PageSelection()

        assertTrue(selection.restore(4, PageSelection.Mode.MANUAL.name, intArrayOf()))

        assertEquals(0, selection.count())
        assertEquals(PageSelection.Mode.MANUAL, selection.mode)
    }
}
