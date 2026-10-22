package com.mobilerun.portal.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleOffsetTest {
    // a 165 px bubble asked for at x 0, y 605; the system put its window 80 px lower (status bar)
    private val size = 165
    private val asked = 0 to 605
    private val inset = 0 to 80

    /** What the view reports when it is drawn at this scale (it scales around its center, like the pop-in). */
    private fun measured(scale: Float): Pair<IntArray, IntArray> {
        val shift = (size / 2f * (1 - scale)).toInt()
        val inWindow = intArrayOf(shift, shift)
        val onScreen = intArrayOf(asked.first + inset.first + shift, asked.second + inset.second + shift)
        return onScreen to inWindow
    }

    @Test
    fun `the offset is the window's real spot, also while the bubble is still popping in`() {
        for (scale in listOf(1f, 0.9f, 0.02f, 0f)) {
            val (onScreen, inWindow) = measured(scale)
            assertEquals("scale $scale", inset, windowOffset(onScreen, inWindow, asked.first, asked.second))
        }
    }
}
