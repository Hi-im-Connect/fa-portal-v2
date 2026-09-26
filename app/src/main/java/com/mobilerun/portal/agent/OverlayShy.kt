package com.mobilerun.portal.agent

/**
 * The phone as the agent sees it, with FastAutomate's floating bubble taken out of the way while the
 * agent looks (screenshot) or touches (tap, swipe), so it is never in a picture and never tapped.
 */
class OverlayShy(private val inner: PhoneControl, private val setHidden: (Boolean) -> Unit) : PhoneControl by inner {
    private fun <T> aside(block: () -> T): T {
        setHidden(true)
        try {
            return block()
        } finally {
            setHidden(false)
        }
    }

    override fun screenshot(maxSide: Int, quality: Int) = aside { inner.screenshot(maxSide, quality) }
    override fun tap(x: Int, y: Int) = aside { inner.tap(x, y) }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = aside { inner.swipe(x1, y1, x2, y2, durationMs) }
}
