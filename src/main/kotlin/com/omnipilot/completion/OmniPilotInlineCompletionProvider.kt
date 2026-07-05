package com.omnipilot.completion

import com.intellij.codeInsight.inline.completion.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.omnipilot.settings.OmniPilotSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OmniPilotInlineCompletionProvider : InlineCompletionProvider {
    override suspend fun getProposals(request: InlineCompletionRequest): Flow<InlineCompletionElement> = flow {
        val settings = OmniPilotSettingsState.instance
        if (!settings.enableInlineCompletions) return@flow

        val editor: Editor = request.editor
        val project: Project? = editor.project
        if (project == null) return@flow

        // In a real implementation:
        // 1. Gather text before and after cursor from request.document
        // 2. Call OpenAiApiClient to get a completion (debounced)
        // 3. Emit the completion as an InlineCompletionElement

        // For MVP Scaffold, we just emit a placeholder if triggered manually or in specific context
        // emit(InlineCompletionElement(" // OmniPilot ghost text suggestion"))
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return OmniPilotSettingsState.instance.enableInlineCompletions
    }
}
