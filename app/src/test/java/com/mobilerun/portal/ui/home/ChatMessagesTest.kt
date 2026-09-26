package com.mobilerun.portal.ui.home

import com.mobilerun.portal.agent.RunEvent
import com.mobilerun.portal.agent.RunRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessagesTest {
    private fun record(status: String, result: String, vararg events: Pair<String, String>) = RunRecord(
        "u", "open settings", "app", status, "t", null, 3, result, events.map { RunEvent(it.first, it.second, "t") },
    )

    @Test
    fun `a finished task is your message and one result`() {
        val r = record(
            "succeeded", "Android 16",
            "plan" to "1. Settings is open", "action" to "tap index=6", "ok" to "Tapped \"Settings\"", "answer" to "Android 16",
        )
        assertEquals(
            listOf(ChatMessage(true, "open settings", Tone.USER), ChatMessage(false, "Done · 3 steps\nAndroid 16", Tone.GOOD)),
            ChatMessages.of(listOf(r)),
        )
    }

    @Test
    fun `a running task shows its latest steps`() {
        val r = record("running", "", "plan" to "1. a", "ok" to "one", "error" to "two", "ok" to "three", "ok" to "four", "action" to "tap")
        assertEquals(listOf("open settings", "two", "three", "four"), ChatMessages.of(listOf(r)).map { it.text })
        assertEquals(ChatMessage(false, "Working...", Tone.STEP), ChatMessages.of(listOf(record("running", ""))).last())
        assertEquals(ChatMessage(false, "Paused", Tone.STEP), ChatMessages.of(listOf(record("running", "", "paused" to "Paused"))).last())
    }

    @Test
    fun `stopped and failed tasks say so`() {
        assertEquals(ChatMessage(false, "Stopped · 3 steps", Tone.BAD), ChatMessages.of(listOf(record("stopped", "Stopped"))).last())
        assertEquals(ChatMessage(false, "Failed · 3 steps\nno network", Tone.BAD), ChatMessages.of(listOf(record("failed", "no network"))).last())
    }
}
