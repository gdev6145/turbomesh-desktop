package com.turbomesh.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository

@Composable
fun NetworkScreen(repo: MeshRepository) {
    val stats by repo.networkStats.collectAsState()
    val nodes by repo.scanResults.collectAsState()
    val isBridgeConnected by repo.isBridgeConnected.collectAsState()
    val settings by repo.settingsStore.settings.collectAsState()
    val nodeLastSeen by repo.nodeLastSeen.collectAsState()
    val rssiHistory by repo.rssiHistory.collectAsState()

    var selectedNodeId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Stats row
        item {
            Text("Network Overview", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Sent", stats.messagesSent.toString(), modifier = Modifier.weight(1f))
                StatCard("Received", stats.messagesReceived.toString(), modifier = Modifier.weight(1f))
                StatCard("Nearby", stats.nearbyNodes.size.toString(), modifier = Modifier.weight(1f))
                StatCard("BLE", nodes.size.toString(), modifier = Modifier.weight(1f))
            }
        }

        // Bridge card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bridge Relay", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isBridgeConnected) "● Connected to ${settings.bridgeServerUrl}"
                            else if (settings.bridgeEnabled) "○ Disconnected (${settings.bridgeServerUrl})"
                            else "Bridge disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isBridgeConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (settings.bridgeEnabled) {
                        Button(
                            onClick = {
                                if (isBridgeConnected) repo.disconnectBridge()
                                else repo.connectBridge(settings.bridgeServerUrl)
                            },
                            colors = if (isBridgeConnected)
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            else ButtonDefaults.buttonColors()
                        ) { Text(if (isBridgeConnected) "Disconnect" else "Connect") }
                    }
                }
            }
        }

        // RSSI Chart for selected node
        if (selectedNodeId != null) {
            item {
                val history = rssiHistory[selectedNodeId] ?: emptyList()
                val nick = repo.getNickname(selectedNodeId!!).ifBlank { selectedNodeId!!.take(12) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("RSSI — $nick", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { selectedNodeId = null }) { Text("Close") }
                        }
                        RssiChart(
                            readings = history,
                            modifier = Modifier.fillMaxWidth(),
                            lineColor = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }

        // Visible nodes
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Visible Nodes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${nodes.size} found", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (nodes.isEmpty()) {
            item {
                Text("No nodes visible — scan from Devices tab",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(nodes, key = { it.id }) { node ->
                val lastSeenMs = nodeLastSeen[node.id]
                val lastSeenText = if (lastSeenMs != null) relativeTime(lastSeenMs) else "never"
                val history = rssiHistory[node.id] ?: emptyList()

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedNodeId = if (selectedNodeId == node.id) null else node.id }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            val nick = repo.getNickname(node.id).ifBlank { node.name }
                            Text(nick, fontWeight = FontWeight.Medium)
                            Text(node.address, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Last seen: $lastSeenText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            RssiChipSmall(node.rssi)
                            if (history.size > 1) {
                                Text("${history.size} readings",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // Local identity
        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text("Local Node", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(repo.localNodeId, style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("TTL: ${settings.defaultTtl}  •  Heartbeat: ${settings.heartbeatIntervalMs / 1000}s  •  " +
                "Encryption: ${if (settings.encryptionEnabled) "on" else "off"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RssiChipSmall(rssi: Int) {
    val color = when {
        rssi >= -65 -> MaterialTheme.colorScheme.primary
        rssi >= -80 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text("$rssi dBm", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "long ago"
    }
}
