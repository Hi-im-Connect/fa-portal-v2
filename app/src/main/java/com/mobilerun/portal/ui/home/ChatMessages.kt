package com.mobilerun.portal.ui.home

import com.mobilerun.portal.agent.RunRecord

enum class Tone { USER, STEP, GOOD, BAD }

data class ChatMessage(val fromUser: Boolean, val text: String, val tone: Tone)

/** The phone's runs as a chat: your task on the right, what the phone did and found on the left. */
object ChatMessages {
    private val SHOWN = setOf("ok", "error", "paused", "resumed")

    fun of(records: List<RunRecord>): List<ChatMessage> = records.flatMap { r ->
        buildList {
            add(ChatMessage(true, r.instruction, Tone.USER))
            for (e in r.events) {
                when (e.kind) {
                    "plan" -> add(ChatMessage(false, "Plan:\n${e.text}", Tone.STEP))
                    in SHOWN -> add(ChatMessage(false, e.text, Tone.STEP))
                }
            }
            when (r.status) {
                "running" -> if (r.events.none { it.kind in SHOWN || it.kind == "plan" }) add(ChatMessage(false, "Working...", Tone.STEP))
                "succeeded" -> add(ChatMessage(false, "Done: ${r.result}", Tone.GOOD))
                "stopped" -> add(ChatMessage(false, "Stopped", Tone.BAD))
                else -> add(ChatMessage(false, "Failed: ${r.result}", Tone.BAD))
            }
        }
    }
}
