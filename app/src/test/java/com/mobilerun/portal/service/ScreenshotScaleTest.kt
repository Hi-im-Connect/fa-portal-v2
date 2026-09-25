package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotScaleTest {
    @Test
    fun `longest side is capped and the aspect ratio kept`() {
        assertEquals(432 to 960, AccessibilityScreenshotApi30.scaledSize(1080, 2400, 960))
        assertEquals(960 to 432, AccessibilityScreenshotApi30.scaledSize(2400, 1080, 960))
    }

    @Test
    fun `small screens and zero max side stay full size`() {
        assertEquals(720 to 1280, AccessibilityScreenshotApi30.scaledSize(720, 1280, 1600))
        assertEquals(1080 to 2400, AccessibilityScreenshotApi30.scaledSize(1080, 2400, 0))
    }
}
