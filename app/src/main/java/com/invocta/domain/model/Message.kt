package com.invocta.domain.model

import java.util.UUID

/**
 * Represents a message in the assistant conversation
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: MessageType = MessageType.TEXT
)

enum class MessageType {
    TEXT,
    VOICE,
    COMMAND,
    ERROR
}

