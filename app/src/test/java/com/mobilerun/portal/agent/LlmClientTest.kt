package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

fun toolReply(name: String, args: String, thought: String = "") = HttpResult(
    200,
    JSONObject().put(
        "choices",
        JSONArray().put(
            JSONObject().put(
                "message",
                JSONObject().put("content", thought).put(
                    "tool_calls",
                    JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                        .put("function", JSONObject().put("name", name).put("arguments", args))),
                ),
            ),
        ),
    ).toString(),
)

fun textReply(text: String) = HttpResult(
    200,
    JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString(),
)

class ScriptedTransport(vararg replies: HttpResult) : LlmTransport {
    val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<Pair<Map<String, String>, JSONObject>>()
    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResult {
        requests += headers to JSONObject(body)
        return queue.removeFirstOrNull() ?: textReply("(script ended)")
    }
}

class LlmClientTest {
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
    private val tools = JSONArray().put(JSONObject().put("type", "function"))

    @Test
    fun `a tool call comes back as a tool reply`() {
        val transport = ScriptedTransport(toolReply("tap", """{"index":3}""", "Tap the button"))
        val reply = LlmClient(transport, "https://openrouter.ai/api/v1", { "sk-or-v1-phone" }).complete("e/m", messages, tools)
        reply as LlmReply.Tool
        assertEquals("tap", reply.call.name)
        assertEquals(3, reply.call.args.getInt("index"))
        assertEquals("Tap the button", reply.thought)
        val (headers, body) = transport.requests.single()
        assertEquals("Bearer sk-or-v1-phone", headers["Authorization"])
        assertEquals("required", body.getString("tool_choice"))
        assertEquals("e/m", body.getString("model"))
    }

    @Test
    fun `unreadable arguments become a text reply`() {
        val reply = LlmClient.parse(toolReply("tap", "{index:").body)
        assertTrue(reply is LlmReply.Text && reply.text.contains("Unreadable arguments for tap"))
    }

    @Test
    fun `key limit is a budget error`() {
        val limit = HttpResult(403, """{"error":{"message":"Key limit exceeded (daily limit)"}}""")
        try {
            LlmClient(ScriptedTransport(limit), "u", { "k" }).complete("m", messages, tools)
            fail("expected a budget error")
        } catch (e: LlmError) {
            assertTrue(e.budget)
            assertEquals(LlmClient.BUDGET_MESSAGE, e.message)
        }
    }

    @Test
    fun `empty account is a budget error with its own message`() {
        try {
            LlmClient(ScriptedTransport(HttpResult(402, "{}")), "u", { "k" }).complete("m", messages, tools)
            fail("expected a budget error")
        } catch (e: LlmError) {
            assertEquals(LlmClient.ACCOUNT_EMPTY_MESSAGE, e.message)
        }
    }

    @Test
    fun `busy and failing servers are retried twice`() {
        val transport = ScriptedTransport(HttpResult(429, "{}"), HttpResult(-1, "timeout"), textReply("ok"))
        val slept = mutableListOf<Long>()
        val reply = LlmClient(transport, "u", { "k" }, { slept += it }).complete("m", messages, null)
        assertEquals(LlmReply.Text("ok"), reply)
        assertEquals(listOf(1500L, 3000L), slept)
    }

    @Test
    fun `no key yet is a readable error`() {
        try {
            LlmClient(ScriptedTransport(), "u", { null }).complete("m", messages, null)
            fail("expected an error")
        } catch (e: LlmError) {
            assertTrue(e.message!!.contains("no AI key"))
        }
    }

    @Test
    fun `a 200 reply without choices is retried, then a readable error`() {
        val bad = HttpResult(200, """{"error":{"message":"upstream failed"}}""")
        val slept = mutableListOf<Long>()
        try {
            LlmClient(ScriptedTransport(bad, bad, bad), "u", { "k" }, { slept += it }).complete("m", messages, tools)
            fail("expected an error")
        } catch (e: LlmError) {
            assertTrue(e.message!!, e.message!!.contains("upstream failed"))
        }
        assertEquals(2, slept.size)
    }

    @Test
    fun `a tool call without a name is a text reply`() {
        val body = """{"choices":[{"message":{"content":"","tool_calls":[{"function":{"arguments":"{}"}}]}}]}"""
        assertTrue(LlmClient.parse(body) is LlmReply.Text)
    }
}
