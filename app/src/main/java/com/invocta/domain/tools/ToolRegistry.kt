package com.invocta.domain.tools

import android.content.Context

/**
 * Registry that manages all available tools for the AI agent
 */
class ToolRegistry(private val context: Context) {
    private val tools = mutableMapOf<String, Tool>()
    private val permissionManager = ToolPermissionManager(context)

    // Pending tool execution waiting for permission
    private var pendingToolExecution: PendingToolExecution? = null

    /**
     * Register a tool with the registry
     */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /**
     * Register multiple tools at once
     */
    fun registerAll(vararg toolList: Tool) {
        toolList.forEach { register(it) }
    }

    /**
     * Get a tool by name
     */
    fun getTool(name: String): Tool? = tools[name]

    /**
     * Get all registered tools
     */
    fun getAllTools(): List<Tool> = tools.values.toList()

    /**
     * Check if a tool exists
     */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /**
     * Execute a tool by name with given arguments
     * Will check permissions first and return NeedsPermission if required
     */
    suspend fun executeTool(name: String, arguments: Map<String, Any?>): ToolResult {
        val tool = getTool(name) ?: return ToolResult.Error("Tool '$name' not found")

        // Check if tool requires permissions
        if (tool.requiredPermissions.isNotEmpty()) {
            val permissionCheck = permissionManager.checkPermissions(tool.requiredPermissions)

            if (permissionCheck is PermissionCheckResult.NeedsPermission) {
                // Store pending execution for after permission is granted
                pendingToolExecution = PendingToolExecution(
                    toolName = name,
                    arguments = arguments
                )

                return ToolResult.NeedsPermission(
                    permissions = permissionCheck.permissions,
                    toolName = name,
                    pendingArguments = arguments
                )
            }
        }

        // Execute the tool
        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            ToolResult.Error("Tool execution failed: ${e.message}")
        }
    }

    /**
     * Execute a pending tool after permission was granted
     */
    suspend fun executePendingTool(): ToolResult? {
        val pending = pendingToolExecution ?: return null
        pendingToolExecution = null

        return executeTool(pending.toolName, pending.arguments)
    }

    /**
     * Clear any pending tool execution
     */
    fun clearPendingExecution() {
        pendingToolExecution = null
    }

    /**
     * Check if there's a pending tool execution
     */
    fun hasPendingExecution(): Boolean = pendingToolExecution != null

    /**
     * Get permissions needed for pending execution
     */
    fun getPendingPermissions(): List<ToolPermission>? {
        val pending = pendingToolExecution ?: return null
        val tool = getTool(pending.toolName) ?: return null
        return tool.requiredPermissions
    }

    /**
     * Get Android permission strings for pending execution
     */
    fun getPendingAndroidPermissions(): Array<String>? {
        val permissions = getPendingPermissions() ?: return null
        return permissionManager.getAndroidPermissions(permissions)
    }

    /**
     * Convert all tools to OpenAI function calling format
     */
    fun toOpenAITools(): List<Map<String, Any>> {
        return tools.values.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description.lineSequence().first().trim(),
                    "parameters" to mapOf(
                        "type" to tool.parameters.type,
                        "properties" to tool.parameters.properties.mapValues { (_, prop) ->
                            buildMap {
                                put("type", prop.type)
                                put("description", prop.description)
                                prop.enum?.let { put("enum", it) }
                            }
                        },
                        "required" to tool.parameters.required
                    )
                )
            )
        }
    }
}

/**
 * Represents a tool execution that's waiting for permission
 */
data class PendingToolExecution(
    val toolName: String,
    val arguments: Map<String, Any?>
)

