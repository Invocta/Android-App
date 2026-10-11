package com.invocta.domain.tools.impl

import android.content.Context
import com.invocta.domain.tools.Tool
import com.invocta.domain.tools.ToolParameters
import com.invocta.domain.tools.ToolProperty
import com.invocta.domain.tools.ToolResult
import com.invocta.domain.tools.PluginTrustStore
import com.invocta.pluginsdk.PluginClient
import org.json.JSONArray
import org.json.JSONObject

class DiscoverAppActionsTool(context: Context) : Tool {
    private val client = PluginClient(context)
    private val trust = PluginTrustStore(context)
    override val name = "discover_app_actions"
    override val description = "Find actions explicitly offered by installed apps, including input schemas. Use before screen controls for supported app tasks."
    override val parameters = ToolParameters(properties = mapOf(
        "app" to ToolProperty("string", "Optional app name to filter results")
    ))

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val filter = (arguments["app"] as? String).orEmpty().trim()
        val matches = client.discover().filter {
            filter.isEmpty() || it.label.contains(filter, true) ||
                it.component.packageName.contains(filter, true)
        }
        if (matches.isEmpty()) return ToolResult.Success("No installed app offers matching Invocta actions.")
        val apps = JSONArray()
        for (app in matches) {
            val item = JSONObject().put("app", app.label).put("provider", app.id)
            if (!trust.isAllowed(app)) {
                item.put("status", "Enable this app in Invocta Settings before using its actions.")
                apps.put(item)
                continue
            }
            try {
                item.put("actions", client.actions(app).getJSONArray("actions"))
            } catch (error: Exception) {
                item.put("status", "Access unavailable. Enable Invocta integration inside this app.")
            }
            apps.put(item)
        }
        return ToolResult.Success(apps.toString())
    }
}

class CallAppActionTool(context: Context) : Tool {
    private val client = PluginClient(context)
    private val trust = PluginTrustStore(context)
    override val name = "call_app_action"
    override val description = "Call an action from discover_app_actions using its provider and action id. Only call actions the user requested."
    override val parameters = ToolParameters(
        properties = mapOf(
            "provider" to ToolProperty("string", "Provider id returned by discover_app_actions"),
            "action" to ToolProperty("string", "Action id returned by discover_app_actions"),
            "input_json" to ToolProperty("string", "JSON object matching the action input schema; use {} when empty")
        ),
        required = listOf("provider", "action", "input_json")
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = try {
        val provider = arguments["provider"] as? String ?: error("Provider is required")
        val action = arguments["action"] as? String ?: error("Action is required")
        val input = JSONObject(arguments["input_json"] as? String ?: error("Input JSON is required"))
        val app = client.discover().firstOrNull { it.id == provider } ?: error("Provider unavailable")
        require(trust.isAllowed(app)) { "Enable this app in Invocta Settings first" }
        val offered = client.actions(app).getJSONArray("actions")
        require((0 until offered.length()).any { offered.getJSONObject(it).getString("id") == action }) {
            "Action is not offered by this app"
        }
        ToolResult.Success(client.execute(app, action, input).toString())
    } catch (error: Exception) {
        ToolResult.Error(error.message ?: "App action failed")
    }
}
