package com.mobilerun.portal

import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.taskprompt.PortalAuthDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2IdentityTest {
    @Test
    fun `v2 talks to the v2 dashboard`() {
        assertEquals(
            "wss://digimate.fastautomate.com/mobile2/v1/providers/personal/join",
            ConfigManager.DEFAULT_REVERSE_CONNECTION_URL,
        )
        assertTrue(
            PortalAuthDeepLink.buildCloudLoginUrl("d1", false)
                .startsWith("https://digimate.fastautomate.com/mobile2/connect/device?deviceId=d1&scheme=fastautomate2"),
        )
    }

    @Test
    fun `only the fastautomate2 scheme is accepted`() {
        assertEquals("fastautomate2", PortalAuthDeepLink.PREFERRED_CALLBACK_SCHEME)
        assertTrue(PortalAuthDeepLink.isConnectLink("fastautomate2", "connect"))
        assertFalse(PortalAuthDeepLink.isConnectLink("fastautomate", "connect"))
        assertFalse(PortalAuthDeepLink.isAuthCallback("mobilerun", "auth-callback"))
        assertTrue(PortalAuthDeepLink.isAuthCallback("fastautomate2", "auth-callback"))
    }
}
