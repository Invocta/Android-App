package com.invocta.pluginsdk

import org.json.JSONObject

/** A fast, local action explicitly offered by another Android app. */
data class PluginAction(
    val id: String,
    val description: String,
    val inputSchema: JSONObject = JSONObject().put("type", "object")
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,47}"))) { "Invalid action id" }
        require(description.length in 1..200) { "Action description must be 1–200 characters" }
        require(inputSchema.optString("type") == "object") { "Input schema must be an object" }
    }

    internal fun toJson() = JSONObject()
        .put("id", id)
        .put("description", description)
        .put("inputSchema", inputSchema)
}

object PluginProtocol {
    const val ACTION = "com.invocta.action.PLUGIN"
    const val VERSION = 1
    const val LIST = 1
    const val EXECUTE = 2
    const val MAX_JSON_CHARS = 8192
    const val TIMEOUT_MS = 5000L
}
