package com.omnipilot.api

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

object OmniPilotAgentTools {

    fun getAgentTools(): List<Tool> {
        return listOf(
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "read_file",
                    description = "Read the contents of a file in the project.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "The relative path to the file from the project root.")
                            }
                        }
                        putJsonArray("required") {
                            add(kotlinx.serialization.json.JsonPrimitive("path"))
                        }
                    }
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "write_file",
                    description = "Write or overwrite contents of a file in the project.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "The relative path to the file from the project root.")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "The complete new content of the file.")
                            }
                        }
                        putJsonArray("required") {
                            add(kotlinx.serialization.json.JsonPrimitive("path"))
                            add(kotlinx.serialization.json.JsonPrimitive("content"))
                        }
                    }
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "run_command",
                    description = "Execute a terminal command in the project root. Runs in PowerShell (Windows), so standard commands like ls, pwd, cat work fine.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("command") {
                                put("type", "string")
                                put("description", "The shell command to execute.")
                            }
                        }
                        putJsonArray("required") {
                            add(kotlinx.serialization.json.JsonPrimitive("command"))
                        }
                    }
                )
            )
        )
    }

    fun executeTool(project: Project, name: String, arguments: String): String {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val args = json.parseToJsonElement(arguments).jsonObject
            val basePath = project.basePath ?: return "Error: Project base path is null."
            
            return when (name) {
                "read_file" -> {
                    val path = args["path"]?.jsonPrimitive?.content ?: return "Error: Missing path argument."
                    val file = File(basePath, path)
                    if (file.exists() && file.isFile) {
                        file.readText()
                    } else {
                        "Error: File not found at $path"
                    }
                }
                "write_file" -> {
                    val path = args["path"]?.jsonPrimitive?.content ?: return "Error: Missing path argument."
                    val content = args["content"]?.jsonPrimitive?.content ?: return "Error: Missing content argument."
                    val file = File(basePath, path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    
                    // Refresh the VFS so IntelliJ sees the change
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.refresh(false, false)
                    }
                    "File successfully written."
                }
                "run_command" -> {
                    val command = args["command"]?.jsonPrimitive?.content ?: return "Error: Missing command argument."
                    
                    val tempOut = File.createTempFile("omnipilot_out_", ".txt")
                    val tempDone = File(tempOut.absolutePath + ".done")
                    
                    // The command wraps the user's command in a powershell block, Tees the output to the screen and file, then creates a .done file.
                    val wrappedCommand = "powershell.exe -NoProfile -Command \"& { $command } | Tee-Object -FilePath '${tempOut.absolutePath}'; Set-Content -Path '${tempDone.absolutePath}' -Value 'DONE'\""

                    var widget: org.jetbrains.plugins.terminal.ShellTerminalWidget? = null
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                        val terminalView = org.jetbrains.plugins.terminal.TerminalView.getInstance(project)
                        // This creates or reuses a tab named "OmniPilot"
                        widget = terminalView.createLocalShellWidget(basePath, "OmniPilot")
                        widget?.executeCommand(wrappedCommand)
                    }

                    // Poll for completion (up to 60 seconds)
                    var attempts = 0
                    while (!tempDone.exists() && attempts < 120) {
                        Thread.sleep(500)
                        attempts++
                    }

                    val output = if (tempDone.exists()) {
                        val bytes = tempOut.readBytes()
                        val res = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                            String(bytes, Charsets.UTF_16LE)
                        } else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                            String(bytes, Charsets.UTF_16BE)
                        } else {
                            String(bytes, Charsets.UTF_8)
                        }
                        tempDone.delete()
                        res
                    } else {
                        "Error: Command timed out after 60 seconds or failed to write output."
                    }
                    tempOut.delete()
                    
                    if (output.isBlank()) "Command executed successfully (no output)." else output
                }
                else -> "Error: Unknown tool $name."
            }
        } catch (e: Exception) {
            return "Error executing tool: ${e.message}"
        }
    }
}
