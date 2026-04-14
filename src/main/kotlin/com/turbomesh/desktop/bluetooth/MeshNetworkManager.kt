package com.turbomesh.desktop.bluetooth

import com.turbomesh.desktop.data.AppDatabase
import com.turbomesh.desktop.data.SettingsStore
import com.turbomesh.desktop.mesh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.UUID

class MeshNetworkManager(
    private val bleScanner: BleScanner,
    private val bridgeManager: BridgeManager,
    private val router: MeshRouter,
    private val crypto: CryptoManager,
    private val settingsStore: SettingsStore,
) {
    private val log = LoggerFactory.getLogger(MeshNetworkManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    private val _inboundPackets = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 128)
    val inboundPackets: SharedFlow<MeshMessage> = _inboundPackets.asSharedFlow()

    val localNodeId: String = generateLocalId()

    init {
        scope.launch { observeBridge() }
        scope.launch { observeProximity() }
        scope.launch { loadStoredMessages() }
    }

    private fun generateLocalId(): String {
        val prefs = java.util.prefs.Preferences.userRoot().node("turbomesh/identity")
        return prefs.get("node_id", null) ?: run {
            val id = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
            prefs.put("node_id", id)
            id
        }
    }

    fun sendMessage(
        destinationId: String,
        payload: ByteArray,
        type: MeshMessageType = MeshMessageType.DATA,
        replyToMsgId: String? = null,
        scheduledAtMs: Long? = null,
        expiresAtMs: Long? = null,
    ): MeshMessage {
        val settings = settingsStore.current()
        val finalPayload = if (settings.encryptionEnabled) {
            crypto.encrypt(payload, destinationId) ?: payload
        } else payload

        val msg = MeshMessage(
            id = UUID.randomUUID().toString(),
            sourceNodeId = localNodeId,
            destinationNodeId = destinationId,
            type = type,
            payload = finalPayload,
            timestamp = System.currentTimeMillis(),
            ttl = settings.defaultTtl,
            hopCount = 0,
            replyToMsgId = replyToMsgId,
            scheduledAtMs = scheduledAtMs,
            expiresAtMs = expiresAtMs,
        )
        AppDatabase.insertMessage(msg)
        _messages.value = _messages.value + msg

        scope.launch { routeOutbound(msg) }
        return msg
    }

    fun editMessage(msgId: String, newPayload: ByteArray) {
        val atMs = System.currentTimeMillis()
        AppDatabase.markEdited(msgId, newPayload, atMs)
        _messages.value = _messages.value.map { if (it.id == msgId) it.copy(payload = newPayload, isEdited = true, editedAtMs = atMs) else it }
    }

    fun deleteMessage(msgId: String) {
        val atMs = System.currentTimeMillis()
        AppDatabase.markDeleted(msgId, atMs)
        _messages.value = _messages.value.map { if (it.id == msgId) it.copy(deletedAtMs = atMs) else it }
    }

    fun pinMessage(msgId: String, pinned: Boolean) {
        AppDatabase.setPinned(msgId, pinned)
        _messages.value = _messages.value.map { if (it.id == msgId) it.copy(isPinned = pinned) else it }
    }

    fun markRead(msgId: String) {
        val atMs = System.currentTimeMillis()
        AppDatabase.markRead(msgId, atMs)
        _messages.value = _messages.value.map { if (it.id == msgId) it.copy(readAtMs = atMs) else it }
    }

    fun acknowledgeMessage(msgId: String) {
        AppDatabase.markAcknowledged(msgId)
        _messages.value = _messages.value.map { if (it.id == msgId) it.copy(isAcknowledged = true) else it }
    }

    private suspend fun routeOutbound(msg: MeshMessage) {
        val settings = settingsStore.current()
        if (settings.bridgeEnabled && bridgeManager.isConnected.value) {
            val packet = msg.serialize()
            bridgeManager.forward(msg.sourceNodeId, msg.destinationNodeId, packet)
        }
        _networkStats.value = _networkStats.value.copy(messagesSent = _networkStats.value.messagesSent + 1)
    }

    private suspend fun observeBridge() {
        bridgeManager.inboundMessages.collect { (src, data) ->
            handleInboundPacket(src, data)
        }
    }

    private suspend fun observeProximity() {
        bleScanner.proximityEvents.collect { (nodeId, nearby) ->
            log.info("Node $nodeId is ${if (nearby) "nearby" else "far"}")
            _networkStats.value = _networkStats.value.copy(
                nearbyNodes = if (nearby)
                    _networkStats.value.nearbyNodes + nodeId
                else
                    _networkStats.value.nearbyNodes - nodeId
            )
        }
    }

    private fun handleInboundPacket(src: String, data: ByteArray) {
        try {
            val raw = deserializePacket(data) ?: return
            val routed = router.routeMessage(raw) ?: return  // null = duplicate or TTL exhausted

            val settings = settingsStore.current()
            val payload = if (settings.encryptionEnabled) {
                crypto.decrypt(routed.payload, src) ?: routed.payload
            } else routed.payload

            val received = routed.copy(payload = payload)
            AppDatabase.insertMessage(received)
            _messages.value = _messages.value + received
            scope.launch { _inboundPackets.emit(received) }
            _networkStats.value = _networkStats.value.copy(messagesReceived = _networkStats.value.messagesReceived + 1)

            AppDatabase.logRssi(src, -70)
        } catch (e: Exception) {
            log.warn("Failed to parse inbound packet from $src: ${e.message}")
        }
    }

    private suspend fun loadStoredMessages() {
        val stored = withContext(Dispatchers.IO) { AppDatabase.getAllMessages() }
        _messages.value = stored
    }

    fun connectBridge(url: String) {
        bridgeManager.connect(url)
    }

    fun disconnectBridge() {
        bridgeManager.disconnect()
    }

    fun startScan() = bleScanner.startScan()
    fun stopScan() = bleScanner.stopScan()

    fun destroy() {
        scope.cancel()
    }
}

data class NetworkStats(
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val nearbyNodes: Set<String> = emptySet(),
    val routeCount: Int = 0,
)

private fun MeshMessage.serialize(): ByteArray {
    val idBytes = id.toByteArray()
    val srcBytes = sourceNodeId.toByteArray()
    val dstBytes = destinationNodeId.toByteArray()
    val buf = java.io.ByteArrayOutputStream()
    fun writeLen(b: ByteArray) { buf.write(b.size); buf.write(b) }
    buf.write(type.opcode.toInt())
    buf.write(ttl)
    buf.write(hopCount)
    writeLen(idBytes); writeLen(srcBytes); writeLen(dstBytes)
    buf.write(payload.size shr 8); buf.write(payload.size and 0xFF)
    buf.write(payload)
    return buf.toByteArray()
}

private fun deserializePacket(data: ByteArray): MeshMessage? {
    return try {
        val buf = java.io.ByteArrayInputStream(data)
        val opcode = buf.read().toByte()
        val type = MeshMessageType.values().firstOrNull { it.opcode == opcode } ?: MeshMessageType.DATA
        val ttl = buf.read()
        val hopCount = buf.read()
        fun readStr(): String { val len = buf.read(); return String(buf.readNBytes(len)) }
        val id = readStr(); val src = readStr(); val dst = readStr()
        val payloadLen = (buf.read() shl 8) or buf.read()
        val payload = buf.readNBytes(payloadLen)
        MeshMessage(id = id, sourceNodeId = src, destinationNodeId = dst, type = type,
            payload = payload, timestamp = System.currentTimeMillis(), ttl = ttl, hopCount = hopCount)
    } catch (_: Exception) { null }
}
