package com.omnipilot.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.UUID

data class ProviderConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var baseUrl: String = "",
    var models: String = ""
)

@State(
    name = "com.omnipilot.settings.OmniPilotSettingsState",
    storages = [Storage("OmniPilotPlugin.xml")]
)
class OmniPilotSettingsState : PersistentStateComponent<OmniPilotSettingsState> {
    
    var providers: MutableList<ProviderConfig> = mutableListOf()
    
    var activeProviderId: String = ""
    
    var enableInlineCompletions: Boolean = true
    var autoApproveEdits: Boolean = false
    var mcpServerUrl: String = ""

    override fun getState(): OmniPilotSettingsState {
        return this
    }

    override fun loadState(state: OmniPilotSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: OmniPilotSettingsState
            get() = ApplicationManager.getApplication().getService(OmniPilotSettingsState::class.java)
    }
}
