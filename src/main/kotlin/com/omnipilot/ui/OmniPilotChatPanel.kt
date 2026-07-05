package com.omnipilot.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.omnipilot.api.ChatMessage
import com.omnipilot.api.OpenAiApiClient
import com.omnipilot.settings.OmniPilotSettingsState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

@Serializable
data class ProviderDto(val id: String, val name: String, val models: List<String>)

@Serializable
data class ChatPayload(val providerId: String, val model: String, val mode: String, val messages: List<com.omnipilot.api.ChatMessage>)

class OmniPilotChatPanel(private val project: Project) {
    @Volatile
    private var currentPermissionFuture: java.util.concurrent.CompletableFuture<String>? = null
    @Volatile
    private var allowAllWorkspace = false

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) {
        try { JBCefBrowser() } catch (e: Exception) { null }
    } else {
        null
    }
    
    val content: JComponent
    private val apiClient = OpenAiApiClient()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (browser != null) {
            content = browser.component
            
            val htmlContent = javaClass.getResourceAsStream("/webview/chat.html")
                ?.bufferedReader()?.readText()
                
            if (htmlContent != null) {
                browser.loadHTML(htmlContent, "http://omnipilot.local/chat.html")
            } else {
                browser.loadHTML("<html><body><h2>Error: chat.html not found in classpath</h2></body></html>")
            }

            setupJsBridge()
        } else {
            content = buildJcefFallbackPanel()
        }

        // Subscribe to settings changes to refresh providers in the UI
        val connection = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(project)
        connection.subscribe(com.omnipilot.settings.OmniPilotSettingsListener.TOPIC, object : com.omnipilot.settings.OmniPilotSettingsListener {
            override fun onSettingsChanged() {
                browser?.cefBrowser?.let {
                    it.executeJavaScript("if (typeof loadProviders === 'function') { loadProviders(); }", it.url, 0)
                }
            }
        })
    }

    private fun buildJcefFallbackPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.background = Color(0x1e1e1e)
        panel.border = BorderFactory.createEmptyBorder(24, 24, 24, 24)

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = GridBagConstraints.RELATIVE
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
            insets = Insets(6, 0, 6, 0)
        }

        // Robot emoji label
        val iconLabel = JLabel("🤖", SwingConstants.CENTER)
        iconLabel.font = Font("Dialog", Font.PLAIN, 40)
        panel.add(iconLabel, gbc)

        // Title
        val titleLabel = JLabel("<html><center>Chromium (JCEF) Not Enabled</center></html>", SwingConstants.CENTER)
        titleLabel.font = Font("Dialog", Font.BOLD, 14)
        titleLabel.foreground = Color(0xd4d4d4)
        panel.add(titleLabel, gbc)

        // Description
        val descLabel = JLabel(
            "<html><center style='color:#8c8c8c;font-size:11px;line-height:1.6'>" +
            "OmniPilot's chat UI requires the JetBrains Runtime<br>" +
            "with JCEF (Chromium Embedded Framework).<br><br>" +
            "Android Studio ships with a runtime that has<br>" +
            "JCEF disabled by default. Fix it in 3 steps:</center></html>",
            SwingConstants.CENTER
        )
        descLabel.foreground = Color(0x8c8c8c)
        panel.add(descLabel, gbc)

        // Steps
        val stepsLabel = JLabel(
            "<html><ol style='color:#a9b7c6;font-size:11px;margin-left:16px'>" +
            "<li>Open <b>Help → Find Action</b> (Ctrl+Shift+A)</li>" +
            "<li>Search for <b>\"Choose Boot Java Runtime\"</b></li>" +
            "<li>Select a runtime labelled <b>jbr-…-jcef</b> and restart</li>" +
            "</ol></html>"
        )
        stepsLabel.foreground = Color(0xa9b7c6)
        panel.add(stepsLabel, gbc)

        // Button to trigger the action directly
        val fixBtn = JButton("⚡ Open Boot Runtime Selector")
        fixBtn.background = Color(0x3574f0)
        fixBtn.foreground = Color.WHITE
        fixBtn.isBorderPainted = false
        fixBtn.isFocusPainted = false
        fixBtn.font = Font("Dialog", Font.BOLD, 12)
        fixBtn.preferredSize = Dimension(240, 34)
        fixBtn.cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        fixBtn.addActionListener {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                // Trigger the "Choose Boot Runtime for the IDE" action directly
                val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                val action = actionManager.getAction("ChooseBootRuntimeAction")
                    ?: actionManager.getAction("com.intellij.ide.actions.ChooseBootRuntimeAction")
                if (action != null) {
                    val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                        action,
                        null,
                        com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
                        com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project)
                    )
                    action.actionPerformed(event)
                } else {
                    // Fallback: open the JetBrains help page
                    com.intellij.ide.BrowserUtil.browse("https://www.jetbrains.com/help/idea/switching-boot-jdk.html")
                }
            }
        }
        panel.add(fixBtn, gbc)

        // Help link
        val helpLabel = JLabel(
            "<html><center><a style='color:#3574f0' href=''>Learn more about JCEF in JetBrains IDEs</a></center></html>",
            SwingConstants.CENTER
        )
        helpLabel.cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        helpLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                com.intellij.ide.BrowserUtil.browse("https://www.jetbrains.com/help/idea/switching-boot-jdk.html")
            }
        })
        panel.add(helpLabel, gbc)

        return panel
    }

    private fun setupJsBridge() {
        if (browser == null) return

        // Query 1: For sending a prompt
        val chatQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        chatQuery.addHandler { requestStr: String ->
            try {
                val payload = json.decodeFromString<ChatPayload>(requestStr)
                
                val allTools = com.omnipilot.api.OmniPilotAgentTools.getAgentTools()
                val tools = if (payload.mode == "readonly") {
                    emptyList()
                } else {
                    allTools
                }
                
                var openFilePath = "None"
                var selectedText = ""
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                    openFilePath = editor?.virtualFile?.path ?: "None"
                    selectedText = editor?.selectionModel?.selectedText ?: ""
                }
                
                val contextInfo = if (openFilePath != "None") {
                    "\n--- CONTEXT ---\nCurrently open file: $openFilePath" + (if (selectedText.isNotEmpty()) "\nSelected text: $selectedText" else "") + "\n-------------\n"
                } else ""
                
                val osInfo = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
                val systemPrompt = if (payload.mode == "readonly") {
                    "You are OmniPilot, an AI coding assistant in IntelliJ. You are in READ-ONLY mode. You can read the project, provide suggestions, and output code in markdown blocks. You CANNOT update code directly or run commands. Your host OS is $osInfo.$contextInfo"
                } else if (payload.mode == "chat") {
                    "You are OmniPilot, an AI coding assistant in IntelliJ. You are in CHAT mode. You can read/write files and run terminal commands, but writes and commands will require user permission. Always ask for permission implicitly by calling tools. Your host OS is $osInfo.$contextInfo"
                } else {
                    "You are OmniPilot, an AI coding agent inside IntelliJ. You have full auto-pilot access to tools to read/write files or run terminal commands to solve the user's task. Your host OS is $osInfo.$contextInfo"
                }
                
                val messagesWithSystem = mutableListOf(com.omnipilot.api.ChatMessage(role = "system", content = systemPrompt))
                messagesWithSystem.addAll(payload.messages)
                
                apiClient.streamChatCompletion(
                    project = project,
                    providerId = payload.providerId,
                    model = payload.model,
                    messages = messagesWithSystem,
                    tools = tools.ifEmpty { null },
                    mode = payload.mode,
                    onPermissionRequest = { toolName, argsStr ->
                        if (allowAllWorkspace) {
                            "ALLOW"
                        } else {
                            currentPermissionFuture = java.util.concurrent.CompletableFuture<String>()
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                val jsArgs = json.encodeToString(argsStr).replace("'", "\\'")
                                browser.cefBrowser.executeJavaScript("if (typeof showPermissionDialog === 'function') { showPermissionDialog('$toolName', $jsArgs); }", browser.cefBrowser.url, 0)
                            }
                            
                            val res = currentPermissionFuture!!.get()
                            currentPermissionFuture = null
                            if (res == "ALLOW_WORKSPACE") {
                                allowAllWorkspace = true
                                "ALLOW"
                            } else {
                                res
                            }
                        }
                    },
                    onToken = { token ->
                        val b64 = injectBase64(token)
                        browser.cefBrowser.executeJavaScript("window.omniPilotReceiveToken(decodeURIComponent(escape(atob('$b64'))));", browser.cefBrowser.url, 0)
                    },
                    onComplete = {
                        browser.cefBrowser.executeJavaScript("window.omniPilotReceiveComplete();", browser.cefBrowser.url, 0)
                    },
                    onError = { error ->
                        val b64 = injectBase64(error.message ?: "Unknown Error")
                        browser.cefBrowser.executeJavaScript("window.omniPilotReceiveError(decodeURIComponent(escape(atob('$b64'))));", browser.cefBrowser.url, 0)
                    }
                )
                
                JBCefJSQuery.Response("Started")
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 500, e.message ?: "Failed to parse request")
            }
        }

        // Query 2: For getting providers
        val cancelQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        cancelQuery.addHandler { _ ->
            apiClient.cancelCurrentStream()
            JBCefJSQuery.Response("OK")
        }

        val attachQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        attachQuery.addHandler { _ ->
            var selectedFile = ""
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().apply {
                    val basePath = project.basePath
                    if (basePath != null) {
                        val projectDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath)
                        if (projectDir != null) {
                            withRoots(projectDir)
                        }
                    }
                }
                val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null)
                if (file != null) {
                    selectedFile = file.path
                }
            }
            JBCefJSQuery.Response(selectedFile)
        }

        val providersQuery = JBCefJSQuery.create(browser)
        providersQuery.addHandler { _ ->
            val settings = OmniPilotSettingsState.instance
            val dtoList = settings.providers.map { 
                val modelList = it.models.split(",").map { m -> m.trim() }.filter { m -> m.isNotEmpty() }
                ProviderDto(it.id, it.name, modelList) 
            }
            val jsonStr = Json.encodeToString(dtoList)
            JBCefJSQuery.Response(jsonStr)
        }

        val permissionQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        permissionQuery.addHandler { responseStr ->
            currentPermissionFuture?.complete(responseStr)
            JBCefJSQuery.Response("OK")
        }

        val insertQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        insertQuery.addHandler { encodedCode ->
            val code = java.net.URLDecoder.decode(encodedCode, Charsets.UTF_8)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                        val caretModel = editor.caretModel
                        val document = editor.document
                        val offset = caretModel.offset
                        document.insertString(offset, code)
                        caretModel.moveToOffset(offset + code.length)
                    }
                }
            }
            JBCefJSQuery.Response("OK")
        }

        val newFileQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        newFileQuery.addHandler { encodedCode ->
            val code = java.net.URLDecoder.decode(encodedCode, Charsets.UTF_8)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    val scratchFile = com.intellij.ide.scratch.ScratchRootType.getInstance().createScratchFile(project, "snippet.txt", com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE, code)
                    if (scratchFile != null) {
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(scratchFile, true)
                    }
                }
            }
            JBCefJSQuery.Response("OK")
        }

        val saveHistoryQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        saveHistoryQuery.addHandler { historyJson ->
            com.intellij.ide.util.PropertiesComponent.getInstance(project).setValue("omniPilot.chatHistory", historyJson)
            JBCefJSQuery.Response("OK")
        }

        val loadHistoryQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        loadHistoryQuery.addHandler { _ ->
            val historyJson = com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue("omniPilot.chatHistory") ?: "[]"
            JBCefJSQuery.Response(historyJson)
        }

        val openSettingsQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        openSettingsQuery.addHandler { _ ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, com.omnipilot.settings.OmniPilotConfigurable::class.java)
            }
            JBCefJSQuery.Response("OK")
        }

        val showDiffQuery = JBCefJSQuery.create(browser as JBCefBrowser)
        showDiffQuery.addHandler { argsStr ->
            try {
                val args = json.decodeFromString<JsonObject>(argsStr)
                val path = args["path"]?.jsonPrimitive?.content
                val newContent = args["content"]?.jsonPrimitive?.content
                if (path != null && newContent != null) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
                        val project = this.project
                        val diffFactory = com.intellij.diff.DiffContentFactory.getInstance()
                        val oldDiffContent = if (virtualFile != null) diffFactory.create(project, virtualFile) else diffFactory.create("")
                        val newDiffContent = diffFactory.create(newContent)
                        val request = com.intellij.diff.requests.SimpleDiffRequest("Review Changes: ${java.io.File(path as String).name}", oldDiffContent, newDiffContent, "Current File", "Proposed Changes")
                        com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
                    }
                }
            } catch(e: Exception) {
                // Ignore
            }
            JBCefJSQuery.Response("OK")
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                val injectScript = """
                    window.omniPilotBackend = function(request) {
                        return new Promise((resolve, reject) => {
                            try {
                                ${chatQuery.inject("request", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotCancelStream = function() {
                        return new Promise((resolve, reject) => {
                            try {
                                ${cancelQuery.inject("''", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotSelectFile = function() {
                        return new Promise((resolve, reject) => {
                            try {
                                ${attachQuery.inject("''", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotGetProviders = function() {
                        return new Promise((resolve, reject) => {
                            try {
                                ${providersQuery.inject("''", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotInsertCode = function(code) {
                        return new Promise((resolve, reject) => {
                            try {
                                ${insertQuery.inject("code", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotNewFile = function(code) {
                        return new Promise((resolve, reject) => {
                            try {
                                ${newFileQuery.inject("code", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotPermission = function(res) {
                        return new Promise((resolve, reject) => {
                            try {
                                ${permissionQuery.inject("res", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotSaveHistory = function(jsonStr) {
                        return new Promise((resolve, reject) => {
                            try {
                                ${saveHistoryQuery.inject("jsonStr", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotLoadHistory = function() {
                        return new Promise((resolve, reject) => {
                            try {
                                ${loadHistoryQuery.inject("''", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotOpenSettings = function() {
                        return new Promise((resolve, reject) => {
                            try {
                                ${openSettingsQuery.inject("''", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.omniPilotShowDiff = function(argsStr) {
                        return new Promise((resolve, reject) => {
                            try {
                                ${showDiffQuery.inject("argsStr", "resolve", "reject")}
                            } catch(e) {
                                reject(e);
                            }
                        });
                    };
                    
                    window.dispatchEvent(new CustomEvent('OmniPilotBridgeReady'));
                """.trimIndent()
                cefBrowser.executeJavaScript(injectScript, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)
    }

    private fun injectBase64(text: String): String {
        return java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    }
}
