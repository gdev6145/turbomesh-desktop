package com.turbomesh.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

class StarStore {
    private val prefs = Preferences.userRoot().node("turbomesh/stars")
    private val _starred = MutableStateFlow(loadAll())
    val starred: StateFlow<Set<String>> = _starred.asStateFlow()

    fun star(msgId: String) {
        prefs.putBoolean(msgId, true)
        _starred.value = loadAll()
    }

    fun unstar(msgId: String) {
        prefs.remove(msgId)
        _starred.value = loadAll()
    }

    fun toggle(msgId: String) {
        if (isStarred(msgId)) unstar(msgId) else star(msgId)
    }

    fun isStarred(msgId: String) = _starred.value.contains(msgId)

    private fun loadAll(): Set<String> =
        try { prefs.keys().filter { prefs.getBoolean(it, false) }.toSet() }
        catch (_: Exception) { emptySet() }
}
