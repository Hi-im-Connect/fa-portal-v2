package com.mobilerun.portal.agent

import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.service.ActionDispatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DispatcherPhoneControlTest {
    private val dispatcher = mockk<ActionDispatcher>()
    private val control = DispatcherPhoneControl { dispatcher }

    @Test
    fun `actions go to the local dispatcher and errors come back as text`() {
        val params = slot<JSONObject>()
        every { dispatcher.dispatch("tap", capture(params), any(), any()) } returns ApiResponse.Success("ok")
        every { dispatcher.dispatch("global", any(), any(), any()) } returns ApiResponse.Error("Failed to perform global action 9")
        assertNull(control.tap(10, 20))
        assertEquals(10 to 20, params.captured.getInt("x") to params.captured.getInt("y"))
        assertEquals("Failed to perform global action 9", control.global(9))
    }

    @Test
    fun `typing sends base64 text and reading returns the state`() {
        val params = slot<JSONObject>()
        every { dispatcher.dispatch("keyboard/input", capture(params), any(), any()) } returns ApiResponse.Success("ok")
        every { dispatcher.dispatch("state", any(), any(), any()) } returns ApiResponse.RawObject(JSONObject().put("a11y_tree", JSONObject()))
        every { dispatcher.dispatch("screenshot", any(), any(), any()) } returns ApiResponse.Text("BASE64JPEG")
        assertNull(control.type("héllo", clear = true))
        assertEquals("héllo", String(java.util.Base64.getDecoder().decode(params.captured.getString("base64_text"))))
        assertEquals(true, control.readScreen()!!.has("a11y_tree"))
        assertEquals("BASE64JPEG", control.screenshot(960, 60))
    }

    @Test
    fun `apps are listed by label`() {
        val apps = JSONArray().put(JSONObject().put("label", "Settings").put("packageName", "com.android.settings"))
        every { dispatcher.dispatch("packages", any(), any(), any()) } returns ApiResponse.RawArray(apps)
        assertEquals(listOf("Settings" to "com.android.settings"), control.launchableApps())
    }

    @Test
    fun `no accessibility service means readable errors`() {
        val off = DispatcherPhoneControl { null }
        assertEquals("Turn on FastAutomate v2 in Accessibility", off.tap(1, 1))
        assertNull(off.readScreen())
    }
}
