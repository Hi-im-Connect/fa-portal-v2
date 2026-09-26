package com.mobilerun.portal.agent

import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.service.ActionDispatcher
import org.json.JSONArray
import org.json.JSONObject

/** What the agent can do on its phone. Actions return null when they worked, else the error text. */
interface PhoneControl {
    fun readScreen(): JSONObject?
    fun screenshot(maxSide: Int, quality: Int): String?
    fun tap(x: Int, y: Int): String?
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String?
    fun type(text: String, clear: Boolean): String?
    fun key(keyCode: Int): String?
    fun global(action: Int): String?
    fun launchableApps(): List<Pair<String, String>>
    fun launch(packageName: String): String?
    fun sleep(ms: Long) = Thread.sleep(ms)
}

/** The same actions the dashboard used to call remotely, now called locally. */
class DispatcherPhoneControl(private val dispatcher: () -> ActionDispatcher?) : PhoneControl {
    private fun call(method: String, params: JSONObject): ApiResponse =
        dispatcher()?.dispatch(method, params, ActionDispatcher.Origin.WEBSOCKET_LOCAL)
            ?: ApiResponse.Error(NO_SERVICE)

    private fun problem(response: ApiResponse): String? = (response as? ApiResponse.Error)?.message

    override fun readScreen(): JSONObject? = when (val r = call("state", JSONObject().put("filter", true))) {
        is ApiResponse.RawObject -> r.json
        is ApiResponse.Success -> (r.data as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        else -> null
    }

    override fun screenshot(maxSide: Int, quality: Int): String? {
        val params = JSONObject().put("format", "jpeg").put("maxSide", maxSide).put("quality", quality).put("hideOverlay", true)
        return (call("screenshot", params) as? ApiResponse.Text)?.data
    }

    override fun tap(x: Int, y: Int) = problem(call("tap", JSONObject().put("x", x).put("y", y)))

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = problem(
        call("swipe", JSONObject().put("startX", x1).put("startY", y1).put("endX", x2).put("endY", y2).put("duration", durationMs)),
    )

    override fun type(text: String, clear: Boolean): String? {
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        return problem(call("keyboard/input", JSONObject().put("base64_text", encoded).put("clear", clear)))
    }

    override fun key(keyCode: Int) = problem(call("keyboard/key", JSONObject().put("key_code", keyCode)))

    override fun global(action: Int) = problem(call("global", JSONObject().put("action", action)))

    override fun launchableApps(): List<Pair<String, String>> {
        val list: JSONArray = when (val r = call("packages", JSONObject())) {
            is ApiResponse.RawArray -> r.json
            else -> return emptyList()
        }
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { it.optString("label") to it.optString("packageName") }
        }.filter { it.second.isNotEmpty() }
    }

    override fun launch(packageName: String) = problem(call("app", JSONObject().put("package", packageName)))

    companion object {
        const val NO_SERVICE = "Turn on FastAutomate v2 in Accessibility"
    }
}
