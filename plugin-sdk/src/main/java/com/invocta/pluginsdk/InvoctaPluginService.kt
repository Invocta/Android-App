package com.invocta.pluginsdk

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host apps subclass this service and export it with [PluginProtocol.ACTION].
 * Caller access is denied until the host implements [isCallerAllowed].
 */
abstract class InvoctaPluginService : Service() {
    abstract fun actions(): List<PluginAction>
    abstract fun executeAction(id: String, input: JSONObject): JSONObject

    /** Check the actual Messenger sender UID, plus the host app's user opt-in policy. */
    open fun isCallerAllowed(uid: Int): Boolean = false

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        val response = Message.obtain(null, request.what).apply {
            arg1 = PluginProtocol.VERSION
        }
        val result = try {
            require(request.sendingUid >= 0 && isCallerAllowed(request.sendingUid)) {
                "Access not enabled in this app"
            }
            require(request.arg1 == PluginProtocol.VERSION) { "Unsupported protocol version" }
            when (request.what) {
                PluginProtocol.LIST -> JSONObject().put(
                    "actions", JSONArray().also { array ->
                        actions().forEach { array.put(it.toJson()) }
                    }
                ).toString()
                PluginProtocol.EXECUTE -> {
                    val id = request.data.getString("id").orEmpty()
                    val rawInput = request.data.getString("input").orEmpty()
                    require(rawInput.length <= PluginProtocol.MAX_JSON_CHARS) { "Input too large" }
                    require(actions().any { it.id == id }) { "Unknown action" }
                    executeAction(id, JSONObject(rawInput)).toString()
                }
                else -> error("Unknown request")
            }.also { require(it.length <= PluginProtocol.MAX_JSON_CHARS) { "Result too large" } }
        } catch (error: Exception) {
            response.data = android.os.Bundle().apply {
                putString("error", error.message ?: "Action failed")
            }
            null
        }
        if (result != null) response.data = android.os.Bundle().apply { putString("result", result) }
        try { request.replyTo?.send(response) } catch (_: android.os.RemoteException) { }
        true
    })

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == PluginProtocol.ACTION) messenger.binder else null
}
