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
            else -> friendly(r.result)?.let { ChatMessage(false, "Couldn't finish · $count\n$it", Tone.BAD) }
                ?: ChatMessage(false, "Failed · $count\n${r.result}", Tone.BAD)
        }
    }

    /** The agent's limits in plain words. */
    private fun friendly(result: String): String? = when {
        result.startsWith("Reached the step limit") -> "I ran out of steps. Try a smaller request?"
        result == "Ran out of time" -> "I ran out of time. Try a smaller request?"
        else -> null
    }

    /** A chat head's letters: the first letters of the first two real words (one letter for Arabic, whose
     *  letters join; the article "ال" skipped). A new chat has none (it shows a chat icon). */
    fun initials(name: String): String {
        if (name == "New chat") return ""
        val words = name.split(Regex("\\s+")).map { w -> w.filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }
        val first = words.firstOrNull() ?: return "?"
        val arabic = first.first() in '\u0600'..'\u06FF'
        if (arabic) return first.removePrefix("ال").ifEmpty { first }.take(1)
        return words.take(2).joinToString("") { it.take(1).uppercase() }
    }
}
