package com.turbomesh.desktop.bluetooth

import com.turbomesh.desktop.data.SettingsStore
import com.turbomesh.desktop.mesh.MeshMessage
import com.turbomesh.desktop.mesh.MeshMessageType
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.UUID

class HeartbeatService(
    private val settingsStore: SettingsStore,
    private val localNodeId: String,
    private val onSend: (MeshMessage) -> Unit,
) {
    private val log = LoggerFactory.getLogger(HeartbeatService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val intervalMs = settingsStore.current().heartbeatIntervalMs
                delay(intervalMs)
                val hb = MeshMessage(
                    id = UUID.randomUUID().toString(),
                    sourceNodeId = localNodeId,
                    destinationNodeId = MeshMessage.BROADCAST_DESTINATION,
                    type = MeshMessageType.HEARTBEAT,
                    payload = localNodeId.toByteArray(),
                )
                try { onSend(hb) } catch (e: Exception) { log.warn("Heartbeat send failed: ${e.message}") }
                log.debug("Heartbeat sent from $localNodeId")
            }
        }
        log.info("HeartbeatService started (interval=${settingsStore.current().heartbeatIntervalMs}ms)")
    }

    fun stop() { job?.cancel(); job = null }
    fun destroy() { scope.cancel() }
}
