package com.turbomesh.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.AppDatabase
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.ui.App

fun main() = application {
    AppDatabase.connect()

    val repo = MeshRepository()

    val windowState = rememberWindowState(size = DpSize(1024.dp, 768.dp))

    Window(
        onCloseRequest = {
            repo.destroy()
            exitApplication()
        },
        state = windowState,
        title = "TurboMesh Desktop",
    ) {
        App(repo)
    }
}
