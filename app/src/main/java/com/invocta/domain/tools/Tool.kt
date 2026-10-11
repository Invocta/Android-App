package com.invocta.domain.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Base interface for all tools that the AI agent can call
 */
interface Tool {
    /** Unique identifier for the tool */
    val name: String

    /** Human-readable description of what the tool does */
    val description: String

    /** JSON schema defining the parameters this tool accepts */
    val parameters: ToolParameters

    /** List of permissions required by this tool (empty if none needed) */
    val requiredPermissions: List<ToolPermission>
        get() = emptyList()

    /**
     * Execute the tool with the given arguments
     * @param arguments The arguments passed by the AI
     * @return Result containing the tool output or an error
     */
    suspend fun execute(arguments: Map<String, Any?>): ToolResult
}

/**
 * Defines the parameters schema for a tool (OpenAI function calling format)
 */
@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

/**
 * Result of a tool execution
 */
sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class NeedsPermission(
        val permissions: List<ToolPermission>,
        val toolName: String,
        val pendingArguments: Map<String, Any?>
    ) : ToolResult()

    /**
     * Success result that includes an image for vision analysis.
     * The ToolExecutor will send this image along with the message to the AI.
     */
    data class SuccessWithImage(
        val message: String,
        val base64Image: String,
        val mimeType: String = "image/jpeg"
    ) : ToolResult()

    fun toOutputString(): String = when (this) {
        is Success -> output
        is Error -> "Error: $message"
        is NeedsPermission -> "Permission required: ${permissions.joinToString { it.displayName }}"
        is SuccessWithImage -> message
    }

    fun hasImage(): Boolean = this is SuccessWithImage

    fun isPermissionRequest(): Boolean = this is NeedsPermission
}

/**
 * Represents a tool call request from the AI
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String, // JSON string of arguments
    val thoughtSignature: String? = null
)

/**
 * Represents the result of a tool call to send back to the AI
 */
data class ToolCallResult(
    val toolCallId: String,
    val toolName: String,
    val result: ToolResult
)

