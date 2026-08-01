package com.omnipilot.server

import com.intellij.openapi.project.Project
import com.omnipilot.api.OmniPilotAgentTools
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ToolExecutionHandler {
    fun setupListeners(project: Project, client: JsonRpcClient) {
        client.onNotification("chat/toolCall") { params ->
            if (params != null) {
                val callId = params["callId"]?.jsonPrimitive?.contentOrNull ?: ""
                val toolName = params["toolName"]?.jsonPrimitive?.contentOrNull ?: ""
                val args = params["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"

                try {
                    val result = OmniPilotAgentTools.executeTool(project, toolName, args)
                    client.sendNotification("tool/result", """
                        {
                            "callId": "${quote(callId)}",
                            "result": ${quote(result)}
                        }
                    """.trimIndent())
                } catch (e: Exception) {
                    client.sendNotification("tool/result", """
                        {
                            "callId": "${quote(callId)}",
                            "error": ${quote(e.message ?: "Execution error")}
                        }
                    """.trimIndent())
                }
            }
        }
    }

    private fun quote(str: String): String {
        return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
