package com.turbomesh.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

data class MeshSettings(
    val defaultTtl: Int = 7,
    val heartbeatIntervalMs: Long = 10_000L,
    val maxReconnectAttempts: Int = 5,
    val messageRetries: Int = 3,
    val encryptionEnabled: Boolean = false,
    val bridgeEnabled: Boolean = false,
    val bridgeServerUrl: String = "",
    val darkTheme: Boolean = false,
    // Auto-lock
    val autoLockEnabled: Boolean = false,
    val autoLockTimeoutMs: Long = 5 * 60 * 1000L,
    val appPin: String = "",
    // i18n
    val appLanguage: String = "en",
)

class SettingsStore {
    private val prefs = Preferences.userRoot().node("turbomesh/settings")
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<MeshSettings> = _settings.asStateFlow()

    fun update(s: MeshSettings) {
        prefs.putInt("ttl", s.defaultTtl)
        prefs.putLong("heartbeat_ms", s.heartbeatIntervalMs)
        prefs.putInt("max_reconnect", s.maxReconnectAttempts)
        prefs.putInt("retries", s.messageRetries)
        prefs.putBoolean("encryption", s.encryptionEnabled)
        prefs.putBoolean("bridge_enabled", s.bridgeEnabled)
        prefs.put("bridge_url", s.bridgeServerUrl)
        prefs.putBoolean("dark_theme", s.darkTheme)
        prefs.putBoolean("auto_lock_enabled", s.autoLockEnabled)
        prefs.putLong("auto_lock_timeout_ms", s.autoLockTimeoutMs)
        prefs.put("app_pin", s.appPin)
        prefs.put("app_language", s.appLanguage)
        _settings.value = s
    }

    fun current() = _settings.value

    private fun load() = MeshSettings(
        defaultTtl = prefs.getInt("ttl", 7),
        heartbeatIntervalMs = prefs.getLong("heartbeat_ms", 10_000L),
        maxReconnectAttempts = prefs.getInt("max_reconnect", 5),
        messageRetries = prefs.getInt("retries", 3),
        encryptionEnabled = prefs.getBoolean("encryption", false),
        bridgeEnabled = prefs.getBoolean("bridge_enabled", false),
        bridgeServerUrl = prefs.get("bridge_url", ""),
        darkTheme = prefs.getBoolean("dark_theme", false),
        autoLockEnabled = prefs.getBoolean("auto_lock_enabled", false),
        autoLockTimeoutMs = prefs.getLong("auto_lock_timeout_ms", 5 * 60 * 1000L),
        appPin = prefs.get("app_pin", ""),
        appLanguage = prefs.get("app_language", "en"),
    )
}

class NicknameStore {
    private val prefs = Preferences.userRoot().node("turbomesh/nicknames")
    private val _nicknames = MutableStateFlow(loadAll())
    val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    fun set(nodeId: String, name: String) {
        if (name.isBlank()) prefs.remove(nodeId) else prefs.put(nodeId, name.trim())
        _nicknames.value = loadAll()
    }

    fun get(nodeId: String): String = _nicknames.value[nodeId] ?: ""

    private fun loadAll(): Map<String, String> =
        prefs.keys().associateWith { prefs.get(it, "") }.filterValues { it.isNotBlank() }
}
