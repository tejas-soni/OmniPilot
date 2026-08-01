package com.omnipilot.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.omnipilot.ui.swing.OmniPilotSwingChatPanel

class OmniPilotToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Set the tool window icon programmatically for compatibility with all IDE versions
        val icon = IconLoader.getIcon("/icons/toolWindowIcon.svg", OmniPilotToolWindowFactory::class.java)
        toolWindow.setIcon(icon)

        // Ensure language server process is started
        com.omnipilot.server.OmniPilotProcessManager.ensureStarted()

        val contentFactory = ContentFactory.getInstance()

        // Auto-detect JCEF: if available, use the rich web UI; otherwise, use native Swing chat
        val isJcefAvailable = try {
            JBCefApp.isSupported()
        } catch (e: Exception) {
            false
        }

        if (isJcefAvailable) {
            val chatPanel = OmniPilotChatPanel(project)
            val content = contentFactory.createContent(chatPanel.content, "", false)
            toolWindow.contentManager.addContent(content)
        } else {
            val swingPanel = OmniPilotSwingChatPanel(project)
            val content = contentFactory.createContent(swingPanel, "", false)
            toolWindow.contentManager.addContent(content)
        }
    }
}

