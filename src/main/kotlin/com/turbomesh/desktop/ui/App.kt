package com.turbomesh.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.turbomesh.desktop.data.MeshRepository

@Composable
fun App(repo: MeshRepository) {
    var darkTheme by remember { mutableStateOf(repo.settingsStore.current().darkTheme) }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            MainContent(repo = repo, onThemeToggle = { darkTheme = it })
        }
    }
}

@Composable
private fun MainContent(repo: MeshRepository, onThemeToggle: (Boolean) -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Messaging", "Devices", "Network", "Settings")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        when (selectedTab) {
            0 -> MessagingScreen(repo)
            1 -> DevicesScreen(repo)
            2 -> NetworkScreen(repo)
            3 -> SettingsScreen(repo, onThemeToggle)
        }
    }
}
