package com.invocta.data.repository

import android.content.Context
import android.util.Log
import com.invocta.data.remote.AIClient
import com.invocta.data.remote.SupabaseClientProvider
import com.invocta.data.remote.api.AuthRequiredException
import com.invocta.data.remote.api.InvoctaApiClient
import com.invocta.data.remote.model.ChatMessage
import com.invocta.data.remote.model.SupabaseChatMessageRow
import com.invocta.data.remote.model.SupabaseChatSessionRow
import com.invocta.domain.model.ChatSession
import com.invocta.domain.model.Message
import com.invocta.domain.model.MessageType
import com.invocta.domain.repository.ChatSessionRepository
import com.invocta.domain.skills.SkillRegistry
import com.invocta.domain.tools.ToolExecutor
import com.invocta.domain.tools.ToolRegistry
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.currentUserOrNull
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.result.decodeList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ChatSessionRepositoryImpl(
    private val context: Context,
    private val apiClient: InvoctaApiClient,
    private val aiClient: AIClient,
    private val toolRegistry: ToolRegistry
) : ChatSessionRepository {

    companion object {
        private const val TAG = "ChatSessionRepo"

        private val SYSTEM_PROMPT = """
You are Invocta, a helpful, friendly, and intelligent AI assistant. You are designed to replace the default phone assistant.

Key behaviors:
1. Be concise but helpful - mobile users prefer shorter responses
2. Be friendly and conversational
3. If asked about your capabilities, explain you can help with questions, conversations, information lookup, and general assistance
4. You can help with a wide variety of tasks including:
   - Answering questions on any topic
   - Having conversations
   - Helping with calculations
   - Providing explanations
   - Creative writing assistance
   - And much more
5. If you don't know something, admit it honestly
6. Keep responses mobile-friendly (not too long)

Response formatting guidelines:
- Use **bold** for important terms or emphasis
- Use bullet points or numbered lists for multiple items
- Use proper punctuation (commas, periods) for natural reading flow
- Keep paragraphs short and scannable
- Avoid overly technical jargon unless asked
- Use emojis sparingly to add personality 😊

Note: Your responses will be read aloud, so write in complete sentences with good punctuation.

Current context: You are running on an Android device as the user's personal assistant.
        """.trimIndent()

        private fun getSystemPromptWithTools(skillInstructions: String = ""): String {
            val deviceInfo = com.invocta.util.DeviceInfo.getDeviceSummary()

            return """
You are Invocta, an expert AI assistant that controls Android devices. You are the BEST Android power user who knows everything about Android apps and how to navigate them efficiently.

$deviceInfo

## RESPONSE RULES
Be brief. Use **bold** for key info. Keep confirmations short (e.g., "Done! Sent message to **Kunal**.")

## ⚠️ CRITICAL: SCROLL TO SHOW USER WHAT YOU'RE DOING
**The user is watching! Before interacting with elements, make sure they're VISIBLE on screen.**

When read_screen shows elements with Y coordinates > 2000 (off-screen):
1. **FIRST scroll_screen("down") to bring the element into view**
2. Then read_screen to get updated coordinates
3. Now interact with the element

**WHY?** Users need to SEE what's being changed. Don't interact with hidden elements!

## ⚠️ CRITICAL: FAILURE RECOVERY PROTOCOL
**MANDATORY: When ANY tool fails, you MUST immediately call read_screen() BEFORE retrying.**

Why? Tool failures usually mean:
- A popup/dialog appeared unexpectedly
- Screen changed due to accidental click
- App navigated to different screen
- Element is no longer visible
- **Element is off-screen (need to scroll!)**

**FAILURE RECOVERY STEPS:**
1. Tool fails → IMMEDIATELY call read_screen() to see current state
2. Check if you're still on the expected screen/app
3. If popup appeared → dismiss it (click "Cancel", "OK", navigate back)
4. If wrong screen → navigate back to correct screen
5. **If element not found → scroll_screen("down") → read_screen again**
6. Once confirmed on correct screen → retry the original action

**⚠️ WEB FORM TEXT INPUT FAILURES - NEVER GIVE UP!**
If input_text fails on a web form:
1. scroll_screen("down") to ensure field is visible
2. read_screen to get updated coordinates  
3. click_element with coordinates to focus the field
4. input_text again
5. Repeat at least 3 times! NEVER say "fields not accessible" without trying!

**DO NOT:**
- Retry the same failed action multiple times without reading screen
- Assume the screen hasn't changed
- Use blind coordinate clicks after failures
- **Interact with elements the user can't see - SCROLL FIRST!**
- **Give up on web form text fields after 1 failure - TRY SCROLL + CLICK + INPUT!**

## AVAILABLE TOOLS
open_app, read_screen, read_screen_vision, click_element, toggle_switch, input_text, scroll_screen, navigate(back/home), press_key, web_search, calculator, flashlight, get_battery_info, set_seekbar

## QUICK REFERENCE
- read_screen → see elements on screen (USE AFTER ANY FAILURE!)
- read_screen_vision → capture screenshot with coordinate grid (for poor accessibility apps)
- click_element(text="X") or click_element(description="X") or click_element(x=N, y=N)
- toggle_switch(label="X") → flip a switch or select radio/checkbox (works for web forms!)
- input_text(text="X") → type into focused field
- press_key(key="next"/"tab") → move to next field (NOT for sending messages!)
- scroll_screen(direction="down") → **USE THIS to show elements to user before interacting!**
- **If element Y > 2000 → scroll_screen("down") first to make it visible!**
- **For autocomplete/suggestions: read_screen_vision → click with coordinates**

## ⚠️ SENDING MESSAGES WARNING
**NEVER use press_key("send") or press_key("enter") to send messages in chat apps!**
These keys add newlines, not send. Instead:
- click_element(description="Send") - always click the Send button
- After sending, verify with read_screen that message appears in chat (not in input field)
$skillInstructions
            """.trimIndent()
        }
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Tool executor for handling tool calling loops
    private val toolExecutor = ToolExecutor(toolRegistry, aiClient, context)

    // Skill registry for domain-specific knowledge
    private val skillRegistry = SkillRegistry.createDefault()

    // Local caches for reactive flows
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val _messagesCache = mutableMapOf<String, MutableStateFlow<List<Message>>>()

    private val postgrest = SupabaseClientProvider.client
    private var aiServerHealthy = true

    private suspend fun ensureAuthenticated() {
        if (!SupabaseClientProvider.waitForAuthReady()) throw AuthRequiredException()
        if (postgrest.auth.currentUserOrNull() == null) throw AuthRequiredException()
    }

    private suspend fun currentUserId(): String {
        ensureAuthenticated()
        return postgrest.auth.currentUserOrNull()?.id ?: throw AuthRequiredException()
    }


    private suspend fun refreshSessions() {
        aiServerHealthy = apiClient.checkHealth().getOrDefault(false)

        // Wait for auth to be ready (handles async loading from storage)
        val isAuthenticated = SupabaseClientProvider.waitForAuthReady()
        if (!isAuthenticated) {
            Log.d(TAG, "Not authenticated - waiting for sign in")
            return
        }
        
        try {
            val sessions = postgrest.from("chat_sessions").select {
                order("updated_at", Order.DESCENDING)
            }.decodeList<SupabaseChatSessionRow>()
            _sessions.value = sessions.map { it.toDomain() }
            Log.d(TAG, "Refreshed ${sessions.size} sessions from Supabase")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to refresh sessions from Supabase", error)
        }
    }

    private suspend fun refreshMessages(sessionId: String) {
        if (!SupabaseClientProvider.waitForAuthReady()) return

        try {
            val messages = postgrest.from("chat_messages").select {
                filter { eq("session_id", sessionId) }
                order("timestamp", Order.ASCENDING)
            }.decodeList<SupabaseChatMessageRow>()
            getOrCreateMessagesFlow(sessionId).value = messages.map { it.toDomain() }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to refresh messages for $sessionId from Supabase", error)
        }
    }

    private fun getOrCreateMessagesFlow(sessionId: String): MutableStateFlow<List<Message>> {
        return _messagesCache.getOrPut(sessionId) {
            MutableStateFlow(emptyList())
        }
    }

    override fun getAllSessions(): Flow<List<ChatSession>> {
        // Refresh in background (catches any errors to prevent crash)
        repositoryScope.launch {
            try {
                refreshSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing sessions", e)
            }
        }
        return _sessions.asStateFlow()
    }

    override suspend fun getSessionById(sessionId: String): ChatSession? {
        // First check cache
        _sessions.value.find { it.id == sessionId }?.let { return it }
        if (!SupabaseClientProvider.waitForAuthReady()) return null

        return try {
            postgrest.from("chat_sessions").select {
                filter { eq("id", sessionId) }
                limit(1)
            }.decodeList<SupabaseChatSessionRow>().firstOrNull()?.toDomain()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to get session $sessionId from Supabase", error)
            null
        }
    }

    override suspend fun createSession(title: String): ChatSession {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val userId = currentUserId()
        val row = SupabaseChatSessionRow(
            id = sessionId,
            userId = userId,
            title = title,
            createdAt = now,
            updatedAt = now,
            isActive = true
        )

        postgrest.from("chat_sessions").insert(row)
        val session = row.toDomain()

        // Update local cache
        _sessions.value = listOf(session) + _sessions.value

        return session
    }

    override suspend fun updateSessionTitle(sessionId: String, title: String) {
        ensureAuthenticated()
        val now = System.currentTimeMillis()
        postgrest.from("chat_sessions").update({
            set("title", title)
            set("updated_at", now)
        }) {
            filter { eq("id", sessionId) }
        }
        // Update local cache
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(title = title, updatedAt = System.currentTimeMillis())
            else it
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        ensureAuthenticated()
        postgrest.from("chat_sessions").delete {
            filter { eq("id", sessionId) }
        }
        // Remove from local cache
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        _messagesCache.remove(sessionId)
    }

    override suspend fun deleteAllSessions() {
        postgrest.from("chat_sessions").delete {
            filter { eq("user_id", currentUserId()) }
        }

        // Clear local caches
        _sessions.value = emptyList()
        _messagesCache.clear()
    }

    override fun getMessages(sessionId: String): Flow<List<Message>> {
        // Refresh in background (catches any errors to prevent crash)
        repositoryScope.launch {
            try {
                refreshMessages(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing messages for $sessionId", e)
            }
        }
        return getOrCreateMessagesFlow(sessionId).asStateFlow()
    }

    override suspend fun addMessage(sessionId: String, message: Message) {
        ensureAuthenticated()
        val now = System.currentTimeMillis()
        val row = SupabaseChatMessageRow(
            id = message.id,
            sessionId = sessionId,
            content = message.content,
            isFromUser = message.isFromUser,
            timestamp = message.timestamp,
            messageType = message.messageType.name
        )

        postgrest.from("chat_messages").insert(row)
        postgrest.from("chat_sessions").update({
            set("updated_at", now)
        }) {
            filter { eq("id", sessionId) }
        }
        
        // Update local cache - avoid duplicates by filtering first
        val messagesFlow = getOrCreateMessagesFlow(sessionId)
        val newMessage = row.toDomain()
        val currentMessages = messagesFlow.value
        
        // Only add if not already present (prevents race condition with refreshMessages)
        if (currentMessages.none { it.id == newMessage.id }) {
            messagesFlow.value = currentMessages + newMessage
        }

        // Update session timestamp in cache
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(
                updatedAt = now,
                lastMessage = message.content
            )
            else it
        }
    }

    override suspend fun processUserInput(
        sessionId: String,
        input: String,
        onStreamingContent: ((delta: String, accumulated: String) -> Unit)?
    ): Result<Message> {
        // Add user message first
        val userMessage = Message(
            content = input,
            isFromUser = true
        )
        addMessage(sessionId, userMessage)

        // Check if server is reachable
        if (!isAIConfigured()) {
            val errorMessage = Message(
                content = "⚠️ Cannot connect to the server. Please check your internet connection and try again.",
                isFromUser = false,
                messageType = MessageType.ERROR
            )
            addMessage(sessionId, errorMessage)
            return Result.failure(Exception("Server not available"))
        }

        // Get conversation history for context
        val recentMessages = getRecentMessages(sessionId, 10)
        val conversationHistory = recentMessages
            .dropLast(1) // Exclude the current user message
            .map { msg ->
                if (msg.isFromUser) ChatMessage.user(msg.content)
                else ChatMessage.assistant(msg.content)
            }

        // Check if tools are available
        val hasTools = toolRegistry.getAllTools().isNotEmpty()
        Log.i(TAG, "Processing input: \"${input.take(50)}${if (input.length > 50) "..." else ""}\"")
        Log.d(TAG, "Tools enabled: $hasTools (${toolRegistry.getAllTools().size} tools)")
        Log.d(TAG, "Conversation history: ${conversationHistory.size} messages")

        val result = if (hasTools) {
            // Get relevant skills for this query
            val skillInstructions = skillRegistry.getSkillInstructions(input)
            val relevantSkills = skillRegistry.findRelevantSkills(input)
            if (relevantSkills.isNotEmpty()) {
                Log.d(TAG, "Active skills: ${relevantSkills.map { it.name }}")
            }

            // Use tool executor for agentic workflow with dynamic skills
            toolExecutor.executeWithTools(
                systemPrompt = getSystemPromptWithTools(skillInstructions),
                conversationHistory = conversationHistory,
                userMessage = input,
                onStreamingContent = onStreamingContent,
                useStreaming = onStreamingContent != null
            ).map { executionResult ->
                // Handle cancelled execution
                if (executionResult.wasCancelled) {
                    Log.i(TAG, "🛑 Task stopped by user")
                }
                // Log tool calls summary
                if (executionResult.toolCallsLog.isNotEmpty()) {
                    Log.i(TAG, "📊 Tool execution summary:")
                    executionResult.toolCallsLog.forEachIndexed { index, log ->
                        Log.i(TAG, "   ${index + 1}. ${log.toolName} → ${if (log.result.startsWith("Error")) "❌" else "✅"}")
                    }
                }
                executionResult.finalResponse
            }
        } else {
            // Fallback to simple chat without tools
            aiClient.chat(
                systemPrompt = SYSTEM_PROMPT,
                conversationHistory = conversationHistory,
                userMessage = input
            )
        }

        return result.fold(
            onSuccess = { responseText ->
                val assistantMessage = Message(
                    content = responseText,
                    isFromUser = false,
                    messageType = MessageType.TEXT
                )
                addMessage(sessionId, assistantMessage)

                // Auto-generate title after first exchange
                autoGenerateTitle(sessionId)

                Result.success(assistantMessage)
            },
            onFailure = { error ->
                val errorMessage = Message(
                    content = "❌ ${error.message ?: "Failed to get response"}",
                    isFromUser = false,
                    messageType = MessageType.ERROR
                )
                addMessage(sessionId, errorMessage)
                Result.failure(error)
            }
        )
    }

    override suspend fun clearSessionMessages(sessionId: String) {
        ensureAuthenticated()
        val messagesFlow = getOrCreateMessagesFlow(sessionId)
        postgrest.from("chat_messages").delete {
            filter { eq("session_id", sessionId) }
        }

        // Clear local cache
        messagesFlow.value = emptyList()
    }

    override suspend fun getRecentMessages(sessionId: String, limit: Int): List<Message> {
        if (!SupabaseClientProvider.waitForAuthReady()) {
            return getOrCreateMessagesFlow(sessionId).value.takeLast(limit)
        }
        return try {
            postgrest.from("chat_messages").select {
                filter { eq("session_id", sessionId) }
                order("timestamp", Order.DESCENDING)
                limit(limit.toLong())
            }.decodeList<SupabaseChatMessageRow>().map { it.toDomain() }.reversed()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to get recent messages for $sessionId", error)
            getOrCreateMessagesFlow(sessionId).value.takeLast(limit)
        }
    }

    override fun isAIConfigured(): Boolean {
        return aiServerHealthy
    }

    override suspend fun autoGenerateTitle(sessionId: String) {
        val session = getSessionById(sessionId) ?: return

        // Only auto-generate if title is still default
        if (session.title != "New Chat") return

        // Get first user message to generate title
        val messages = getOrCreateMessagesFlow(sessionId).value
        val firstUserMessage = messages.firstOrNull { it.isFromUser } ?: return

        // Generate a short title from the first message (max 30 chars)
        val title = firstUserMessage.content
            .take(30)
            .let { if (it.length == 30) "$it..." else it }
            .replace("\n", " ")
            .trim()

        if (title.isNotBlank()) {
            updateSessionTitle(sessionId, title)
        }
    }
}

// Extension functions for API to Domain mapping
private fun SupabaseChatSessionRow.toDomain(): ChatSession {
    return ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isActive = isActive,
        messageCount = 0,
        lastMessage = null
    )
}

private fun SupabaseChatMessageRow.toDomain(): Message {
    return Message(
        id = id,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp,
        messageType = try {
            MessageType.valueOf(messageType)
        } catch (e: Exception) {
            MessageType.TEXT
        }
    )
}
