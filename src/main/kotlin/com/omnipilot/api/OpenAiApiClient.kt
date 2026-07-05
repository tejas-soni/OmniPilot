package com.omnipilot.api

import com.omnipilot.settings.CredentialManager
import com.omnipilot.settings.OmniPilotSettingsState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiApiClient {

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
        
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    private val jsonMediaType = "application/json".toMediaType()
    private var currentStream: EventSource? = null

    fun cancelCurrentStream() {
        currentStream?.cancel()
        currentStream = null
    }

    fun streamChatCompletion(
        project: com.intellij.openapi.project.Project?,
        providerId: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<Tool>? = null,
        mode: String = "chat",
        onPermissionRequest: ((String, String) -> String)? = null,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val settings = OmniPilotSettingsState.instance
        val provider = settings.providers.find { it.id == providerId }
        
        if (provider == null) {
            onError(IllegalStateException("Provider not found or not configured"))
            return
        }
        
        val apiKey = CredentialManager.getApiKey(provider.id)
        
        if (apiKey.isNullOrEmpty()) {
            onError(IllegalStateException("API Key is missing for ${provider.name}"))
            return
        }

        // Clean model and base URL from any invisible characters/spaces
        var cleanModel = model.replace(Regex("[^\\x20-\\x7E]"), "").trim()
        if (cleanModel.startsWith("openai/")) {
            cleanModel = cleanModel.substring(7)
        }
        
        var cleanBaseUrl = provider.baseUrl.replace(Regex("[^\\x20-\\x7E]"), "").trim()
        cleanBaseUrl = cleanBaseUrl.removeSuffix("/")

        var normalizedMessages = messages.toMutableList()
        if (cleanModel.contains("claude", ignoreCase = true) || cleanModel.contains("anthropic", ignoreCase = true)) {
            val systemMsgs = normalizedMessages.filter { it.role == "system" }
            if (systemMsgs.isNotEmpty()) {
                val sysContent = systemMsgs.mapNotNull { it.content }.joinToString("\n\n")
                normalizedMessages.removeAll { it.role == "system" }
                val firstUserIdx = normalizedMessages.indexOfFirst { it.role == "user" }
                if (firstUserIdx != -1) {
                    val userMsg = normalizedMessages[firstUserIdx]
                    normalizedMessages[firstUserIdx] = userMsg.copy(content = "System Instructions:\n$sysContent\n\n---\n${userMsg.content}")
                } else {
                    normalizedMessages.add(0, ChatMessage(role = "user", content = sysContent))
                }
            }
        }

        val requestBody = ChatCompletionRequest(
            model = cleanModel,
            messages = normalizedMessages,
            tools = tools,
            stream = true
        )

        val jsonString = json.encodeToString(requestBody)
        val body = jsonString.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$cleanBaseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("User-Agent", "OpenAI/NodeJS/4.0.0") // Mimic NodeJS client perfectly
            .post(body)
            .build()

        // Debug logging to IDE console so the user can inspect it
        println("=== OMNIPILOT API DEBUG ===")
        println("URL: ${request.url}")
        println("Headers: ${request.headers.joinToString { "${it.first}: ${if (it.first == "Authorization") "***" else it.second}" }}")
        println("Payload: $jsonString")
        println("===========================")

        var pendingToolCallId: String? = null
        var pendingToolCallName: String? = null
        var pendingToolCallArgs = StringBuilder()
        var isToolCall = false

        val eventSourceFactory = EventSources.createFactory(client)
        currentStream = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    currentStream = null
                    
                    if (isToolCall && pendingToolCallName != null && project != null) {
                        val argsStr = pendingToolCallArgs.toString()
                        val parsedArgs = try { kotlinx.serialization.json.Json.parseToJsonElement(argsStr).jsonObject } catch(e: Exception) { null }
                        val displayStr = when (pendingToolCallName) {
                            "run_command" -> {
                                val cmd = parsedArgs?.get("command")?.jsonPrimitive?.content ?: "unknown"
                                "\n\n> **Terminal Command:** `$cmd`\n"
                            }
                            "read_file" -> {
                                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                                "\n\n> **Reading File:** `$path`\n"
                            }
                            "write_file" -> {
                                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                                "\n\n> **Writing File:** `$path`\n"
                            }
                            else -> "\n\n> **Executing tool:** `$pendingToolCallName`\n"
                        }
                        onToken(displayStr)
                        var execute = true
                        if (mode == "chat" && (pendingToolCallName == "run_command" || pendingToolCallName == "write_file")) {
                            val permissionStr = onPermissionRequest?.invoke(pendingToolCallName!!, argsStr)
                            if (permissionStr == "DENY") {
                                execute = false
                            }
                        }
                        
                        val result = if (execute) {
                            OmniPilotAgentTools.executeTool(project, pendingToolCallName!!, argsStr)
                        } else {
                            "Error: User denied permission to execute this tool."
                        }
                        
                        if (execute) onToken("> *(Done)*\n\n") else onToken("> *(Denied)*\n\n")
                        
                        // Recurse with new messages
                        val newMessages = messages.toMutableList()
                        newMessages.add(ChatMessage(
                            role = "assistant",
                            toolCalls = listOf(ToolCall(
                                id = pendingToolCallId ?: "call_1",
                                type = "function",
                                function = FunctionCall(
                                    name = pendingToolCallName,
                                    arguments = pendingToolCallArgs.toString()
                                )
                            ))
                        ))
                        newMessages.add(ChatMessage(
                            role = "tool",
                            name = pendingToolCallName,
                            content = result,
                            toolCallId = pendingToolCallId ?: "call_1"
                        ))
                        
                        streamChatCompletion(project, providerId, model, newMessages, tools, mode, onPermissionRequest, onToken, onComplete, onError)
                    } else {
                        onComplete()
                    }
                    return
                }
                try {
                    val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                    val delta = chunk.choices?.firstOrNull()?.delta
                    
                    if (delta?.toolCalls?.isNotEmpty() == true) {
                        isToolCall = true
                        val tc = delta.toolCalls.first()
                        if (tc.id != null) pendingToolCallId = tc.id
                        if (tc.function?.name != null) pendingToolCallName = tc.function.name
                        if (tc.function?.arguments != null) pendingToolCallArgs.append(tc.function.arguments)
                    } else {
                        val content = delta?.content
                        if (content != null) {
                            onToken(content)
                        }
                    }
                } catch (e: Exception) {
                    println("Failed to decode chunk: $data - ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                currentStream = null
                if (!isToolCall) {
                    onComplete()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                currentStream = null
                try {
                    val errorBody = try { response?.body?.string() } catch (e: Exception) { null }
                    val errorMsg = buildString {
                        append("Stream failed for ${request.url}")
                        if (response != null) append(" (HTTP ${response.code})")
                        if (t != null) append(": ${t.message}")
                        if (!errorBody.isNullOrBlank()) append("\n\nServer Response:\n$errorBody")
                    }
                    onError(Exception(errorMsg, t))
                } catch (e: Exception) {
                    onError(Exception("Stream failed with unknown error", e))
                }
            }
        })
    }
}
