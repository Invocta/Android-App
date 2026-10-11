package com.invocta.domain.repository

import com.invocta.domain.model.ChatSession
import com.invocta.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for chat session operations
 */
interface ChatSessionRepository {

    /**
     * Get all chat sessions ordered by most recent
     */
    fun getAllSessions(): Flow<List<ChatSession>>

    /**
     * Get a specific session by ID
     */
    suspend fun getSessionById(sessionId: String): ChatSession?

    /**
     * Create a new chat session
     */
    suspend fun createSession(title: String = "New Chat"): ChatSession

    /**
     * Update session title
     */
    suspend fun updateSessionTitle(sessionId: String, title: String)

    /**
     * Delete a chat session and all its messages
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * Delete all chat sessions
     */
    suspend fun deleteAllSessions()
    suspend fun syncLocalChatsToCloud()

    /**
     * Get messages for a specific session
     */
    fun getMessages(sessionId: String): Flow<List<Message>>

    /**
     * Add a message to a session
     */
    suspend fun addMessage(sessionId: String, message: Message)

    /**
     * Process user input and get AI response
     * @param sessionId The session to process input in
     * @param input The user's input text
     * @param onStreamingContent Optional callback for streaming content updates
     *        - delta: The new chunk of content
     *        - accumulated: All content received so far
     */
    suspend fun processUserInput(
        sessionId: String,
        input: String,
        onStreamingContent: ((delta: String, accumulated: String) -> Unit)? = null
    ): Result<Message>

    /**
     * Clear messages in a session (but keep the session)
     */
    suspend fun clearSessionMessages(sessionId: String)

    /**
     * Get recent messages for context (for AI)
     */
    suspend fun getRecentMessages(sessionId: String, limit: Int = 10): List<Message>

    /**
     * Auto-generate title from first user message
     */
    suspend fun autoGenerateTitle(sessionId: String)
}

