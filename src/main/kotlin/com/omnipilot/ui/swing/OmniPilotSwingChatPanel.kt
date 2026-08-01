package com.omnipilot.ui.swing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.omnipilot.actions.ContextService
import com.omnipilot.server.OmniPilotProcessManager
import com.omnipilot.settings.OmniPilotSettingsConfigurable
import com.omnipilot.settings.OmniPilotSettingsListener
import com.omnipilot.settings.OmniPilotSettingsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

class OmniPilotSwingChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val json = Json { ignoreUnknownKeys = true }
    private var currentSessionId = UUID.randomUUID().toString()
    private var isStreaming = false

    // UI Components
    private val mainContentPanel = JPanel(BorderLayout())
    private val messageContainer = JPanel()
    private val scrollPane: JBScrollPane
    private val emptyStateHelper: JPanel

    // Input Components
    private val inputTextArea = JTextArea(2, 20)
    private val inputContainer = JPanel(BorderLayout())
    private val providerCombo = JComboBox<String>()
    private val modelCombo = JComboBox<String>()
    private val modeCombo = JComboBox<String>(arrayOf("Chat (Ask)", "Agent (Auto)", "Read-Only"))
    private val sendBtn = JButton()

    // History Drawer Overlay Panel
    private val historyOverlay = JPanel(BorderLayout())
    private val historyListContainer = JPanel()
    private var isHistoryOpen = false

    // Permission Dialog Overlay Panel
    private val permissionOverlay = JPanel(BorderLayout())
    private val permToolLabel = JLabel()
    private val permArgsArea = JTextArea(3, 20)
    private var currentPermFuture: CompletableFuture<String>? = null

    // Assistant streaming bubble
    private var currentAssistantEditor: JEditorPane? = null
    private var currentAssistantHtml = StringBuilder()

    init {
        background = Color(0x1e, 0x1e, 0x1e)
        mainContentPanel.background = Color(0x1e, 0x1e, 0x1e)

        // 1. TOP HEADER (AI Chat | + New Chat | History | Settings)
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)

        // 2. CHAT CONTAINER & SCROLL PANE
        messageContainer.layout = BoxLayout(messageContainer, BoxLayout.Y_AXIS)
        messageContainer.background = Color(0x1e, 0x1e, 0x1e)
        messageContainer.border = EmptyBorder(16, 16, 16, 16)

        emptyStateHelper = createEmptyStateHelper()
        messageContainer.add(emptyStateHelper)

        scrollPane = JBScrollPane(messageContainer)
        scrollPane.border = null
        scrollPane.background = Color(0x1e, 0x1e, 0x1e)
        scrollPane.viewport.background = Color(0x1e, 0x1e, 0x1e)
        mainContentPanel.add(scrollPane, BorderLayout.CENTER)

        // 3. BOTTOM INPUT WRAPPER
        val inputWrapper = createInputWrapperPanel()
        mainContentPanel.add(inputWrapper, BorderLayout.SOUTH)

        // Add main content panel to center
        add(mainContentPanel, BorderLayout.CENTER)

        // 4. HISTORY SIDEBAR DRAWER (Slide-out panel)
        setupHistoryOverlay()

        // 5. PERMISSION OVERLAY BANNER
        setupPermissionOverlay()

        // 6. INITIALIZE PROVIDERS & LISTENERS
        loadProviders()
        subscribeSettingsListener()
        setupServerListeners()
    }

    // --- HEADER PANEL ---
    private fun createHeaderPanel(): JPanel {
        val header = JPanel(BorderLayout())
        header.background = Color(0x1e, 0x1e, 0x1e)
        header.border = CompoundBorder(
            MatteBorder(0, 0, 1, 0, Color(0x33, 0x33, 0x33)),
            EmptyBorder(10, 14, 10, 14)
        )

        val titleLabel = JLabel("AI Chat")
        titleLabel.font = Font("SansSerif", Font.BOLD, 13)
        titleLabel.foreground = Color(0xd4, 0xd4, 0xd4)
        header.add(titleLabel, BorderLayout.WEST)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        actionsPanel.isOpaque = false

        val newChatBtn = JButton("+ New Chat")
        styleHeaderButton(newChatBtn)
        newChatBtn.addActionListener { startNewChat() }
        actionsPanel.add(newChatBtn)

        val historyBtn = JButton("📜 History")
        styleHeaderButton(historyBtn)
        historyBtn.addActionListener { toggleHistoryDrawer() }
        actionsPanel.add(historyBtn)

        val settingsBtn = JButton("⚙ Settings")
        styleHeaderButton(settingsBtn)
        settingsBtn.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OmniPilotSettingsConfigurable::class.java)
        }
        actionsPanel.add(settingsBtn)

        header.add(actionsPanel, BorderLayout.EAST)
        return header
    }

    private fun styleHeaderButton(btn: JButton) {
        btn.isContentAreaFilled = false
        btn.isBorderPainted = false
        btn.isFocusPainted = false
        btn.font = Font("SansSerif", Font.PLAIN, 12)
        btn.foreground = Color(0x8c, 0x8c, 0x8c)
        btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        btn.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { btn.foreground = Color(0xd4, 0xd4, 0xd4) }
            override fun mouseExited(e: MouseEvent) { btn.foreground = Color(0x8c, 0x8c, 0x8c) }
        })
    }

    // --- EMPTY STATE HELPER ---
    private fun createEmptyStateHelper(): JPanel {
        val helper = JPanel()
        helper.layout = BoxLayout(helper, BoxLayout.Y_AXIS)
        helper.isOpaque = false
        helper.border = EmptyBorder(40, 20, 40, 20)

        val centerLabel = JLabel("<html><center style='color:#7a7a7a; font-size:13px; line-height:2.0'>" +
                "<b>OmniPilot AI Assistant</b><br><br>" +
                "Multiline code completion: <span style='background:#333; color:#d4d4d4; padding:2px 6px; border-radius:4px'>Alt + Shift + \\</span><br>" +
                "Code generation in editor: <span style='background:#333; color:#d4d4d4; padding:2px 6px; border-radius:4px'>Ctrl + \\</span><br>" +
                "AI actions available in editor context menu</center></html>", SwingConstants.CENTER)
        centerLabel.alignmentX = Component.CENTER_ALIGNMENT
        helper.add(centerLabel)

        return helper
    }

    // --- INPUT WRAPPER PANEL ---
    private fun createInputWrapperPanel(): JPanel {
        val wrapper = JPanel(BorderLayout())
        wrapper.background = Color(0x1e, 0x1e, 0x1e)
        wrapper.border = EmptyBorder(12, 14, 14, 14)

        inputContainer.background = Color(0x1e, 0x1e, 0x1e)
        inputContainer.border = LineBorder(Color(0x4d, 0x4d, 0x4d), 1, true)

        inputTextArea.background = Color(0x1e, 0x1e, 0x1e)
        inputTextArea.foreground = Color(0xd4, 0xd4, 0xd4)
        inputTextArea.caretColor = Color.WHITE
        inputTextArea.font = Font("SansSerif", Font.PLAIN, 13)
        inputTextArea.lineWrap = true
        inputTextArea.wrapStyleWord = true
        inputTextArea.border = EmptyBorder(10, 12, 10, 12)

        inputTextArea.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                inputContainer.border = LineBorder(Color(0x35, 0x74, 0xf0), 1, true)
            }
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                inputContainer.border = LineBorder(Color(0x4d, 0x4d, 0x4d), 1, true)
            }
        })

        inputTextArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    handleSendOrStop()
                }
            }
        })

        inputContainer.add(inputTextArea, BorderLayout.CENTER)

        // TOOLBAR INSIDE INPUT CONTAINER
        val toolbar = JPanel(BorderLayout())
        toolbar.isOpaque = false
        toolbar.border = EmptyBorder(4, 8, 8, 8)

        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        leftGroup.isOpaque = false
        styleComboBox(providerCombo)
        providerCombo.addActionListener { onProviderSelected() }
        leftGroup.add(JLabel("Provider:").apply { foreground = Color(0x8c, 0x8c, 0x8c); font = Font("SansSerif", Font.PLAIN, 11) })
        leftGroup.add(providerCombo)
        toolbar.add(leftGroup, BorderLayout.WEST)

        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        rightGroup.isOpaque = false
        styleComboBox(modelCombo)
        styleComboBox(modeCombo)
        rightGroup.add(modelCombo)
        rightGroup.add(modeCombo)

        sendBtn.text = "▲ Send"
        sendBtn.font = Font("SansSerif", Font.BOLD, 12)
        sendBtn.background = Color(0x35, 0x74, 0xf0)
        sendBtn.foreground = Color.WHITE
        sendBtn.isFocusPainted = false
        sendBtn.isBorderPainted = false
        sendBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        sendBtn.addActionListener { handleSendOrStop() }
        rightGroup.add(sendBtn)

        toolbar.add(rightGroup, BorderLayout.EAST)
        inputContainer.add(toolbar, BorderLayout.SOUTH)

        wrapper.add(inputContainer, BorderLayout.CENTER)

        val shareFeedback = JLabel("<html><center style='color:#666; font-size:11px'>Share feedback ↗</center></html>", SwingConstants.CENTER)
        shareFeedback.border = EmptyBorder(6, 0, 0, 0)
        wrapper.add(shareFeedback, BorderLayout.SOUTH)

        return wrapper
    }

    private fun styleComboBox(combo: JComboBox<String>) {
        combo.background = Color(0x3c, 0x3f, 0x41)
        combo.foreground = Color(0xa9, 0xb7, 0xc6)
        combo.font = Font("SansSerif", Font.PLAIN, 12)
        combo.isFocusable = false
    }

    // --- HISTORY SIDEBAR OVERLAY ---
    private fun setupHistoryOverlay() {
        historyOverlay.background = Color(0x1e, 0x1e, 0x1e)
        historyOverlay.border = MatteBorder(0, 1, 0, 0, Color(0x33, 0x33, 0x33))
        historyOverlay.preferredSize = Dimension(280, 0)

        val historyHeader = JPanel(BorderLayout())
        historyHeader.background = Color(0x1e, 0x1e, 0x1e)
        historyHeader.border = CompoundBorder(
            MatteBorder(0, 0, 1, 0, Color(0x33, 0x33, 0x33)),
            EmptyBorder(10, 12, 10, 12)
        )

        val title = JLabel("Chat History")
        title.font = Font("SansSerif", Font.BOLD, 13)
        title.foreground = Color(0xd4, 0xd4, 0xd4)
        historyHeader.add(title, BorderLayout.WEST)

        val closeBtn = JButton("✕")
        styleHeaderButton(closeBtn)
        closeBtn.addActionListener { toggleHistoryDrawer() }
        historyHeader.add(closeBtn, BorderLayout.EAST)

        historyOverlay.add(historyHeader, BorderLayout.NORTH)

        historyListContainer.layout = BoxLayout(historyListContainer, BoxLayout.Y_AXIS)
        historyListContainer.background = Color(0x1e, 0x1e, 0x1e)
        historyListContainer.border = EmptyBorder(8, 8, 8, 8)

        val historyScroll = JBScrollPane(historyListContainer)
        historyScroll.border = null
        historyScroll.viewport.background = Color(0x1e, 0x1e, 0x1e)
        historyOverlay.add(historyScroll, BorderLayout.CENTER)
    }

    private fun toggleHistoryDrawer() {
        isHistoryOpen = !isHistoryOpen
        if (isHistoryOpen) {
            fetchHistoryFromRpc()
            add(historyOverlay, BorderLayout.EAST)
        } else {
            remove(historyOverlay)
        }
        revalidate()
        repaint()
    }

    private fun fetchHistoryFromRpc() {
        historyListContainer.removeAll()
        val rpc = OmniPilotProcessManager.rpcClient ?: return

        rpc.sendRequest("history/list", "{}") { resultJson, errorJson ->
            if (resultJson != null) {
                try {
                    val arr = json.parseToJsonElement(resultJson).jsonArray
                    SwingUtilities.invokeLater {
                        if (arr.isEmpty()) {
                            val emptyLbl = JLabel("No past chats found.").apply { foreground = Color(0x7a, 0x7a, 0x7a) }
                            historyListContainer.add(emptyLbl)
                        } else {
                            for (item in arr) {
                                constObj(item.jsonObject)
                            }
                        }
                        historyListContainer.revalidate()
                        historyListContainer.repaint()
                    }
                } catch (e: Exception) {
                    // Handle JSON parse edge cases
                }
            }
        }
    }

    private fun constObj(obj: kotlinx.serialization.json.JsonObject) {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val titleStr = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chat"

        val itemPanel = JPanel(BorderLayout())
        itemPanel.background = Color(0x1e, 0x1e, 0x1e)
        itemPanel.border = EmptyBorder(6, 8, 6, 8)

        val label = JLabel(titleStr)
        label.foreground = Color(0xa9, 0xb7, 0xc6)
        label.font = Font("SansSerif", Font.PLAIN, 12)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { loadSessionFromRpc(id) }
            override fun mouseEntered(e: MouseEvent) { label.foreground = Color.WHITE }
            override fun mouseExited(e: MouseEvent) { label.foreground = Color(0xa9, 0xb7, 0xc6) }
        })
        itemPanel.add(label, BorderLayout.CENTER)

        val delBtn = JButton("🗑")
        styleHeaderButton(delBtn)
        delBtn.addActionListener { deleteSessionFromRpc(id) }
        itemPanel.add(delBtn, BorderLayout.EAST)

        historyListContainer.add(itemPanel)
    }

    private fun loadSessionFromRpc(id: String) {
        val rpc = OmniPilotProcessManager.rpcClient ?: return
        rpc.sendRequest("history/load", "{\"id\": \"$id\"}") { resultJson, _ ->
            if (resultJson != null) {
                try {
                    val sessObj = json.parseToJsonElement(resultJson).jsonObject
                    val msgsArr = sessObj["messages"]?.jsonArray
                    if (msgsArr != null) {
                        SwingUtilities.invokeLater {
                            currentSessionId = id
                            messageContainer.removeAll()
                            for (m in msgsArr) {
                                val role = m.jsonObject["role"]?.jsonPrimitive?.contentOrNull ?: ""
                                val contentStr = m.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (role == "user") {
                                    appendUserBubble(contentStr)
                                } else if (role == "assistant") {
                                    appendAssistantBubble(contentStr)
                                }
                            }
                            messageContainer.revalidate()
                            messageContainer.repaint()
                            toggleHistoryDrawer()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun deleteSessionFromRpc(id: String) {
        val rpc = OmniPilotProcessManager.rpcClient ?: return
        rpc.sendRequest("history/delete", "{\"id\": \"$id\"}") { _, _ ->
            fetchHistoryFromRpc()
        }
    }

    // --- PERMISSION OVERLAY BANNER ---
    private fun setupPermissionOverlay() {
        permissionOverlay.background = Color(0x2b, 0x2d, 0x30)
        permissionOverlay.border = CompoundBorder(
            LineBorder(Color(0x35, 0x74, 0xf0), 1, true),
            EmptyBorder(12, 14, 12, 14)
        )

        val titleLbl = JLabel("Permission Required for Agent Execution")
        titleLbl.font = Font("SansSerif", Font.BOLD, 13)
        titleLbl.foreground = Color.WHITE
        permissionOverlay.add(titleLbl, BorderLayout.NORTH)

        permToolLabel.font = Font("SansSerif", Font.PLAIN, 12)
        permToolLabel.foreground = Color(0xa9, 0xb7, 0xc6)

        permArgsArea.background = Color(0x1e, 0x1e, 0x1e)
        permArgsArea.foreground = Color(0xd4, 0xd4, 0xd4)
        permArgsArea.font = Font("Monospaced", Font.PLAIN, 11)
        permArgsArea.isEditable = false

        val centerPanel = JPanel(BorderLayout(0, 6))
        centerPanel.isOpaque = false
        centerPanel.add(permToolLabel, BorderLayout.NORTH)
        centerPanel.add(JBScrollPane(permArgsArea), BorderLayout.CENTER)
        permissionOverlay.add(centerPanel, BorderLayout.CENTER)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        actionsPanel.isOpaque = false

        val denyBtn = JButton("Deny").apply { addActionListener { resolvePermission("deny") } }
        val allowWorkspaceBtn = JButton("Allow Workspace").apply { addActionListener { resolvePermission("allowWorkspace") } }
        val allowBtn = JButton("Allow").apply {
            background = Color(0x35, 0x74, 0xf0)
            foreground = Color.WHITE
            addActionListener { resolvePermission("allow") }
        }

        actionsPanel.add(denyBtn)
        actionsPanel.add(allowWorkspaceBtn)
        actionsPanel.add(allowBtn)
        permissionOverlay.add(actionsPanel, BorderLayout.SOUTH)
    }

    private fun resolvePermission(choice: String) {
        mainContentPanel.remove(permissionOverlay)
        mainContentPanel.revalidate()
        mainContentPanel.repaint()
        currentPermFuture?.complete(choice)
        currentPermFuture = null
    }

    // --- PROVIDERS & SETTINGS SYNC ---
    private fun loadProviders() {
        val settings = OmniPilotSettingsState.instance
        providerCombo.removeAllItems()
        modelCombo.removeAllItems()

        if (settings.providers.isEmpty()) {
            providerCombo.addItem("No Providers")
            modelCombo.addItem("No Models")
            return
        }

        for (p in settings.providers) {
            providerCombo.addItem(p.name)
        }

        onProviderSelected()
    }

    private fun onProviderSelected() {
        modelCombo.removeAllItems()
        val settings = OmniPilotSettingsState.instance
        val selectedIndex = providerCombo.selectedIndex
        if (selectedIndex >= 0 && selectedIndex < settings.providers.size) {
            val provider = settings.providers[selectedIndex]
            val modelsList = provider.models.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (modelsList.isEmpty()) {
                modelCombo.addItem("No Models")
            } else {
                for (m in modelsList) {
                    modelCombo.addItem(m)
                }
            }
        }
    }

    private fun subscribeSettingsListener() {
        val connection = ApplicationManager.getApplication().messageBus.connect(project)
        connection.subscribe(OmniPilotSettingsListener.TOPIC, object : OmniPilotSettingsListener {
            override fun onSettingsChanged() {
                SwingUtilities.invokeLater { loadProviders() }
            }
        })
    }

    // --- CHAT SEND & STREAMING ---
    private fun handleSendOrStop() {
        if (isStreaming) {
            stopStreaming()
            return
        }

        val text = inputTextArea.text.trim()
        if (text.isEmpty()) return

        if (emptyStateHelper.parent != null) {
            messageContainer.remove(emptyStateHelper)
        }

        appendUserBubble(text)
        inputTextArea.text = ""

        val selectedProviderIndex = providerCombo.selectedIndex
        val settings = OmniPilotSettingsState.instance
        val provider = if (selectedProviderIndex >= 0 && selectedProviderIndex < settings.providers.size) settings.providers[selectedProviderIndex] else null
        val providerId = provider?.id ?: ""
        val selectedModel = modelCombo.selectedItem?.toString() ?: ""
        val modeStr = when (modeCombo.selectedIndex) {
            1 -> "AGENT"
            2 -> "READ-ONLY"
            else -> "CHAT"
        }

        val editorContext = ContextService.getCurrentContext(project)
        val osInfo = System.getProperty("os.name")

        val rpc = OmniPilotProcessManager.rpcClient
        if (rpc == null) {
            appendAssistantBubble("Error: OmniPilot language server is not connected.")
            return
        }

        setStreamingState(true)
        currentAssistantEditor = appendAssistantBubble("")
        currentAssistantHtml = StringBuilder()

        val jsonPayload = """
            {
                "sessionId": "${currentSessionId}",
                "messages": [{"role": "user", "content": ${quote(text)}}],
                "providerId": "${quote(providerId)}",
                "model": "${quote(selectedModel)}",
                "mode": "${modeStr}",
                "osInfo": "${quote(osInfo)}",
                "editorContext": {
                    "file": ${quote(editorContext.file?.path ?: "")},
                    "content": ${quote(editorContext.content)},
                    "selectedText": ${quote(editorContext.selectedText ?: "")},
                    "language": ${quote(editorContext.language ?: "")}
                }
            }
        """.trimIndent()

        rpc.sendRequest("chat/send", jsonPayload) { _, errorJson ->
            if (errorJson != null) {
                SwingUtilities.invokeLater {
                    updateAssistantContent("Error: $errorJson")
                    setStreamingState(false)
                }
            }
        }
    }

    private fun stopStreaming() {
        val rpc = OmniPilotProcessManager.rpcClient
        rpc?.sendNotification("chat/cancel", "{}")
        setStreamingState(false)
    }

    private fun setStreamingState(streaming: Boolean) {
        isStreaming = streaming
        SwingUtilities.invokeLater {
            if (isStreaming) {
                sendBtn.text = "■ Stop"
                sendBtn.background = Color(0xf4, 0x87, 0x71)
            } else {
                sendBtn.text = "▲ Send"
                sendBtn.background = Color(0x35, 0x74, 0xf0)
            }
        }
    }

    private fun setupServerListeners() {
        val rpc = OmniPilotProcessManager.rpcClient ?: return

        rpc.onNotification("chat/token") { params ->
            val token = params?.get("token")?.jsonPrimitive?.contentOrNull ?: ""
            SwingUtilities.invokeLater {
                currentAssistantHtml.append(token)
                updateAssistantContent(currentAssistantHtml.toString())
            }
        }

        rpc.onNotification("chat/complete") {
            SwingUtilities.invokeLater { setStreamingState(false) }
        }

        rpc.onNotification("chat/error") { params ->
            val errMsg = params?.get("message")?.jsonPrimitive?.contentOrNull ?: "Stream error"
            SwingUtilities.invokeLater {
                updateAssistantContent("Error: $errMsg")
                setStreamingState(false)
            }
        }
    }

    // --- MESSAGE BUBBLE BUILDERS ---
    private fun appendUserBubble(text: String) {
        val bubble = JPanel(BorderLayout())
        bubble.background = Color(0x2b, 0x2d, 0x30)
        bubble.border = CompoundBorder(
            LineBorder(Color(0x3c, 0x3f, 0x41), 1, true),
            EmptyBorder(10, 14, 10, 14)
        )

        val userLabel = JLabel("<html><body style='width:100%; color:#d4d4d4; font-family:sans-serif; font-size:13px;'>" +
                "<b>You</b><br>${escapeHtml(text)}</body></html>")
        bubble.add(userLabel, BorderLayout.CENTER)

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.border = EmptyBorder(0, 40, 12, 0)
        wrapper.add(bubble, BorderLayout.EAST)

        messageContainer.add(wrapper)
        messageContainer.revalidate()
        scrollBottom()
    }

    private fun appendAssistantBubble(initialText: String): JEditorPane {
        val editor = JEditorPane("text/html", "")
        editor.isEditable = false
        editor.background = Color(0x1e, 0x1e, 0x1e)
        editor.border = EmptyBorder(8, 0, 16, 0)

        messageContainer.add(editor)
        messageContainer.revalidate()
        scrollBottom()

        updateAssistantContent(initialText)
        return editor
    }

    private fun updateAssistantContent(markdownText: String) {
        val editor = currentAssistantEditor ?: return
        val html = convertMarkdownToHtml(markdownText)
        editor.text = "<html><body style='color:#a9b7c6; font-family:sans-serif; font-size:13px;'>$html</body></html>"
        messageContainer.revalidate()
        scrollBottom()
    }

    private fun convertMarkdownToHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
    }

    private fun scrollBottom() {
        SwingUtilities.invokeLater {
            val vertical = scrollPane.verticalScrollBar
            vertical.value = vertical.maximum
        }
    }

    private fun startNewChat() {
        currentSessionId = UUID.randomUUID().toString()
        messageContainer.removeAll()
        messageContainer.add(emptyStateHelper)
        messageContainer.revalidate()
        messageContainer.repaint()
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    }

    private fun quote(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }
}
