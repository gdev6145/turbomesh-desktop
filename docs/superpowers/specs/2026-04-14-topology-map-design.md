# Enhanced Node Map / Topology Visualizer — Design Spec

**Date:** 2026-04-14  
**Project:** turbomesh-desktop  
**Scope:** Upgrade the existing `TopologyScreen` with six enhancements: pan & zoom, draggable nodes, peer-to-peer edges, packet flow animation, node detail panel, and filter/highlight.

---

## 1. Architecture

The existing monolithic `TopologyScreen.kt` (~250 lines) is split into four focused files:

```
ui/
  topology/
    TopologyScreen.kt        ← thin coordinator; public API unchanged
    TopologyState.kt         ← all mutable state: transform, positions, animations
    TopologyCanvas.kt        ← Canvas draw calls only; receives all data as parameters
    TopologyDetailPanel.kt   ← animated side panel for selected node
```

`TopologyScreen.kt` retains the same public signature (`@Composable fun TopologyScreen(repo: MeshRepository)`) so the tab bar wiring in `App.kt` requires no changes.

---

## 2. Components

### TopologyState.kt

A `@Stable` class (not a ViewModel — kept simple) that holds all mutable topology state:

| Field | Type | Purpose |
|---|---|---|
| `transformState` | `TransformableState` | Drives pan offset + scale via `rememberTransformableState` |
| `panOffset` | `MutableState<Offset>` | Current pan translation |
| `scale` | `MutableState<Float>` | Current zoom level; clamped to `0.3f..4.0f` |
| `positions` | `MutableState<Map<String, NodePosition>>` | x/y + `pinned: Boolean` + `dragging: Boolean` per node |
| `selectedNodeId` | `MutableState<String?>` | Drives detail panel open/close |
| `filterQuery` | `MutableState<String>` | Drives dim/highlight; empty = all full opacity |
| `animationQueue` | `Channel<PacketAnimation>` | `CONFLATED`; drains into active `Animatable` list |

`NodePosition` extends the existing x/y fields with `pinned: Boolean` and `dragging: Boolean`. Pinned nodes are excluded from the spring simulation.

`PacketAnimation(fromId: String, toId: String, color: Color)` — each runs an `Animatable(0f)→1f` over 1.2 s. The canvas reads the current progress to position the dot.

### TopologyCanvas.kt

Pure `Canvas {}` composable — no state reads, receives everything as parameters:

- **Peer-peer edges** — dashed (`PathEffect.dashPathEffect`), drawn from router hop paths. Color: `#3a5f8a`, opacity 60%.
- **Local→peer edges** — solid lines. Color reflects RSSI: strong=blue, fair=yellow, weak=red.
- **Hop count labels** — drawn via `drawText(textMeasurer, ...)` (Compose Desktop `TextMeasurer` API) at edge midpoints.
- **Node circles** — RSSI color-coded; local node larger (radius 22px vs 14px); hover/selected ring at +8px.
- **Dim overlay** — nodes not matching `filterQuery` draw at 30% opacity; their edges also at 30%.
- **Packet dots** — glowing circle at `lerp(startOffset, endOffset, progress)` per active animation; `BlurMaskFilter` for glow effect.
- **Ghost nodes** — intermediate hop nodes not in `scanResults` drawn as grey circles (radius 8px, no label). Their positions are auto-inserted into the `positions` map at the midpoint between their two path neighbors on first encounter; they participate in the spring simulation (unpinned) so they settle naturally.

### TopologyDetailPanel.kt

`AnimatedVisibility(visible = selectedNodeId != null, enter = slideInHorizontally(from right))`:

- **Header** — colored dot (RSSI color), display name, connection quality string, close (✕) button
- **Signal section** — RSSI value, trend arrow, mini bar chart from `rssiHistory` flow (last 10 readings)
- **Network section** — BLE address, hop count from `router` table, last-seen timestamp, battery level
- **Session section** — connected-since duration, outbound message count, presence status
- **"Send Message" button** — writes destination to a shared `MutableStateFlow<String?>` in `MeshRepository`; `App.kt` observes it to switch to the Messaging tab with the node pre-selected

### TopologyScreen.kt

Thin coordinator:

```
Column {
  TopBar(filterQuery, onQueryChange, showLabels, onLabelsToggle, onReset)
  Row {
    Box {
      TopologyCanvas(...)        // fills remaining space
      ZoomControls(...)          // absolute overlay, bottom-left
      Legend(...)                // absolute overlay, bottom-right
    }
    TopologyDetailPanel(...)     // slides in from right when node selected
  }
}
```

Gesture handling lives here via `Modifier.transformable` (pan/zoom) and `Modifier.pointerInput` (drag + tap-to-select) on the canvas `Box`.

---

## 3. Data Flow

### Pan & Zoom
`Modifier.transformable(transformState)` captures pinch and two-finger pan gestures. A `graphicsLayer { scaleX; scaleY; translationX; translationY }` is applied to the `Canvas` only. Label overlays and zoom buttons are outside the transform layer; they compute screen positions using the same `(nodeX * canvasW * scale + panOffset.x)` formula to stay aligned.

Zoom +/− buttons increment/decrement scale by 0.25f per tap. A "reset" button resets `panOffset = Offset.Zero` and `scale = 1f`.

Pan is clamped: `panOffset.x` stays within `±canvasWidth * scale`, `panOffset.y` within `±canvasHeight * scale`.

### Draggable Nodes
`detectDragGestures` on the canvas `Box`:
1. `onDragStart(offset)` — hit-test: find node whose screen position is within 30px of touch point (accounting for current transform). Set `dragging = true`, `pinned = true`.
2. `onDrag(change, dragAmount)` — update `positions[id].x += dragAmount.x / (canvasW * scale)`, same for y. Clamp to `0.05f..0.95f`.
3. `onDragEnd` — set `dragging = false`; node stays `pinned = true`.

Pinned nodes are skipped in the spring simulation `LaunchedEffect`. Right-clicking a pinned node shows a context menu with "Unpin" — sets `pinned = false`, spring re-attracts it to the cluster.

### Peer-Peer Edges
`MeshRouter` exposes a new method `fun getPath(destinationId: String): List<String>` which returns `routingTable[destinationId] ?: emptyList()` — the stored hop list (empty means direct).

On each recomposition, for every entry in `router.knownNodes()`:
1. Call `router.getPath(destId)` to get `[hop1, hop2, ..., destId]` (or `[]` for direct).
2. Reconstruct full path: `[localId] + hops + [destId]`.
3. Each consecutive pair `(a, b)` in the path becomes a dashed edge.
4. Hop count label = pair index + 1.

This is computed as a `derivedStateOf` to avoid redundant recalculations.

### Packet Flow Animation
`MeshNetworkManager.inboundPackets: SharedFlow<MeshMessage>` is collected in `TopologyState.init {}`. Each arriving message enqueues `PacketAnimation(from=sourceNodeId, to=localNodeId, color=Blue)`. Each outbound send (observed via a new `outboundPackets: SharedFlow` added to `MeshNetworkManager`) enqueues `(from=local, to=dest)`.

A coroutine drains the `CONFLATED` channel: for each animation, launches a child coroutine that runs `Animatable(0f).animateTo(1f, tween(1200))` and appends it to `activeAnimations: MutableStateList`. On completion, removes it.

The canvas reads `activeAnimations` each frame to draw dots.

### Filter & Highlight
`filterQuery` is a `MutableState<String>` updated by the search field. A `derivedStateOf` computes `dimmedIds: Set<String>` — all node ids whose `displayName` and `id` do not contain the query (case-insensitive). Empty query → `dimmedIds` is empty (nothing dimmed).

---

## 4. Error Handling & Edge Cases

| Scenario | Behaviour |
|---|---|
| No nodes | Existing "No topology data" empty state shown unchanged |
| Single node | No edges, no animations; detail panel opens normally |
| Node disappears mid-drag | Position entry removed from map; drag gesture ends cleanly on next pointer event |
| Animation queue storm | Channel is `CONFLATED` — oldest pending animation dropped, steady flow rendered |
| Unknown intermediate hop node | Drawn as small ghost circle (grey, radius 8px, no label) so path edge renders correctly |
| Transform out of bounds | Pan clamped to ±2× canvas size; scale clamped to `0.3f..4.0f` |
| `router` returns empty hops | Treated as direct (1-hop) connection; single edge drawn, label "1 hop" |

---

## 5. Testing

### Unit tests (`TopologyStateTest.kt`)
- Spring simulation excludes pinned nodes
- Filter produces correct `dimmedIds` for various query strings
- Animation queue drops on overflow (CONFLATED channel behaviour)
- Pan/zoom clamping at boundaries

### Manual verification checklist
- [ ] Pan to canvas edge — translation clamped, at least one node remains visible
- [ ] Pinch to 0.3× and 4.0× — scale clamped, canvas doesn't disappear
- [ ] Drag a node — moves with pointer, spring no longer pulls it
- [ ] Right-click pinned node → "Unpin" context menu — unpins, spring re-attracts to cluster
- [ ] Multi-hop path in router — dashed peer-peer edges appear, hop labels correct
- [ ] Ghost node for unknown intermediate — grey circle on edge path
- [ ] Send a message — blue dot animates from local node to destination
- [ ] Receive a message — blue dot animates from source to local node
- [ ] Type in filter — non-matching nodes and their edges dim to 30%
- [ ] Click node → detail panel slides in; click ✕ → slides out
- [ ] "Send Message" in panel → Messaging tab opens with node pre-selected
- [ ] 0 nodes, 1 node, 2 nodes, 10+ nodes — no crashes

---

## 6. Files Changed

| File | Change |
|---|---|
| `ui/topology/TopologyScreen.kt` | Rewritten as thin coordinator (replaces `ui/TopologyScreen.kt`) |
| `ui/topology/TopologyState.kt` | New |
| `ui/topology/TopologyCanvas.kt` | New (extracted + extended from old TopologyScreen) |
| `ui/topology/TopologyDetailPanel.kt` | New |
| `mesh/MeshRouter.kt` | Add `fun getPath(destinationId: String): List<String>` |
| `bluetooth/MeshNetworkManager.kt` | Add `outboundPackets: SharedFlow<MeshMessage>` emitted in `routeOutbound()` |
| `data/MeshRepository.kt` | Add `messagingDestination: MutableStateFlow<String?>` |
| `ui/App.kt` | Observe `messagingDestination` to switch tabs |
