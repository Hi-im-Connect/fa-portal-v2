package com.mobilerun.portal.agent

/**
 * The phone as the agent sees it, with FastAutomate's floating bubble stepping out of the way only
 * when a tap or swipe would land on it (covers = is this point on the bubble), so it is never tapped
 * and does not blink on every step.
 */
class OverlayShy(
    private val inner: PhoneControl,
    private val covers: (Int, Int) -> Boolean,
    private val setHidden: (Boolean) -> Unit,
) : PhoneControl by inner {
    private fun <T> aside(hit: Boolean, block: () -> T): T {
        if (!hit) return block()
        setHidden(true)
        try {
            return block()
        } finally {
            setHidden(false)
        }
    }

    override fun tap(x: Int, y: Int) = aside(covers(x, y)) { inner.tap(x, y) }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String? {
        val hit = (0..SAMPLES).any { i -> covers(x1 + (x2 - x1) * i / SAMPLES, y1 + (y2 - y1) * i / SAMPLES) }
        return aside(hit) { inner.swipe(x1, y1, x2, y2, durationMs) }
    }

    private companion object {
        const val SAMPLES = 20
    }
}
