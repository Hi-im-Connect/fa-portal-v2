package com.mobilerun.portal.agent

import org.json.JSONObject

/** Turns the app's accessibility state ("state" with filter=true) into the numbered list the agent reads. */
object ScreenReader {
    const val MAX_ELEMENTS = 150

    fun parse(state: JSONObject): Screen {
        val phone = state.optJSONObject("phone_state") ?: JSONObject()
        val bounds = state.optJSONObject("device_context")?.optJSONObject("screen_bounds")
        val elements = ArrayList<Element>()
        state.optJSONObject("a11y_tree")?.let { collect(it, elements) }
        return Screen(
            app = phone.optString("currentApp"),
            packageName = phone.optString("packageName"),
            keyboardVisible = phone.optBoolean("keyboardVisible"),
            width = bounds?.optInt("width") ?: 0,
            height = bounds?.optInt("height") ?: 0,
            elements = elements,
        )
    }

    private fun collect(node: JSONObject, out: MutableList<Element>) {
        if (out.size >= MAX_ELEMENTS) return
        val b = node.optJSONObject("boundsInScreen")
        val left = b?.optInt("left") ?: 0
        val top = b?.optInt("top") ?: 0
        val right = b?.optInt("right") ?: 0
        val bottom = b?.optInt("bottom") ?: 0
        val text = node.optString("text").trim()
        val desc = node.optString("contentDescription").trim()
        val clickable = node.optBoolean("isClickable") || node.optBoolean("isLongClickable")
        val editable = node.optBoolean("isEditable")
        val scrollable = node.optBoolean("isScrollable")
        val visible = node.optBoolean("isVisibleToUser", true) && right > left && bottom > top
        if (visible && (clickable || editable || scrollable || text.isNotEmpty() || desc.isNotEmpty())) {
            out += Element(
                index = out.size + 1,
                className = node.optString("className").substringAfterLast('.'),
                text = text.take(80),
                description = desc.take(80),
                resourceId = node.optString("resourceId").substringAfter(":id/", ""),
                left = left, top = top, right = right, bottom = bottom,
                clickable = clickable, editable = editable, scrollable = scrollable,
            )
        }
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            children.optJSONObject(i)?.let { collect(it, out) }
        }
    }

    fun describe(screen: Screen): String = buildString {
        append("App: ").append(screen.app).append(" (").append(screen.packageName).append(')')
        if (screen.keyboardVisible) append(", keyboard open")
        append("\nScreen: ").append(screen.width).append('x').append(screen.height).append("\nElements:\n")
        if (screen.elements.isEmpty()) append("(nothing readable: use the screenshot and tap_at)\n")
        for (e in screen.elements) {
            append(e.index).append(' ').append(e.className)
            if (e.text.isNotEmpty()) append(" \"").append(e.text).append('"')
            if (e.description.isNotEmpty() && e.description != e.text) append(" desc=\"").append(e.description).append('"')
            if (e.resourceId.isNotEmpty()) append(" id=").append(e.resourceId)
            val flags = listOfNotNull("tap".takeIf { e.clickable }, "edit".takeIf { e.editable }, "scroll".takeIf { e.scrollable })
            if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
            append(" @").append(e.centerX).append(',').append(e.centerY).append('\n')
        }
    }
}
