package com.omnipilot.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Component
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class OmniPilotSettingsComponent {
    val panel: JPanel
    
    val providerCombo = ComboBox<ProviderConfig>()
    val providerNameText = JBTextField()
    val baseUrlText = JBTextField()
    val modelNameText = JBTextField()
    val apiKeyText = JBPasswordField()
    
    val enableInlineCompletionsCheckbox = JBCheckBox("Enable Inline Completions (Ghost Text)")
    val autoApproveEditsCheckbox = JBCheckBox("Auto-approve file edits in Agent mode")
    val mcpServerUrlText = JBTextField()

    private val addBtn = JButton("Add")
    private val removeBtn = JButton("Remove")
    
    var currentProviders = mutableListOf<ProviderConfig>()
    var apiKeyCache = mutableMapOf<String, String>()
    
    private var isUpdatingUi = false
    private var lastSelectedId: String? = null

    init {
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Select Provider:"))
            add(providerCombo)
            add(addBtn)
            add(removeBtn)
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(topPanel)
            .addLabeledComponent(JBLabel("Provider Name: "), providerNameText, 1, false)
            .addLabeledComponent(JBLabel("API Base URL: "), baseUrlText, 1, false)
            .addLabeledComponent(JBLabel("Models (comma-separated): "), modelNameText, 1, false)
            .addLabeledComponent(JBLabel("API Key: "), apiKeyText, 1, false)
            .addSeparator()
            .addComponent(enableInlineCompletionsCheckbox, 1)
            .addComponent(autoApproveEditsCheckbox, 1)
            .addSeparator()
            .addLabeledComponent(JBLabel("MCP Server URL (Optional): "), mcpServerUrlText, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        providerCombo.addActionListener {
            if (!isUpdatingUi) {
                saveCurrentSelectionToModel()
                loadSelectionToUi()
            }
        }
        
        providerCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ProviderConfig) {
                    text = value.name.ifEmpty { "Unnamed Provider" }
                }
                return this
            }
        }

        // Live update name in combo box
        providerNameText.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { updateComboName() }
            override fun removeUpdate(e: DocumentEvent) { updateComboName() }
            override fun changedUpdate(e: DocumentEvent) { updateComboName() }
            private fun updateComboName() {
                if (!isUpdatingUi) {
                    (providerCombo.selectedItem as? ProviderConfig)?.name = providerNameText.text
                    providerCombo.repaint()
                }
            }
        })

        addBtn.addActionListener {
            saveCurrentSelectionToModel()
            val newProvider = ProviderConfig(id = UUID.randomUUID().toString(), name = "New Provider")
            currentProviders.add(newProvider)
            refreshCombo()
            providerCombo.selectedItem = newProvider
        }

        removeBtn.addActionListener {
            val selected = providerCombo.selectedItem as? ProviderConfig
            if (selected != null && currentProviders.size > 1) {
                currentProviders.remove(selected)
                refreshCombo()
                providerCombo.selectedIndex = 0
            }
        }
    }

    val preferredFocusedComponent: JComponent
        get() = providerCombo

    fun saveCurrentSelectionToModel() {
        val selectedId = lastSelectedId ?: return
        val selected = currentProviders.find { it.id == selectedId } ?: return
        
        selected.name = providerNameText.text.trim()
        selected.baseUrl = baseUrlText.text.trim()
        selected.models = modelNameText.text.trim()
        apiKeyCache[selectedId] = String(apiKeyText.password).trim()
    }

    private fun loadSelectionToUi() {
        isUpdatingUi = true
        val selected = providerCombo.selectedItem as? ProviderConfig
        if (selected != null) {
            lastSelectedId = selected.id
            providerNameText.text = selected.name
            baseUrlText.text = selected.baseUrl
            modelNameText.text = selected.models
            
            // Load key from cache, or fallback to CredentialManager if not cached yet
            apiKeyText.text = apiKeyCache[selected.id] ?: CredentialManager.getApiKey(selected.id) ?: ""
        } else {
            lastSelectedId = null
            providerNameText.text = ""
            baseUrlText.text = ""
            modelNameText.text = ""
            apiKeyText.text = ""
        }
        isUpdatingUi = false
    }

    fun setProviders(providers: List<ProviderConfig>) {
        currentProviders = providers.map { it.copy() }.toMutableList()
        if (currentProviders.isEmpty()) {
            currentProviders.add(ProviderConfig(id = UUID.randomUUID().toString(), name = "Default NIM", baseUrl = "https://integrate.api.nvidia.com/v1"))
        }
        apiKeyCache.clear() // Clear cache on reset
        refreshCombo()
        if (currentProviders.isNotEmpty()) {
            providerCombo.selectedIndex = 0
        }
    }

    private fun refreshCombo() {
        isUpdatingUi = true
        val currentSelectedId = (providerCombo.selectedItem as? ProviderConfig)?.id
        providerCombo.removeAllItems()
        for (p in currentProviders) {
            providerCombo.addItem(p)
        }
        
        val toSelect = currentProviders.find { it.id == currentSelectedId }
        if (toSelect != null) {
            providerCombo.selectedItem = toSelect
        }
        isUpdatingUi = false
        loadSelectionToUi()
    }
}
