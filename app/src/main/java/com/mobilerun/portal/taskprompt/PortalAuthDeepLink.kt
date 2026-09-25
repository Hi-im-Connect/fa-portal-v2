package com.mobilerun.portal.taskprompt

import java.net.URLEncoder

object PortalAuthDeepLink {
    const val CALLBACK_HOST = "auth-callback"
    const val PREFERRED_CALLBACK_SCHEME = "fastautomate2"

        private const val CLOUD_AUTH_DEVICE_URL = "https://digimate.fastautomate.com/mobile2/connect/device"
    // QR / link from the dashboard: fastautomate2://connect?token=...&url=...
    const val CONNECT_HOST = "connect"
    private val ACCEPTED_CALLBACK_SCHEMES =
        setOf(PREFERRED_CALLBACK_SCHEME)

    fun buildCloudLoginUrl(deviceId: String, forceLogin: Boolean): String {
        val encodedDeviceId = URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        return buildString {
            append(CLOUD_AUTH_DEVICE_URL)
            append("?deviceId=")
            append(encodedDeviceId)
            append("&scheme=")
            append(PREFERRED_CALLBACK_SCHEME)
            if (forceLogin) {
                append("&force_login=true")
            }
        }
    }

    fun isConnectLink(scheme: String?, host: String?): Boolean {
        return scheme == PREFERRED_CALLBACK_SCHEME && host == CONNECT_HOST
    }

    fun isAuthCallback(scheme: String?, host: String?): Boolean {
        return scheme in ACCEPTED_CALLBACK_SCHEMES && host == CALLBACK_HOST
    }
}
