package com.omnipilot.actions

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.omnipilot.settings.OmniPilotSettingsState

object AgentActionService {

    fun applyAgentEdit(
        project: Project,
        virtualFile: VirtualFile,
        originalContent: String,
        newContent: String
    ) {
        val settings = OmniPilotSettingsState.instance
        
        if (settings.autoApproveEdits) {
            // Apply directly
            executeEdit(project, virtualFile, newContent)
        } else {
            // Show Diff before applying
            showDiffAndPrompt(project, virtualFile, originalContent, newContent)
        }
    }

    private fun showDiffAndPrompt(
        project: Project,
        virtualFile: VirtualFile,
        originalContent: String,
        newContent: String
    ) {
        // In a full implementation, this would show a dialog with the DiffRequest.
        // We can use DiffManager to create a SimpleDiffRequest.
        val diffContentFactory = com.intellij.diff.DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "OmniPilot Suggested Edit",
            diffContentFactory.create(project, originalContent, virtualFile),
            diffContentFactory.create(project, newContent, virtualFile),
            "Current Code",
            "Agent Suggestion"
        )
        
        // This opens the diff window natively
        DiffManager.getInstance().showDiff(project, request)
        
        // The user would need a way to Accept/Reject from this view.
        // For MVP purposes, let's assume they click an "Accept" button we inject or handle via a callback
        // which ultimately calls executeEdit().
        // For now, this just shows the diff.
    }

    fun executeEdit(project: Project, virtualFile: VirtualFile, newContent: String) {
        val document: Document? = FileDocumentManager.getInstance().getDocument(virtualFile)
        
        if (document != null) {
            // WriteCommandAction ensures this action is added to the IntelliJ Undo stack
            WriteCommandAction.runWriteCommandAction(project, "OmniPilot Edit", "OmniPilot", {
                document.setText(newContent)
            })
        }
    }
}
