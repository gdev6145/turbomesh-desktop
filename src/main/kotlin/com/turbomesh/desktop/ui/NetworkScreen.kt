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
fun NetworkScreen(repo: MeshRepository) {
    val stats by repo.networkStats.collectAsState()
    val nodes by repo.scanResults.collectAsState()
    val isBridgeConnected by repo.isBridgeConnected.collectAsState()
    val settings by repo.settingsStore.settings.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Network Overview", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // Stats cards
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Sent", stats.messagesSent.toString(), modifier = Modifier.weight(1f))
            StatCard("Received", stats.messagesReceived.toString(), modifier = Modifier.weight(1f))
            StatCard("Nearby", stats.nearbyNodes.size.toString(), modifier = Modifier.weight(1f))
            StatCard("BLE Nodes", nodes.size.toString(), modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        // Bridge status
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bridge Connection", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isBridgeConnected) "Connected to ${settings.bridgeServerUrl}"
                        else if (settings.bridgeEnabled) "Disconnected (${settings.bridgeServerUrl})"
                        else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = if (isBridgeConnected) MaterialTheme.colorScheme.primary.copy(0.12f)
                    else MaterialTheme.colorScheme.error.copy(0.12f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        if (isBridgeConnected) "Online" else "Offline",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isBridgeConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Visible Nodes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (nodes.isEmpty()) {
            Text("No nodes visible — start scanning from Devices tab",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(nodes, key = { it.id }) { node ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                val nick = repo.getNickname(node.id).ifBlank { node.name }
                                Text(nick, fontWeight = FontWeight.Medium)
                                Text(node.address, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${node.rssi} dBm",
                                style = MaterialTheme.typography.labelMedium,
                                color = rssiColor(node.rssi))
                        }
                    }
                }
            }
        }

        // Local identity
        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Local Node ID", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(repo.localNodeId, style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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
private fun rssiColor(rssi: Int) = when {
    rssi >= -65 -> MaterialTheme.colorScheme.primary
    rssi >= -80 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}
