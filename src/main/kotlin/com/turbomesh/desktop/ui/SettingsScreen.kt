package com.turbomesh.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.data.MeshSettings

@Composable
fun SettingsScreen(repo: MeshRepository, onThemeToggle: (Boolean) -> Unit) {
    val settings by repo.settingsStore.settings.collectAsState()
    var draft by remember(settings) { mutableStateOf(settings) }
    var dirty by remember { mutableStateOf(false) }

    fun update(s: MeshSettings) { draft = s; dirty = true }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (dirty) {
                Button(onClick = {
                    repo.updateSettings(draft)
                    onThemeToggle(draft.darkTheme)
                    dirty = false
                }) { Text("Save") }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Mesh section
        SectionHeader("Mesh Protocol")
        SettingRow("Default TTL (${draft.defaultTtl})") {
            Slider(
                value = draft.defaultTtl.toFloat(),
                onValueChange = { update(draft.copy(defaultTtl = it.toInt())) },
                valueRange = 1f..15f, steps = 13, modifier = Modifier.weight(1f)
            )
        }
        SettingRow("Heartbeat interval (${draft.heartbeatIntervalMs / 1000}s)") {
            Slider(
                value = draft.heartbeatIntervalMs.toFloat(),
                onValueChange = { update(draft.copy(heartbeatIntervalMs = it.toLong())) },
                valueRange = 2000f..60000f, modifier = Modifier.weight(1f)
            )
        }
        SettingRow("Max reconnect attempts (${draft.maxReconnectAttempts})") {
            Slider(
                value = draft.maxReconnectAttempts.toFloat(),
                onValueChange = { update(draft.copy(maxReconnectAttempts = it.toInt())) },
                valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f)
            )
        }
        SettingRow("Message retries (${draft.messageRetries})") {
            Slider(
                value = draft.messageRetries.toFloat(),
                onValueChange = { update(draft.copy(messageRetries = it.toInt())) },
                valueRange = 0f..5f, steps = 4, modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("Security")
        SwitchRow(
            label = "End-to-End Encryption",
            sublabel = "ECDH P-256 + AES-256-GCM",
            checked = draft.encryptionEnabled,
            onCheckedChange = { update(draft.copy(encryptionEnabled = it)) }
        )

        Spacer(Modifier.height(8.dp))
        SectionHeader("Bridge")
        SwitchRow(
            label = "Enable Bridge",
            sublabel = "Forward packets via WebSocket relay",
            checked = draft.bridgeEnabled,
            onCheckedChange = { update(draft.copy(bridgeEnabled = it)) }
        )
        if (draft.bridgeEnabled) {
            OutlinedTextField(
                value = draft.bridgeServerUrl,
                onValueChange = { update(draft.copy(bridgeServerUrl = it)) },
                label = { Text("Bridge Server URL") },
                placeholder = { Text("ws://localhost:8080/relay") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("Appearance")
        SwitchRow(
            label = "Dark Theme",
            sublabel = "Toggle between light and dark",
            checked = draft.darkTheme,
            onCheckedChange = { update(draft.copy(darkTheme = it)) }
        )

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("TurboMesh Desktop", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Compose Multiplatform • BLE Mesh over BlueZ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SettingRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun SwitchRow(label: String, sublabel: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sublabel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
