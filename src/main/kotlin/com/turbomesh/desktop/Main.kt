package com.turbomesh.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.turbomesh.desktop.data.AppDatabase
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.mesh.MeshMessageType
import com.turbomesh.desktop.ui.App
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

fun main() = application {
    AppDatabase.connect()

    val repo = remember { MeshRepository() }
    val windowState = rememberWindowState(size = DpSize(1024.dp, 768.dp))
    var windowVisible by remember { mutableStateOf(true) }

    val trayIcon = remember { makeTrayIcon() }
    val unreadCount = remember { mutableStateOf(0) }

    // Watch for inbound messages and fire notifications
    LaunchedEffect(Unit) {
        repo.inboundPackets.onEach { msg ->
            if (msg.type in listOf(
                    MeshMessageType.DATA, MeshMessageType.BROADCAST,
                    MeshMessageType.EMERGENCY, MeshMessageType.REPLY)) {
                unreadCount.value++
                val sender = repo.getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId.take(8) }
                val body = String(msg.payload).take(80)
                val title = if (msg.type == MeshMessageType.EMERGENCY) "🆘 EMERGENCY" else "New message from $sender"
                showTrayNotification(title, body)
            }
        }.launchIn(this)
    }

    // System tray
    Tray(
        icon = trayIcon,
        tooltip = "TurboMesh",
        menu = {
            Item("Open TurboMesh") { windowVisible = true }
            Item("New Broadcast…") { windowVisible = true }
            Separator()
            Item("Quit") { repo.destroy(); exitApplication() }
        }
    )

    if (windowVisible) {
        Window(
            onCloseRequest = { windowVisible = false },  // minimize to tray instead of quit
            state = windowState,
            title = if (unreadCount.value > 0) "TurboMesh (${unreadCount.value})" else "TurboMesh Desktop",
        ) {
            LaunchedEffect(windowVisible) { if (windowVisible) unreadCount.value = 0 }
            App(repo)
        }
    }
}

private fun makeTrayIcon(): BitmapPainter {
    val size = 32
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    // Draw mesh node icon: circle with 3 connection lines
    g.color = java.awt.Color(0x1565C0)
    g.fillOval(0, 0, size, size)
    g.color = java.awt.Color.WHITE
    g.fillOval(4, 4, 8, 8)       // top-left node
    g.fillOval(20, 4, 8, 8)      // top-right node
    g.fillOval(12, 18, 8, 8)     // bottom node
    g.stroke = java.awt.BasicStroke(2f)
    g.drawLine(8, 8, 24, 8)      // top edge
    g.drawLine(8, 8, 16, 22)     // left diagonal
    g.drawLine(24, 8, 16, 22)    // right diagonal
    g.dispose()
    val baos = ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    return BitmapPainter(loadImageBitmap(baos.toByteArray().inputStream()))
}

private fun showTrayNotification(title: String, body: String) {
    try {
        val tray = java.awt.SystemTray.getSystemTray()
        val icons = tray.trayIcons
        if (icons.isNotEmpty()) {
            icons[0].displayMessage(title, body, java.awt.TrayIcon.MessageType.INFO)
        }
    } catch (_: Exception) {}
}
