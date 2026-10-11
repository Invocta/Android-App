package com.invocta.data.local.mapper

import com.invocta.data.local.entity.ChatMessageEntity
import com.invocta.data.local.entity.ChatSessionEntity
import com.invocta.domain.model.ChatSession
import com.invocta.domain.model.Message
import com.invocta.domain.model.MessageType

/**
 * Mapper functions between Room entities and domain models
 */

fun ChatSessionEntity.toDomain(messageCount: Int = 0, lastMessage: String? = null): ChatSession {
    return ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isActive = isActive,
        messageCount = messageCount,
        lastMessage = lastMessage
    )
}

fun ChatSession.toEntity(): ChatSessionEntity {
    return ChatSessionEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isActive = isActive
    )
}

fun ChatMessageEntity.toDomain(): Message {
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

fun Message.toEntity(sessionId: String): ChatMessageEntity {
    return ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp,
        messageType = messageType.name
    )
}

fun List<ChatMessageEntity>.toDomainList(): List<Message> = map { it.toDomain() }

fun List<Message>.toEntityList(sessionId: String): List<ChatMessageEntity> =
    map { it.toEntity(sessionId) }

