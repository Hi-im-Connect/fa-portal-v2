package com.mobilerun.portal.ui.home

import com.mobilerun.portal.agent.Chat
import com.mobilerun.portal.agent.ChatLine
import com.mobilerun.portal.agent.RunEvent
import com.mobilerun.portal.agent.RunRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessagesTest {
    private fun record(status: String, result: String, vararg events: Pair<String, String>) = RunRecord(
        "r", "open settings", "app", status, "t", null, 3, result, events.map { RunEvent(it.first, it.second, "t") },
    )

    private fun chat(vararg lines: ChatLine) = Chat("c", lines.toList())

    private fun of(r: RunRecord?) = ChatMessages.of(chat(ChatLine("task", "open settings", run = "r", ts = 1))) { r }.single()

    @Test
    fun `you on the right, the assistant on the left`() {
        val c = chat(ChatLine("user", "hi", ts = 1), ChatLine("assistant", "Hello!", ts = 2))
        assertEquals(listOf(ChatMessage(true, "hi", Tone.USER), ChatMessage(false, "Hello!", Tone.STEP)), ChatMessages.of(c) { null })
    }

    @Test
    fun `a finished task is one result`() {
        assertEquals(ChatMessage(false, "Done · 3 steps\nAndroid 16", Tone.GOOD), of(record("succeeded", "Android 16", "ok" to "Tapped")))
        assertEquals(ChatMessage(false, "Stopped · 3 steps", Tone.BAD), of(record("stopped", "Stopped")))
        assertEquals(ChatMessage(false, "Failed · 3 steps\nno network", Tone.BAD), of(record("failed", "no network")))
    }

    @Test
    fun `a running task shows only its latest step`() {
        assertEquals(ChatMessage(false, "Working...\nthree", Tone.STEP), of(record("running", "", "ok" to "one", "error" to "two", "ok" to "three", "action" to "tap")))
        assertEquals(ChatMessage(false, "Working...", Tone.STEP), of(record("running", "")))
        assertEquals(ChatMessage(false, "Paused", Tone.STEP), of(record("running", "", "ok" to "one", "paused" to "Paused")))
        assertEquals(ChatMessage(false, "Working...\none", Tone.STEP), of(record("running", "", "paused" to "Paused", "resumed" to "Resumed", "ok" to "one")))
    }
}
