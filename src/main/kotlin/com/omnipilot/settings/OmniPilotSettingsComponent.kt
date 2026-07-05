package com.omnipilot.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnActionButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

class OmniPilotSettingsComponent {
    val panel: JPanel
    
    val providerCombo = ComboBox<ProviderConfig>()
    val providerNameText = JBTextField()
    val baseUrlText = JBTextField()
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

    // Models Table
    private val modelsTableModel = object : DefaultTableModel(arrayOf("Enabled", "Model Name"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 0) java.lang.Boolean::class.java else java.lang.String::class.java
        }
        override fun isCellEditable(row: Int, column: Int): Boolean {
            return true
        }
    }
    private val modelsTable = JBTable(modelsTableModel).apply {
        columnModel.getColumn(0).maxWidth = 80
    }

    init {
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Select Provider:"))
            add(providerCombo)
            add(addBtn)
            add(removeBtn)
        }

        val decorator = ToolbarDecorator.createDecorator(modelsTable)
            .setAddAction {
                modelsTableModel.addRow(arrayOf(true, ""))
                val row = modelsTableModel.rowCount - 1
                modelsTable.editCellAt(row, 1)
                modelsTable.editorComponent?.requestFocus()
            }
            .setRemoveAction {
                val selectedRows = modelsTable.selectedRows
                for (i in selectedRows.indices.reversed()) {
                    modelsTableModel.removeRow(selectedRows[i])
                }
            }
            .addExtraAction(object : AnActionButton("Fetch Models", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    fetchModels()
                }
            })

        val modelsPanel = decorator.createPanel()
        modelsPanel.preferredSize = Dimension(400, 200)

        panel = FormBuilder.createFormBuilder()
            .addComponent(topPanel)
            .addLabeledComponent(JBLabel("Provider Name: "), providerNameText, 1, false)
            .addLabeledComponent(JBLabel("API Base URL: "), baseUrlText, 1, false)
            .addLabeledComponent(JBLabel("API Key: "), apiKeyText, 1, false)
            .addLabeledComponent(JBLabel("Models: "), modelsPanel, 1, true)
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

    private fun fetchModels() {
        if (modelsTable.isEditing) {
            modelsTable.cellEditor.stopCellEditing()
        }
        
        val baseUrl = baseUrlText.text.trim().removeSuffix("/")
        val apiKey = String(apiKeyText.password).trim().ifEmpty {
            val currentId = lastSelectedId
            if (currentId != null) {
                apiKeyCache[currentId] ?: CredentialManager.getApiKey(currentId) ?: ""
            } else ""
        }
        
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            Messages.showErrorDialog("Please enter both Base URL and API Key to fetch models.", "Missing Details")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("$baseUrl/models")
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}: ${response.body?.string()}")
                    }
                    
                    val bodyStr = response.body?.string() ?: throw Exception("Empty response body")
                    val jsonObj = Json { ignoreUnknownKeys = true }.parseToJsonElement(bodyStr).jsonObject
                    val dataArray = jsonObj["data"]?.jsonArray ?: throw Exception("Invalid JSON structure: missing 'data' array")
                    
                    val fetchedModels = dataArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                    
                    if (fetchedModels.isEmpty()) {
                        throw Exception("No models found in the response")
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val existingModels = mutableSetOf<String>()
                        for (i in 0 until modelsTableModel.rowCount) {
                            val modelName = modelsTableModel.getValueAt(i, 1) as? String ?: continue
                            if (modelName.isNotBlank()) existingModels.add(modelName.trim())
                        }
                        
                        var addedCount = 0
                        for (fm in fetchedModels) {
                            if (!existingModels.contains(fm)) {
                                modelsTableModel.addRow(arrayOf(false, fm))
                                addedCount++
                            }
                        }
                        
                        if (addedCount == 0) {
                            Messages.showInfoMessage("No new models found. All fetched models are already in the list.", "Fetch Models")
                        } else {
                            Messages.showInfoMessage("Successfully fetched and added $addedCount new models.", "Fetch Models")
                        }
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog("Failed to fetch models:\n${e.message}\n\nPlease add them manually.", "Fetch Error")
                }
            }
        }
    }

    val preferredFocusedComponent: JComponent
        get() = providerCombo

    fun saveCurrentSelectionToModel() {
        val selectedId = lastSelectedId ?: return
        val selected = currentProviders.find { it.id == selectedId } ?: return
        
        if (modelsTable.isEditing) {
            modelsTable.cellEditor.stopCellEditing()
        }

        selected.name = providerNameText.text.trim()
        selected.baseUrl = baseUrlText.text.trim()
        apiKeyCache[selectedId] = String(apiKeyText.password).trim()
        
        val selectedModels = mutableListOf<String>()
        for (i in 0 until modelsTableModel.rowCount) {
            val enabled = modelsTableModel.getValueAt(i, 0) as? Boolean ?: false
            val modelName = modelsTableModel.getValueAt(i, 1) as? String ?: ""
            if (enabled && modelName.isNotBlank()) {
                selectedModels.add(modelName.trim())
            }
        }
        selected.models = selectedModels.joinToString(", ")
    }

    private fun loadSelectionToUi() {
        isUpdatingUi = true
        val selected = providerCombo.selectedItem as? ProviderConfig
        if (selected != null) {
            lastSelectedId = selected.id
            providerNameText.text = selected.name
            baseUrlText.text = selected.baseUrl
            
            modelsTableModel.rowCount = 0
            if (selected.models.isNotBlank()) {
                val parsed = selected.models.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                for (m in parsed) {
                    modelsTableModel.addRow(arrayOf(true, m))
                }
            }
            
            apiKeyText.text = apiKeyCache[selected.id] ?: CredentialManager.getApiKey(selected.id) ?: ""
        } else {
            lastSelectedId = null
            providerNameText.text = ""
            baseUrlText.text = ""
            modelsTableModel.rowCount = 0
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
