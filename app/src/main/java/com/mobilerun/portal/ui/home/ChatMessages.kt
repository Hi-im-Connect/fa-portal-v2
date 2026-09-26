package com.mobilerun.portal.ui.home

import com.mobilerun.portal.agent.Chat
import com.mobilerun.portal.agent.RunRecord

enum class Tone { USER, STEP, GOOD, BAD }

data class ChatMessage(val fromUser: Boolean, val text: String, val tone: Tone)

/** A chat as bubbles: you on the right; the assistant on the left, and each task it started as one bubble. */
object ChatMessages {
    private val STEPS = setOf("ok", "error", "paused", "resumed")

    fun of(chat: Chat, run: (String) -> RunRecord?): List<ChatMessage> = chat.lines.map { line ->
        when (line.role) {
            "user" -> ChatMessage(true, line.text, Tone.USER)
            "task" -> task(line.run?.let(run))
            else -> ChatMessage(false, line.text, Tone.STEP)
        }
    }

    private fun task(r: RunRecord?): ChatMessage {
        if (r == null) return ChatMessage(false, "Task ended", Tone.STEP)
        val count = "${r.steps} step${if (r.steps == 1) "" else "s"}"
        return when (r.status) {
            "running" -> {
                val latest = r.events.lastOrNull { it.kind in STEPS }
                when (latest?.kind) {
                    "paused" -> ChatMessage(false, "Paused", Tone.STEP)
                    null, "resumed" -> ChatMessage(false, "Working...", Tone.STEP)
                    else -> ChatMessage(false, "Working...\n${latest.text}", Tone.STEP)
                }
            }
            "succeeded" -> ChatMessage(false, "Done · $count\n${r.result}", Tone.GOOD)
            "stopped" -> ChatMessage(false, "Stopped · $count", Tone.BAD)
            else -> ChatMessage(false, "Failed · $count\n${r.result}", Tone.BAD)
        }
    }
}
