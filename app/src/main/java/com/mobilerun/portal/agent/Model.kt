package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject

/** Something on the screen the agent can refer to by its number. */
data class Element(
    val index: Int,
    val className: String,
    val text: String,
    val description: String,
    val resourceId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class Screen(
    val app: String,
    val packageName: String,
    val keyboardVisible: Boolean,
    val width: Int,
    val height: Int,
    val elements: List<Element>,
    val screenshotBase64: String? = null,
)

/** Instructions and tools from the dashboard (FastAutomate v2: sent with every task). */
data class Prompts(val version: String, val planner: String, val executor: String, val tools: JSONArray)

data class RunSpec(
    val uuid: String,
    val instruction: String,
    val reasoning: Boolean,
    val maxSteps: Int,
    val timeLimitMs: Long,
    val vision: Boolean,
    val plannerModel: String,
    val executorModel: String,
    val baseUrl: String,
    val prompts: Prompts,
) {
    companion object {
        /** The dashboard's agent/run params (or the saved defaults plus a uuid and an instruction). */
        fun fromJson(json: JSONObject): RunSpec {
            val p = json.getJSONObject("prompts")
            return RunSpec(
                uuid = json.getString("uuid"),
                instruction = json.getString("instruction"),
                reasoning = json.optBoolean("reasoning", true),
                maxSteps = json.optInt("max_steps", 30).coerceIn(1, 200),
                timeLimitMs = json.optLong("time_limit_s", 900L).coerceIn(60L, 14_400L) * 1000,
                vision = json.optBoolean("vision", true),
                plannerModel = json.getString("planner_model"),
                executorModel = json.getString("executor_model"),
                baseUrl = json.optString("base_url", "https://openrouter.ai/api/v1").trimEnd('/'),
                prompts = Prompts(p.optString("version"), p.getString("planner"), p.getString("executor"), p.getJSONArray("tools")),
            )
        }
    }
}

data class ToolCall(val name: String, val args: JSONObject)

data class ActionOutcome(
    val ok: Boolean,
    val text: String,
    val done: Boolean = false,
    val success: Boolean = false,
    val answer: String = "",
)

enum class RunStatus(val wire: String) { SUCCEEDED("succeeded"), FAILED("failed"), STOPPED("stopped") }

/** Where a run reports progress; AgentHost numbers and sends each report. */
fun interface EventSink {
    fun event(kind: String, text: String, steps: Int?)
}
