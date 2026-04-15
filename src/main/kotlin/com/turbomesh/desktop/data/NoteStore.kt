package com.turbomesh.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

class NoteStore {
    private val prefs = Preferences.userRoot().node("turbomesh/notes")
    private val _notes = MutableStateFlow(loadAll())
    val notes: StateFlow<Map<String, String>> = _notes.asStateFlow()

    fun set(nodeId: String, note: String) {
        if (note.isBlank()) prefs.remove(nodeId) else prefs.put(nodeId, note.trim())
        _notes.value = loadAll()
    }

    fun get(nodeId: String): String = _notes.value[nodeId] ?: ""

    private fun loadAll(): Map<String, String> =
        try { prefs.keys().associateWith { prefs.get(it, "") }.filterValues { it.isNotBlank() } }
        catch (_: Exception) { emptyMap() }
}
