package com.omnipilot.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class OmniPilotConfigurable : Configurable {
    private var component: OmniPilotSettingsComponent? = null

    override fun getDisplayName(): String = "OmniPilot"

    override fun getPreferredFocusedComponent(): JComponent? = component?.preferredFocusedComponent

    override fun createComponent(): JComponent {
        component = OmniPilotSettingsComponent()
        return component!!.panel
    }

    override fun isModified(): Boolean {
        val settings = OmniPilotSettingsState.instance
        val comp = component ?: return false
        
        comp.saveCurrentSelectionToModel()
        
        if (comp.enableInlineCompletionsCheckbox.isSelected != settings.enableInlineCompletions) return true
        if (comp.autoApproveEditsCheckbox.isSelected != settings.autoApproveEdits) return true
        if (comp.mcpServerUrlText.text != settings.mcpServerUrl) return true
        
        if (comp.currentProviders.size != settings.providers.size) return true
        
        // Deep compare providers
        for (i in comp.currentProviders.indices) {
            val p1 = comp.currentProviders[i]
            val p2 = settings.providers[i]
            if (p1 != p2) return true
            
            // Check if API key changed
            val cachedKey = comp.apiKeyCache[p1.id]
            if (cachedKey != null) {
                val currentKey = CredentialManager.getApiKey(p1.id) ?: ""
                if (cachedKey != currentKey) return true
            }
        }
        
        val activeId = (comp.providerCombo.selectedItem as? ProviderConfig)?.id
        if (activeId != null && activeId != settings.activeProviderId) return true

        return false
    }

    override fun apply() {
        val settings = OmniPilotSettingsState.instance
        val comp = component ?: return
        
        comp.saveCurrentSelectionToModel()
        
        settings.enableInlineCompletions = comp.enableInlineCompletionsCheckbox.isSelected
        settings.autoApproveEdits = comp.autoApproveEditsCheckbox.isSelected
        settings.mcpServerUrl = comp.mcpServerUrlText.text
        
        settings.providers = comp.currentProviders.map { it.copy() }.toMutableList()
        settings.activeProviderId = (comp.providerCombo.selectedItem as? ProviderConfig)?.id ?: settings.activeProviderId
        
        // Save cached API keys
        for ((providerId, apiKey) in comp.apiKeyCache) {
            CredentialManager.setApiKey(providerId, apiKey)
        }
        
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .messageBus.syncPublisher(OmniPilotSettingsListener.TOPIC).onSettingsChanged()
    }

    override fun reset() {
        val settings = OmniPilotSettingsState.instance
        val comp = component ?: return
        
        comp.enableInlineCompletionsCheckbox.isSelected = settings.enableInlineCompletions
        comp.autoApproveEditsCheckbox.isSelected = settings.autoApproveEdits
        comp.mcpServerUrlText.text = settings.mcpServerUrl
        
        comp.setProviders(settings.providers)
        
        val activeProvider = comp.currentProviders.find { it.id == settings.activeProviderId }
        if (activeProvider != null) {
            comp.providerCombo.selectedItem = activeProvider
        }
    }

    override fun disposeUIResources() {
        component = null
    }
}
