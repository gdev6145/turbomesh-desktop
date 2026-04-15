package com.turbomesh.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository

@Composable
fun DevicesScreen(repo: MeshRepository) {
    val s = LocalStrings.current
    val nodes by repo.scanResults.collectAsState()
    val isScanning by repo.isScanning.collectAsState()
    val scanError by repo.scanError.collectAsState()
    val nodeLastSeen by repo.nodeLastSeen.collectAsState()
    val settings by repo.settingsStore.settings.collectAsState()

    val myStatusLabel = when (settings.userStatus) {
        "away" -> s.statusAway
        "busy" -> s.statusBusy
        "dnd"  -> s.statusDnd
        else   -> s.statusAvailable
    }
    val myStatusColor = when (settings.userStatus) {
        "away" -> androidx.compose.ui.graphics.Color(0xFFFFCA28)
        "busy" -> androidx.compose.ui.graphics.Color(0xFFFF7043)
        "dnd"  -> androidx.compose.ui.graphics.Color(0xFFEF5350)
        else   -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.bleDevices, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Surface(
                color = myStatusColor.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(myStatusColor))
                    Text(myStatusLabel, style = MaterialTheme.typography.labelSmall, color = myStatusColor)
                }
            }
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            if (nodes.isNotEmpty()) {
                OutlinedButton(
                    onClick = { repo.clearScanResults() },
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(s.clearDevices) }
            }
            Button(onClick = { if (isScanning) repo.stopScan() else repo.startScan() }) {
                Text(if (isScanning) s.stopScan else s.scan)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Error banner
        scanError?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.scanFailed, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(err, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    TextButton(onClick = { repo.clearScanResults() }) { Text(s.dismiss) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (isScanning) s.scanningForDevices else s.pressScanPrompt,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!isScanning && scanError == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.requiresBluetooth, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            Text(
                "${nodes.size} ${if (nodes.size != 1) s.devicesFound else s.deviceFound}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nodes, key = { it.id }) { node ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val lastSeen = nodeLastSeen[node.id]
                                PresenceDot(lastSeenMs = lastSeen)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val nickname = repo.getNickname(node.id)
                                    Text(nickname.ifBlank { node.name }, fontWeight = FontWeight.SemiBold)
                                    Text(node.address, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (lastSeen != null) {
                                        val diffMs = System.currentTimeMillis() - lastSeen
                                        val label = when {
                                            diffMs < 30_000 -> s.online
                                            diffMs < 300_000 -> s.idle
                                            else -> s.offline
                                        }
                                        Text(label, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                // Battery level badge
                                if (node.batteryLevel >= 0) {
                                    BatteryChip(node.batteryLevel)
                                    Spacer(Modifier.width(6.dp))
                                }
                                RssiChip(node.rssi)
                            }
                            Spacer(Modifier.height(8.dp))
                            NodeActions(node = node, repo = repo)
                            // Node notes
                            NodeNotes(node = node, repo = repo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeActions(node: com.turbomesh.desktop.mesh.MeshNode, repo: MeshRepository) {
    val s = LocalStrings.current
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    val nickname = repo.getNickname(node.id)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { showNicknameDialog = true },
            modifier = Modifier.weight(1f)
        ) { Text(if (nickname.isBlank()) s.nickname else s.rename) }
        Button(
            onClick = { repo.sendMessage(node.id, "PING") },
            modifier = Modifier.weight(1f)
        ) { Text(s.ping) }
        Button(
            onClick = { repo.broadcastMessage("Hello from ${repo.localNodeId}") },
            modifier = Modifier.weight(1f)
        ) { Text(s.wave) }
        OutlinedButton(
            onClick = { showPairingDialog = true },
            modifier = Modifier.weight(1f)
        ) { Text(s.pair) }
    }

    if (showNicknameDialog) {
        var text by remember { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text(s.setNickname) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(s.nickname) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { repo.setNickname(node.id, text); showNicknameDialog = false }) {
                    Text(s.save)
                }
            },
            dismissButton = { TextButton(onClick = { showNicknameDialog = false }) { Text(s.cancel) } }
        )
    }

    if (showPairingDialog) {
        PairingDialog(
            repo = repo,
            targetNodeId = node.id,
            onDismiss = { showPairingDialog = false },
        )
    }
}

@Composable
private fun NodeNotes(node: com.turbomesh.desktop.mesh.MeshNode, repo: MeshRepository) {
    val s = LocalStrings.current
    val notes by repo.noteStore.notes.collectAsState()
    var text by remember(node.id) { mutableStateOf(notes[node.id] ?: "") }
    var expanded by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    Column {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
        ) {
            Text(
                if (expanded) "▲ ${s.notes}" else "▼ ${s.notes}${if (text.isNotBlank()) " •" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(s.notesPlaceholder, style = MaterialTheme.typography.bodySmall) },
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    repo.noteStore.set(node.id, text)
                }) { Text(s.save, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun PresenceDot(lastSeenMs: Long?) {
    val now = System.currentTimeMillis()
    val color = when {
        lastSeenMs == null -> Color(0xFF9E9E9E)
        now - lastSeenMs < 30_000 -> Color(0xFF4CAF50)
        now - lastSeenMs < 300_000 -> Color(0xFFFFCA28)
        else -> Color(0xFF9E9E9E)
    }
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun BatteryChip(level: Int) {
    val icon = when {
        level >= 80 -> "🔋"
        level >= 40 -> "🪫"
        else -> "⚠️"
    }
    val color = when {
        level >= 60 -> MaterialTheme.colorScheme.primary
        level >= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(
            "$icon $level%",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun RssiChip(rssi: Int) {
    val (color, label) = when {
        rssi >= -65 -> MaterialTheme.colorScheme.primary to "Strong"
        rssi >= -80 -> MaterialTheme.colorScheme.tertiary to "Fair"
        else -> MaterialTheme.colorScheme.error to "Weak"
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text("$rssi dBm • $label",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = color)
    }
}
