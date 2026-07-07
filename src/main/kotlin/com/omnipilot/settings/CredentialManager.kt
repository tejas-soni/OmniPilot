package com.omnipilot.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

object CredentialManager {
    private fun createCredentialAttributes(providerId: String): CredentialAttributes {
        return CredentialAttributes(com.intellij.credentialStore.generateServiceName("OmniPilot-$providerId", "omnipilot_user"))
    }

    fun getApiKey(providerId: String): String? {
        val attributes = createCredentialAttributes(providerId)
        return PasswordSafe.instance.getPassword(attributes)
    }

    fun setApiKey(providerId: String, apiKey: String?) {
        val attributes = createCredentialAttributes(providerId)
        if (apiKey.isNullOrEmpty()) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            val credentials = Credentials("omnipilot_user", apiKey)
            PasswordSafe.instance.set(attributes, credentials)
        }
    }
}
