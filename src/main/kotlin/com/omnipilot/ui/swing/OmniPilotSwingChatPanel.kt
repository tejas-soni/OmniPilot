package com.omnipilot.ui.swing

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.omnipilot.actions.AgentActionService
import com.omnipilot.actions.ContextService
import com.omnipilot.api.ChatMessage
import com.omnipilot.history.ChatSession
import com.omnipilot.history.OmniPilotHistoryManager
import com.omnipilot.server.OmniPilotProcessManager
import com.omnipilot.settings.OmniPilotConfigurable
import com.omnipilot.settings.OmniPilotSettingsListener
import com.omnipilot.settings.OmniPilotSettingsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class OmniPilotSwingChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val json = Json { ignoreUnknownKeys = true }
    private var currentSessionId = UUID.randomUUID().toString()
    private var currentMessages = mutableListOf<ChatMessage>()
    private var isStreaming = false

    // Layered Container for Sliding Sidebar & Permission Glass Pane
    private val layeredPane = JLayeredPane()
    private val mainContentPanel = JPanel(BorderLayout())
    private val messageContainer = JPanel()
    private val scrollPane: JBScrollPane
    private val emptyStateHelper: JPanel
    private val noModelsBanner: JPanel

    // Custom Input Components (Matching chat.html 100%)
    private val inputWrapper = JPanel(BorderLayout())
    private val inputContainer = JPanel(BorderLayout())
    private val inputTextArea = CustomPlaceholderTextArea("Message OmniPilot...")
    private val providerDropdown = CustomSelectDropdown("Loading Agents...")
    private val modelDropdown = CustomSelectDropdown("Loading Models...")
    private val modeDropdown = CustomSelectDropdown("Chat (Ask)")
    private val sendBtn = CustomSendIconButton()

    // History Sidebar
    private val historyOverlay = JPanel(BorderLayout())
    private val historyListContainer = JPanel()
    private var isHistoryOpen = false

    // Permission Glass Pane
    private val permissionOverlay = JPanel(BorderLayout())
    private val permToolLabel = JLabel()
    private val permArgsArea = JTextArea(3, 20)
    private var currentPermFuture: CompletableFuture<String>? = null

    // Streaming
    private var currentAssistantPanel: AssistantMessagePanel? = null

    init {
        background = Color(0x1e, 0x1e, 0x1e)
        mainContentPanel.background = Color(0x1e, 0x1e, 0x1e)

        // 1. TOP HEADER (AI Chat | + New Chat | History Icon | Settings Icon)
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)

        // 2. MESSAGES CONTAINER
        messageContainer.layout = BoxLayout(messageContainer, BoxLayout.Y_AXIS)
        messageContainer.background = Color(0x1e, 0x1e, 0x1e)
        messageContainer.border = EmptyBorder(20, 20, 20, 20)

        emptyStateHelper = createEmptyStateHelper()
        noModelsBanner = createNoModelsBanner()
        messageContainer.add(emptyStateHelper)

        scrollPane = JBScrollPane(messageContainer as Component)
        scrollPane.border = null
        scrollPane.background = Color(0x1e, 0x1e, 0x1e)
        scrollPane.viewport.background = Color(0x1e, 0x1e, 0x1e)
        mainContentPanel.add(scrollPane, BorderLayout.CENTER)

        // 3. INPUT WRAPPER
        val inputWrapPanel = createInputWrapperPanel()
        mainContentPanel.add(inputWrapPanel, BorderLayout.SOUTH)

        // 4. LAYERED PANE SETUP
        layeredPane.layout = null
        layeredPane.add(mainContentPanel, JLayeredPane.DEFAULT_LAYER)

        setupHistoryOverlay()
        layeredPane.add(historyOverlay, JLayeredPane.PALETTE_LAYER)

        setupPermissionOverlay()
        layeredPane.add(permissionOverlay, JLayeredPane.MODAL_LAYER)

        add(layeredPane, BorderLayout.CENTER)

        // Layout bounds resizer
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                updateLayeredBounds()
            }
        })

        // Initial Data Load
        modeDropdown.setItems(listOf("Chat (Ask)", "Agent (Auto)", "Read-Only"))
        loadProviders()
        subscribeSettingsListener()
        setupServerListeners()
    }

    private fun updateLayeredBounds() {
        val w = layeredPane.width
        val h = layeredPane.height
        if (w <= 0 || h <= 0) return

        mainContentPanel.setBounds(0, 0, w, h)

        val sidebarW = 300
        val targetX = if (isHistoryOpen) w - sidebarW else w
        historyOverlay.setBounds(targetX, 0, sidebarW, h)

        if (permissionOverlay.isVisible) {
            permissionOverlay.setBounds(20, 20, w - 40, 180)
        }

        layeredPane.revalidate()
        layeredPane.repaint()
    }

    // --- TOP HEADER ---
    private fun createHeaderPanel(): JPanel {
        val header = JPanel(BorderLayout())
        header.background = Color(0x1e, 0x1e, 0x1e)
        header.border = CompoundBorder(
            MatteBorder(0, 0, 1, 0, Color(0x33, 0x33, 0x33)),
            EmptyBorder(12, 16, 12, 16)
        )

        val titleLabel = JLabel("AI Chat").apply {
            font = Font("Inter", Font.BOLD, 13)
            foreground = Color(0xd4, 0xd4, 0xd4)
        }
        header.add(titleLabel, BorderLayout.WEST)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply { isOpaque = false }

        val newChatBtn = JLabel("+ New Chat").apply {
            font = Font("Inter", Font.PLAIN, 12)
            foreground = Color(0x8c, 0x8c, 0x8c)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { startNewChat() }
                override fun mouseEntered(e: MouseEvent) { foreground = Color(0xd4, 0xd4, 0xd4) }
                override fun mouseExited(e: MouseEvent) { foreground = Color(0x8c, 0x8c, 0x8c) }
            })
        }
        actionsPanel.add(newChatBtn)

        val historyIconBtn = SvgIconButton(SvgType.CLOCK, "Chat History") { toggleHistoryDrawer() }
        actionsPanel.add(historyIconBtn)

        val settingsIconBtn = SvgIconButton(SvgType.MORE, "More Options") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OmniPilotConfigurable::class.java)
        }
        actionsPanel.add(settingsIconBtn)

        header.add(actionsPanel, BorderLayout.EAST)
        return header
    }

    // --- EMPTY STATE HELPER ---
    private fun createEmptyStateHelper(): JPanel {
        val helper = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = EmptyBorder(40, 20, 40, 20)
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            anchor = GridBagConstraints.CENTER
            insets = Insets(6, 0, 6, 0)
        }

        fun createShortcutRow(desc: String, vararg keys: String): JPanel {
            val p = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0)).apply { isOpaque = false }
            p.add(JLabel(desc).apply {
                foreground = Color(0x7a, 0x7a, 0x7a)
                font = Font("Inter", Font.PLAIN, 13)
            })
            for (k in keys) {
                p.add(JLabel(k).apply {
                    foreground = Color(0xd4, 0xd4, 0xd4)
                    background = Color(0x33, 0x33, 0x33)
                    font = Font("Inter", Font.PLAIN, 11)
                    isOpaque = true
                    border = CompoundBorder(
                        CustomRoundedBorder(Color(0x44, 0x44, 0x44), 4),
                        EmptyBorder(2, 6, 2, 6)
                    )
                })
            }
            return p
        }

        helper.add(createShortcutRow("Multiline code completion", "Alt", "Shift", "\\"), gbc)
        helper.add(createShortcutRow("Code generation in the editor", "Ctrl", "\\"), gbc)

        val row3 = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0)).apply { isOpaque = false }
        row3.add(JLabel("AI actions in the editor's context menu").apply {
            foreground = Color(0x7a, 0x7a, 0x7a)
            font = Font("Inter", Font.PLAIN, 13)
        })
        helper.add(row3, gbc)

        val allFeaturesLbl = JLabel("All features").apply {
            foreground = Color(0x35, 0x74, 0xf0)
            font = Font("Inter", Font.PLAIN, 13)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = EmptyBorder(8, 0, 0, 0)
        }
        helper.add(allFeaturesLbl, gbc)

        return helper
    }

    // --- NO MODELS BANNER ---
    private fun createNoModelsBanner(): JPanel {
        val banner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(0x23, 0x2d, 0x3d)
            border = CompoundBorder(
                CustomRoundedBorder(Color(0x35, 0x74, 0xf0), 10),
                EmptyBorder(24, 20, 24, 20)
            )
            isVisible = false
        }

        val iconLbl = JLabel("🤖", SwingConstants.CENTER).apply {
            font = Font("Dialog", Font.PLAIN, 28)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val titleLbl = JLabel("No Models Configured", SwingConstants.CENTER).apply {
            font = Font("Inter", Font.BOLD, 14)
            foreground = Color(0xd4, 0xd4, 0xd4)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val descLbl = JLabel("<html><center style='color:#8c8c8c; font-size:12px; line-height:1.5'>" +
                "This provider has no models added yet.<br>Go to Settings to fetch or add models for it.</center></html>", SwingConstants.CENTER).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val settingsBtn = JButton("⚙ Open Settings").apply {
            background = Color(0x35, 0x74, 0xf0)
            foreground = Color.WHITE
            font = Font("Inter", Font.BOLD, 12)
            isFocusPainted = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, OmniPilotConfigurable::class.java)
            }
        }

        banner.add(iconLbl)
        banner.add(Box.createVerticalStrut(8))
        banner.add(titleLbl)
        banner.add(Box.createVerticalStrut(6))
        banner.add(descLbl)
        banner.add(Box.createVerticalStrut(14))
        banner.add(settingsBtn)

        return banner
    }

    // --- INPUT WRAPPER ---
    private fun createInputWrapperPanel(): JPanel {
        inputWrapper.background = Color(0x1e, 0x1e, 0x1e)
        inputWrapper.border = CompoundBorder(
            MatteBorder(1, 0, 0, 0, Color(0x33, 0x33, 0x33)),
            EmptyBorder(16, 16, 16, 16)
        )

        inputContainer.background = Color(0x1e, 0x1e, 0x1e)
        val defaultBorder = CustomRoundedBorder(Color(0x4d, 0x4d, 0x4d), 8)
        val focusedBorder = CustomRoundedBorder(Color(0x35, 0x74, 0xf0), 8)
        inputContainer.border = defaultBorder

        inputTextArea.background = Color(0x1e, 0x1e, 0x1e)
        inputTextArea.foreground = Color(0xd4, 0xd4, 0xd4)
        inputTextArea.caretColor = Color.WHITE
        inputTextArea.font = Font("Inter", Font.PLAIN, 14)
        inputTextArea.lineWrap = true
        inputTextArea.wrapStyleWord = true
        inputTextArea.border = EmptyBorder(12, 14, 12, 14)
        inputTextArea.rows = 1

        inputTextArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) { inputContainer.border = focusedBorder }
            override fun focusLost(e: FocusEvent?) { inputContainer.border = defaultBorder }
        })

        inputTextArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    handleSendOrStop()
                }
            }
        })

        inputTextArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { updateSendButtonState() }
            override fun removeUpdate(e: DocumentEvent?) { updateSendButtonState() }
            override fun changedUpdate(e: DocumentEvent?) { updateSendButtonState() }
        })

        inputContainer.add(inputTextArea, BorderLayout.CENTER)

        // TOOLBAR INSIDE INPUT CONTAINER
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(6, 12, 10, 12)
        }

        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        val attachBtn = SvgIconButton(SvgType.PLUS, "Attach Context") {
            // Attach context
        }
        leftGroup.add(attachBtn)
        leftGroup.add(providerDropdown)
        providerDropdown.setOnSelect { onProviderSelected() }
        toolbar.add(leftGroup, BorderLayout.WEST)

        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        rightGroup.add(modelDropdown)
        rightGroup.add(modeDropdown)

        sendBtn.setOnClickListener { handleSendOrStop() }
        rightGroup.add(sendBtn)

        toolbar.add(rightGroup, BorderLayout.EAST)
        inputContainer.add(toolbar, BorderLayout.SOUTH)

        inputWrapper.add(inputContainer, BorderLayout.CENTER)

        val shareFeedback = JLabel("Share feedback ↗", SwingConstants.CENTER).apply {
            foreground = Color(0x66, 0x66, 0x66)
            font = Font("Inter", Font.PLAIN, 12)
            border = EmptyBorder(8, 0, 0, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    BrowserUtil.browse("https://github.com")
                }
            })
        }
        inputWrapper.add(shareFeedback, BorderLayout.SOUTH)

        return inputWrapper
    }

    private fun updateSendButtonState() {
        val hasText = inputTextArea.text.trim().isNotEmpty()
        if (!isStreaming) {
            sendBtn.setHasText(hasText)
        }
    }

    // --- HISTORY SIDEBAR OVERLAY ---
    private fun setupHistoryOverlay() {
        historyOverlay.background = Color(0x1e, 0x1e, 0x1e)
        historyOverlay.border = MatteBorder(0, 1, 0, 0, Color(0x33, 0x33, 0x33))

        val historyHeader = JPanel(BorderLayout()).apply {
            background = Color(0x1e, 0x1e, 0x1e)
            border = CompoundBorder(
                MatteBorder(0, 0, 1, 0, Color(0x33, 0x33, 0x33)),
                EmptyBorder(12, 16, 12, 16)
            )
        }

        val title = JLabel("Chat History").apply {
            font = Font("Inter", Font.BOLD, 13)
            foreground = Color(0xd4, 0xd4, 0xd4)
        }
        historyHeader.add(title, BorderLayout.WEST)

        val closeBtn = SvgIconButton(SvgType.CLOSE, "Close History") { toggleHistoryDrawer() }
        historyHeader.add(closeBtn, BorderLayout.EAST)

        historyOverlay.add(historyHeader, BorderLayout.NORTH)

        historyListContainer.layout = BoxLayout(historyListContainer, BoxLayout.Y_AXIS)
        historyListContainer.background = Color(0x1e, 0x1e, 0x1e)
        historyListContainer.border = EmptyBorder(8, 8, 8, 8)

        val historyScroll = JBScrollPane(historyListContainer as Component)
        historyScroll.border = null
        historyScroll.viewport.background = Color(0x1e, 0x1e, 0x1e)
        historyOverlay.add(historyScroll, BorderLayout.CENTER)
    }

    private fun toggleHistoryDrawer() {
        isHistoryOpen = !isHistoryOpen
        if (isHistoryOpen) {
            fetchHistoryFromStore()
        }
        updateLayeredBounds()
    }

    private fun fetchHistoryFromStore() {
        historyListContainer.removeAll()
        val sessions = OmniPilotHistoryManager.getSessions()

        if (sessions.isEmpty()) {
            val emptyLbl = JLabel("No past chats found.").apply {
                foreground = Color(0x88, 0x88, 0x88)
                font = Font("Inter", Font.PLAIN, 12)
                border = EmptyBorder(12, 12, 12, 12)
            }
            historyListContainer.add(emptyLbl)
        } else {
            val now = LocalDate.now()
            val groups = LinkedHashMap<String, MutableList<ChatSession>>()
            groups["Today"] = mutableListOf()
            groups["Yesterday"] = mutableListOf()
            groups["Previous 7 Days"] = mutableListOf()
            groups["Older"] = mutableListOf()

            for (s in sessions) {
                val date = Instant.ofEpochMilli(s.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                val diffDays = ChronoUnit.DAYS.between(date, now)
                when {
                    diffDays == 0L -> groups["Today"]?.add(s)
                    diffDays == 1L -> groups["Yesterday"]?.add(s)
                    diffDays in 2..7 -> groups["Previous 7 Days"]?.add(s)
                    else -> groups["Older"]?.add(s)
                }
            }

            for ((groupTitle, groupSessions) in groups) {
                if (groupSessions.isNotEmpty()) {
                    val titleLbl = JLabel(groupTitle.uppercase()).apply {
                        font = Font("Inter", Font.BOLD, 10)
                        foreground = Color(0x88, 0x88, 0x88)
                        border = EmptyBorder(12, 4, 4, 4)
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                    historyListContainer.add(titleLbl)

                    for (sess in groupSessions) {
                        historyListContainer.add(createHistoryItemRow(sess))
                    }
                }
            }
        }

        historyListContainer.revalidate()
        historyListContainer.repaint()
    }

    private fun createHistoryItemRow(sess: ChatSession): JPanel {
        val row = JPanel(BorderLayout()).apply {
            background = if (sess.id == currentSessionId) Color(0x2d, 0x2d, 0x2d) else Color(0x1e, 0x1e, 0x1e)
            border = EmptyBorder(8, 12, 8, 12)
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val titleLbl = JLabel(sess.title).apply {
            foreground = if (sess.id == currentSessionId) Color(0xd4, 0xd4, 0xd4) else Color(0xa9, 0xb7, 0xc6)
            font = Font("Inter", Font.PLAIN, 13)
        }
        row.add(titleLbl, BorderLayout.CENTER)

        val delBtn = SvgIconButton(SvgType.TRASH, "Delete Session") {
            OmniPilotHistoryManager.deleteSession(sess.id)
            if (sess.id == currentSessionId) {
                startNewChat()
            } else {
                fetchHistoryFromStore()
            }
        }
        delBtn.isVisible = false
        row.add(delBtn, BorderLayout.EAST)

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                row.background = Color(0x2d, 0x2d, 0x2d)
                titleLbl.foreground = Color(0xd4, 0xd4, 0xd4)
                delBtn.isVisible = true
            }
            override fun mouseExited(e: MouseEvent) {
                if (sess.id != currentSessionId) {
                    row.background = Color(0x1e, 0x1e, 0x1e)
                    titleLbl.foreground = Color(0xa9, 0xb7, 0xc6)
                    delBtn.isVisible = false
                }
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.source != delBtn && e.component != delBtn) {
                    loadSession(sess.id)
                }
            }
        })

        return row
    }

    private fun loadSession(id: String) {
        val sess = OmniPilotHistoryManager.getSession(id) ?: return
        currentSessionId = sess.id
        currentMessages = sess.messages.toMutableList()

        messageContainer.removeAll()
        for (m in currentMessages) {
            if (m.role == "user") {
                appendUserBubble(m.content ?: "")
            } else if (m.role == "assistant") {
                appendAssistantBubble(m.content ?: "")
            }
        }

        toggleHistoryDrawer()
        messageContainer.revalidate()
        messageContainer.repaint()
    }

    // --- PERMISSION GLASS PANE ---
    private fun setupPermissionOverlay() {
        permissionOverlay.background = Color(0x2b, 0x2d, 0x30)
        permissionOverlay.border = CompoundBorder(
            CustomRoundedBorder(Color(0x4d, 0x4d, 0x4d), 8),
            EmptyBorder(16, 16, 16, 16)
        )
        permissionOverlay.isVisible = false

        val titleLbl = JLabel("Permission Required").apply {
            font = Font("Inter", Font.BOLD, 14)
            foreground = Color(0xd4, 0xd4, 0xd4)
        }
        permissionOverlay.add(titleLbl, BorderLayout.NORTH)

        permToolLabel.font = Font("Inter", Font.PLAIN, 13)
        permToolLabel.foreground = Color(0xa9, 0xb7, 0xc6)

        permArgsArea.background = Color(0x1e, 0x1e, 0x1e)
        permArgsArea.foreground = Color(0xd4, 0xd4, 0xd4)
        permArgsArea.font = Font("Monospaced", Font.PLAIN, 12)
        permArgsArea.isEditable = false

        val centerPanel = JPanel(BorderLayout(0, 8)).apply { isOpaque = false }
        centerPanel.add(permToolLabel, BorderLayout.NORTH)
        centerPanel.add(JBScrollPane(permArgsArea as Component), BorderLayout.CENTER)
        permissionOverlay.add(centerPanel, BorderLayout.CENTER)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }

        val denyBtn = JButton("Deny").apply {
            background = Color(0x3c, 0x3f, 0x41)
            foreground = Color(0xd4, 0xd4, 0xd4)
            addActionListener { resolvePermission("deny") }
        }
        val allowWorkspaceBtn = JButton("Allow Workspace").apply {
            background = Color(0x3c, 0x3f, 0x41)
            foreground = Color(0xd4, 0xd4, 0xd4)
            addActionListener { resolvePermission("allowWorkspace") }
        }
        val allowBtn = JButton("Allow").apply {
            background = Color(0x35, 0x74, 0xf0)
            foreground = Color.WHITE
            isBorderPainted = false
            addActionListener { resolvePermission("allow") }
        }

        actionsPanel.add(denyBtn)
        actionsPanel.add(allowWorkspaceBtn)
        actionsPanel.add(allowBtn)
        permissionOverlay.add(actionsPanel, BorderLayout.SOUTH)
    }

    private fun resolvePermission(choice: String) {
        permissionOverlay.isVisible = false
        updateLayeredBounds()
        currentPermFuture?.complete(choice)
        currentPermFuture = null
    }

    // --- PROVIDERS & SETTINGS SYNC ---
    private fun loadProviders() {
        val settings = OmniPilotSettingsState.instance
        val providerNames = settings.providers.map { it.name }

        if (providerNames.isEmpty()) {
            providerDropdown.setItems(listOf("No Agents Configured"))
            modelDropdown.setItems(listOf("No models"))
            noModelsBanner.isVisible = true
            inputWrapper.isVisible = false
            return
        }

        providerDropdown.setItems(providerNames)
        onProviderSelected()
    }

    private fun onProviderSelected() {
        val settings = OmniPilotSettingsState.instance
        val selectedIndex = providerDropdown.getSelectedIndex()

        if (selectedIndex >= 0 && selectedIndex < settings.providers.size) {
            val provider = settings.providers[selectedIndex]
            val modelsList = provider.models.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (modelsList.isEmpty()) {
                modelDropdown.setItems(listOf("No models"))
                noModelsBanner.isVisible = true
                inputWrapper.isVisible = false
            } else {
                modelDropdown.setItems(modelsList)
                noModelsBanner.isVisible = false
                inputWrapper.isVisible = true
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
        currentMessages.add(ChatMessage("user", text))
        saveCurrentSession()

        inputTextArea.text = ""
        updateSendButtonState()

        val selectedProviderIndex = providerDropdown.getSelectedIndex()
        val settings = OmniPilotSettingsState.instance
        val provider = if (selectedProviderIndex >= 0 && selectedProviderIndex < settings.providers.size) settings.providers[selectedProviderIndex] else null
        val providerId = provider?.id ?: ""
        val selectedModel = modelDropdown.getSelectedValue()
        val modeStr = when (modeDropdown.getSelectedIndex()) {
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
        currentAssistantPanel = appendAssistantBubble("…")

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
                    currentAssistantPanel?.setContent("Error: $errorJson")
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
            sendBtn.setIsStreaming(isStreaming)
            if (!isStreaming) {
                updateSendButtonState()
                val text = currentAssistantPanel?.getRawContent() ?: ""
                if (text.isNotEmpty()) {
                    currentMessages.add(ChatMessage("assistant", text))
                    saveCurrentSession()
                }
            }
        }
    }

    private fun setupServerListeners() {
        val rpc = OmniPilotProcessManager.rpcClient ?: return

        rpc.onNotification("chat/token") { params ->
            val token = params?.get("token")?.jsonPrimitive?.contentOrNull ?: ""
            SwingUtilities.invokeLater {
                currentAssistantPanel?.appendToken(token)
                scrollBottomIfNear()
            }
        }

        rpc.onNotification("chat/complete") {
            SwingUtilities.invokeLater { setStreamingState(false) }
        }

        rpc.onNotification("chat/error") { params ->
            val errMsg = params?.get("message")?.jsonPrimitive?.contentOrNull ?: "Stream error"
            SwingUtilities.invokeLater {
                currentAssistantPanel?.setContent("Error: $errMsg")
                setStreamingState(false)
            }
        }
    }

    // --- USER & ASSISTANT BUBBLE BUILDERS ---
    private fun appendUserBubble(text: String) {
        val bubble = JPanel(BorderLayout()).apply {
            background = Color(0x2b, 0x2d, 0x30)
            border = CompoundBorder(
                CustomRoundedBorder(Color(0x3c, 0x3f, 0x41), 8),
                EmptyBorder(12, 16, 12, 16)
            )
        }

        val userLabel = JLabel("<html><body style='color:#d4d4d4; font-family:sans-serif; font-size:14px; line-height:1.6;'>" +
                "${escapeHtml(text)}</body></html>")
        bubble.add(userLabel, BorderLayout.CENTER)

        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(0, 50, 20, 0)
        }
        wrapper.add(bubble, BorderLayout.EAST)

        messageContainer.add(wrapper)
        messageContainer.revalidate()
        scrollBottomIfNear()
    }

    private fun appendAssistantBubble(initialText: String): AssistantMessagePanel {
        val panel = AssistantMessagePanel(project, initialText)
        messageContainer.add(panel)
        messageContainer.revalidate()
        scrollBottomIfNear()
        return panel
    }

    private fun scrollBottomIfNear() {
        SwingUtilities.invokeLater {
            val vertical = scrollPane.verticalScrollBar
            val isNearBottom = (vertical.maximum - vertical.value - vertical.visibleAmount) < 80
            if (isNearBottom || currentMessages.size <= 2) {
                vertical.value = vertical.maximum
            }
        }
    }

    private fun startNewChat() {
        currentSessionId = UUID.randomUUID().toString()
        currentMessages.clear()
        messageContainer.removeAll()
        messageContainer.add(emptyStateHelper)
        messageContainer.revalidate()
        messageContainer.repaint()
    }

    private fun saveCurrentSession() {
        if (currentMessages.isEmpty()) return
        val firstUserMsg = currentMessages.find { it.role == "user" }?.content ?: "Chat"
        val title = if (firstUserMsg.length > 30) firstUserMsg.substring(0, 30) + "..." else firstUserMsg

        val session = ChatSession(
            id = currentSessionId,
            title = title,
            timestamp = System.currentTimeMillis(),
            messages = currentMessages.toList()
        )
        OmniPilotHistoryManager.saveSession(session)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    }

    private fun quote(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }
}

// --- CUSTOM ASSISTANT MESSAGE PANEL WITH CODE BLOCKS & HOVER ACTIONS ---

class AssistantMessagePanel(private val project: Project, initialText: String) : JPanel() {
    private val rawText = StringBuilder(initialText)
    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        layout = BorderLayout()
        isOpaque = false
        border = EmptyBorder(0, 0, 20, 0)
        add(contentPanel, BorderLayout.CENTER)
        render()
    }

    fun appendToken(token: String) {
        if (rawText.toString() == "…") {
            rawText.clear()
        }
        rawText.append(token)
        render()
    }

    fun setContent(text: String) {
        rawText.clear()
        rawText.append(text)
        render()
    }

    fun getRawContent(): String = rawText.toString()

    private fun render() {
        contentPanel.removeAll()
        val text = rawText.toString()

        // Split text by markdown code blocks ```lang ... ```
        val codeBlockRegex = Regex("```(?:[a-zA-Z]+)?\n([\\s\\S]*?)```")
        var lastIdx = 0

        for (match in codeBlockRegex.findAll(text)) {
            val start = match.range.first
            if (start > lastIdx) {
                val markdownSnippet = text.substring(lastIdx, start)
                contentPanel.add(createHtmlPane(markdownSnippet))
            }

            val codeSnippet = match.groupValues[1]
            contentPanel.add(CodeBlockPanel(project, codeSnippet))
            lastIdx = match.range.last + 1
        }

        if (lastIdx < text.length) {
            val remainingSnippet = text.substring(lastIdx)
            contentPanel.add(createHtmlPane(remainingSnippet))
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createHtmlPane(markdownSnippet: String): JEditorPane {
        val pane = JEditorPane("text/html", "").apply {
            isEditable = false
            isOpaque = false
            background = Color(0x1e, 0x1e, 0x1e)
            border = null
        }
        val html = markdownSnippet
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
            .replace(Regex("`([^`]+)`"), "<code style='font-family:monospace; font-size:13px;'>$1</code>")
            .replace("\n", "<br>")

        pane.text = "<html><body style='color:#a9b7c6; font-family:sans-serif; font-size:14px; line-height:1.6;'>$html</body></html>"
        return pane
    }
}

// --- CODE BLOCK PANEL WITH HOVER ACTION BUTTONS ---

class CodeBlockPanel(private val project: Project, private val codeStr: String) : JPanel(BorderLayout()) {
    private val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

    init {
        background = Color(0x1e, 0x1f, 0x22)
        border = CompoundBorder(
            CustomRoundedBorder(Color(0x43, 0x45, 0x4a), 6),
            EmptyBorder(10, 12, 10, 12)
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        alignmentX = Component.LEFT_ALIGNMENT

        val codeArea = JTextArea(codeStr).apply {
            isEditable = false
            background = Color(0x1e, 0x1f, 0x22)
            foreground = Color(0xa9, 0xb7, 0xc6)
            font = Font("JetBrains Mono", Font.PLAIN, 13)
            border = null
        }
        add(codeArea, BorderLayout.CENTER)

        // Action Buttons
        val insertBtn = createCodeActionButton("Insert at Cursor") {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                WriteCommandAction.runWriteCommandAction(project, "Insert Code", "OmniPilot", {
                    val offset = editor.caretModel.offset
                    editor.document.insertString(offset, codeStr)
                })
            }
        }
        val copyBtn = createCodeActionButton("Copy") {
            val sel = StringSelection(codeStr)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            insertBtn.text = "Copied!"
            Timer(1500) { insertBtn.text = "Insert at Cursor" }.apply { isRepeats = false; start() }
        }

        actionPanel.add(insertBtn)
        actionPanel.add(copyBtn)
        actionPanel.isVisible = false

        add(actionPanel, BorderLayout.NORTH)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { actionPanel.isVisible = true }
            override fun mouseExited(e: MouseEvent) { actionPanel.isVisible = false }
        })
    }

    private fun createCodeActionButton(text: String, onClick: () -> Unit): JButton {
        return JButton(text).apply {
            background = Color(0x3c, 0x3f, 0x41)
            foreground = Color(0xa9, 0xb7, 0xc6)
            font = Font("Inter", Font.PLAIN, 11)
            border = CompoundBorder(
                CustomRoundedBorder(Color(0x4d, 0x4d, 0x4d), 4),
                EmptyBorder(3, 8, 3, 8)
            )
            isFocusPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = Color(0x4b, 0x4d, 0x4d)
                    foreground = Color.WHITE
                }
                override fun mouseExited(e: MouseEvent) {
                    background = Color(0x3c, 0x3f, 0x41)
                    foreground = Color(0xa9, 0xb7, 0xc6)
                }
            })
            addActionListener { onClick() }
        }
    }
}

// --- CUSTOM DROPDOWN COMPONENT MATCHING chat.html 100% ---

class CustomSelectDropdown(initialValue: String) : JLabel(initialValue) {
    private val items = mutableListOf<String>()
    private var selectedIndex = 0
    private var onSelectCallback: (() -> Unit)? = null
    private var isHovered = false

    init {
        font = Font("Inter", Font.PLAIN, 13)
        foreground = Color(0xa9, 0xb7, 0xc6)
        border = EmptyBorder(4, 8, 4, 20)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { isHovered = true; foreground = Color(0xd4, 0xd4, 0xd4); repaint() }
            override fun mouseExited(e: MouseEvent) { isHovered = false; foreground = Color(0xa9, 0xb7, 0xc6); repaint() }
            override fun mouseClicked(e: MouseEvent) { showPopupMenu() }
        })
    }

    fun setItems(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = 0
        text = if (items.isNotEmpty()) items[0] else ""
        repaint()
    }

    fun setOnSelect(cb: () -> Unit) {
        this.onSelectCallback = cb
    }

    fun getSelectedIndex(): Int = selectedIndex
    fun getSelectedValue(): String = if (selectedIndex >= 0 && selectedIndex < items.size) items[selectedIndex] else ""

    private fun showPopupMenu() {
        if (items.isEmpty()) return
        val popup = JPopupMenu()
        popup.background = Color(0x3c, 0x3f, 0x41)
        popup.border = CustomRoundedBorder(Color(0x4d, 0x4d, 0x4d), 4)

        for ((idx, itemStr) in items.withIndex()) {
            val item = JMenuItem(itemStr).apply {
                font = Font("Inter", Font.PLAIN, 13)
                foreground = Color(0xa9, 0xb7, 0xc6)
                background = if (idx == selectedIndex) Color(0x35, 0x74, 0xf0) else Color(0x3c, 0x3f, 0x41)
                border = EmptyBorder(6, 12, 6, 12)
                addActionListener {
                    selectedIndex = idx
                    this@CustomSelectDropdown.text = itemStr
                    onSelectCallback?.invoke()
                }
            }
            popup.add(item)
        }
        popup.show(this, 0, height + 2)
    }

    override fun paintComponent(g: Graphics) {
        if (isHovered) {
            val g2d = g.create() as Graphics2D
            g2d.color = Color(255, 255, 255, 13)
            g2d.fillRoundRect(0, 0, width, height, 4, 4)
            g2d.dispose()
        }
        super.paintComponent(g)

        // Draw Chevron Arrow SVG icon at right
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = foreground
        val arrowX = width - 14
        val arrowY = height / 2 - 2
        val xPoints = intArrayOf(arrowX, arrowX + 8, arrowX + 4)
        val yPoints = intArrayOf(arrowY, arrowY, arrowY + 4)
        g2.fillPolygon(xPoints, yPoints, 3)
        g2.dispose()
    }
}

// --- CUSTOM SVG ICON BUTTON & SEND BUTTON ---

enum class SvgType { CLOCK, MORE, PLUS, CLOSE, TRASH }

class SvgIconButton(
    private val svgType: SvgType,
    tooltip: String = "",
    private val onClick: () -> Unit
) : JLabel() {
    private var isHovered = false

    init {
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(24, 24)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { isHovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { isHovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { onClick() }
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        if (isHovered) {
            g2.color = Color(255, 255, 255, 13)
            g2.fillRoundRect(0, 0, width, height, 4, 4)
        }

        g2.color = if (isHovered) Color(0xd4, 0xd4, 0xd4) else Color(0x8c, 0x8c, 0x8c)
        val cx = width / 2
        val cy = height / 2

        when (svgType) {
            SvgType.CLOCK -> {
                g2.stroke = BasicStroke(1.5f)
                g2.drawOval(cx - 7, cy - 7, 14, 14)
                g2.drawLine(cx, cy - 4, cx, cy)
                g2.drawLine(cx, cy, cx + 3, cy + 2)
            }
            SvgType.MORE -> {
                g2.fillOval(cx - 2, cy - 6, 4, 4)
                g2.fillOval(cx - 2, cy - 2, 4, 4)
                g2.fillOval(cx - 2, cy + 2, 4, 4)
            }
            SvgType.PLUS -> {
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(cx - 5, cy, cx + 5, cy)
                g2.drawLine(cx, cy - 5, cx, cy + 5)
            }
            SvgType.CLOSE -> {
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(cx - 4, cy - 4, cx + 4, cy + 4)
                g2.drawLine(cx + 4, cy - 4, cx - 4, cy + 4)
            }
            SvgType.TRASH -> {
                g2.stroke = BasicStroke(1.2f)
                g2.drawRect(cx - 4, cy - 2, 8, 8)
                g2.drawLine(cx - 5, cy - 4, cx + 5, cy - 4)
                g2.drawLine(cx - 2, cy - 6, cx + 2, cy - 6)
            }
        }
        g2.dispose()
    }
}

class CustomSendIconButton : JLabel() {
    private var isHasText = false
    private var isStreaming = false
    private var isHovered = false
    private var onClickListener: (() -> Unit)? = null

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(24, 24)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { isHovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { isHovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { onClickListener?.invoke() }
        })
    }

    fun setHasText(hasText: Boolean) {
        this.isHasText = hasText
        repaint()
    }

    fun setIsStreaming(streaming: Boolean) {
        this.isStreaming = streaming
        repaint()
    }

    fun setOnClickListener(onClick: () -> Unit) {
        this.onClickListener = onClick
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val cx = width / 2
        val cy = height / 2

        if (isStreaming) {
            g2.color = if (isHovered) Color(0xff, 0x6b, 0x4a) else Color(0xf4, 0x87, 0x71)
            g2.fillRect(cx - 5, cy - 5, 10, 10)
        } else {
            g2.color = if (isHasText || isHovered) Color(0xd4, 0xd4, 0xd4) else Color(0x8c, 0x8c, 0x8c)
            val xPoints = intArrayOf(cx - 6, cx + 6, cx - 6, cx - 3)
            val yPoints = intArrayOf(cy - 6, cy, cy + 6, cy)
            g2.fillPolygon(xPoints, yPoints, 4)
        }
        g2.dispose()
    }
}

class CustomPlaceholderTextArea(private val placeholder: String) : JTextArea() {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (text.isEmpty()) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0x6c, 0x6c, 0x6c)
            g2.font = font
            val fm = g2.fontMetrics
            val insets = insets
            g2.drawString(placeholder, insets.left, insets.top + fm.ascent)
            g2.dispose()
        }
    }
}

class CustomRoundedBorder(private val color: Color, private val radius: Int) : AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)
    override fun getBorderInsets(c: Component, insets: Insets): Insets {
        insets.set(1, 1, 1, 1)
        return insets
    }
}
