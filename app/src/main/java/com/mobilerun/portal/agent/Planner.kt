package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject

/** Short goals from the planner model; replans with what went wrong when the agent is stuck. */
class Planner(private val llm: LlmClient) {
    fun plan(spec: RunSpec, screen: Screen, trouble: List<String>): List<String> {
        val user = buildString {
            append("Task: ").append(spec.instruction).append("\n\n")
            if (trouble.isNotEmpty()) append("The previous plan got stuck. Recent actions:\n").append(trouble.joinToString("\n")).append("\n\n")
            append("Current screen:\n").append(ScreenReader.describe(screen))
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", spec.prompts.planner))
            .put(JSONObject().put("role", "user").put("content", user))
        val text = (llm.complete(spec.plannerModel, messages, null, 800) as? LlmReply.Text)?.text.orEmpty()
        return parseGoals(text)?.takeIf { it.isNotEmpty() } ?: listOf(spec.instruction)
    }

    companion object {
        fun parseGoals(text: String): List<String>? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val goals = runCatching { JSONObject(text.substring(start, end + 1)).getJSONArray("goals") }.getOrNull() ?: return null
            return (0 until goals.length()).mapNotNull { goals.optString(it).trim().takeIf(String::isNotEmpty) }.take(6)
        }
    }
}
