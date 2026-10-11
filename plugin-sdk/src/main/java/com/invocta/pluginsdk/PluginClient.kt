package com.invocta.pluginsdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONObject
import kotlin.coroutines.suspendCoroutine

data class PluginApp(val component: ComponentName, val label: String) {
    val id: String get() = component.flattenToString()
}

/** Discovers opted-in providers and exchanges small JSON messages over Android IPC. */
class PluginClient(context: Context) {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    fun discover(): List<PluginApp> =
        appContext.packageManager.queryIntentServices(Intent(PluginProtocol.ACTION), 0)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                if (!service.exported || service.packageName == appContext.packageName) return@mapNotNull null
                PluginApp(ComponentName(service.packageName, service.name),
                    info.loadLabel(appContext.packageManager).toString().take(80))
            }
            .distinctBy { it.id }

    suspend fun actions(app: PluginApp): JSONObject =
        JSONObject(exchange(app, PluginProtocol.LIST, Bundle()))

    suspend fun execute(app: PluginApp, id: String, input: JSONObject): JSONObject {
        val raw = input.toString()
        require(raw.length <= PluginProtocol.MAX_JSON_CHARS) { "Input too large" }
        return JSONObject(exchange(app, PluginProtocol.EXECUTE, Bundle().apply {
            putString("id", id)
            putString("input", raw)
        }))
    }

    private suspend fun exchange(app: PluginApp, operation: Int, data: Bundle): String =
        suspendCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            main.post {
                var bound = false
                var finished = false
                lateinit var connection: ServiceConnection
                lateinit var timeout: Runnable
                fun finish(result: Result<String>) {
                    if (finished) return
                    finished = true
                    main.removeCallbacks(timeout)
                    if (bound) appContext.unbindService(connection)
                    continuation.resumeWith(result)
                }
                timeout = Runnable { finish(Result.failure(IllegalStateException("Plugin timed out"))) }
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    val error = message.data.getString("error")
                    val result = message.data.getString("result")
                    finish(when {
                        message.arg1 != PluginProtocol.VERSION ->
                            Result.failure(IllegalStateException("Unsupported plugin response"))
                        error != null -> Result.failure(IllegalStateException(error))
                        result == null || result.length > PluginProtocol.MAX_JSON_CHARS ->
                            Result.failure(IllegalStateException("Invalid plugin response"))
                        else -> Result.success(result)
                    })
                    true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (finished) return
                        try {
                            Messenger(binder).send(Message.obtain(null, operation).apply {
                                arg1 = PluginProtocol.VERSION
                                this.data = data
                                replyTo = reply
                            })
                        } catch (error: Exception) {
                            finish(Result.failure(error))
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        finish(Result.failure(IllegalStateException("Plugin disconnected")))
                    }
                }
                try {
                    bound = appContext.bindService(
                        Intent(PluginProtocol.ACTION).setComponent(app.component),
                        connection,
                        Context.BIND_AUTO_CREATE
                    )
                    if (bound) main.postDelayed(timeout, PluginProtocol.TIMEOUT_MS)
                    else finish(Result.failure(IllegalStateException("Plugin unavailable")))
                } catch (error: Exception) {
                    finish(Result.failure(error))
                }
            }
        }
}
