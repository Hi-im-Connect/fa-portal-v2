package com.mobilerun.portal.agent

import org.json.JSONObject

/** A phone for tests: records what the agent does and shows a scripted screen. */
class FakePhone(var state: JSONObject = JSONObject(), var shot: String? = "SHOT") : PhoneControl {
    val done = mutableListOf<String>()
    var failTaps = false
    var unreadable = false
    var apps = listOf("Settings" to "com.android.settings", "Chrome" to "com.android.chrome")

    override fun readScreen() = if (unreadable) null else state
    override fun screenshot(maxSide: Int, quality: Int) = shot.also { done += "shot $maxSide" }
    override fun tap(x: Int, y: Int): String? { done += "tap $x,$y"; return if (failTaps) "Failed to perform tap" else null }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String? { done += "swipe $x1,$y1>$x2,$y2"; return null }
    override fun type(text: String, clear: Boolean): String? { done += "type $text"; return null }
    override fun key(keyCode: Int): String? { done += "key $keyCode"; return null }
    override fun global(action: Int): String? { done += "global $action"; return null }
    override fun launchableApps() = apps
    override fun launch(packageName: String): String? { done += "launch $packageName"; return null }
    override fun sleep(ms: Long) { done += "sleep $ms" }
}
