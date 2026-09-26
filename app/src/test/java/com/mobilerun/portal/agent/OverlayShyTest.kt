package com.mobilerun.portal.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayShyTest {
    @Test
    fun `the bubble steps aside only for touches that land on it`() {
        val log = mutableListOf<String>()
        val inner = FakePhone(JSONObject())
        // the bubble covers x 0..100, y 0..100
        val phone = OverlayShy(inner, covers = { x, y -> x in 0..100 && y in 0..100 }) { hidden -> log += if (hidden) "hide" else "show" }
        phone.screenshot(960, 60)
        phone.tap(500, 500)
        phone.tap(50, 50)
        phone.swipe(500, 500, 600, 600, 300)
        phone.swipe(500, 50, 50, 50, 300) // passes over it
        phone.type("hi", true)
        assertEquals(listOf("hide", "show", "hide", "show"), log)
        assertEquals(listOf("shot 960", "tap 500,500", "tap 50,50", "swipe 500,500>600,600", "swipe 500,50>50,50", "type hi"), inner.done)
    }
}
