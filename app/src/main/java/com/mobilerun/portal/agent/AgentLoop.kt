package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject

/** One task on this phone: (plan), then read the screen, ask for one action, do it, report; repeat. */
class AgentLoop(
    private val spec: RunSpec,
    private val phone: PhoneControl,
    private val llm: LlmClient,
    private val sink: EventSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val status: RunStatus, val result: String, val steps: Int)

    @Volatile
    var stopRequested = false

    private val actions = Actions(phone)
    private val planner = Planner(llm)
    private val history = ArrayDeque<String>()
    private val seenHere = HashMap<String, Int>()  // (screen, action) -> times chosen: catches back-and-forth loops

    fun run(): Result {
        val started = clock()
        var steps = 0
        var failuresInRow = 0
        var sameInRow = 0
        var lastKey = ""
        sink.event("phase", "Reading the screen", 0)
        var screen = observe() ?: return Result(RunStatus.FAILED, DispatcherPhoneControl.NO_SERVICE, 0)
        try {
            var goals = if (spec.reasoning) plan(screen, emptyList()) else emptyList()
            while (true) {
                if (stopRequested) return Result(RunStatus.STOPPED, "Stopped", steps)
                if (steps >= spec.maxSteps) return Result(RunStatus.FAILED, "Reached the step limit (${spec.maxSteps}) before finishing", steps)
                if (clock() - started > spec.timeLimitMs) return Result(RunStatus.FAILED, "Ran out of time", steps)
                sink.event("phase", "Thinking", steps)
                val reply = llm.complete(spec.executorModel, executorMessages(screen, goals), spec.prompts.tools)
                steps++
                val (key, outcome) = when (reply) {
                    is LlmReply.Text -> "text" to ActionOutcome(false, "The model answered without choosing an action: ${reply.text.take(200)}")
                    is LlmReply.Tool -> {
                        if (reply.thought.isNotBlank()) sink.event("think", reply.thought.take(500), steps)
                        val description = describe(reply.call)
                        sink.event("action", description, steps)
                        description to actions.run(reply.call, screen)
                    }
                }
                val here = seenHere.merge(ScreenReader.describe(screen) + "|" + key, 1, Int::plus) ?: 1
                if (outcome.done) {
                    if (outcome.answer.isNotBlank()) sink.event("answer", outcome.answer, steps)
                    val status = if (outcome.success) RunStatus.SUCCEEDED else RunStatus.FAILED
                    return Result(status, outcome.answer.ifBlank { if (outcome.success) "Done" else "The agent gave up" }, steps)
                }
                sink.event(if (outcome.ok) "ok" else "error", outcome.text, steps)
                val repeat = if (here > 1) " (you already did this on this screen ${here - 1}x: try something else)" else ""
                history.addLast("$steps. $key -> ${if (outcome.ok) "ok" else "failed"}: ${outcome.text}$repeat")
                while (history.size > HISTORY) history.removeFirst()
                failuresInRow = if (outcome.ok) 0 else failuresInRow + 1
                sameInRow = if (key == lastKey) sameInRow + 1 else 1
                lastKey = key
                if (failuresInRow >= STUCK || sameInRow >= STUCK || here >= STUCK) {
                    if (spec.reasoning) {
                        sink.event("phase", "Making a new plan", steps)
                        goals = plan(screen, history.toList())
                        failuresInRow = 0
                        sameInRow = 0
                        seenHere.clear()
                    } else if (failuresInRow >= STUCK) {
                        return Result(RunStatus.FAILED, "Three actions in a row failed: ${outcome.text}", steps)
                    }
                }
                phone.sleep(SETTLE_MS)
                screen = observe() ?: screen
            }
        } catch (e: LlmError) {
            return Result(RunStatus.FAILED, e.message ?: "AI error", steps)
        }
    }

    private fun plan(screen: Screen, trouble: List<String>): List<String> {
        val goals = planner.plan(spec, screen, trouble)
        sink.event("plan", goals.mapIndexed { i, g -> "${i + 1}. $g" }.joinToString("\n"), null)
        return goals
    }

    private fun observe(): Screen? {
        val state = phone.readScreen() ?: return null
        val screen = ScreenReader.parse(state)
        return if (spec.vision) screen.copy(screenshotBase64 = phone.screenshot(VISION_SIDE, VISION_QUALITY)) else screen
    }

    private fun executorMessages(screen: Screen, goals: List<String>): JSONArray {
        val text = buildString {
            append("Task: ").append(spec.instruction).append('\n')
            if (goals.isNotEmpty()) append("\nPlan:\n").append(goals.mapIndexed { i, g -> "${i + 1}. $g" }.joinToString("\n")).append('\n')
            if (history.isNotEmpty()) append("\nRecent actions:\n").append(history.joinToString("\n")).append('\n')
            append("\nCurrent screen:\n").append(ScreenReader.describe(screen))
        }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        screen.screenshotBase64?.let {
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it")))
        }
        return JSONArray()
            .put(JSONObject().put("role", "system").put("content", spec.prompts.executor))
            .put(JSONObject().put("role", "user").put("content", content))
    }

    private fun describe(call: ToolCall): String {
        val args = call.args.keys().asSequence().sorted().joinToString(" ") { "$it=${call.args.opt(it)}" }
        return if (args.isEmpty()) call.name else "${call.name} $args"
    }

    companion object {
        const val SETTLE_MS = 700L
        const val HISTORY = 8
        const val STUCK = 3
        const val VISION_SIDE = 960
        const val VISION_QUALITY = 60
    }
}
