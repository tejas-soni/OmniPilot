package com.omnipilot.history

import com.omnipilot.api.ChatMessage
import kotlinx.serialization.Serializable

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val timestamp: Long,
    val messages: List<ChatMessage>
)

@Serializable
data class ChatHistoryStore(
    val sessions: MutableList<ChatSession> = mutableListOf()
)
