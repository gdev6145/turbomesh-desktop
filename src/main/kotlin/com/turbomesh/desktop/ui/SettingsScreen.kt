package com.turbomesh.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.data.MeshSettings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repo: MeshRepository, onThemeToggle: (Boolean) -> Unit) {
    val s = LocalStrings.current
    val settings by repo.settingsStore.settings.collectAsState()
    var draft by remember(settings) { mutableStateOf(settings) }
    var dirty by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPinDialog by remember { mutableStateOf(false) }

    fun update(s: MeshSettings) { draft = s; dirty = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.settingsTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (dirty) {
                    Button(onClick = {
                        repo.updateSettings(draft)
                        onThemeToggle(draft.darkTheme)
                        dirty = false
                    }) { Text(s.save) }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── Mesh Protocol ────────────────────────────────────────────
            SectionHeader(s.meshProtocol)
            SettingRow("${s.defaultTtl} (${draft.defaultTtl})") {
                Slider(value = draft.defaultTtl.toFloat(),
                    onValueChange = { update(draft.copy(defaultTtl = it.toInt())) },
                    valueRange = 1f..15f, steps = 13, modifier = Modifier.weight(1f))
            }
            SettingRow("${s.heartbeatInterval} (${draft.heartbeatIntervalMs / 1000}s)") {
                Slider(value = draft.heartbeatIntervalMs.toFloat(),
                    onValueChange = { update(draft.copy(heartbeatIntervalMs = it.toLong())) },
                    valueRange = 2000f..60000f, modifier = Modifier.weight(1f))
            }
            SettingRow("${s.maxReconnectAttempts} (${draft.maxReconnectAttempts})") {
                Slider(value = draft.maxReconnectAttempts.toFloat(),
                    onValueChange = { update(draft.copy(maxReconnectAttempts = it.toInt())) },
                    valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
            }
            SettingRow("${s.messageRetries} (${draft.messageRetries})") {
                Slider(value = draft.messageRetries.toFloat(),
                    onValueChange = { update(draft.copy(messageRetries = it.toInt())) },
                    valueRange = 0f..5f, steps = 4, modifier = Modifier.weight(1f))
            }

            // ── Security ─────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader(s.security)
            SwitchRow(s.e2eEncryption, s.e2eSubtitle, draft.encryptionEnabled) {
                update(draft.copy(encryptionEnabled = it))
            }

            // ── Bridge ───────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader(s.bridge)
            SwitchRow(s.enableBridge, s.bridgeSubtitle, draft.bridgeEnabled) {
                update(draft.copy(bridgeEnabled = it))
            }
            if (draft.bridgeEnabled) {
                OutlinedTextField(
                    value = draft.bridgeServerUrl,
                    onValueChange = { update(draft.copy(bridgeServerUrl = it)) },
                    label = { Text(s.bridgeServerUrl) },
                    placeholder = { Text(s.bridgePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }

            // ── Appearance ───────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader(s.appearance)
            SwitchRow(s.darkTheme, s.darkThemeSubtitle, draft.darkTheme) {
                update(draft.copy(darkTheme = it))
            }

            // ── Language ─────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader(s.language)
            var langExpanded by remember { mutableStateOf(false) }
            val currentLangLabel = languageOptions.firstOrNull { it.first == draft.appLanguage }?.second ?: "English"
            Box {
                OutlinedButton(onClick = { langExpanded = true }) {
                    Text(currentLangLabel)
                }
                DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                    languageOptions.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                update(draft.copy(appLanguage = code))
                                langExpanded = false
                            }
                        )
                    }
                }
            }

            // ── Auto-Lock ────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader(s.autoLock)
            SwitchRow(s.enableAutoLock, s.autoLockSubtitle, draft.autoLockEnabled) {
                update(draft.copy(autoLockEnabled = it))
            }
            if (draft.autoLockEnabled) {
                val timeoutMin = draft.autoLockTimeoutMs / 60_000L
                SettingRow("${s.autoLockTimeout} ${timeoutMin} ${s.minutes}") {
                    Slider(
                        value = timeoutMin.toFloat(),
                        onValueChange = { update(draft.copy(autoLockTimeoutMs = it.toLong() * 60_000L)) },
                        valueRange = 1f..30f, steps = 28, modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPinDialog = true }) {
                        Text(if (draft.appPin.isBlank()) s.setPin else s.changePin)
                    }
                    if (draft.appPin.isNotBlank()) {
                        OutlinedButton(
                            onClick = { update(draft.copy(appPin = "")) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(s.clearPin) }
                    }
                }
            }

            // ── Export All ───────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader(s.exportAll)
            Text(s.exportAllSubtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                scope.launch {
                    val file = repo.exportAllConversations()
                    snackbarHostState.showSnackbar("${s.exportedTo}: ${file.absolutePath}")
                }
            }) { Text(s.exportAll) }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            Text(s.appVersion, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.appSubtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showPinDialog) {
        PinSetupDialog(
            existingPin = draft.appPin,
            onSave = { newPin ->
                update(draft.copy(appPin = newPin))
                showPinDialog = false
                scope.launch { snackbarHostState.showSnackbar(s.pinSet) }
            },
            onDismiss = { showPinDialog = false }
        )
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
