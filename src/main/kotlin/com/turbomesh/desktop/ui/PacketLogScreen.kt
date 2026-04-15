package com.turbomesh.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.mesh.PacketDirection
import com.turbomesh.desktop.mesh.PacketLogEntry
import com.turbomesh.desktop.mesh.MeshMessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PacketLogScreen(repo: MeshRepository) {
    val s = LocalStrings.current
    val allEntries by repo.packetLog.collectAsState()
    var dirFilter by remember { mutableStateOf<PacketDirection?>(null) }
    var typeFilter by remember { mutableStateOf<MeshMessageType?>(null) }
    var typeExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val filtered = remember(allEntries, dirFilter, typeFilter) {
        allEntries
            .let { list -> dirFilter?.let { d -> list.filter { it.direction == d } } ?: list }
            .let { list -> typeFilter?.let { t -> list.filter { it.type == t } } ?: list }
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Title + actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.packetLogTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text("${allEntries.size} entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = { repo.clearPacketLog() }) { Text(s.clearLog) }
        }
        Spacer(Modifier.height(8.dp))

        // Filter row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = dirFilter == null, onClick = { dirFilter = null }, label = { Text(s.filterAll) })
            FilterChip(selected = dirFilter == PacketDirection.INBOUND, onClick = { dirFilter = PacketDirection.INBOUND },
                label = { Text("▼ ${s.filterInbound}") })
            FilterChip(selected = dirFilter == PacketDirection.OUTBOUND, onClick = { dirFilter = PacketDirection.OUTBOUND },
                label = { Text("▲ ${s.filterOutbound}") })
            Spacer(Modifier.width(8.dp))
            // Type filter
            Box {
                OutlinedButton(onClick = { typeExpanded = true }) {
                    Text(typeFilter?.name ?: "All Types")
                }
                DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                    DropdownMenuItem(text = { Text("All Types") }, onClick = { typeFilter = null; typeExpanded = false })
                    MeshMessageType.values().forEach { t ->
                        DropdownMenuItem(text = { Text(t.name) }, onClick = { typeFilter = t; typeExpanded = false })
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.noPackets, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered, key = { "${it.timestampMs}_${it.msgId}_${it.direction}" }) { entry ->
                    PacketRow(entry)
                }
            }
        }
    }
}

@Composable
private fun PacketRow(entry: PacketLogEntry) {
    val isIn = entry.direction == PacketDirection.INBOUND
    val dirColor = if (isIn) Color(0xFF42A5F5) else Color(0xFF66BB6A)
    val dirLabel = if (isIn) "▼ IN" else "▲ OUT"
    val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestampMs))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Direction badge
        Surface(
            color = dirColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                dirLabel,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = dirColor,
                fontWeight = FontWeight.Bold,
            )
        }

        // Timestamp
        Text(
            timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

        // src → dst
        Text(
            "${entry.src.take(6)} → ${entry.dst.take(8)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )

        // Type chip
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                entry.type.name,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Size + hops
        Text(
            "${entry.sizeBytes}B  •  ${entry.hopCount}h",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
