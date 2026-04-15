# Enhanced Topology Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade `TopologyScreen` with pan/zoom, draggable nodes, peer-peer edges, packet flow animation, a node detail panel, and filter/highlight.

**Architecture:** The existing monolithic `TopologyScreen.kt` is split into four files under `ui/topology/`. `TopologyState.kt` holds all mutable state and pure logic. `TopologyCanvas.kt` is a stateless Canvas composable. `TopologyDetailPanel.kt` is a slide-in side panel. `TopologyScreen.kt` is the thin coordinator wired into the existing tab system.

**Tech Stack:** Kotlin 1.9, Compose Desktop 1.6 (Skia canvas, `drawText` with `TextMeasurer`, `ContextMenuArea`, `graphicsLayer`), kotlinx-coroutines 1.8, JUnit 5 + coroutines-test for unit tests.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `gradle/libs.versions.toml` | Add coroutines-test version alias |
| Modify | `build.gradle.kts` | Add test dependencies |
| Modify | `src/main/kotlin/.../mesh/MeshRouter.kt` | Add `getPath()` |
| Modify | `src/main/kotlin/.../bluetooth/MeshNetworkManager.kt` | Add `outboundPackets` SharedFlow |
| Modify | `src/main/kotlin/.../data/MeshRepository.kt` | Add `messagingDestination` + `navigateToMessaging()` |
| Create | `src/main/kotlin/.../ui/topology/TopologyState.kt` | All mutable state + pure logic |
| Create | `src/main/kotlin/.../ui/topology/TopologyCanvas.kt` | Stateless Canvas composable |
| Create | `src/main/kotlin/.../ui/topology/TopologyDetailPanel.kt` | Slide-in node detail panel |
| Create | `src/main/kotlin/.../ui/topology/TopologyScreen.kt` | Thin coordinator (new package) |
| Delete | `src/main/kotlin/.../ui/TopologyScreen.kt` | Replaced by above |
| Modify | `src/main/kotlin/.../ui/App.kt` | Update import + observe `messagingDestination` |
| Create | `src/test/kotlin/.../mesh/MeshRouterTest.kt` | Unit tests for `getPath()` |
| Create | `src/test/kotlin/.../ui/topology/TopologyStateTest.kt` | Unit tests for pure logic |

All paths share the prefix `src/main/kotlin/com/turbomesh/desktop/` or `src/test/kotlin/com/turbomesh/desktop/`.

---

## Task 1: Add Test Infrastructure

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Create: `src/test/kotlin/com/turbomesh/desktop/SmokeTest.kt`

- [ ] **Step 1: Add coroutines-test to version catalog**

In `gradle/libs.versions.toml`, add to `[libraries]`:
```toml
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

- [ ] **Step 2: Add test dependencies to build.gradle.kts**

In `build.gradle.kts`, add inside the `dependencies { }` block (after existing entries):
```kotlin
testImplementation(kotlin("test"))
testImplementation(libs.coroutines.test)
```

- [ ] **Step 3: Write a smoke test to confirm the setup works**

Create `src/test/kotlin/com/turbomesh/desktop/SmokeTest.kt`:
```kotlin
package com.turbomesh.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test fun `test infrastructure works`() {
        assertTrue(true)
    }
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew test --tests "com.turbomesh.desktop.SmokeTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts src/test/kotlin/com/turbomesh/desktop/SmokeTest.kt
git commit -m "test: add JUnit + coroutines-test dependencies"
```

---

## Task 2: Add `MeshRouter.getPath()`

**Files:**
- Modify: `src/main/kotlin/com/turbomesh/desktop/mesh/MeshRouter.kt`
- Create: `src/test/kotlin/com/turbomesh/desktop/mesh/MeshRouterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/turbomesh/desktop/mesh/MeshRouterTest.kt`:
```kotlin
package com.turbomesh.desktop.mesh

import kotlin.test.Test
import kotlin.test.assertEquals

class MeshRouterTest {
    @Test fun `getPath returns empty list for direct route`() {
        val router = MeshRouter()
        router.registerDirectRoute("nodeA")
        assertEquals(emptyList(), router.getPath("nodeA"))
    }

    @Test fun `getPath returns hop list for multi-hop route`() {
        val router = MeshRouter()
        router.registerRoute("nodeC", listOf("nodeB", "nodeC"))
        assertEquals(listOf("nodeB", "nodeC"), router.getPath("nodeC"))
    }

    @Test fun `getPath returns empty list for unknown node`() {
        val router = MeshRouter()
        assertEquals(emptyList(), router.getPath("unknown"))
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests "com.turbomesh.desktop.mesh.MeshRouterTest" 2>&1 | tail -20
```

Expected: FAIL — `Unresolved reference: getPath`

- [ ] **Step 3: Add `getPath()` to `MeshRouter.kt`**

In `src/main/kotlin/com/turbomesh/desktop/mesh/MeshRouter.kt`, add after the existing `fun hasRoute(...)` line:
```kotlin
fun getPath(destinationId: String): List<String> = routingTable[destinationId]?.toList() ?: emptyList()
```

- [ ] **Step 4: Run test to confirm it passes**

```bash
./gradlew test --tests "com.turbomesh.desktop.mesh.MeshRouterTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/turbomesh/desktop/mesh/MeshRouter.kt src/test/kotlin/com/turbomesh/desktop/mesh/MeshRouterTest.kt
git commit -m "feat: add MeshRouter.getPath() for topology peer-edge rendering"
```

---

## Task 3: Add `outboundPackets` to `MeshNetworkManager`

**Files:**
- Modify: `src/main/kotlin/com/turbomesh/desktop/bluetooth/MeshNetworkManager.kt`

- [ ] **Step 1: Add the backing field and public flow**

In `MeshNetworkManager.kt`, after the `_inboundPackets` declaration (around line 26), add:
```kotlin
private val _outboundPackets = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
val outboundPackets: SharedFlow<MeshMessage> = _outboundPackets.asSharedFlow()
```

- [ ] **Step 2: Emit in `routeOutbound()`**

In the `routeOutbound` function, after the line `_networkStats.value = _networkStats.value.copy(messagesSent = ...)`, add:
```kotlin
_outboundPackets.tryEmit(msg)
```

- [ ] **Step 3: Expose `getPath()` on `MeshNetworkManager` (avoids accessing private `router` from UI layer)**

In `MeshNetworkManager.kt`, add after the `fun stopScan()` line near the bottom:
```kotlin
fun getPath(destId: String): List<String> = router.getPath(destId)
```

- [ ] **Step 4: Build to confirm no compile errors**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/turbomesh/desktop/bluetooth/MeshNetworkManager.kt
git commit -m "feat: add MeshNetworkManager.outboundPackets SharedFlow and getPath() delegation"
```

---

## Task 4: Add `messagingDestination` to `MeshRepository` and wire `App.kt`

**Files:**
- Modify: `src/main/kotlin/com/turbomesh/desktop/data/MeshRepository.kt`
- Modify: `src/main/kotlin/com/turbomesh/desktop/ui/App.kt`

- [ ] **Step 1: Add `messagingDestination` to `MeshRepository`**

In `MeshRepository.kt`, add after the `val groupInvites` line:
```kotlin
/** Set by TopologyDetailPanel "Send Message" button; App.kt observes to switch tabs. */
val messagingDestination = MutableStateFlow<String?>(null)

fun navigateToMessaging(nodeId: String) {
    messagingDestination.value = nodeId
}
```

Add the import at the top of the file if not present:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
```

- [ ] **Step 2: Wire tab switching in `App.kt`**

In `App.kt`, inside the `AppShell` composable, add a `LaunchedEffect` that observes `messagingDestination` and switches to the Messaging tab (index 0) when it becomes non-null. Add this after the existing `var lastActivityMs` declaration:

```kotlin
// Navigate to Messaging tab when topology detail panel requests it
LaunchedEffect(Unit) {
    repo.messagingDestination.collect { destId ->
        if (destId != null) {
            selectedTab = 0   // Messaging is TABS index 0
            repo.messagingDestination.value = null
        }
    }
}
```

- [ ] **Step 3: Build to confirm no compile errors**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/turbomesh/desktop/data/MeshRepository.kt src/main/kotlin/com/turbomesh/desktop/ui/App.kt
git commit -m "feat: add messagingDestination flow for cross-screen navigation from topology"
```

---

## Task 5: Create `TopologyState.kt`

**Files:**
- Create: `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyState.kt`
- Create: `src/test/kotlin/com/turbomesh/desktop/ui/topology/TopologyStateTest.kt`

- [ ] **Step 1: Write failing tests for the pure logic**

Create `src/test/kotlin/com/turbomesh/desktop/ui/topology/TopologyStateTest.kt`:
```kotlin
package com.turbomesh.desktop.ui.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopologyStateTest {

    // ── Filter logic ─────────────────────────────────────────────────────────

    @Test fun `computeDimmedIds returns empty set when query is blank`() {
        val nodes = listOf(
            NodePosition("abc123", 0.5f, 0.5f),
            NodePosition("def456", 0.3f, 0.3f),
        )
        val result = computeDimmedIds("", nodes, getNickname = { "" })
        assertEquals(emptySet(), result)
    }

    @Test fun `computeDimmedIds dims nodes that do not match query`() {
        val nodes = listOf(
            NodePosition("abc123", 0.5f, 0.5f),
            NodePosition("def456", 0.3f, 0.3f),
        )
        val result = computeDimmedIds("abc", nodes, getNickname = { "" })
        assertFalse("abc123" in result)
        assertTrue("def456" in result)
    }

    @Test fun `computeDimmedIds matches nickname case-insensitively`() {
        val nodes = listOf(NodePosition("aaa", 0.5f, 0.5f))
        val nicknames = mapOf("aaa" to "Alpha")
        val result = computeDimmedIds("alpha", nodes, getNickname = { nicknames[it] ?: "" })
        assertFalse("aaa" in result)
    }

    // ── Spring logic ─────────────────────────────────────────────────────────

    @Test fun `springStep does not move pinned nodes`() {
        val positions = listOf(
            NodePosition("local", 0.5f, 0.5f, pinned = true),
            NodePosition("peer", 0.8f, 0.8f, pinned = true),
        )
        val result = springStep(positions, localId = "local")
        val peer = result.first { it.id == "peer" }
        assertEquals(0.8f, peer.x, absoluteTolerance = 0.001f)
        assertEquals(0.8f, peer.y, absoluteTolerance = 0.001f)
    }

    @Test fun `springStep keeps local node pinned to centre`() {
        val positions = listOf(
            NodePosition("local", 0.3f, 0.7f), // starts off-centre
            NodePosition("peer", 0.8f, 0.8f),
        )
        val result = springStep(positions, localId = "local")
        val local = result.first { it.id == "local" }
        assertEquals(0.5f, local.x, absoluteTolerance = 0.001f)
        assertEquals(0.5f, local.y, absoluteTolerance = 0.001f)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests "com.turbomesh.desktop.ui.topology.TopologyStateTest" 2>&1 | tail -20
```

Expected: FAIL — `Unresolved reference: NodePosition`, `computeDimmedIds`, `springStep`

- [ ] **Step 3: Create `TopologyState.kt`**

Create `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyState.kt`:
```kotlin
package com.turbomesh.desktop.ui.topology

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.mesh.MeshMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.*

// ── Pure data types ───────────────────────────────────────────────────────────

data class NodePosition(
    val id: String,
    var x: Float,
    var y: Float,
    val pinned: Boolean = false,
    val dragging: Boolean = false,
    val isGhost: Boolean = false,   // intermediate hop node not in scanResults
)

data class PacketAnimation(
    val fromId: String,
    val toId: String,
    val color: Color,
    val progress: Animatable<Float, *> = Animatable(0f),
)

// ── Pure logic (testable without Compose runtime) ────────────────────────────

/**
 * Returns the set of node IDs that should be dimmed (don't match [query]).
 * Empty query → no dimming.
 */
fun computeDimmedIds(
    query: String,
    positions: List<NodePosition>,
    getNickname: (String) -> String,
): Set<String> {
    if (query.isBlank()) return emptySet()
    val q = query.lowercase()
    return positions
        .filter { np ->
            !np.id.lowercase().contains(q) &&
            !getNickname(np.id).lowercase().contains(q)
        }
        .map { it.id }
        .toSet()
}

/**
 * Runs one step of the force-directed spring simulation.
 * Pinned nodes are not moved. Local node is always snapped to centre (0.5, 0.5).
 */
fun springStep(positions: List<NodePosition>, localId: String): List<NodePosition> {
    val result = positions.map { it.copy() }.toMutableList()
    for (i in result.indices) {
        if (result[i].pinned || result[i].id == localId) continue
        var fx = 0f; var fy = 0f
        for (j in result.indices) {
            if (i == j) continue
            val dx = result[i].x - result[j].x
            val dy = result[i].y - result[j].y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val force = 0.004f / (dist * dist)
            fx += dx / dist * force
            fy += dy / dist * force
        }
        // Centre gravity for non-local nodes
        fx += (0.5f - result[i].x) * 0.01f
        fy += (0.5f - result[i].y) * 0.01f
        result[i] = result[i].copy(
            x = (result[i].x + fx).coerceIn(0.05f, 0.95f),
            y = (result[i].y + fy).coerceIn(0.05f, 0.95f),
        )
    }
    // Local node always at centre
    val localIdx = result.indexOfFirst { it.id == localId }
    if (localIdx >= 0) result[localIdx] = result[localIdx].copy(x = 0.5f, y = 0.5f)
    return result
}

// ── Compose state holder ─────────────────────────────────────────────────────

class TopologyState(
    private val repo: MeshRepository,
    private val scope: CoroutineScope,
) {
    // Transform
    var scale by mutableStateOf(1f)
    var panOffset by mutableStateOf(Offset.Zero)

    // Node positions
    var positions by mutableStateOf<List<NodePosition>>(emptyList())

    // Selection + filter
    var selectedNodeId by mutableStateOf<String?>(null)
    var filterQuery by mutableStateOf("")
    var showLabels by mutableStateOf(true)

    // Active packet animations
    val activeAnimations = mutableStateListOf<PacketAnimation>()

    // Animation input channel — CONFLATED so bursts don't backlog
    private val animChannel = Channel<PacketAnimation>(Channel.CONFLATED)

    val dimmedIds: Set<String>
        get() = computeDimmedIds(filterQuery, positions, repo::getNickname)

    init {
        // Collect inbound packets → animate towards local node
        scope.launch {
            repo.inboundPackets.collect { msg ->
                if (msg.sourceNodeId != repo.localNodeId) {
                    animChannel.trySend(
                        PacketAnimation(
                            fromId = msg.sourceNodeId,
                            toId = repo.localNodeId,
                            color = Color(0xFF42A5F5),
                        )
                    )
                }
            }
        }
        // Collect outbound packets → animate away from local node
        scope.launch {
            repo.networkManager.outboundPackets.collect { msg ->
                animChannel.trySend(
                    PacketAnimation(
                        fromId = repo.localNodeId,
                        toId = msg.destinationNodeId,
                        color = Color(0xFFFFD54F),
                    )
                )
            }
        }
        // Drain animation channel
        scope.launch {
            for (anim in animChannel) {
                val a = anim
                activeAnimations.add(a)
                launch {
                    a.progress.animateTo(
                        1f,
                        androidx.compose.animation.core.tween(1200),
                    )
                    activeAnimations.remove(a)
                }
            }
        }
    }

    fun resetTransform() {
        scale = 1f
        panOffset = Offset.Zero
    }

    fun zoom(factor: Float) {
        scale = (scale * factor).coerceIn(0.3f, 4f)
    }

    fun applyPan(delta: Offset, canvasWidth: Float, canvasHeight: Float) {
        val maxX = canvasWidth * scale
        val maxY = canvasHeight * scale
        panOffset = Offset(
            (panOffset.x + delta.x).coerceIn(-maxX, maxX),
            (panOffset.y + delta.y).coerceIn(-maxY, maxY),
        )
    }

    /** Convert a screen-space tap offset to normalised canvas coordinates (0..1). */
    fun screenToNorm(tapX: Float, tapY: Float, canvasW: Float, canvasH: Float): Pair<Float, Float> {
        val canvasX = (tapX - panOffset.x - canvasW / 2f) / scale + canvasW / 2f
        val canvasY = (tapY - panOffset.y - canvasH / 2f) / scale + canvasH / 2f
        return canvasX / canvasW to canvasY / canvasH
    }

    /** Find the node closest to a normalised (nx, ny) point, within a normalised radius. */
    fun hitTest(nx: Float, ny: Float, radiusNorm: Float = 0.04f): String? =
        positions.minByOrNull {
            val dx = it.x - nx; val dy = it.y - ny; dx * dx + dy * dy
        }?.takeIf {
            val dx = it.x - nx; val dy = it.y - ny
            sqrt(dx * dx + dy * dy) <= radiusNorm
        }?.id

    fun startDrag(nodeId: String) {
        positions = positions.map {
            if (it.id == nodeId) it.copy(pinned = true, dragging = true) else it
        }
    }

    fun dragNode(nodeId: String, deltaNormX: Float, deltaNormY: Float) {
        positions = positions.map {
            if (it.id == nodeId) it.copy(
                x = (it.x + deltaNormX).coerceIn(0.05f, 0.95f),
                y = (it.y + deltaNormY).coerceIn(0.05f, 0.95f),
            ) else it
        }
    }

    fun endDrag(nodeId: String) {
        positions = positions.map {
            if (it.id == nodeId) it.copy(dragging = false) else it
        }
    }

    fun unpinNode(nodeId: String) {
        positions = positions.map {
            if (it.id == nodeId) it.copy(pinned = false) else it
        }
    }
}

@Composable
fun rememberTopologyState(repo: MeshRepository): TopologyState {
    val scope = rememberCoroutineScope()
    return remember { TopologyState(repo, scope) }
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew test --tests "com.turbomesh.desktop.ui.topology.TopologyStateTest" 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyState.kt \
        src/test/kotlin/com/turbomesh/desktop/ui/topology/TopologyStateTest.kt
git commit -m "feat: add TopologyState with spring simulation, filter, and animation queue"
```

---

## Task 6: Create `TopologyCanvas.kt`

**Files:**
- Create: `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyCanvas.kt`

No unit tests — Canvas draw calls are verified manually.

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyCanvas.kt`:
```kotlin
package com.turbomesh.desktop.ui.topology

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val COLOR_STRONG   = Color(0xFF66BB6A)
private val COLOR_FAIR     = Color(0xFFFFCA28)
private val COLOR_WEAK     = Color(0xFFEF5350)
private val COLOR_LOCAL    = Color(0xFF1565C0)
private val COLOR_GHOST    = Color(0xFF607D8B)
private val COLOR_PEER_EDGE = Color(0xFF3A5F8A)
private val COLOR_LABEL    = Color(0xFF8899BB)

private fun rssiColor(rssi: Int) = when {
    rssi >= -65 -> COLOR_STRONG
    rssi >= -80 -> COLOR_FAIR
    else        -> COLOR_WEAK
}

private fun localEdgeColor(rssi: Int) = rssiColor(rssi).copy(alpha = 0.7f)

/**
 * Stateless Canvas composable. All data passed as parameters.
 *
 * @param positions         All node positions (includes ghost nodes).
 * @param localNodeId       ID of the local node (drawn at centre, larger).
 * @param paths             Map of destId → full path list [localId, hop1…, destId] for peer edges.
 * @param rssiMap           nodeId → latest RSSI value.
 * @param activeAnimations  Currently running packet animations.
 * @param dimmedIds         Node IDs to render at 30% opacity.
 * @param showLabels        Whether to draw node name labels.
 * @param getNickname       Resolver from nodeId to display name.
 * @param textMeasurer      Compose TextMeasurer for drawing text on canvas.
 * @param selectedNodeId    Highlighted (selected) node ID.
 */
@Composable
fun TopologyCanvas(
    positions: List<NodePosition>,
    localNodeId: String,
    paths: Map<String, List<String>>,
    rssiMap: Map<String, Int>,
    activeAnimations: List<PacketAnimation>,
    dimmedIds: Set<String>,
    showLabels: Boolean,
    getNickname: (String) -> String,
    textMeasurer: TextMeasurer,
    selectedNodeId: String?,
    modifier: Modifier = Modifier,
) {
    val posMap = positions.associateBy { it.id }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun nodeOffset(np: NodePosition) = Offset(np.x * w, np.y * h)

        // ── Peer-peer dashed edges ────────────────────────────────────────────
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        paths.forEach { (_, path) ->
            if (path.size < 2) return@forEach
            for (i in 0 until path.size - 1) {
                val a = posMap[path[i]] ?: return@forEach
                val b = posMap[path[i + 1]] ?: return@forEach
                val aDimmed = path[i] in dimmedIds
                val bDimmed = path[i + 1] in dimmedIds
                val alpha = if (aDimmed || bDimmed) 0.18f else 0.6f
                drawLine(
                    color = COLOR_PEER_EDGE.copy(alpha = alpha),
                    start = nodeOffset(a),
                    end = nodeOffset(b),
                    strokeWidth = 1.5f,
                    pathEffect = dashEffect,
                )
                // Hop count label at midpoint
                val hopLabel = "${i + 1} hop${if (i + 1 > 1) "s" else ""}"
                val mid = Offset((a.x + b.x) / 2f * w, (a.y + b.y) / 2f * h)
                drawText(
                    textMeasurer = textMeasurer,
                    text = hopLabel,
                    topLeft = mid + Offset(4f, -14f),
                    style = TextStyle(fontSize = 8.sp, color = COLOR_LABEL.copy(alpha = alpha)),
                )
            }
        }

        // ── Local → peer solid edges ──────────────────────────────────────────
        val localPos = posMap[localNodeId] ?: return@Canvas
        positions.forEach { peer ->
            if (peer.id == localNodeId || peer.isGhost) return@forEach
            val rssi = rssiMap[peer.id] ?: -80
            val dimAlpha = if (peer.id in dimmedIds) 0.3f else 1f
            drawLine(
                color = localEdgeColor(rssi).copy(alpha = 0.5f * dimAlpha),
                start = nodeOffset(localPos),
                end = nodeOffset(peer),
                strokeWidth = 1.8f,
            )
        }

        // ── Ghost node circles ────────────────────────────────────────────────
        positions.filter { it.isGhost }.forEach { ghost ->
            drawCircle(
                color = COLOR_GHOST.copy(alpha = 0.5f),
                radius = 8f,
                center = nodeOffset(ghost),
            )
        }

        // ── Real node circles ─────────────────────────────────────────────────
        positions.filter { !it.isGhost }.forEach { np ->
            val isLocal = np.id == localNodeId
            val rssi = rssiMap[np.id] ?: -80
            val color = if (isLocal) COLOR_LOCAL else rssiColor(rssi)
            val radius = if (isLocal) 22f else 14f
            val dimAlpha = if (np.id in dimmedIds) 0.3f else 1f

            // Selection / hover ring
            if (np.id == selectedNodeId || isLocal) {
                drawCircle(
                    color = color.copy(alpha = 0.25f * dimAlpha),
                    radius = radius + 9f,
                    center = nodeOffset(np),
                )
            }
            drawCircle(
                color = color.copy(alpha = dimAlpha),
                radius = radius,
                center = nodeOffset(np),
            )
            // Specular highlight
            drawCircle(
                color = Color.White.copy(alpha = 0.25f * dimAlpha),
                radius = radius * 0.45f,
                center = nodeOffset(np) + Offset(-radius * 0.2f, -radius * 0.2f),
            )

            // Label
            if (showLabels) {
                val label = if (isLocal) "Me" else getNickname(np.id).ifBlank { np.id.take(8) }
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = nodeOffset(np) + Offset(-label.length * 3.5f, radius + 4f),
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.85f * dimAlpha),
                    ),
                )
            }
        }

        // ── Packet animation dots ─────────────────────────────────────────────
        activeAnimations.forEach { anim ->
            val from = posMap[anim.fromId] ?: return@forEach
            val to   = posMap[anim.toId]   ?: return@forEach
            val p    = anim.progress.value
            val dot  = Offset(
                lerp(from.x, to.x, p) * w,
                lerp(from.y, to.y, p) * h,
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    color = anim.color
                    asFrameworkPaint().maskFilter =
                        android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawCircle(dot, 5f, paint)
            }
            drawCircle(color = anim.color, radius = 4f, center = dot)
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
```

> **Note on BlurMaskFilter:** Compose Desktop uses Skia under the hood. Replace `android.graphics.BlurMaskFilter` with `org.jetbrains.skia.MaskFilter` and use `drawIntoCanvas { it.drawCircle(...) }` with a `org.jetbrains.skia.Paint` if the Android import fails to compile. The Skia equivalent is:
> ```kotlin
> drawIntoCanvas { canvas ->
>     val skiaPaint = org.jetbrains.skia.Paint().apply {
>         color = anim.color.toArgb()
>         maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(
>             org.jetbrains.skia.FilterBlurMode.NORMAL, 8f
>         )
>     }
>     (canvas as androidx.compose.ui.graphics.DesktopCanvas).skiaCanvas
>         .drawCircle(dot.x, dot.y, 5f, skiaPaint)
> }
> ```

- [ ] **Step 2: Build to confirm compile**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyCanvas.kt
git commit -m "feat: add TopologyCanvas with peer edges, labels, ghost nodes, and packet dots"
```

---

## Task 7: Create `TopologyDetailPanel.kt`

**Files:**
- Create: `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyDetailPanel.kt`

- [ ] **Step 1: Create the file**

Create `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyDetailPanel.kt`:
```kotlin
package com.turbomesh.desktop.ui.topology

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.mesh.MeshNode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TopologyDetailPanel(
    nodeId: String?,
    nodes: List<MeshNode>,
    rssiHistory: Map<String, List<Int>>,
    nodeLastSeen: Map<String, Long>,
    repo: MeshRepository,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = nodeId != null,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier,
    ) {
        val node = nodes.firstOrNull { it.id == nodeId }
        val rssi = node?.rssi ?: -80
        val rssiColor = when {
            rssi >= -65 -> Color(0xFF66BB6A)
            rssi >= -80 -> Color(0xFFFFCA28)
            else -> Color(0xFFEF5350)
        }
        val history = rssiHistory[nodeId] ?: emptyList()
        val lastSeenMs = nodeLastSeen[nodeId]
        val path = nodeId?.let { repo.networkManager.getPath(it) } ?: emptyList()
        val hopCount = if (path.isEmpty()) 1 else path.size

        Surface(
            modifier = Modifier.width(240.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(rssiColor)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            node?.displayName ?: nodeId?.take(8) ?: "",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            node?.connectionQuality ?: "Unknown",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Text("✕", style = MaterialTheme.typography.labelMedium)
                    }
                }

                HorizontalDivider()

                // ── Signal ────────────────────────────────────────────────────
                PanelSection(title = "Signal") {
                    StatRow("RSSI", "$rssi dBm")
                    StatRow("Trend", node?.rssiTrend?.ifBlank { "—" } ?: "—")
                    Spacer(Modifier.height(6.dp))
                    MiniRssiChart(history = history)
                }

                HorizontalDivider()

                // ── Network ───────────────────────────────────────────────────
                PanelSection(title = "Network") {
                    StatRow("Address", node?.address?.takeLast(11) ?: "—")
                    StatRow("Hops to local", hopCount.toString())
                    StatRow("Last seen", lastSeenMs?.let { formatRelative(it) } ?: "—")
                    val battery = node?.batteryLevel ?: -1
                    if (battery >= 0) StatRow("Battery", "$battery%")
                }

                HorizontalDivider()

                // ── Session ───────────────────────────────────────────────────
                PanelSection(title = "Session") {
                    val connectedSince = node?.connectedSinceMs ?: 0L
                    StatRow("Connected", if (connectedSince > 0) formatRelative(connectedSince) else "—")
                    StatRow("Status", node?.presenceStatus?.ifBlank { "available" } ?: "—")
                }

                Spacer(Modifier.height(8.dp))

                // ── Send Message ──────────────────────────────────────────────
                Button(
                    onClick = { nodeId?.let { repo.navigateToMessaging(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Send Message →")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MiniRssiChart(history: List<Int>) {
    if (history.isEmpty()) return
    val last10 = history.takeLast(10)
    val min = last10.min().toFloat()
    val max = last10.max().toFloat().coerceAtLeast(min + 1f)

    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        last10.forEach { rssi ->
            val fraction = ((rssi - min) / (max - min)).coerceIn(0f, 1f)
            val barColor = when {
                rssi >= -65 -> Color(0xFF66BB6A)
                rssi >= -80 -> Color(0xFFFFCA28)
                else        -> Color(0xFFEF5350)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction.coerceAtLeast(0.1f))
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(barColor),
            )
        }
    }
}

private fun formatRelative(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    return when {
        diffMs < 60_000 -> "${diffMs / 1000}s ago"
        diffMs < 3_600_000 -> "${diffMs / 60_000}m ago"
        else -> {
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
            fmt.format(Instant.ofEpochMilli(epochMs))
        }
    }
}
```

> **Note:** `repo.networkManager.router` is currently package-private. If the compiler complains, add `internal` visibility to `router` in `MeshNetworkManager` and expose `fun getPath(destId: String) = router.getPath(destId)` on `MeshNetworkManager`, then call `repo.networkManager.getPath(destId)` here.

- [ ] **Step 2: Build to confirm compile**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyDetailPanel.kt
git commit -m "feat: add TopologyDetailPanel with RSSI chart, stats, and Send Message navigation"
```

---

## Task 8: Rewrite `TopologyScreen.kt` as Thin Coordinator

**Files:**
- Create: `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyScreen.kt`
- Delete: `src/main/kotlin/com/turbomesh/desktop/ui/TopologyScreen.kt`

This task replaces the old file with the new thin coordinator. The new coordinator wires together `TopologyState`, `TopologyCanvas`, `TopologyDetailPanel`, the spring simulation `LaunchedEffect`, path derivation, gesture handling (pan/zoom, drag, tap, right-click), and the top bar.

- [ ] **Step 1: Create the new coordinator**

Create `src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyScreen.kt`:
```kotlin
package com.turbomesh.desktop.ui.topology

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopologyScreen(repo: MeshRepository) {
    val state = rememberTopologyState(repo)
    val nodes by repo.scanResults.collectAsState()
    val rssiHistory by repo.rssiHistory.collectAsState()
    val nodeLastSeen by repo.nodeLastSeen.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    // Build rssiMap: nodeId → latest RSSI
    val rssiMap = remember(nodes) { nodes.associate { it.id to it.rssi } }

    // Initialise / update positions when node list changes (new nodes inserted unpinned)
    LaunchedEffect(nodes) {
        val rng = java.util.Random()
        val existingIds = state.positions.map { it.id }.toSet()
        val local = NodePosition(repo.localNodeId, 0.5f, 0.5f, pinned = true)
        val peers = nodes.map { node ->
            state.positions.firstOrNull { it.id == node.id } ?: NodePosition(
                id = node.id,
                x = 0.3f + rng.nextFloat() * 0.4f,
                y = 0.3f + rng.nextFloat() * 0.4f,
            )
        }
        // Remove positions for nodes that left, then add local + peers
        state.positions = listOf(local) + peers
    }

    // Spring simulation (runs 80 iterations, then idles until nodes change)
    LaunchedEffect(nodes) {
        repeat(80) {
            state.positions = springStep(state.positions, repo.localNodeId)
            delay(16)
        }
    }

    // Derive peer-peer edge paths from router
    val paths: Map<String, List<String>> = remember(nodes) {
        nodes.associate { node ->
            val hops = repo.networkManager.getPath(node.id)
            val fullPath = listOf(repo.localNodeId) + hops +
                if (hops.isEmpty() || hops.last() != node.id) listOf(node.id) else emptyList()
            node.id to fullPath
        }
    }

    // Insert ghost nodes: intermediate hops not in scanResults
    LaunchedEffect(paths, nodes) {
        val knownIds = nodes.map { it.id }.toSet() + setOf(repo.localNodeId)
        val ghosts = paths.values.flatten().toSet() - knownIds
        val existingGhostIds = state.positions.filter { it.isGhost }.map { it.id }.toSet()
        val newGhosts = (ghosts - existingGhostIds).map { ghostId ->
            NodePosition(ghostId, 0.4f + Math.random().toFloat() * 0.2f, 0.4f + Math.random().toFloat() * 0.2f, isGhost = true)
        }
        if (newGhosts.isNotEmpty()) {
            state.positions = state.positions + newGhosts
        }
        // Remove ghost nodes that are no longer referenced
        state.positions = state.positions.filter { !it.isGhost || it.id in ghosts }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Network Topology", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = state.filterQuery,
                onValueChange = { state.filterQuery = it },
                placeholder = { Text("Find node…") },
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
            FilterChip(
                selected = state.showLabels,
                onClick = { state.showLabels = !state.showLabels },
                label = { Text("Labels") },
            )
            OutlinedButton(onClick = {
                state.resetTransform()
                // Unpin all nodes so spring re-attracts
                state.positions = state.positions.map { it.copy(pinned = it.id == repo.localNodeId) }
            }) { Text("Reset") }
        }

        // ── Canvas + detail panel ─────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val canvasW = constraints.maxWidth.toFloat()
                val canvasH = constraints.maxHeight.toFloat()

                // Track drag state
                var dragNodeId by remember { mutableStateOf<String?>(null) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Pan via pointer scroll / two-finger drag
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.type == PointerEventType.Scroll) {
                                        val scrollY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        val factor = if (scrollY < 0) 1.1f else 0.9f
                                        state.zoom(factor)
                                    }
                                }
                            }
                        }
                        // Drag nodes
                        .pointerInput(state.positions) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val (nx, ny) = state.screenToNorm(offset.x, offset.y, canvasW, canvasH)
                                    dragNodeId = state.hitTest(nx, ny)
                                    dragNodeId?.let { state.startDrag(it) }
                                },
                                onDrag = { _, delta ->
                                    dragNodeId?.let { id ->
                                        state.dragNode(
                                            nodeId = id,
                                            deltaNormX = delta.x / (canvasW * state.scale),
                                            deltaNormY = delta.y / (canvasH * state.scale),
                                        )
                                    }
                                    // Pan when not dragging a node
                                    if (dragNodeId == null) {
                                        state.applyPan(delta, canvasW, canvasH)
                                    }
                                },
                                onDragEnd = { dragNodeId?.let { state.endDrag(it) }; dragNodeId = null },
                                onDragCancel = { dragNodeId?.let { state.endDrag(it) }; dragNodeId = null },
                            )
                        }
                        // Tap to select
                        .pointerInput(state.positions) {
                            detectTapGestures { offset ->
                                val (nx, ny) = state.screenToNorm(offset.x, offset.y, canvasW, canvasH)
                                state.selectedNodeId = state.hitTest(nx, ny)
                                    ?.takeIf { it != repo.localNodeId }
                            }
                        }
                ) {
                    // Right-click context menu for pinned nodes (unpin)
                    val pinnedIds = state.positions.filter { it.pinned && it.id != repo.localNodeId }.map { it.id }
                    ContextMenuArea(items = {
                        if (state.selectedNodeId in pinnedIds) {
                            listOf(ContextMenuItem("Unpin node") {
                                state.selectedNodeId?.let { state.unpinNode(it) }
                            })
                        } else emptyList()
                    }) {
                        TopologyCanvas(
                            positions = state.positions,
                            localNodeId = repo.localNodeId,
                            paths = paths,
                            rssiMap = rssiMap,
                            activeAnimations = state.activeAnimations,
                            dimmedIds = state.dimmedIds,
                            showLabels = state.showLabels,
                            getNickname = repo::getNickname,
                            textMeasurer = textMeasurer,
                            selectedNodeId = state.selectedNodeId,
                            modifier = Modifier.graphicsLayer {
                                scaleX = state.scale
                                scaleY = state.scale
                                translationX = state.panOffset.x
                                translationY = state.panOffset.y
                            },
                        )
                    }

                    // Zoom controls
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SmallIconButton("+") { state.zoom(1.25f) }
                        SmallIconButton("−") { state.zoom(0.8f) }
                        SmallIconButton("⊙") { state.resetTransform() }
                    }

                    // Legend
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LegendItem(Color(0xFF66BB6A), "Strong")
                        LegendItem(Color(0xFFFFCA28), "Fair")
                        LegendItem(Color(0xFFEF5350), "Weak")
                    }
                }
            }

            // Detail panel (slides in from right)
            TopologyDetailPanel(
                nodeId = state.selectedNodeId,
                nodes = nodes,
                rssiHistory = rssiHistory,
                nodeLastSeen = nodeLastSeen,
                repo = repo,
                onClose = { state.selectedNodeId = null },
            )
        }
    }
}

// ── Small helpers ─────────────────────────────────────────────────────────────

@Composable
private fun SmallIconButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        contentPadding = PaddingValues(0.dp),
    ) { Text(label) }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

> **Note:** `ContextMenuArea` is `@ExperimentalFoundationApi` in Compose Desktop — the `@OptIn` annotation at the top handles this.

> **Note on `router` visibility:** `MeshNetworkManager.router` is private. Add `fun getPath(destId: String): List<String> = router.getPath(destId)` to `MeshNetworkManager` and call `repo.networkManager.getPath(node.id)` here instead.

- [ ] **Step 2: Delete the old TopologyScreen**

```bash
rm /home/ghostdev/turbomesh-desktop/src/main/kotlin/com/turbomesh/desktop/ui/TopologyScreen.kt
git add -A
```

- [ ] **Step 3: Update the import in `App.kt`**

In `src/main/kotlin/com/turbomesh/desktop/ui/App.kt`, add the import for the new package:
```kotlin
import com.turbomesh.desktop.ui.topology.TopologyScreen
```

The `TABS` list already calls `TopologyScreen(repo)` — no change needed there.

- [ ] **Step 4: Build to confirm compile**

```bash
./gradlew compileKotlin 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`

If you see "Unresolved reference: router" in `TopologyDetailPanel` or `TopologyScreen`, add this to `MeshNetworkManager.kt`:
```kotlin
fun getPath(destId: String): List<String> = router.getPath(destId)
```

And update the call sites to use `repo.networkManager.getPath(destId)`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/turbomesh/desktop/ui/topology/TopologyScreen.kt \
        src/main/kotlin/com/turbomesh/desktop/ui/App.kt
git commit -m "feat: replace TopologyScreen with modular ui/topology split — pan/zoom, drag, detail panel, filter"
```

---

## Task 9: Run All Tests and Verify

- [ ] **Step 1: Run all tests**

```bash
./gradlew test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 2: Build the full application**

```bash
./gradlew build 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run the app and manually verify**

```bash
./gradlew run
```

Work through the manual checklist from the spec:
- [ ] Pan to canvas edge — translation clamped, at least one node remains visible
- [ ] Scroll wheel to zoom in/out — clamped at 0.3× and 4.0×
- [ ] Drag a node — moves with pointer, spring no longer pulls it
- [ ] Right-click pinned node → "Unpin node" menu appears, node re-joins spring
- [ ] Multi-hop path in router — dashed peer-peer edges appear with hop labels
- [ ] Ghost node for unknown intermediate — grey circle on edge path
- [ ] Send a message — yellow dot animates from local node to destination
- [ ] Receive a message — blue dot animates from source to local node
- [ ] Type in filter field — non-matching nodes and edges dim to 30%
- [ ] Click node → detail panel slides in; click ✕ → slides out
- [ ] "Send Message" in panel → Messaging tab opens with node pre-selected
- [ ] 0 nodes, 1 node, 2 nodes — no crashes or blank screens

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat: topology map complete — pan/zoom, drag, peer edges, animation, detail panel, filter"
```
