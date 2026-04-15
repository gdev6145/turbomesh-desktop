package com.turbomesh.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.mesh.MeshNode
import kotlin.math.*

private data class NodePos(val id: String, var x: Float, var y: Float, val node: MeshNode?)

@Composable
fun TopologyScreen(repo: MeshRepository) {
    val s = LocalStrings.current
    val nodes by repo.scanResults.collectAsState()
    var showLabels by remember { mutableStateOf(true) }
    var layoutSeed by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.topologyTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { layoutSeed++ }, modifier = Modifier.padding(end = 8.dp)) {
                Text(s.refreshLayout)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.showLabels, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(4.dp))
                Switch(checked = showLabels, onCheckedChange = { showLabels = it })
            }
        }
        Spacer(Modifier.height(12.dp))

        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.noTopology, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            TopologyGraph(
                localId = repo.localNodeId,
                nodes = nodes,
                showLabels = showLabels,
                seed = layoutSeed,
                getNickname = { repo.getNickname(it) },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            )
        }
    }
}

@Composable
private fun TopologyGraph(
    localId: String,
    nodes: List<MeshNode>,
    showLabels: Boolean,
    seed: Int,
    getNickname: (String) -> String,
    modifier: Modifier = Modifier,
) {
    var hoveredId by remember { mutableStateOf<String?>(null) }

    // Build positions — local node in centre, others in a circle, then run spring simulation
    var positions by remember(nodes, seed) {
        val rng = java.util.Random(seed.toLong())
        val list = mutableListOf<NodePos>()
        // Local node at a placeholder; will be translated to centre after we have canvas size
        list += NodePos(localId, 0.5f, 0.5f, null)
        val angleStep = if (nodes.isEmpty()) 0f else (2 * PI / nodes.size).toFloat()
        nodes.forEachIndexed { i, node ->
            val angle = i * angleStep + rng.nextFloat() * 0.2f
            val radius = 0.28f + rng.nextFloat() * 0.05f
            list += NodePos(node.id, 0.5f + radius * cos(angle), 0.5f + radius * sin(angle), node)
        }
        mutableStateOf(list)
    }

    // Simple force iteration via LaunchedEffect (spring repulsion + centre gravity)
    LaunchedEffect(nodes, seed) {
        repeat(80) {
            val next = positions.map { p -> p.copy() }.toMutableList()
            for (i in next.indices) {
                var fx = 0f; var fy = 0f
                // Repulsion from each other
                for (j in next.indices) {
                    if (i == j) continue
                    val dx = next[i].x - next[j].x
                    val dy = next[i].y - next[j].y
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                    val force = 0.004f / (dist * dist)
                    fx += dx / dist * force
                    fy += dy / dist * force
                }
                // Attraction toward centre for non-local
                if (next[i].id != localId) {
                    fx += (0.5f - next[i].x) * 0.01f
                    fy += (0.5f - next[i].y) * 0.01f
                }
                // Keep local pinned to centre
                if (next[i].id == localId) { next[i].x = 0.5f; next[i].y = 0.5f }
                else {
                    next[i].x = (next[i].x + fx).coerceIn(0.05f, 0.95f)
                    next[i].y = (next[i].y + fy).coerceIn(0.05f, 0.95f)
                }
            }
            positions = next
            kotlinx.coroutines.delay(16)
        }
    }

    BoxWithConstraints(modifier.pointerInput(positions) {
        detectTapGestures(
            onPress = { offset ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                hoveredId = positions.minByOrNull {
                    val dx = it.x * w - offset.x
                    val dy = it.y * h - offset.y
                    dx * dx + dy * dy
                }?.id
            }
        )
    }) {
        val canvasWidthDp = maxWidth
        val canvasHeightDp = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val posMap = positions.associateBy { it.id }

            // Draw edges: local → every node
            val localPos = posMap[localId] ?: return@Canvas
            positions.forEach { peer ->
                if (peer.id == localId) return@forEach
                drawLine(
                    color = Color(0xFF64B5F6).copy(alpha = 0.4f),
                    start = Offset(localPos.x * w, localPos.y * h),
                    end = Offset(peer.x * w, peer.y * h),
                    strokeWidth = 1.5f,
                )
            }

            // Draw node circles
            positions.forEach { np ->
                val isLocal = np.id == localId
                val node = np.node
                val rssi = node?.rssi ?: -60
                val color = when {
                    isLocal -> Color(0xFF42A5F5)
                    rssi >= -65 -> Color(0xFF66BB6A)
                    rssi >= -80 -> Color(0xFFFFCA28)
                    else -> Color(0xFFEF5350)
                }
                val radius = if (isLocal) 22f else 14f

                // Shadow ring for hovered
                if (np.id == hoveredId || isLocal) {
                    drawCircle(
                        color = color.copy(alpha = 0.25f),
                        radius = radius + 8f,
                        center = Offset(np.x * w, np.y * h),
                    )
                }
                drawCircle(color = color, radius = radius, center = Offset(np.x * w, np.y * h))
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f),
                    radius = radius * 0.5f,
                    center = Offset(np.x * w - radius * 0.2f, np.y * h - radius * 0.2f),
                )
            }
        }

        if (showLabels) {
            positions.forEach { np ->
                val isLocal = np.id == localId
                val label = if (isLocal) {
                    "Me\n${localId.take(6)}"
                } else {
                    val nick = getNickname(np.id)
                    if (nick.isNotBlank()) nick else np.id.take(8)
                }
                // Position labels relative to actual canvas size; offset left by ~24dp to center
                val nodeRadiusDp = if (isLocal) 22.dp else 14.dp
                val xDp = canvasWidthDp * np.x - 24.dp
                val yDp = canvasHeightDp * np.y + nodeRadiusDp + 2.dp
                Box(Modifier.absoluteOffset(xDp.coerceAtLeast(0.dp), yDp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isLocal) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // Tooltip for hovered node
        hoveredId?.let { hid ->
            if (hid == localId) return@let
            val node = positions.firstOrNull { it.id == hid }?.node ?: return@let
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(getNickname(node.id).ifBlank { node.name }, fontWeight = FontWeight.SemiBold)
                    Text("${node.address}  •  ${node.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(node.connectionQuality,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Legend
        Row(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(Color(0xFF66BB6A)); Text("Strong", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LegendDot(Color(0xFFFFCA28)); Text("Fair", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LegendDot(Color(0xFFEF5350)); Text("Weak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}
