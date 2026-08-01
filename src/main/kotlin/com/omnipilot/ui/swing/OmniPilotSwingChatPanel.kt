package com.omnipilot.ui.swing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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
import javax.swing.border.AbstractBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.border.MatteBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class OmniPilotSwingChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val json = Json { ignoreUnknownKeys = true }
    private var currentSessionId = UUID.randomUUID().toString()
    private var currentMessages = mutableListOf<ChatMessage>()
    private var isStreaming = false

    // UI Structure
    private val layeredPane = JLayeredPane()
    private val mainContentPanel = JPanel(BorderLayout())
    private val messageContainer = JPanel()
    private val scrollPane: JBScrollPane
    private val emptyStateHelper: JPanel
    private val noModelsBanner: JPanel

    // Input Components
    private val inputWrapper = JPanel(BorderLayout())
    private val inputContainer = JPanel(BorderLayout())
    private val inputTextArea = PlaceholderTextArea("Message OmniPilot...")
    private val providerCombo = JComboBox<String>()
    private val modelCombo = JComboBox<String>()
    private val modeCombo = JComboBox<String>(arrayOf("Chat (Ask)", "Agent (Auto)", "Read-Only"))
    private val sendBtn = IconButton()

    // History Sidebar Overlay Panel
    private val historyOverlay = JPanel(BorderLayout())
    private val historyListContainer = JPanel()
    private var isHistoryOpen = false
    private var historySlideX = 300

    // Permission Dialog Glass Pane Overlay
    private val permissionOverlay = JPanel(BorderLayout())
    private val permToolLabel = JLabel()
    private val permArgsArea = JTextArea(3, 20)
    private var currentPermFuture: CompletableFuture<String>? = null

    // Streaming State
    private var currentAssistantEditor: JEditorPane? = null
    private var currentAssistantText = StringBuilder()

    init {
        background = Color(0x1e, 0x1e, 0x1e)
        mainContentPanel.background = Color(0x1e, 0x1e, 0x1e)

        // 1. HEADER
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)

        // 2. CHAT MESSAGES & SCROLL
        messageContainer.layout = BoxLayout(messageContainer, BoxLayout.Y_AXIS)
        messageContainer.background = Color(0x1e, 0x1e, 0x1e)
        messageContainer.border = EmptyBorder(20, 20, 20, 20)

        emptyStateHelper = createEmptyStateHelper()
        noModelsBanner = createNoModelsBanner()

        messageContainer.add(emptyStateHelper)

        scrollPane = JBScrollPane(messageContainer)
        scrollPane.border = null
        scrollPane.background = Color(0x1e, 0x1e, 0x1e)
        scrollPane.viewport.background = Color(0x1e, 0x1e, 0x1e)
        mainContentPanel.add(scrollPane, BorderLayout.CENTER)

        // 3. INPUT WRAPPER
        val inputWrapPanel = createInputWrapperPanel()
        mainContentPanel.add(inputWrapPanel, BorderLayout.SOUTH)

        // 4. LAYERED PANE SETUP FOR SLIDING SIDEBAR & PERMISSION OVERLAY
        layeredPane.layout = null
        layeredPane.add(mainContentPanel, JLayeredPane.DEFAULT_LAYER)

        setupHistoryOverlay()
        layeredPane.add(historyOverlay, JLayeredPane.PALETTE_LAYER)

        setupPermissionOverlay()
        layeredPane.add(permissionOverlay, JLayeredPane.MODAL_LAYER)

        add(layeredPane, BorderLayout.CENTER)

        // Resize listener for LayeredPane bounds
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                updateLayeredBounds()
            }
        })

        // 5. INITIALIZE DATA & LISTENERS
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

    // --- HEADER PANEL ---
    private fun createHeaderPanel(): JPanel {
        val header = JPanel(BorderLayout())
        header.background = Color(0x1e, 0x1e, 0x1e)
        header.border = CompoundBorder(
            MatteBorder(0, 0, 1, 0, Color(0x33, 0x33, 0x33)),
            EmptyBorder(12, 16, 12, 16)
        )

        val titleLabel = JLabel("AI Chat")
        titleLabel.font = Font("Inter", Font.BOLD, 13)
        titleLabel.foreground = Color(0xd4, 0xd4, 0xd4)
        header.add(titleLabel, BorderLayout.WEST)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0))
        actionsPanel.isOpaque = false

        val newChatBtn = JLabel("+ New Chat")
        newChatBtn.font = Font("Inter", Font.PLAIN, 12)
        newChatBtn.foreground = Color(0x8c, 0x8c, 0x8c)
        newChatBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        newChatBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { startNewChat() }
            override fun mouseEntered(e: MouseEvent) { newChatBtn.foreground = Color(0xd4, 0xd4, 0xd4) }
            override fun mouseExited(e: MouseEvent) { newChatBtn.foreground = Color(0x8c, 0x8c, 0x8c) }
        })
        actionsPanel.add(newChatBtn)

        // History Clock Icon Button
        val historyIconBtn = IconButton(IconType.CLOCK, "Chat History") { toggleHistoryDrawer() }
        actionsPanel.add(historyIconBtn)

        // More Options Icon Button
        val moreIconBtn = IconButton(IconType.MORE, "More Options") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OmniPilotConfigurable::class.java)
        }
        actionsPanel.add(moreIconBtn)

        header.add(actionsPanel, BorderLayout.EAST)
        return header
    }

    // --- EMPTY STATE HELPER ---
    private fun createEmptyStateHelper(): JPanel {
        val helper = JPanel(GridBagLayout())
        helper.isOpaque = false
        helper.border = EmptyBorder(40, 20, 40, 20)

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            anchor = GridBagConstraints.CENTER
            insets = Insets(6, 0, 6, 0)
        }

        fun createShortcutRow(desc: String, vararg keys: String): JPanel {
            val p = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0))
            p.isOpaque = false

            val descLbl = JLabel(desc).apply {
                foreground = Color(0x7a, 0x7a, 0x7a)
                font = Font("Inter", Font.PLAIN, 13)
            }
            p.add(descLbl)

            for (k in keys) {
                val kbd = JLabel(k).apply {
                    foreground = Color(0xd4, 0xd4, 0xd4)
                    background = Color(0x33, 0x33, 0x33)
                    font = Font("Inter", Font.PLAIN, 11)
                    isOpaque = true
                    border = CompoundBorder(
                        LineBorder(Color(0x44, 0x44, 0x44), 1, true),
                        EmptyBorder(2, 6, 2, 6)
                    )
                }
                p.add(kbd)
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
        val banner = JPanel()
        banner.layout = BoxLayout(banner, BoxLayout.Y_AXIS)
        banner.background = Color(0x23, 0x2d, 0x3d)
        banner.border = CompoundBorder(
            LineBorder(Color(0x35, 0x74, 0xf0), 1, true),
            EmptyBorder(20, 20, 20, 20)
        )
        banner.isVisible = false

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

    // --- INPUT WRAPPER PANEL ---
    private fun createInputWrapperPanel(): JPanel {
        inputWrapper.background = Color(0x1e, 0x1e, 0x1e)
        inputWrapper.border = CompoundBorder(
            MatteBorder(1, 0, 0, 0, Color(0x33, 0x33, 0x33)),
            EmptyBorder(16, 16, 16, 16)
        )

        inputContainer.background = Color(0x1e, 0x1e, 0x1e)
        val defaultBorder = RoundedBorder(Color(0x4d, 0x4d, 0x4d), 8)
        val focusedBorder = RoundedBorder(Color(0x35, 0x74, 0xf0), 8)
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

        // INPUT TOOLBAR
        val toolbar = JPanel(BorderLayout())
        toolbar.isOpaque = false
        toolbar.border = EmptyBorder(6, 12, 10, 12)

        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        val attachBtn = IconButton(IconType.PLUS, "Attach Context") {
            // Attach context functionality
        }
        leftGroup.add(attachBtn)
        styleComboBox(providerCombo)
        providerCombo.addActionListener { onProviderSelected() }
        leftGroup.add(providerCombo)
        toolbar.add(leftGroup, BorderLayout.WEST)

        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        styleComboBox(modelCombo)
        styleComboBox(modeCombo)
        rightGroup.add(modelCombo)
        rightGroup.add(modeCombo)

        sendBtn.setIconType(IconType.SEND)
        sendBtn.addActionListener { handleSendOrStop() }
        rightGroup.add(sendBtn)

        toolbar.add(rightGroup, BorderLayout.EAST)
        inputContainer.add(toolbar, BorderLayout.SOUTH)

        inputWrapper.add(inputContainer, BorderLayout.CENTER)

        val shareFeedback = JLabel("Share feedback ↗", SwingConstants.CENTER).apply {
            foreground = Color(0x66, 0x66, 0x66)
            font = Font("Inter", Font.PLAIN, 12)
            border = EmptyBorder(8, 0, 0, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        inputWrapper.add(shareFeedback, BorderLayout.SOUTH)

        return inputWrapper
    }

    private fun styleComboBox(combo: JComboBox<String>) {
        combo.background = Color(0x3c, 0x3f, 0x41)
        combo.foreground = Color(0xa9, 0xb7, 0xc6)
        combo.font = Font("Inter", Font.PLAIN, 13)
        combo.isFocusable = false
    }

    private fun updateSendButtonState() {
        val hasText = inputTextArea.text.trim().isNotEmpty()
        if (!isStreaming) {
            sendBtn.setActiveState(hasText)
        }
    }

    // --- HISTORY SIDEBAR OVERLAY ---
    private fun setupHistoryOverlay() {
        historyOverlay.background = Color(0x1e, 0x1e, 0x1e)
        historyOverlay.border = MatteBorder(0, 1, 0, 0, Color(0x33, 0x33, 0x33))

        val historyHeader = JPanel(BorderLayout())
        historyHeader.background = Color(0x1e, 0x1e, 0x1e)
        historyHeader.border = CompoundBorder(
            MatteBorder(0, 0, 1, 0, Color(0x33, 0x33, 0x33)),
            EmptyBorder(12, 16, 12, 16)
        )

        val title = JLabel("Chat History").apply {
            font = Font("Inter", Font.BOLD, 13)
            foreground = Color(0xd4, 0xd4, 0xd4)
        }
        historyHeader.add(title, BorderLayout.WEST)

        val closeBtn = IconButton(IconType.CLOSE, "Close History") { toggleHistoryDrawer() }
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
        val row = JPanel(BorderLayout())
        row.background = if (sess.id == currentSessionId) Color(0x2d, 0x2d, 0x2d) else Color(0x1e, 0x1e, 0x1e)
        row.border = EmptyBorder(8, 12, 8, 12)
        row.maximumSize = Dimension(Int.MAX_VALUE, 36)
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val titleLbl = JLabel(sess.title).apply {
            foreground = if (sess.id == currentSessionId) Color(0xd4, 0xd4, 0xd4) else Color(0xa9, 0xb7, 0xc6)
            font = Font("Inter", Font.PLAIN, 13)
        }
        row.add(titleLbl, BorderLayout.CENTER)

        val delBtn = IconButton(IconType.TRASH, "Delete Session") {
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

    // --- PERMISSION OVERLAY GLASS PANE ---
    private fun setupPermissionOverlay() {
        permissionOverlay.background = Color(0x2b, 0x2d, 0x30)
        permissionOverlay.border = CompoundBorder(
            LineBorder(Color(0x4d, 0x4d, 0x4d), 1, true),
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
        centerPanel.add(JBScrollPane(permArgsArea), BorderLayout.CENTER)
        permissionOverlay.add(centerPanel, BorderLayout.CENTER)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }

        val denyBtn = JButton("Deny").apply {
            background = Color(0x3c, 0x3f, 0x41)
            foreground = Color(0xd4, 0xd4, 0xd4)
            border = LineBorder(Color(0x4d, 0x4d, 0x4d))
            addActionListener { resolvePermission("deny") }
        }
        val allowWorkspaceBtn = JButton("Allow Workspace").apply {
            background = Color(0x3c, 0x3f, 0x41)
            foreground = Color(0xd4, 0xd4, 0xd4)
            border = LineBorder(Color(0x4d, 0x4d, 0x4d))
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
        providerCombo.removeAllItems()
        modelCombo.removeAllItems()

        if (settings.providers.isEmpty()) {
            providerCombo.addItem("No Agents Configured")
            modelCombo.addItem("No models")
            noModelsBanner.isVisible = true
            inputWrapper.isVisible = false
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
                modelCombo.addItem("No models")
                noModelsBanner.isVisible = true
                inputWrapper.isVisible = false
            } else {
                for (m in modelsList) {
                    modelCombo.addItem(m)
                }
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
        currentAssistantText = StringBuilder()
        currentAssistantEditor = appendAssistantBubble("…")

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
                sendBtn.setIconType(IconType.STOP)
            } else {
                sendBtn.setIconType(IconType.SEND)
                updateSendButtonState()
                if (currentAssistantText.isNotEmpty()) {
                    currentMessages.add(ChatMessage("assistant", currentAssistantText.toString()))
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
                currentAssistantText.append(token)
                updateAssistantContent(currentAssistantText.toString())
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
        val bubble = JPanel(BorderLayout()).apply {
            background = Color(0x2b, 0x2d, 0x30)
            border = CompoundBorder(
                LineBorder(Color(0x3c, 0x3f, 0x41), 1, true),
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

    private fun appendAssistantBubble(initialText: String): JEditorPane {
        val editor = JEditorPane("text/html", "").apply {
            isEditable = false
            background = Color(0x1e, 0x1e, 0x1e)
            border = EmptyBorder(0, 0, 20, 0)
        }

        messageContainer.add(editor)
        messageContainer.revalidate()
        scrollBottomIfNear()

        updateAssistantContent(initialText)
        return editor
    }

    private fun updateAssistantContent(markdownText: String) {
        val editor = currentAssistantEditor ?: return
        val html = convertMarkdownToHtml(markdownText)
        editor.text = "<html><body style='color:#a9b7c6; font-family:sans-serif; font-size:14px; line-height:1.6;'>$html</body></html>"
        messageContainer.revalidate()
        scrollBottomIfNear()
    }

    private fun convertMarkdownToHtml(text: String): String {
        var result = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Handle code blocks ```...```
        val codeBlockRegex = Regex("```(?:[a-zA-Z]+)?\n([\\s\\S]*?)```")
        result = codeBlockRegex.replace(result) { match ->
            val code = match.groupValues[1]
            "<pre style='background:#1e1f22; padding:12px; border-radius:6px; border:1px solid #43454a; font-family:monospace; font-size:13px;'><code>$code</code></pre>"
        }

        // Inline code `...`
        result = result.replace(Regex("`([^`]+)`"), "<code style='font-family:monospace; font-size:13px;'>$1</code>")

        // Bold **...**
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")

        // Newlines to <br> outside <pre>
        return result.replace("\n", "<br>")
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

// --- HELPER CUSTOM COMPONENTS ---

enum class IconType { CLOCK, MORE, PLUS, CLOSE, TRASH, SEND, STOP }

class IconButton(
    private var iconType: IconType = IconType.CLOCK,
    tooltip: String = "",
    private var onClickListener: (() -> Unit)? = null
) : JLabel() {
    private var isHovered = false
    private var isActive = false

    init {
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(24, 24)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { isHovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { isHovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { onClickListener?.invoke() }
        })
    }

    fun addActionListener(listener: () -> Unit) {
        this.onClickListener = listener
    }

    fun setIconType(type: IconType) {
        this.iconType = type
        repaint()
    }

    fun setActiveState(active: Boolean) {
        this.isActive = active
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val color = when {
            iconType == IconType.STOP -> Color(0xf4, 0x87, 0x71)
            iconType == IconType.TRASH && isHovered -> Color(0xe5, 0x5a, 0x5a)
            iconType == IconType.TRASH -> Color(0xc7, 0x54, 0x50)
            isHovered -> Color(0xd4, 0xd4, 0xd4)
            isActive -> Color(0xd4, 0xd4, 0xd4)
            else -> Color(0x8c, 0x8c, 0x8c)
        }
        g2.color = color

        val cx = width / 2
        val cy = height / 2

        when (iconType) {
            IconType.CLOCK -> {
                g2.stroke = BasicStroke(1.5f)
                g2.drawOval(cx - 7, cy - 7, 14, 14)
                g2.drawLine(cx, cy - 4, cx, cy)
                g2.drawLine(cx, cy, cx + 3, cy + 2)
            }
            IconType.MORE -> {
                g2.fillOval(cx - 2, cy - 6, 4, 4)
                g2.fillOval(cx - 2, cy - 2, 4, 4)
                g2.fillOval(cx - 2, cy + 2, 4, 4)
            }
            IconType.PLUS -> {
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(cx - 5, cy, cx + 5, cy)
                g2.drawLine(cx, cy - 5, cx, cy + 5)
            }
            IconType.CLOSE -> {
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(cx - 4, cy - 4, cx + 4, cy + 4)
                g2.drawLine(cx + 4, cy - 4, cx - 4, cy + 4)
            }
            IconType.TRASH -> {
                g2.stroke = BasicStroke(1.2f)
                g2.drawRect(cx - 4, cy - 2, 8, 8)
                g2.drawLine(cx - 5, cy - 4, cx + 5, cy - 4)
                g2.drawLine(cx - 2, cy - 6, cx + 2, cy - 6)
            }
            IconType.SEND -> {
                val xPoints = intArrayOf(cx - 6, cx + 6, cx - 6, cx - 3)
                val yPoints = intArrayOf(cy - 6, cy, cy + 6, cy)
                g2.fillPolygon(xPoints, yPoints, 4)
            }
            IconType.STOP -> {
                g2.fillRect(cx - 5, cy - 5, 10, 10)
            }
        }
        g2.dispose()
    }
}

class PlaceholderTextArea(private val placeholder: String) : JTextArea() {
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

class RoundedBorder(private val color: Color, private val radius: Int) : AbstractBorder() {
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
