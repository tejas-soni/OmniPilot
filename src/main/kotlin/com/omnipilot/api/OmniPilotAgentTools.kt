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
                    runShellCommand(command, basePath)
                }

                else -> "Error: Unknown tool $name."
            }
        } catch (e: Exception) {
            return "Error executing tool: ${e.message}"
        }
    }

    /**
     * Runs a shell command directly via a child process (Cline-style) instead of
     * spawning an IDE terminal widget. The old approach created a NEW terminal per
     * command and polled a ".done" file; after a few commands the terminals piled up
     * and the .done file was never written, so the agent stalled on a 60s timeout.
     *
     * This implementation:
     *  - starts the process with ProcessBuilder in the project directory
     *  - drains stdout and stderr on separate threads (prevents pipe-buffer deadlock)
     *  - waits up to [timeoutSeconds] and forcibly destroys the process on timeout
     */
    private fun runShellCommand(command: String, basePath: String, timeoutSeconds: Long = 120): String {
        val isWindows = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
        val pb = if (isWindows) {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
        pb.directory(File(basePath))
        pb.redirectErrorStream(true)

        val process = try {
            pb.start()
        } catch (e: Exception) {
            return "Error: Failed to start command: ${e.message}"
        }

        // Drain the combined output on a separate thread so a full pipe buffer
        // never blocks the child process.
        val output = StringBuilder()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                }
            } catch (_: Exception) { /* stream closed on destroy */ }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!finished) {
            process.destroyForcibly()
            try { process.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) {}
            return "Error: Command timed out after $timeoutSeconds seconds and was terminated.\n\nPartial output:\n${synchronized(output) { output.toString() }.trim()}"
        }

        readerThread.join(2000)
        val exitCode = process.exitValue()
        val result = synchronized(output) { output.toString() }.trim()

        return when {
            result.isEmpty() && exitCode == 0 -> "Command executed successfully (no output)."
            result.isEmpty() -> "Command finished with exit code $exitCode (no output)."
            exitCode == 0 -> result
            else -> "$result\n\n(exit code: $exitCode)"
        }
    }
}
