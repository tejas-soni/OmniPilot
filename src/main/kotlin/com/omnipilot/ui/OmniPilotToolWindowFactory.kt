package com.omnipilot.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class OmniPilotToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Set the tool window icon programmatically for compatibility with all IDE versions
        val icon = IconLoader.getIcon("/icons/toolWindowIcon.svg", OmniPilotToolWindowFactory::class.java)
        toolWindow.setIcon(icon)

        val chatPanel = OmniPilotChatPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(chatPanel.content, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
