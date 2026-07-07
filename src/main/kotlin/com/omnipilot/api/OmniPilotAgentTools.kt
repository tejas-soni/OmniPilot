package com.omnipilot.api

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object OmniPilotAgentTools {

    fun getAgentTools(): List<Tool> {
        val isWindows = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
        val shellDescription = if (isWindows) "Runs in PowerShell on Windows." else "Runs in /bin/sh on macOS/Linux."
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
                    description = "Execute a terminal command in the project root. $shellDescription",
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

                    // Security: Prevent path traversal outside project root
                    if (!file.canonicalPath.startsWith(File(basePath).canonicalPath)) {
                        return "Error: Access denied. Path is outside the project directory."
                    }

                    if (!file.exists() || !file.isFile) return "Error: File not found at $path"

                    // Safety: Limit file size to prevent OOM
                    if (file.length() > 500_000) {
                        return "Error: File is too large to read (${file.length() / 1024}KB). Max is 500KB."
                    }

                    file.readText(Charsets.UTF_8)
                }

                "write_file" -> {
                    val path = args["path"]?.jsonPrimitive?.content ?: return "Error: Missing path argument."
                    val content = args["content"]?.jsonPrimitive?.content ?: return "Error: Missing content argument."
                    val file = File(basePath, path)

                    // Security: Prevent path traversal outside project root
                    if (!file.canonicalPath.startsWith(File(basePath).canonicalPath)) {
                        return "Error: Access denied. Path is outside the project directory."
                    }

                    file.parentFile?.mkdirs()
                    file.writeText(content, Charsets.UTF_8)

                    // Refresh VFS so IntelliJ sees the change immediately
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.refresh(false, false)
                    }
                    "File successfully written."
                }

                "run_command" -> {
                    val command = args["command"]?.jsonPrimitive?.content ?: return "Error: Missing command argument."
                    val isWindows = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)

                    val tempOut = File.createTempFile("omnipilot_out_", ".txt")
                    val tempDone = File(tempOut.absolutePath + ".done")

                    try {
                        // Build OS-appropriate wrapped command
                        val wrappedCommand: Array<String> = if (isWindows) {
                            arrayOf(
                                "powershell.exe", "-NoProfile", "-Command",
                                "& { $command } | Tee-Object -FilePath '${tempOut.absolutePath}'; Set-Content -Path '${tempDone.absolutePath}' -Value 'DONE'"
                            )
                        } else {
                            arrayOf(
                                "sh", "-c",
                                "{ $command ; } 2>&1 | tee '${tempOut.absolutePath}'; echo DONE > '${tempDone.absolutePath}'"
                            )
                        }

                        // Use CompletableFuture + invokeLater to avoid invokeAndWait deadlock risk
                        val widgetReady = CompletableFuture<Unit>()
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            try {
                                val terminalViewClass = try {
                                    Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
                                } catch (e: ClassNotFoundException) {
                                    Class.forName("org.jetbrains.plugins.terminal.TerminalView")
                                }
                                val getInstanceMethod = terminalViewClass.getMethod("getInstance", Project::class.java)
                                val terminalInstance = getInstanceMethod.invoke(null, project)
                                val createWidgetMethod = terminalViewClass.getMethod("createLocalShellWidget", String::class.java, String::class.java)
                                val widget = createWidgetMethod.invoke(terminalInstance, basePath, "OmniPilot")
                                val executeCommandMethod = widget.javaClass.getMethod("executeCommand", String::class.java)
                                executeCommandMethod.invoke(widget, wrappedCommand.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it })
                                widgetReady.complete(Unit)
                            } catch (e: Exception) {
                                widgetReady.completeExceptionally(e)
                            }
                        }

                        // Wait for the widget to start (max 10s)
                        widgetReady.get(10, TimeUnit.SECONDS)

                        // Poll for done file (max 60 seconds)
                        var attempts = 0
                        while (!tempDone.exists() && attempts < 120) {
                            Thread.sleep(500)
                            attempts++
                        }

                        if (!tempDone.exists()) {
                            return "Error: Command timed out after 60 seconds."
                        }

                        val bytes = tempOut.readBytes()
                        val result = when {
                            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                                String(bytes, Charsets.UTF_16LE)
                            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                                String(bytes, Charsets.UTF_16BE)
                            else -> String(bytes, Charsets.UTF_8)
                        }

                        if (result.isBlank()) "Command executed successfully (no output)." else result
                    } finally {
                        // Always clean up temp files even on exception
                        tempOut.delete()
                        tempDone.delete()
                    }
                }

                else -> "Error: Unknown tool $name."
            }
        } catch (e: Exception) {
            return "Error executing tool: ${e.message}"
        }
    }
}
