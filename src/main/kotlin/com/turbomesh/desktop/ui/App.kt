package com.turbomesh.desktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.turbomesh.desktop.data.MeshRepository
import kotlinx.coroutines.delay

// ──────────────────────────────────────────────────────────────────────────────
// Root composable
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun App(repo: MeshRepository) {
    val settings by repo.settingsStore.settings.collectAsState()
    val strings = remember(settings.appLanguage) { languageToStrings(settings.appLanguage) }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(settings.fontScale, baseDensity) {
        Density(density = baseDensity.density, fontScale = settings.fontScale)
    }
    CompositionLocalProvider(LocalStrings provides strings, LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = if (settings.darkTheme) darkColorScheme() else lightColorScheme()) {
            AppShell(repo)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Shell: handles lock overlay, tab bar, detached windows
// ──────────────────────────────────────────────────────────────────────────────

/** Index → tab meta */
private data class TabMeta(val labelFn: (AppStrings) -> String, val content: @Composable (MeshRepository) -> Unit)

private val TABS: List<TabMeta> = listOf(
    TabMeta({ it.tabMessaging })  { repo -> MessagingScreen(repo) },
    TabMeta({ it.tabDevices })    { repo -> DevicesScreen(repo) },
    TabMeta({ it.tabNetwork })    { repo -> NetworkScreen(repo) },
    TabMeta({ it.tabPacketLog })  { repo -> PacketLogScreen(repo) },
    TabMeta({ it.tabTopology })   { repo -> TopologyScreen(repo) },
    TabMeta({ it.tabSettings })   { repo -> SettingsScreen(repo, onThemeToggle = { /* handled via settings flow */ }) },
)

@Composable
private fun AppShell(repo: MeshRepository) {
    val settings by repo.settingsStore.settings.collectAsState()
    val s = LocalStrings.current

    var selectedTab by remember { mutableStateOf(0) }
    var isLocked by remember { mutableStateOf(false) }
    var lastActivityMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val messages by repo.messages.collectAsState()
    val unreadCount = remember(messages) {
        messages.count { msg ->
            msg.readAtMs == null && msg.deletedAtMs == null &&
            msg.sourceNodeId != repo.localNodeId
        }
    }

    // Detached windows: set of tab indices currently open in their own window
    var detachedTabs by remember { mutableStateOf(setOf<Int>()) }

    // ── Navigate to Messaging tab when topology detail panel requests it ────────
    LaunchedEffect(Unit) {
        repo.messagingDestination.collect { destId ->
            if (destId != null) {
                selectedTab = 0   // Messaging is TABS index 0
                repo.messagingDestination.value = null
            }
        }
    }

    // ── Auto-lock timer ──────────────────────────────────────────────────────
    LaunchedEffect(settings.autoLockEnabled, settings.autoLockTimeoutMs) {
        while (true) {
            delay(5_000)
            if (settings.autoLockEnabled && settings.appPin.isNotBlank()) {
                val idle = System.currentTimeMillis() - lastActivityMs
                if (idle >= settings.autoLockTimeoutMs) {
                    isLocked = true
                }
            }
        }
    }

    fun resetActivity() { lastActivityMs = System.currentTimeMillis() }

    // ── Detached windows ─────────────────────────────────────────────────────
    detachedTabs.forEach { tabIdx ->
        key(tabIdx) {
            DetachedTabWindow(
                repo = repo,
                tabIdx = tabIdx,
                onClose = { detachedTabs = detachedTabs - tabIdx },
            )
        }
    }

    // ── Main window content ──────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Reset activity timer on any tap/click
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        resetActivity()
                    }
                }
            }
            .onKeyEvent { resetActivity(); false }
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Tab row + Detach Button ──────────────────────────────────
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        edgePadding = 0.dp,
                        modifier = Modifier.weight(1f) // Tab row takes available space
                    ) {
                        TABS.forEachIndexed { index, meta ->
                            if (index in detachedTabs) return@forEachIndexed
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index; resetActivity() },
                                text = {
                                    if (index == 0 && unreadCount > 0) {
                                        BadgedBox(badge = {
                                            Badge { Text(if (unreadCount > 99) "99+" else "$unreadCount") }
                                        }) {
                                            Text(meta.labelFn(s))
                                        }
                                    } else {
                                        Text(meta.labelFn(s))
                                    }
                                }
                            )
                        }
                    }

                    // Detach button — safely away from screen contents
                    val effectiveTab = if (selectedTab in detachedTabs) {
                        TABS.indices.firstOrNull { it !in detachedTabs } ?: 0
                    } else selectedTab

                    TextButton(
                        onClick = {
                            detachedTabs = detachedTabs + effectiveTab
                            selectedTab = TABS.indices.firstOrNull { it !in detachedTabs && it != effectiveTab } ?: 0
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Text("⧇ Detach", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // ── Tab content ────────────────────────────────────────────────
                Box(modifier = Modifier.weight(1f)) {
                    val effectiveTab = if (selectedTab in detachedTabs) {
                        TABS.indices.firstOrNull { it !in detachedTabs } ?: 0
                    } else selectedTab
                    TABS[effectiveTab].content(repo)
                }
            }
        }

        // ── Lock Screen overlay ───────────────────────────────────────────────
        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            LockScreen(
                storedPin = settings.appPin,
                onUnlock = {
                    isLocked = false
                    resetActivity()
                }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Detached tab window
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun DetachedTabWindow(repo: MeshRepository, tabIdx: Int, onClose: () -> Unit) {
    val tabMeta = TABS[tabIdx]
    val innerSettings by repo.settingsStore.settings.collectAsState()
    val innerStrings = remember(innerSettings.appLanguage) { languageToStrings(innerSettings.appLanguage) }
    val windowState = rememberWindowState(size = DpSize(900.dp, 680.dp))
    Window(
        onCloseRequest = onClose,
        title = "TurboMesh — ${tabMeta.labelFn(innerStrings)}",
        state = windowState,
    ) {
        CompositionLocalProvider(LocalStrings provides innerStrings) {
            MaterialTheme(colorScheme = if (innerSettings.darkTheme) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    tabMeta.content(repo)
                }
            }
        }
    }
}
