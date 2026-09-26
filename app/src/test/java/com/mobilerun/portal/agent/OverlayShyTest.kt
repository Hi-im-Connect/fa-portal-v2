package com.mobilerun.portal.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayShyTest {
    @Test
    fun `the bubble steps aside for screenshots and touches only`() {
        val log = mutableListOf<String>()
        val inner = FakePhone(JSONObject())
        val phone = OverlayShy(inner) { hidden -> log += if (hidden) "hide" else "show" }
        phone.screenshot(960, 60)
        phone.tap(1, 2)
        phone.swipe(1, 2, 3, 4, 300)
        phone.type("hi", true)
        phone.global(Actions.GLOBAL_HOME)
        phone.readScreen()
        assertEquals(listOf("hide", "show", "hide", "show", "hide", "show"), log)
        assertEquals(listOf("shot 960", "tap 1,2", "swipe 1,2>3,4", "type hi", "global ${Actions.GLOBAL_HOME}"), inner.done)
    }
}
