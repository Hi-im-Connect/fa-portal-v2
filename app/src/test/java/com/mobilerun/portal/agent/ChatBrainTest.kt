package com.mobilerun.portal.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBrainTest {
    private fun brain(t: ScriptedTransport) = ChatBrain(LlmClient(t, "https://x", { "k" }, sleep = {}), "m")

    private val runs = mapOf(
        "r1" to RunRecord("r1", "what time is it", "app", "succeeded", "t", "t", 2, "It is 8:53", emptyList()),
    )

    @Test
    fun `it just talks when nothing needs doing`() {
        val t = ScriptedTransport(textReply("Hi! What should I do?"))
        assertEquals(BrainReply.Say("Hi! What should I do?"), brain(t).reply(listOf(ChatLine("user", "hello", ts = 1)), runs::get, busy = false))
        val body = t.requests.single().second
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals("run_task", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun `it hands a task to the agent with a full instruction`() {
        val t = ScriptedTransport(toolReply("run_task", """{"instruction":"Open YouTube and search for lo-fi music","reply":"On it!"}"""))
        assertEquals(
            BrainReply.Run("On it!", "Open YouTube and search for lo-fi music"),
            brain(t).reply(listOf(ChatLine("user", "play some lofi on youtube", ts = 1)), runs::get, busy = false),
        )
    }

    @Test
    fun `it remembers the chat, task results included`() {
        val t = ScriptedTransport(textReply("It was 8:53."))
        val lines = listOf(
            ChatLine("user", "what time is it", ts = 1),
            ChatLine("assistant", "Checking.", ts = 2),
            ChatLine("task", "what time is it", run = "r1", ts = 3),
            ChatLine("user", "what did you find?", ts = 4),
        )
        brain(t).reply(lines, runs::get, busy = false)
        val messages = t.requests.single().second.getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals("Task \"what time is it\" succeeded in 2 steps: It is 8:53", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun `it does not start a second task while one runs`() {
        val t = ScriptedTransport(toolReply("run_task", """{"instruction":"Open Settings"}"""))
        val reply = brain(t).reply(listOf(ChatLine("user", "open settings", ts = 1)), runs::get, busy = true)
        assertEquals(BrainReply.Say(ChatBrain.BUSY), reply)
    }
}
