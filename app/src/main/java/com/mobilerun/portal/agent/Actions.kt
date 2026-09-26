package com.mobilerun.portal.agent

import org.json.JSONException

/** Performs one tool call from the model. Tool names and arguments match the dashboard's TOOLS. */
class Actions(private val phone: PhoneControl) {
    fun run(call: ToolCall, screen: Screen): ActionOutcome = try {
        when (call.name) {
            "tap" -> withElement(call, screen) { e -> result(phone.tap(e.centerX, e.centerY), "Tapped ${label(e)}") }
            "tap_at" -> {
                val (x, y) = onScreen(call.args.getInt("x"), call.args.getInt("y"), screen)
                result(phone.tap(x, y), "Tapped at $x,$y")
            }
            "type" -> type(call, screen)
            "scroll" -> scroll(call, screen)
            "back" -> result(phone.global(GLOBAL_BACK), "Pressed Back")
            "home" -> result(phone.global(GLOBAL_HOME), "Went to the home screen")
            "enter" -> result(phone.key(KEYCODE_ENTER), "Pressed Enter")
            "open_app" -> openApp(call.args.getString("name"))
            "wait" -> {
                val seconds = call.args.optDouble("seconds", 2.0).coerceIn(0.5, 10.0)
                phone.sleep((seconds * 1000).toLong())
                ActionOutcome(true, "Waited ${seconds}s")
            }
            "done" -> {
                val answer = call.args.optString("answer")
                ActionOutcome(true, answer, done = true, success = call.args.optBoolean("success", false), answer = answer)
            }
            else -> ActionOutcome(false, "Unknown action ${call.name}")
        }
    } catch (e: JSONException) {
        ActionOutcome(false, "Bad arguments for ${call.name}: ${e.message}")
    }

    private fun withElement(call: ToolCall, screen: Screen, act: (Element) -> ActionOutcome): ActionOutcome {
        val index = call.args.getInt("index")
        val element = screen.elements.firstOrNull { it.index == index }
            ?: return ActionOutcome(false, "Element $index is not on the screen")
        return act(element)
    }

    private fun type(call: ToolCall, screen: Screen): ActionOutcome {
        val text = call.args.getString("text")
        if (call.args.has("index")) {
            val focus = withElement(call, screen) { e -> result(phone.tap(e.centerX, e.centerY), "Focused ${label(e)}") }
            if (!focus.ok) return focus
            phone.sleep(FOCUS_MS)
        }
        return result(phone.type(text, clear = true), "Typed \"${text.take(60)}\"")
    }

    private fun scroll(call: ToolCall, screen: Screen): ActionOutcome {
        val direction = call.args.getString("direction")
        val area = if (call.args.has("index")) {
            screen.elements.firstOrNull { it.index == call.args.getInt("index") }
                ?: return ActionOutcome(false, "Element ${call.args.getInt("index")} is not on the screen")
        } else {
            null
        }
        val left = area?.left ?: 0
        val top = area?.top ?: 0
        val right = area?.right ?: screen.width
        val bottom = area?.bottom ?: screen.height
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val dx = (right - left) * 3 / 10
        val dy = (bottom - top) * 3 / 10
        val (x1, y1, x2, y2) = when (direction) {
            "down" -> listOf(cx, cy + dy, cx, cy - dy)
            "up" -> listOf(cx, cy - dy, cx, cy + dy)
            "right" -> listOf(cx + dx, cy, cx - dx, cy)
            "left" -> listOf(cx - dx, cy, cx + dx, cy)
            else -> return ActionOutcome(false, "Unknown scroll direction $direction")
        }
        return result(phone.swipe(x1, y1, x2, y2, SCROLL_MS), "Scrolled $direction")
    }

    private fun openApp(name: String): ActionOutcome {
        val apps = phone.launchableApps()
        val match = apps.firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.contains(name, ignoreCase = true) }
            ?: apps.firstOrNull { it.second.equals(name, ignoreCase = true) }
            ?: return ActionOutcome(false, "No app called \"$name\" on this phone")
        return result(phone.launch(match.second), "Opened ${match.first}")
    }

    private fun onScreen(x: Int, y: Int, screen: Screen): Pair<Int, Int> =
        x.coerceIn(0, maxOf(0, screen.width - 1)) to y.coerceIn(0, maxOf(0, screen.height - 1))

    private fun result(problem: String?, success: String) =
        if (problem == null) ActionOutcome(true, success) else ActionOutcome(false, problem)

    private fun label(e: Element) = when {
        e.text.isNotEmpty() -> "\"${e.text}\""
        e.description.isNotEmpty() -> "\"${e.description}\""
        else -> "element ${e.index}"
    }

    companion object {
        const val GLOBAL_BACK = 1
        const val GLOBAL_HOME = 2
        const val KEYCODE_ENTER = 66
        const val FOCUS_MS = 400L
        const val SCROLL_MS = 350
    }
}
