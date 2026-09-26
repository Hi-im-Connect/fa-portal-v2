package com.mobilerun.portal.ui.home

import android.content.Context
import android.content.Intent
import com.mobilerun.portal.agent.InstallStamp
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.taskprompt.PortalCloudClient
import java.io.File

/** Linking the phone to the FastAutomate dashboard: from a connect link or from the invite inside the download. */
object FaConnect {
    private const val PREFS = "fa_v2"
    private const val STAMP_USED = "install_stamp_used"

    /** Only our own dashboard address is accepted, so a link cannot point the phone elsewhere. */
    fun connect(context: Context, rawToken: String?, rawUrl: String?): Boolean {
        val config = ConfigManager.getInstance(context)
        val token = rawToken?.replace("\\s+".toRegex(), "").orEmpty()
        val url = rawUrl?.trim().orEmpty().ifBlank { config.defaultReverseConnectionUrl }
        val official = PortalCloudClient.isOfficialMobilerunCloudConnection(
            reverseConnectionUrl = url,
            defaultReverseConnectionUrl = config.defaultReverseConnectionUrl,
        )
        if (token.isBlank() || !official) return false
        config.reverseConnectionToken = token
        config.reverseConnectionUrl = url
        config.reverseConnectionEnabled = true
        reconnect(context)
        return true
    }

    /** First launch of an invite download: connect with the invite it carries (only once, only if not linked yet). */
    fun connectFromInstall(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(STAMP_USED, false) || isLinked(context)) return false
        prefs.edit().putBoolean(STAMP_USED, true).apply()
        val token = InstallStamp.token(File(context.applicationInfo.sourceDir)) ?: return false
        return connect(context, token, null)
    }

    fun isLinked(context: Context): Boolean = ConfigManager.getInstance(context).reverseConnectionToken.isNotBlank()

    fun reconnect(context: Context) {
        ConfigManager.getInstance(context).faAutoConnect = true
        context.startForegroundService(
            Intent(ReverseConnectionService.ACTION_RECONNECT, null, context, ReverseConnectionService::class.java),
        )
    }
}
