package com.omnipilot.ui.swing

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.omnipilot.server.OmniPilotProcessManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*

class OmniPilotSwingChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val messageBox = JPanel()
    private val inputArea = JTextArea(3, 20)
    private val sendBtn = JButton("Send")

    init {
        background = Color(30, 30, 30)
        messageBox.layout = BoxLayout(messageBox, BoxLayout.Y_AXIS)
        messageBox.background = Color(30, 30, 30)

        val scrollPane = JBScrollPane(messageBox)
        scrollPane.border = null
        add(scrollPane, BorderLayout.CENTER)

        val inputPanel = JPanel(BorderLayout())
        inputPanel.background = Color(40, 40, 40)

        inputArea.background = Color(45, 45, 45)
        inputArea.foreground = Color.WHITE
        inputArea.caretColor = Color.WHITE
        inputArea.font = Font("SansSerif", Font.PLAIN, 13)
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true

        sendBtn.background = Color(53, 116, 240)
        sendBtn.foreground = Color.WHITE
        sendBtn.isFocusPainted = false

        sendBtn.addActionListener {
            sendMessage()
        }

        inputPanel.add(JBScrollPane(inputArea), BorderLayout.CENTER)
        inputPanel.add(sendBtn, BorderLayout.EAST)
        add(inputPanel, BorderLayout.SOUTH)
    }

    private fun sendMessage() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return

        appendUserMessage(text)
        inputArea.text = ""

        val rpc = OmniPilotProcessManager.rpcClient
        if (rpc != null) {
            val agentBubble = appendAgentMessage("Thinking...")

            rpc.sendRequest("chat/send", """
                {
                    "sessionId": "${System.currentTimeMillis()}",
                    "messages": [{"role": "user", "content": ${quote(text)}}],
                    "providerId": "",
                    "model": "",
                    "mode": "CHAT",
                    "osInfo": "${System.getProperty("os.name")}"
                }
            """.trimIndent()) { resultJson, errorJson ->
                if (errorJson != null) {
                    SwingUtilities.invokeLater {
                        agentBubble.text = "Error: $errorJson"
                    }
                }
            }

            rpc.onNotification("chat/token") { params ->
                val token = params?.get("token")?.toString()?.replace("\"", "") ?: ""
                SwingUtilities.invokeLater {
                    if (agentBubble.text == "Thinking...") {
                        agentBubble.text = token
                    } else {
                        agentBubble.text += token
                    }
                }
            }
        } else {
            appendAgentMessage("Error: OmniPilot server process is not connected.")
        }
    }

    private fun appendUserMessage(text: String) {
        val label = JLabel("<html><b>You:</b> ${escapeHtml(text)}</html>")
        label.foreground = Color(200, 220, 255)
        label.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        messageBox.add(label)
        messageBox.revalidate()
    }

    private fun appendAgentMessage(text: String): JLabel {
        val label = JLabel("<html><b>OmniPilot:</b> ${escapeHtml(text)}</html>")
        label.foreground = Color(220, 220, 220)
        label.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        messageBox.add(label)
        messageBox.revalidate()
        return label
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    }

    private fun quote(text: String): String {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
