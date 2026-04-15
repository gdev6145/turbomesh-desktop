package com.turbomesh.desktop.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal telemetry writer that respects the user's opt-in flag.
 * Writes one JSON object per-line to ~/.turbomesh/telemetry.log when enabled.
 */
class Telemetry(private val settingsStore: SettingsStore) {
    private val enabled = AtomicBoolean(settingsStore.current().telemetryEnabled)
    private val dir = File(System.getProperty("user.home"), ".turbomesh")
    private val file = File(dir, "telemetry.log")
    private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun logEvent(name: String, props: Map<String, String> = emptyMap()) {
        try {
            val enabledNow = settingsStore.current().telemetryEnabled
            if (!enabledNow) return
            val ts = sdf.format(Date())
            val propsPart = props.entries.joinToString(",") { (k, v) -> "\"${escape(k)}\":\"${escape(v)}\"" }
            val line = "{\"ts\":\"$ts\",\"event\":\"${escape(name)}\",$propsPart}\n"
            file.appendText(line)
        } catch (_: Exception) {
            // Do not let telemetry errors affect app
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
