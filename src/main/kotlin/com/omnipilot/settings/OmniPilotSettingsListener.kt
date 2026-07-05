package com.omnipilot.settings

import com.intellij.util.messages.Topic

interface OmniPilotSettingsListener {
    companion object {
        val TOPIC = Topic.create("OmniPilotSettingsChanged", OmniPilotSettingsListener::class.java)
    }
    
    fun onSettingsChanged()
}
