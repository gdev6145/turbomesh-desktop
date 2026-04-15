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
    // Notifications
    val soundEnabled: Boolean = true,
    // Telemetry / usage reporting opt-in
    val telemetryEnabled: Boolean = false,
    // Presence
    val userStatus: String = "available", // available | away | busy | dnd
    // Appearance
    val fontScale: Float = 1.0f,
    // Muted destinations (comma-separated node/group IDs)
    val mutedDestinations: String = "",
    // Quick reply templates (pipe-separated)
    val quickReplies: String = "👋 Hello|OK|On my way|Be right back|Can't talk now",
    // Auto-reply
    val autoReplyEnabled: Boolean = false,
    val autoReplyMessage: String = "I'm currently away. I'll get back to you soon.",
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
        prefs.putBoolean("sound_enabled", s.soundEnabled)
        prefs.put("user_status", s.userStatus)
        prefs.putFloat("font_scale", s.fontScale)
        prefs.put("muted_destinations", s.mutedDestinations)
        prefs.putBoolean("telemetry_enabled", s.telemetryEnabled)
        prefs.put("quick_replies", s.quickReplies)
        prefs.putBoolean("auto_reply_enabled", s.autoReplyEnabled)
        prefs.put("auto_reply_message", s.autoReplyMessage)
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
        soundEnabled = prefs.getBoolean("sound_enabled", true),
        userStatus = prefs.get("user_status", "available"),
        fontScale = prefs.getFloat("font_scale", 1.0f),
        mutedDestinations = prefs.get("muted_destinations", ""),
        telemetryEnabled = prefs.getBoolean("telemetry_enabled", false),
        quickReplies = prefs.get("quick_replies", "👋 Hello|OK|On my way|Be right back|Can't talk now"),
        autoReplyEnabled = prefs.getBoolean("auto_reply_enabled", false),
        autoReplyMessage = prefs.get("auto_reply_message", "I'm currently away. I'll get back to you soon."),
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
