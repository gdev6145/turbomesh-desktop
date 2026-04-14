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
import com.turbomesh.desktop.mesh.MeshNode

@Composable
fun DevicesScreen(repo: MeshRepository) {
    val nodes by repo.scanResults.collectAsState()
    val isScanning by repo.isScanning.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BLE Devices", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = { if (isScanning) repo.stopScan() else repo.startScan() }) {
                Text(if (isScanning) "Stop" else "Scan")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No mesh devices found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nodes, key = { it.id }) { node ->
                    NodeCard(node, repo)
                }
            }
        }
    }
}

@Composable
private fun NodeCard(node: MeshNode, repo: MeshRepository) {
    var showNicknameDialog by remember { mutableStateOf(false) }
    val nickname = repo.getNickname(node.id)
    val displayName = if (nickname.isNotBlank()) nickname else node.name

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName, fontWeight = FontWeight.SemiBold)
                    Text(node.address, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    RssiChip(node.rssi)
                    if (node.isConnected) {
                        Text("Connected", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showNicknameDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("Nickname")
                }
                Button(onClick = {
                    repo.sendMessage(node.id, "PING")
                }, modifier = Modifier.weight(1f)) {
                    Text("Ping")
                }
            }
        }
    }

    if (showNicknameDialog) {
        NicknameDialog(
            currentName = nickname,
            onConfirm = { name -> repo.setNickname(node.id, name); showNicknameDialog = false },
            onDismiss = { showNicknameDialog = false }
        )
    }
}

@Composable
private fun RssiChip(rssi: Int) {
    val color = when {
        rssi >= -65 -> MaterialTheme.colorScheme.primary
        rssi >= -80 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text("$rssi dBm", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun NicknameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Nickname") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("Nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
