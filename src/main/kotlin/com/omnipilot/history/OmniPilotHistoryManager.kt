package com.omnipilot.history

import com.intellij.openapi.application.PathManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object OmniPilotHistoryManager {
    private val historyFile = File(PathManager.getOptionsPath(), "omnipilot_history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun loadStore(): ChatHistoryStore {
        if (!historyFile.exists()) {
            return ChatHistoryStore()
        }
        return try {
            val content = historyFile.readText()
            if (content.isBlank()) ChatHistoryStore() else json.decodeFromString<ChatHistoryStore>(content)
        } catch (e: Exception) {
            e.printStackTrace()
            ChatHistoryStore()
        }
    }

    private fun saveStore(store: ChatHistoryStore) {
        try {
            historyFile.writeText(json.encodeToString(store))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSessions(): List<ChatSession> {
        return loadStore().sessions.sortedByDescending { it.timestamp }
    }

    fun getSession(id: String): ChatSession? {
        return loadStore().sessions.find { it.id == id }
    }

    fun saveSession(session: ChatSession) {
        val store = loadStore()
        val index = store.sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            store.sessions[index] = session
        } else {
            store.sessions.add(0, session)
        }
        saveStore(store)
    }

    fun deleteSession(id: String) {
        val store = loadStore()
        store.sessions.removeIf { it.id == id }
        saveStore(store)
    }

    fun clearAll() {
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }
}
