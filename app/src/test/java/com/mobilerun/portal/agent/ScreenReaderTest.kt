package com.mobilerun.portal.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderTest {
    private val state = JSONObject(
        """{"a11y_tree": {"className":"android.widget.FrameLayout","text":"","contentDescription":"","resourceId":"",
             "isScrollable":true,"isVisibleToUser":true,"boundsInScreen":{"left":0,"top":0,"right":1080,"bottom":2400},
             "children":[
               {"className":"android.widget.Button","text":"Sign in","contentDescription":"","resourceId":"com.x:id/login",
                "isClickable":true,"isVisibleToUser":true,"boundsInScreen":{"left":40,"top":300,"right":680,"bottom":380}},
               {"className":"android.widget.EditText","text":"","contentDescription":"Search","resourceId":"",
                "isEditable":true,"isClickable":true,"isVisibleToUser":true,"boundsInScreen":{"left":0,"top":100,"right":1080,"bottom":200}},
               {"className":"android.view.View","text":"","contentDescription":"","resourceId":"",
                "isVisibleToUser":true,"boundsInScreen":{"left":0,"top":0,"right":10,"bottom":10}},
               {"className":"android.widget.TextView","text":"Hidden","isVisibleToUser":false,
                "boundsInScreen":{"left":0,"top":0,"right":100,"bottom":100}}
             ]},
           "phone_state":{"currentApp":"Chrome","packageName":"com.android.chrome","keyboardVisible":true},
           "device_context":{"screen_bounds":{"width":1080,"height":2400}}}""",
    )

    @Test
    fun `keeps what the agent can use and numbers it`() {
        val screen = ScreenReader.parse(state)
        assertEquals(listOf(1, 2, 3), screen.elements.map { it.index })
        val button = screen.elements[1]
        assertEquals(Triple("Button", "Sign in", "login"), Triple(button.className, button.text, button.resourceId))
        assertEquals(360 to 340, button.centerX to button.centerY)
        assertEquals(Triple("Chrome", 1080, 2400), Triple(screen.app, screen.width, screen.height))
    }

    @Test
    fun `describes the screen as numbered lines`() {
        val text = ScreenReader.describe(ScreenReader.parse(state))
        assertTrue(text, text.startsWith("App: Chrome (com.android.chrome), keyboard open"))
        assertTrue(text, text.contains("2 Button \"Sign in\" id=login [tap] @360,340"))
        assertTrue(text, text.contains("3 EditText desc=\"Search\" [tap,edit] @540,150"))
        assertTrue(text, !text.contains("Hidden"))
    }

    @Test
    fun `an unreadable screen says so`() {
        val text = ScreenReader.describe(ScreenReader.parse(JSONObject("{}")))
        assertTrue(text, text.contains("(nothing readable"))
    }
}
