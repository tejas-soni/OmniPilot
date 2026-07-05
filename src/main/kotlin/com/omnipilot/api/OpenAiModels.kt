package com.omnipilot.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class Tool(
    val type: String,
    @SerialName("function") val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: kotlinx.serialization.json.JsonObject? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<Tool>? = null,
    val stream: Boolean = false,
    val temperature: Double? = 1.0,
    @SerialName("top_p") val topP: Double? = 1.0,
    @SerialName("max_tokens") val maxTokens: Int? = 16384
)

@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice>? = null
)

@Serializable
data class ChunkChoice(
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val content: String? = null,
    val role: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null
)

@Serializable
data class DeltaToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCall? = null
)
