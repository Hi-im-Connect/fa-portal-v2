package com.mobilerun.portal.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String)

fun interface LlmTransport {
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResult
}

class OkHttpTransport : LlmTransport {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()

    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResult {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                .newCall(request).execute().use { HttpResult(it.code, it.body?.string().orEmpty()) }
        } catch (e: java.io.IOException) {
            HttpResult(-1, e.message ?: "network error")
        }
    }
}

class LlmError(message: String, val budget: Boolean = false) : Exception(message)

sealed class LlmReply {
    data class Tool(val call: ToolCall, val thought: String) : LlmReply()
    data class Text(val text: String) : LlmReply()
}

/** OpenRouter (OpenAI format) chat completions, paid with this phone's own key. */
class LlmClient(
    private val transport: LlmTransport,
    private val baseUrl: String,
    private val apiKey: () -> String?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun complete(model: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int = 1024): LlmReply {
        val key = apiKey() ?: throw LlmError("This phone has no AI key yet: keep it connected to the dashboard for a moment.")
        val body = JSONObject().put("model", model).put("messages", messages).put("max_tokens", maxTokens)
        if (tools != null) body.put("tools", tools).put("tool_choice", "required")
        val headers = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json", "X-Title" to "FastAutomate v2")
        var attempt = 0
        while (true) {
            val r = transport.post("$baseUrl/chat/completions", headers, body.toString(), 60_000)
            when {
                r.code == 200 -> {
                    val reply = runCatching { parse(r.body) }.getOrNull()
                    if (reply != null) return reply
                    // a 200 without usable choices (OpenRouter relays upstream failures like this): retry, then say so
                    if (attempt < 2) {
                        attempt++
                        sleep(1500L * attempt)
                    } else {
                        throw LlmError("The AI returned no answer: ${errorText(r.body)}")
                    }
                }
                r.code == 402 -> throw LlmError(ACCOUNT_EMPTY_MESSAGE, budget = true)
                r.code == 403 && r.body.contains("limit", ignoreCase = true) -> throw LlmError(BUDGET_MESSAGE, budget = true)
                r.code == 401 -> throw LlmError("This phone's AI key was refused. Reconnect it to the dashboard to get a new one.")
                (r.code == 429 || r.code >= 500 || r.code == -1) && attempt < 2 -> {
                    attempt++
                    sleep(1500L * attempt)
                }
                else -> throw LlmError("The AI said ${r.code}: ${errorText(r.body)}")
            }
        }
    }

    companion object {
        const val BUDGET_MESSAGE = "This phone's daily AI budget is used up"
        const val ACCOUNT_EMPTY_MESSAGE = "The OpenRouter account is out of credit"

        fun parse(body: String): LlmReply {
            val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val thought = if (message.isNull("content")) "" else message.optString("content").trim()
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return LlmReply.Text(thought)
            val function = calls.getJSONObject(0).optJSONObject("function") ?: return LlmReply.Text("A tool call without a function")
            if (function.optString("name").isBlank()) return LlmReply.Text("A tool call without a name")
            val raw = function.optString("arguments").ifBlank { "{}" }
            val args = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                return LlmReply.Text("Unreadable arguments for ${function.optString("name")}: ${raw.take(200)}")
            }
            return LlmReply.Tool(ToolCall(function.getString("name"), args), thought)
        }

        private fun errorText(body: String): String =
            runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(200))
    }
}
