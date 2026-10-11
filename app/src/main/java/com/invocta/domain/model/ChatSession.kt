package com.invocta.domain.model

import java.util.UUID

/**
 * Represents a chat session containing multiple messages
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val messageCount: Int = 0,
    val lastMessage: String? = null
)

