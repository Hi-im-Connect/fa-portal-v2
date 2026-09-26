package com.mobilerun.portal.ui.home

/** What a new phone still needs before it can run tasks. */
enum class Step { NOTIFICATIONS, BATTERY, ACCESSIBILITY, OVERLAY, DASHBOARD }

/** overlay = "Display over other apps": the chat then sits under the system's gesture bar and back arrow, like Messenger. */
data class SetupState(val linked: Boolean, val notifications: Boolean, val battery: Boolean, val accessibility: Boolean, val overlay: Boolean)

object SetupSteps {
    fun missing(s: SetupState): List<Step> = buildList {
        if (!s.notifications) add(Step.NOTIFICATIONS)
        if (!s.battery) add(Step.BATTERY)
        if (!s.accessibility) add(Step.ACCESSIBILITY)
        if (!s.overlay) add(Step.OVERLAY)
        if (!s.linked) add(Step.DASHBOARD)
    }

    /** The step the app opens on its own: each at most once per visit, so a "no" is respected. */
    fun nextAuto(s: SetupState, tried: Set<Step>): Step? =
        missing(s).firstOrNull { it != Step.DASHBOARD && it !in tried }
}
