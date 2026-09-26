package com.mobilerun.portal.ui.home

import com.mobilerun.portal.agent.RunRecord

enum class Tone { USER, STEP, GOOD, BAD }

data class ChatMessage(val fromUser: Boolean, val text: String, val tone: Tone)

/** The phone's runs as a chat: your task on the right; on the left one result, or the latest steps while it runs. */
object ChatMessages {
    private val STEPS = setOf("ok", "error", "paused", "resumed")
    private const val LIVE_STEPS = 3

    fun of(records: List<RunRecord>): List<ChatMessage> = records.flatMap { r ->
        buildList {
            add(ChatMessage(true, r.instruction, Tone.USER))
            val count = "${r.steps} step${if (r.steps == 1) "" else "s"}"
            when (r.status) {
                "running" -> {
                    val latest = r.events.filter { it.kind in STEPS }.takeLast(LIVE_STEPS)
                    if (latest.isEmpty()) add(ChatMessage(false, "Working...", Tone.STEP))
                    latest.forEach { add(ChatMessage(false, it.text, Tone.STEP)) }
                }
                "succeeded" -> add(ChatMessage(false, "Done · $count\n${r.result}", Tone.GOOD))
                "stopped" -> add(ChatMessage(false, "Stopped · $count", Tone.BAD))
                else -> add(ChatMessage(false, "Failed · $count\n${r.result}", Tone.BAD))
            }
        }
    }
}
