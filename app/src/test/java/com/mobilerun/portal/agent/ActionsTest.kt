package com.mobilerun.portal.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsTest {
    private val field = Element(1, "EditText", "", "Search", "", 0, 100, 1080, 200, clickable = true, editable = true, scrollable = false)
    private val list = Element(2, "RecyclerView", "", "", "list", 0, 300, 1080, 2300, clickable = false, editable = false, scrollable = true)
    private val screen = Screen("Chrome", "com.android.chrome", false, 1080, 2400, listOf(field, list))
    private val phone = FakePhone()
    private val actions = Actions(phone)
    private fun call(name: String, args: String = "{}") = actions.run(ToolCall(name, JSONObject(args)), screen)

    @Test
    fun `tap an element by its number`() {
        assertTrue(call("tap", """{"index":1}""").ok)
        assertEquals(listOf("tap 540,150"), phone.done)
    }

    @Test
    fun `missing element is a failed step`() {
        val outcome = call("tap", """{"index":42}""")
        assertFalse(outcome.ok)
        assertEquals("Element 42 is not on the screen", outcome.text)
        assertTrue(phone.done.isEmpty())
    }

    @Test
    fun `typing into a numbered field focuses it first`() {
        assertTrue(call("type", """{"text":"weather","index":1}""").ok)
        assertEquals(listOf("tap 540,150", "sleep 400", "type weather"), phone.done)
    }

    @Test
    fun `scroll down inside a list moves the finger up`() {
        assertTrue(call("scroll", """{"direction":"down","index":2}""").ok)
        assertEquals(listOf("swipe 540,1900>540,700"), phone.done)
    }

    @Test
    fun `tap_at stays on the screen`() {
        call("tap_at", """{"x":5000,"y":-3}""")
        assertEquals(listOf("tap 1079,0"), phone.done)
    }

    @Test
    fun `keys, apps, waiting and done`() {
        assertTrue(call("back").ok && call("home").ok && call("enter").ok)
        assertTrue(call("open_app", """{"name":"settings"}""").ok)
        assertFalse(call("open_app", """{"name":"Nope"}""").ok)
        assertTrue(call("wait", """{"seconds":50}""").ok)
        assertEquals(listOf("global 1", "global 2", "key 66", "launch com.android.settings", "sleep 10000"), phone.done)
        val done = call("done", """{"success":true,"answer":"24 C"}""")
        assertTrue(done.done && done.success)
        assertEquals("24 C", done.answer)
    }

    @Test
    fun `unknown tools and bad arguments are failed steps`() {
        assertFalse(call("fly").ok)
        assertEquals("Bad arguments for tap", call("tap", """{"index":"x"}""").text.substringBefore(':'))
    }

    @Test
    fun `a refused tap is reported`() {
        phone.failTaps = true
        val outcome = call("tap", """{"index":1}""")
        assertFalse(outcome.ok)
        assertEquals("Failed to perform tap", outcome.text)
    }
}
