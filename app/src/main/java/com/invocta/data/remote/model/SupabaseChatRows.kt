package com.invocta.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupabaseChatSessionRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("is_active") val isActive: Boolean
)

@Serializable
data class SupabaseChatMessageRow(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val content: String,
    @SerialName("is_from_user") val isFromUser: Boolean,
    val timestamp: Long,
    @SerialName("message_type") val messageType: String = "TEXT"
)
