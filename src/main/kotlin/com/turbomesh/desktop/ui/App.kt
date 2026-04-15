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

    CompositionLocalProvider(LocalStrings provides strings) {
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
    TabMeta({ it.tabSettings })   { repo, -> SettingsScreen(repo, onThemeToggle = { /* handled via settings flow */ }) },
)

@Composable
private fun AppShell(repo: MeshRepository) {
    val settings by repo.settingsStore.settings.collectAsState()
    val s = LocalStrings.current

    var selectedTab by remember { mutableStateOf(0) }
    var isLocked by remember { mutableStateOf(false) }
    var lastActivityMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // Detached windows: set of tab indices currently open in their own window
    val detachedTabs = remember { mutableStateSetOf<Int>() }

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
        val tabMeta = TABS[tabIdx]
        val windowState = rememberWindowState(size = DpSize(900.dp, 680.dp))
        Window(
            onCloseRequest = { detachedTabs -= tabIdx },
            title = "TurboMesh — ${tabMeta.labelFn(s)}",
            state = windowState,
        ) {
            val innerSettings by repo.settingsStore.settings.collectAsState()
            val innerStrings = remember(innerSettings.appLanguage) { languageToStrings(innerSettings.appLanguage) }
            CompositionLocalProvider(LocalStrings provides innerStrings) {
                MaterialTheme(colorScheme = if (innerSettings.darkTheme) darkColorScheme() else lightColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        tabMeta.content(repo)
                    }
                }
            }
        }
    }

    // ── Main window content ──────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Reset activity timer on any tap/click
            .pointerInput(Unit) {
                detectTapGestures(onPress = { resetActivity() })
            }
            .onKeyEvent { resetActivity(); false }
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Tab row ──────────────────────────────────────────────────
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                ) {
                    TABS.forEachIndexed { index, meta ->
                        if (index in detachedTabs) return@forEachIndexed
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index; resetActivity() },
                            text = { Text(meta.labelFn(s)) }
                        )
                    }
                }

                // ── Tab content + Detach button ──────────────────────────────
                Box(modifier = Modifier.weight(1f)) {
                    val effectiveTab = if (selectedTab in detachedTabs) {
                        // Tab was detached — fall back to first non-detached tab
                        TABS.indices.firstOrNull { it !in detachedTabs } ?: 0
                    } else selectedTab

                    TABS[effectiveTab].content(repo)

                    // Detach button — top-right corner
                    TextButton(
                        onClick = {
                            detachedTabs += effectiveTab
                            // Switch to next available tab
                            selectedTab = TABS.indices.firstOrNull { it !in detachedTabs && it != effectiveTab } ?: 0
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    ) {
                        Text("⧉ Detach", style = MaterialTheme.typography.labelSmall)
                    }
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

// Internal helper: mutable set backed by snapshot state
@Composable
private fun <T> mutableStateSetOf(vararg elements: T): MutableSet<T> {
    return remember { mutableStateOf(mutableSetOf(*elements)).value }
}
