package com.omnipilot.actions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

data class EditorContext(
    val file: VirtualFile?,
    val content: String,
    val selectedText: String?,
    val language: String?
)

object ContextService {
    fun getCurrentContext(project: Project): EditorContext {
        val editorManager = FileEditorManager.getInstance(project)
        val editor: Editor? = editorManager.selectedTextEditor
        val currentFile: VirtualFile? = editor?.virtualFile

        val content = editor?.document?.text ?: ""
        val selectedText = editor?.selectionModel?.selectedText
        val language = currentFile?.extension

        return EditorContext(currentFile, content, selectedText, language)
    }
}
