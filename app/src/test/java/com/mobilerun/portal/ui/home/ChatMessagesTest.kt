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
    fun `a task reads like a conversation`() {
        val r = record(
            "succeeded", "Android 16",
            "phase" to "Thinking", "plan" to "1. Settings is open", "action" to "tap index=6", "ok" to "Tapped \"Settings\"",
            "think" to "hmm", "error" to "Nothing to scroll", "answer" to "Android 16",
        )
        assertEquals(
            listOf(
                ChatMessage(true, "open settings", Tone.USER),
                ChatMessage(false, "Plan:\n1. Settings is open", Tone.STEP),
                ChatMessage(false, "Tapped \"Settings\"", Tone.STEP),
                ChatMessage(false, "Nothing to scroll", Tone.STEP),
                ChatMessage(false, "Done: Android 16", Tone.GOOD),
            ),
            ChatMessages.of(listOf(r)),
        )
    }

    @Test
    fun `a running task ends on its latest step and a failed one says so`() {
        assertEquals(ChatMessage(false, "Working...", Tone.STEP), ChatMessages.of(listOf(record("running", ""))).last())
        assertEquals(ChatMessage(false, "Paused", Tone.STEP), ChatMessages.of(listOf(record("running", "", "paused" to "Paused"))).last())
        assertEquals(ChatMessage(false, "Failed: no network", Tone.BAD), ChatMessages.of(listOf(record("failed", "no network"))).last())
    }
}
