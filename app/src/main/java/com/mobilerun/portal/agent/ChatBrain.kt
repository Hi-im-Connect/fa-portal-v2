package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject

sealed class BrainReply {
    data class Say(val text: String) : BrainReply()
    data class Run(val say: String, val instruction: String) : BrainReply()
}

/**
 * The layer you talk to. It chats, remembers the conversation (task results included), and when
 * something should happen on the phone it hands one clear instruction to the agent (planner, then executor).
 */
class ChatBrain(private val llm: LlmClient, private val model: String) {
    fun reply(lines: List<ChatLine>, runs: (String) -> RunRecord?, busy: Boolean): BrainReply {
        val reply = llm.complete(model, messages(lines, runs), TOOLS, MAX_TOKENS, toolChoice = "auto")
        return when (reply) {
            is LlmReply.Text -> BrainReply.Say(reply.text.ifBlank { "Sorry, I lost my words. Say that again?" })
            is LlmReply.Tool -> {
                val instruction = reply.call.args.optString("instruction").trim()
                when {
                    reply.call.name != "run_task" || instruction.isEmpty() -> BrainReply.Say(reply.thought.ifBlank { "What should I do?" })
                    busy -> BrainReply.Say(BUSY)
                    else -> BrainReply.Run(reply.call.args.optString("reply").trim().ifEmpty { "On it." }, instruction)
                }
            }
        }
    }

    private fun messages(lines: List<ChatLine>, runs: (String) -> RunRecord?): JSONArray {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
        for (line in lines.takeLast(HISTORY)) {
            val (role, text) = when (line.role) {
                "user" -> "user" to line.text
                "task" -> "assistant" to taskText(line, line.run?.let(runs))
                else -> "assistant" to line.text
            }
            out.put(JSONObject().put("role", role).put("content", text))
        }
        return out
    }

    private fun taskText(line: ChatLine, run: RunRecord?): String = when {
        run == null -> "Task \"${line.text}\" (no record)"
        run.status == "running" -> "Task \"${line.text}\" is still running"
        else -> "Task \"${line.text}\" ${run.status} in ${run.steps} step${if (run.steps == 1) "" else "s"}: ${run.result}"
    }

    companion object {
        const val HISTORY = 30
        const val MAX_TOKENS = 600
        const val BUSY = "I'm still working on the current task. Wait for it, or tap the red bubble to stop it, then ask again."

        private val SYSTEM = """
            You are FastAutomate, a friendly assistant living in a chat bubble on the user's Android phone.
            You can operate this phone: open apps, tap, type, scroll, read what is on the screen.
            Talk like a helpful friend: short, warm, plain words. Answer questions yourself when you can.
            When the user wants something done or looked up on the phone, call run_task with ONE complete,
            self-contained instruction (the agent does not see this chat, so include every detail it needs),
            plus a short reply to show the user. If the request is unclear, ask one short question first.
            Task results appear in the chat; use them to answer follow-ups.
        """.trimIndent()

        val TOOLS: JSONArray = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "run_task")
                    .put("description", "Do something on this phone with the phone agent (it plans, then taps and types).")
                    .put(
                        "parameters",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("instruction", JSONObject().put("type", "string").put("description", "The full task for the agent"))
                                .put("reply", JSONObject().put("type", "string").put("description", "A short message to the user, e.g. 'On it!'")),
                        ).put("required", JSONArray().put("instruction")),
                    ),
            ),
        )
    }
}
