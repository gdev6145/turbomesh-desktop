package com.turbomesh.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository

@Composable
fun DevicesScreen(repo: MeshRepository) {
    val s = LocalStrings.current
    val nodes by repo.scanResults.collectAsState()
    val isScanning by repo.isScanning.collectAsState()
    val scanError by repo.scanError.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.bleDevices, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
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
                                Column(modifier = Modifier.weight(1f)) {
                                    val nickname = repo.getNickname(node.id)
                                    Text(nickname.ifBlank { node.name }, fontWeight = FontWeight.SemiBold)
                                    Text(node.address, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
