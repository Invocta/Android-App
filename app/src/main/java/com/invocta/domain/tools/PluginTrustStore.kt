package com.invocta.domain.tools

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.invocta.pluginsdk.PluginApp
import java.security.MessageDigest

/** User approval for one installed provider and its current signing certificate. */
class PluginTrustStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("plugin_trust", Context.MODE_PRIVATE)

    fun isAllowed(app: PluginApp): Boolean = runCatching {
        preferences.getString(app.id, null) == certificateDigest(app.component.packageName)
    }.getOrDefault(false)

    fun setAllowed(app: PluginApp, enabled: Boolean): Boolean = runCatching {
        val editor = preferences.edit()
        if (enabled) editor.putString(app.id, certificateDigest(app.component.packageName))
        else editor.remove(app.id)
        editor.apply()
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun certificateDigest(packageName: String): String {
        val manager = appContext.packageManager
        val certificates = if (Build.VERSION.SDK_INT >= 28) {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: error("Provider certificate unavailable")
        require(certificates.isNotEmpty()) { "Provider certificate unavailable" }
        return certificates.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(":")
    }
}
