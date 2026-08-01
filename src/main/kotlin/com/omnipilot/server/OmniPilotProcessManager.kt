package com.omnipilot.server

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object OmniPilotProcessManager {
    private val LOG = Logger.getInstance(OmniPilotProcessManager::class.java)
    private var process: Process? = null
    var rpcClient: JsonRpcClient? = null
        private set

    fun ensureStarted() {
        if (process != null && process!!.isAlive && rpcClient != null) {
            return
        }
        val pluginId = com.intellij.openapi.extensions.PluginId.getId("com.omnipilot")
        val pluginDescriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)
        val pluginRootDir = pluginDescriptor?.pluginPath?.toFile() ?: File(".")
        startServer(pluginRootDir)
    }

    fun startServer(pluginRootDir: File) {

        try {
            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()

            val platformDir = when {
                osName.contains("win") -> if (osArch.contains("64")) "win32-x64" else "win32-x86"
                osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "darwin-arm64" else "darwin-x64"
                else -> "linux-x64"
            }

            val binaryName = if (osName.contains("win")) "omnipilot-server.exe" else "omnipilot-server"
            val binaryFile = File(pluginRootDir, "omnipilot-server/native/$platformDir/$binaryName")

            val command = if (binaryFile.exists()) {
                listOf(binaryFile.absolutePath, "--stdio")
            } else {
                // Fallback to node execution during development
                val nodeServerScript = File(pluginRootDir, "omnipilot-core/dist/omnipilot-server.js")
                if (nodeServerScript.exists()) {
                    listOf("node", nodeServerScript.absolutePath)
                } else {
                    listOf("node", File(pluginRootDir, "omnipilot-core/dist/index.js").absolutePath)
                }
            }

            val pb = ProcessBuilder(command)
            pb.directory(pluginRootDir)
            val proc = pb.start()
            process = proc

            val client = JsonRpcClient(proc.inputStream, proc.outputStream)
            client.start()
            rpcClient = client

            LOG.info("OmniPilot language server started successfully via stdio.")
        } catch (e: Exception) {
            LOG.error("Failed to start OmniPilot language server", e)
        }
    }

    fun stopServer() {
        try {
            rpcClient?.stop()
            rpcClient = null
            process?.destroyForcibly()
            process = null
            LOG.info("OmniPilot language server stopped.")
        } catch (e: Exception) {
            LOG.error("Error stopping OmniPilot language server", e)
        }
    }
}
