package com.mobilerun.portal.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupStepsTest {
    private val nothing = SetupState(linked = false, notifications = false, battery = false, accessibility = false)

    @Test
    fun `missing steps come in order and finished ones drop out`() {
        assertEquals(listOf(Step.NOTIFICATIONS, Step.BATTERY, Step.ACCESSIBILITY, Step.DASHBOARD), SetupSteps.missing(nothing))
        assertEquals(listOf(Step.ACCESSIBILITY), SetupSteps.missing(nothing.copy(linked = true, notifications = true, battery = true)))
        assertEquals(emptyList<Step>(), SetupSteps.missing(SetupState(true, true, true, true)))
    }

    @Test
    fun `the app opens each step by itself once, and never the dashboard step`() {
        val tried = mutableSetOf<Step>()
        assertEquals(Step.NOTIFICATIONS, SetupSteps.nextAuto(nothing, tried))
        tried += Step.NOTIFICATIONS // the user said no: do not ask again, go on
        assertEquals(Step.BATTERY, SetupSteps.nextAuto(nothing, tried))
        tried += Step.BATTERY
        assertEquals(Step.ACCESSIBILITY, SetupSteps.nextAuto(nothing, tried))
        tried += Step.ACCESSIBILITY
        assertNull(SetupSteps.nextAuto(nothing, tried)) // the dashboard step waits for the invite link
    }
}
