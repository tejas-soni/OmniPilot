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
        // For SSE streaming, readTimeout applies to the gap between chunks, not the
        // whole stream. 300s tolerates slow free-tier models that pause between tokens.
        .readTimeout(300, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
        
    private val json = Json { 
        ignoreUnknownKeys = true 
        explicitNulls = false
        encodeDefaults = true
    }
    private val jsonMediaType = "application/json".toMediaType()
    @Volatile private var currentStream: EventSource? = null

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
            // Explicit Content-Type so strict proxies never mis-frame the JSON body.
            .header("Content-Type", "application/json")
            // Disable keep-alive connection reuse for streaming requests. A shared,
            // long-lived connection whose previous SSE stream was not fully drained can
            // cause the server to read a stale/duplicated buffer, producing a Python
            // "Extra data" JSONDecodeError (HTTP 400) on the next request.
            .header("Connection", "close")
            // Use a neutral User-Agent. Spoofing the Node SDK caused some proxies
            // (e.g. tokenrouter) to apply Node-specific body framing/parsing.
            .header("User-Agent", "OmniPilot-IntelliJ-Plugin")
            .post(body)
            .build()

        // Always-on diagnostic logging to a file so we can capture the exact bytes sent.
        // Written to <user.home>/omnipilot-payload.log (API key is masked).
        try {
            val logFile = java.io.File(System.getProperty("user.home"), "omnipilot-payload.log")
            val payloadBytes = jsonString.toByteArray(Charsets.UTF_8)
            val maskedHeaders = request.headers.joinToString("\n") { h ->
                val value = if (h.first.equals("Authorization", true)) "***" else h.second
                "  ${h.first}: $value"
            }
            val sb = StringBuilder()
            sb.appendLine("=== OMNIPILOT REQUEST @ ${java.time.LocalDateTime.now()} ===")
            sb.appendLine("URL: ${request.url}")
            sb.appendLine("Headers:\n$maskedHeaders")
            sb.appendLine("Payload byte length: ${payloadBytes.size}")
            sb.appendLine("Payload char length: ${jsonString.length}")
            sb.appendLine("Payload (raw):")
            sb.appendLine(jsonString)
            sb.appendLine("--- First 32 bytes (hex) ---")
            sb.appendLine(payloadBytes.take(32).joinToString(" ") { "%02x".format(it) })
            sb.appendLine("--- Last 32 bytes (hex) ---")
            sb.appendLine(payloadBytes.takeLast(32).joinToString(" ") { "%02x".format(it) })
            sb.appendLine("=== END ===")
            sb.appendLine()
            logFile.appendText(sb.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            println("OmniPilot payload log failed: ${e.message}")
        }

        // Accumulate tool calls keyed by their streaming `index`. Models may emit
        // MULTIPLE parallel tool calls in one response; each has its own index and
        // its own arguments fragment stream. The previous code appended ALL fragments
        // into a single buffer, producing invalid concatenated JSON like "{...}{...}"
        // which the server rejected with "Extra data" (HTTP 400).
        data class PendingToolCall(
            var id: String? = null,
            var name: String? = null,
            val args: StringBuilder = StringBuilder()
        )
        val pendingToolCalls = sortedMapOf<Int, PendingToolCall>()
        var isToolCall = false

        val eventSourceFactory = EventSources.createFactory(client)
        currentStream = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    currentStream = null
                    
                    if (isToolCall && pendingToolCalls.isNotEmpty() && project != null) {
                        // Build the assistant message containing ALL tool calls, then
                        // execute each and append one tool-result message per call.
                        val newMessages = ArrayList(messages)
                        val assistantToolCalls = mutableListOf<ToolCall>()
                        val toolResultMessages = mutableListOf<ChatMessage>()

                        for ((idx, pending) in pendingToolCalls) {
                            val toolName = pending.name ?: continue
                            val callId = pending.id ?: "call_$idx"
                            // Some models emit multiple concatenated JSON objects in a single
                            // tool call's arguments ("{...}{...}"). Keep only the first
                            // balanced JSON object so downstream parsing never sees extra data.
                            val argsStr = extractFirstJsonObject(pending.args.toString())

                            val parsedArgs = try { kotlinx.serialization.json.Json.parseToJsonElement(argsStr).jsonObject } catch(e: Exception) { null }
                            val displayStr = when (toolName) {
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
                                else -> "\n\n> **Executing tool:** `$toolName`\n"
                            }
                            onToken(displayStr)

                            var execute = true
                            if (mode == "chat" && (toolName == "run_command" || toolName == "write_file")) {
                                val permissionStr = onPermissionRequest?.invoke(toolName, argsStr)
                                if (permissionStr == "DENY") {
                                    execute = false
                                }
                            }

                            val result = if (execute) {
                                OmniPilotAgentTools.executeTool(project, toolName, argsStr)
                            } else {
                                "Error: User denied permission to execute this tool."
                            }

                            if (execute) onToken("> *(Done)*\n\n") else onToken("> *(Denied)*\n\n")

                            assistantToolCalls.add(ToolCall(
                                id = callId,
                                type = "function",
                                function = FunctionCall(name = toolName, arguments = argsStr)
                            ))
                            toolResultMessages.add(ChatMessage(
                                role = "tool",
                                name = toolName,
                                content = result,
                                toolCallId = callId
                            ))
                        }

                        if (assistantToolCalls.isEmpty()) {
                            onComplete()
                            return
                        }

                        newMessages.add(ChatMessage(role = "assistant", toolCalls = assistantToolCalls))
                        newMessages.addAll(toolResultMessages)

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
                        // Accumulate EVERY tool call fragment keyed by its own index,
                        // so parallel tool calls don't get concatenated together.
                        for (tc in delta.toolCalls) {
                            val pending = pendingToolCalls.getOrPut(tc.index) { PendingToolCall() }
                            if (tc.id != null) pending.id = tc.id
                            if (tc.function?.name != null) pending.name = tc.function.name
                            if (tc.function?.arguments != null) pending.args.append(tc.function.arguments)
                        }
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

    /**
     * Returns the first balanced JSON object found in [raw]. Some models emit
     * multiple concatenated JSON objects ("{...}{...}") in a single tool call's
     * arguments; downstream JSON parsers reject that with "Extra data". This walks
     * the string tracking brace depth (ignoring braces inside string literals) and
     * returns the substring for the first complete object. If no balanced object is
     * found, the original string is returned unchanged.
     */
    private fun extractFirstJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        if (start == -1) return raw
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
        }
        return raw
    }
}
