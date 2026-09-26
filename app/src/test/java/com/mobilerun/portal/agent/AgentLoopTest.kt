package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private val state = JSONObject(
        """{"a11y_tree":{"className":"android.widget.Button","text":"Settings","isClickable":true,"isVisibleToUser":true,
            "boundsInScreen":{"left":0,"top":0,"right":200,"bottom":100}},
           "phone_state":{"currentApp":"Launcher","packageName":"l"},"device_context":{"screen_bounds":{"width":1080,"height":2400}}}""",
    )

    private fun spec(reasoning: Boolean = false, maxSteps: Int = 10, vision: Boolean = false) = RunSpec(
        uuid = "u1", instruction = "open settings", reasoning = reasoning, maxSteps = maxSteps, timeLimitMs = 60_000,
        vision = vision, plannerModel = "p/m", executorModel = "e/m", baseUrl = "u",
        prompts = Prompts("v", "PLAN", "EXEC", JSONArray().put(JSONObject().put("type", "function"))),
    )

    private val events = mutableListOf<Pair<String, String>>()
    private val sink = EventSink { kind, text, _ -> events += kind to text }

    private fun loop(spec: RunSpec, transport: LlmTransport, phone: FakePhone = FakePhone(state), clock: () -> Long = { 0L }) =
        AgentLoop(spec, phone, LlmClient(transport, "u", { "k" }, {}), sink, clock)

    @Test
    fun `finishes when the model calls done`() {
        val transport = ScriptedTransport(toolReply("tap", """{"index":1}"""), toolReply("done", """{"success":true,"answer":"Settings is open"}"""))
        val result = loop(spec(), transport).run()
        assertEquals(AgentLoop.Result(RunStatus.SUCCEEDED, "Settings is open", 2), result)
        assertTrue(events.contains("action" to "tap index=1"))
        assertTrue(events.contains("ok" to "Tapped \"Settings\""))
        assertTrue(events.contains("answer" to "Settings is open"))
        val request = transport.requests.first().second
        assertEquals("EXEC", request.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `model without a tool call is a failed step`() {
        val transport = ScriptedTransport(textReply("I think I should tap"), toolReply("done", """{"success":true,"answer":"ok"}"""))
        val result = loop(spec(), transport).run()
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertTrue(events.any { it.first == "error" && it.second.startsWith("The model answered without choosing an action") })
    }

    @Test
    fun `stops at the step limit`() {
        val transport = ScriptedTransport(*Array(5) { toolReply("wait", """{"seconds":1}""") })
        val result = loop(spec(maxSteps = 3), transport).run()
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(3, result.steps)
        assertTrue(result.result.contains("step limit"))
    }

    @Test
    fun `a stop request ends the run`() {
        val transport = ScriptedTransport(*Array(5) { toolReply("wait", """{"seconds":1}""") })
        lateinit var agent: AgentLoop
        val stopper = EventSink { kind, _, _ -> if (kind == "ok") agent.stopRequested = true }
        agent = AgentLoop(spec(), FakePhone(state), LlmClient(transport, "u", { "k" }, {}), stopper)
        assertEquals(RunStatus.STOPPED, agent.run().status)
    }

    @Test
    fun `three failures in a row make a new plan`() {
        val phone = FakePhone(state).apply { failTaps = true }
        val transport = ScriptedTransport(
            textReply("""{"goals":["Open Settings"]}"""),
            toolReply("tap", """{"index":1}"""), toolReply("tap", """{"index":1}"""), toolReply("tap", """{"index":1}"""),
            textReply("""{"goals":["Use the app drawer"]}"""),
            toolReply("done", """{"success":false,"answer":"cannot"}"""),
        )
        val result = loop(spec(reasoning = true), transport, phone).run()
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(listOf("1. Open Settings", "1. Use the app drawer"), events.filter { it.first == "plan" }.map { it.second })
        assertEquals("p/m", transport.requests[4].second.getString("model"))
    }

    @Test
    fun `budget exhaustion fails the run`() {
        val transport = ScriptedTransport(HttpResult(403, """{"error":{"message":"Key limit exceeded"}}"""))
        val result = loop(spec(), transport).run()
        assertEquals(AgentLoop.Result(RunStatus.FAILED, LlmClient.BUDGET_MESSAGE, 0), result)
    }

    @Test
    fun `runs out of time`() {
        var now = 0L
        val transport = ScriptedTransport(*Array(5) { toolReply("wait", """{"seconds":1}""") })
        val result = loop(spec(), transport, clock = { now.also { now += 40_000 } }).run()
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.result.contains("time"))
    }

    @Test
    fun `vision sends the screenshot`() {
        val transport = ScriptedTransport(toolReply("done", """{"success":true,"answer":"ok"}"""))
        loop(spec(vision = true), transport).run()
        val content = transport.requests.first().second.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("data:image/jpeg;base64,SHOT", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }
}
