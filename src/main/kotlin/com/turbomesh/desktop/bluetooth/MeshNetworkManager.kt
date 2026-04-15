package com.turbomesh.desktop.bluetooth

import com.turbomesh.desktop.data.AppDatabase
import com.turbomesh.desktop.data.SettingsStore
import com.turbomesh.desktop.mesh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.io.File
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

    private val _outboundPackets = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    val outboundPackets: SharedFlow<MeshMessage> = _outboundPackets.asSharedFlow()

    private val _nodeLastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val nodeLastSeen: StateFlow<Map<String, Long>> = _nodeLastSeen.asStateFlow()

    private val _rssiHistory = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    val rssiHistory: StateFlow<Map<String, List<Int>>> = _rssiHistory.asStateFlow()

    private val _typingNodes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val typingNodes: StateFlow<Map<String, Long>> = _typingNodes.asStateFlow()

    private val _packetLog = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val packetLog: StateFlow<List<PacketLogEntry>> = _packetLog.asStateFlow()

    /** Emits (filename, savedFile) when an inbound file transfer completes. */
    private val _receivedFiles = MutableSharedFlow<Pair<String, File>>(extraBufferCapacity = 8)
    val receivedFiles: SharedFlow<Pair<String, File>> = _receivedFiles.asSharedFlow()

    /** msgId -> set of nodeIds that have read the message. */
    private val _readReceipts = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val readReceipts: StateFlow<Map<String, Set<String>>> = _readReceipts.asStateFlow()

    /** Emits (groupId, groupName, members) when a GROUP_INVITE arrives. */
    private val _groupInvites = MutableSharedFlow<Triple<String, String, Set<String>>>(extraBufferCapacity = 8)
    val groupInvites: SharedFlow<Triple<String, String, Set<String>>> = _groupInvites.asSharedFlow()

    private fun appendPacketLog(entry: PacketLogEntry) {
        val current = _packetLog.value
        _packetLog.value = (if (current.size >= 500) current.drop(1) else current) + entry
    }

    fun clearPacketLog() {
        _packetLog.value = emptyList()
    }

    val localNodeId: String = generateLocalId()

    private val heartbeat = HeartbeatService(settingsStore, localNodeId) { msg ->
        scope.launch { routeOutbound(msg) }
    }

    // In-progress file transfers indexed by transferId
    private data class FileTransferState(
        val name: String,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )
    private val fileTransfers = mutableMapOf<String, FileTransferState>()

    init {
        scope.launch { observeBridge() }
        scope.launch { observeProximity() }
        scope.launch { loadStoredMessages() }
        scope.launch { sweepTypingIndicators() }
        scope.launch { sweepPendingDeliveries() }
        scope.launch { sweepScheduledMessages() }
        heartbeat.start()
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
        val finalPayload = if (settings.encryptionEnabled && type != MeshMessageType.BROADCAST) {
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
            pendingDelivery = type in listOf(MeshMessageType.DATA, MeshMessageType.FILE_CHUNK, MeshMessageType.VOICE_CHUNK)
        )
        AppDatabase.insertMessage(msg)
        _messages.value = _messages.value + msg

        scope.launch { routeOutbound(msg) }
        return msg
    }

    fun editMessage(msgId: String, newPayload: ByteArray) {
        val atMs = System.currentTimeMillis()
        AppDatabase.markEdited(msgId, newPayload, atMs)
        _messages.value = _messages.value.map {
            if (it.id == msgId) it.copy(payload = newPayload, isEdited = true, editedAtMs = atMs) else it
        }
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
        // Send read receipt to the original sender
        val msg = _messages.value.firstOrNull { it.id == msgId } ?: return
        if (msg.sourceNodeId != localNodeId) {
            val receipt = MeshMessage(
                sourceNodeId = localNodeId,
                destinationNodeId = msg.sourceNodeId,
                type = MeshMessageType.READ,
                payload = "$msgId:$localNodeId".toByteArray(),
                ttl = 3,
            )
            scope.launch { routeOutbound(receipt) }
        }
    }

    fun acknowledgeMessage(msgId: String) {
        AppDatabase.markAcknowledged(msgId)
        _messages.value = _messages.value.map { if (it.id == msgId) it.copy(isAcknowledged = true, pendingDelivery = false) else it }
    }

    private suspend fun routeOutbound(msg: MeshMessage) {
        val settings = settingsStore.current()
        if (settings.bridgeEnabled) {
            val packet = msg.serialize()
            bridgeManager.forward(msg.sourceNodeId, msg.destinationNodeId, packet)
        }
        _networkStats.value = _networkStats.value.copy(messagesSent = _networkStats.value.messagesSent + 1)
        _outboundPackets.tryEmit(msg)
        appendPacketLog(PacketLogEntry(
            direction = PacketDirection.OUTBOUND,
            src = msg.sourceNodeId, dst = msg.destinationNodeId,
            type = msg.type, sizeBytes = msg.payload.size,
            hopCount = msg.hopCount, msgId = msg.id,
        ))
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

    fun sendTyping(destinationId: String) {
        val msg = MeshMessage(
            sourceNodeId = localNodeId, destinationNodeId = destinationId,
            type = MeshMessageType.TYPING, payload = localNodeId.toByteArray(),
            ttl = 2,
        )
        scope.launch { routeOutbound(msg) }
    }

    fun sendReaction(targetMsgId: String, destinationId: String, emoji: String) {
        sendMessage(destinationId, "$targetMsgId:$emoji".toByteArray(), MeshMessageType.REACTION)
    }

    private fun handleInboundPacket(src: String, data: ByteArray) {
        try {
            val raw = deserializePacket(data) ?: return
            val routed = router.routeMessage(raw) ?: return

            _nodeLastSeen.value = _nodeLastSeen.value + (src to System.currentTimeMillis())

            appendPacketLog(PacketLogEntry(
                direction = PacketDirection.INBOUND,
                src = src, dst = routed.destinationNodeId,
                type = routed.type, sizeBytes = data.size,
                hopCount = routed.hopCount, msgId = routed.id,
            ))

            when (routed.type) {
                MeshMessageType.HEARTBEAT -> {
                    log.debug("Heartbeat from $src")
                    _networkStats.value = _networkStats.value.copy(
                        nearbyNodes = _networkStats.value.nearbyNodes + src
                    )
                    return // don't store heartbeats
                }
                MeshMessageType.TYPING -> {
                    val expiry = System.currentTimeMillis() + 3_000
                    _typingNodes.value = _typingNodes.value + (src to expiry)
                    return
                }
                MeshMessageType.KEY_EXCHANGE -> {
                    crypto.storePeerPublicKey(src, routed.payload)
                    return
                }
                MeshMessageType.ACK -> {
                    val targetId = String(routed.payload)
                    acknowledgeMessage(targetId)
                    return
                }
                MeshMessageType.READ -> {
                    // Payload: "msgId:nodeId"
                    val parts = String(routed.payload).split(":", limit = 2)
                    if (parts.size == 2) {
                        val targetMsgId = parts[0]
                        val readerId = parts[1]
                        val current = _readReceipts.value[targetMsgId] ?: emptySet()
                        _readReceipts.value = _readReceipts.value + (targetMsgId to current + readerId)
                    }
                    return
                }
                MeshMessageType.GROUP_INVITE -> {
                    // Payload: "groupId:groupName:member1,member2,..."
                    val text = String(routed.payload)
                    val parts = text.split(":", limit = 3)
                    if (parts.size == 3) {
                        val members = parts[2].split(",").filter { it.isNotBlank() }.toSet()
                        scope.launch { _groupInvites.emit(Triple(parts[0], parts[1], members)) }
                    }
                    return
                }
                else -> {}
            }

            val settings = settingsStore.current()
            val payload = if (settings.encryptionEnabled && routed.type != MeshMessageType.BROADCAST) {
                crypto.decrypt(routed.payload, src) ?: routed.payload
            } else routed.payload

            val received = routed.copy(payload = payload)

            // Auto-acknowledge DATA messages from other nodes
            if (received.type == MeshMessageType.DATA && received.sourceNodeId != localNodeId) {
                sendAck(received.id, received.sourceNodeId)
            }

            // Handle file transfer chunks
            when (received.type) {
                MeshMessageType.FILE_CHUNK -> {
                    handleFileChunk(received)
                    return
                }
                MeshMessageType.FILE_COMPLETE -> {
                    handleFileComplete(received)
                    return
                }
                else -> {}
            }

            AppDatabase.insertMessage(received)
            _messages.value = _messages.value + received
            scope.launch { _inboundPackets.emit(received) }
            _networkStats.value = _networkStats.value.copy(messagesReceived = _networkStats.value.messagesReceived + 1)

            val rssi = bleScanner.scanResults.value.firstOrNull { it.id == src }?.rssi ?: -70
            AppDatabase.logRssi(src, rssi)
            val history = (_rssiHistory.value[src] ?: emptyList()) + rssi
            _rssiHistory.value = _rssiHistory.value + (src to history.takeLast(60))
        } catch (e: Exception) {
            log.warn("Failed to parse inbound packet from $src: ${e.message}")
        }
    }

    private fun sendAck(msgId: String, destinationId: String) {
        val ack = MeshMessage(
            sourceNodeId = localNodeId,
            destinationNodeId = destinationId,
            type = MeshMessageType.ACK,
            payload = msgId.toByteArray(),
            ttl = 2,
        )
        scope.launch { routeOutbound(ack) }
    }

    private data class FileChunkInfo(
        val transferId: String,
        val idx: Int,
        val total: Int,
        val name: String,
        val data: ByteArray,
    )

    private fun parseFileChunk(payload: ByteArray): FileChunkInfo? {
        // Payload format: "transferId:idx:total:filename:" + chunkBytes
        var colonCount = 0
        var pos = 0
        while (pos < payload.size && colonCount < 4) {
            if (payload[pos] == ':'.code.toByte()) colonCount++
            pos++
        }
        if (colonCount < 4) return null
        val metaStr = String(payload, 0, pos - 1)
        val chunkData = payload.copyOfRange(pos, payload.size)
        val parts = metaStr.split(":", limit = 4)
        if (parts.size < 4) return null
        return FileChunkInfo(
            transferId = parts[0],
            idx = parts[1].toIntOrNull() ?: return null,
            total = parts[2].toIntOrNull() ?: return null,
            name = parts[3],
            data = chunkData,
        )
    }

    private fun handleFileChunk(msg: MeshMessage) {
        val info = parseFileChunk(msg.payload) ?: return
        val state = fileTransfers.getOrPut(info.transferId) {
            FileTransferState(info.name, info.total)
        }
        state.chunks[info.idx] = info.data
        log.debug("File chunk ${info.idx + 1}/${info.total} received for '${info.name}'")
    }

    private fun handleFileComplete(msg: MeshMessage) {
        // FILE_COMPLETE payload: "transferId:totalChunks:filename:originalSize"
        val metaParts = String(msg.payload).split(":", limit = 4)
        if (metaParts.size < 3) return
        val transferId = metaParts[0]
        val totalChunks = metaParts[1].toIntOrNull() ?: return
        val name = metaParts[2]

        val state = fileTransfers[transferId]
        if (state == null || state.chunks.size < totalChunks) {
            log.warn("Incomplete file transfer $transferId: ${state?.chunks?.size ?: 0}/$totalChunks chunks")
            return
        }

        val dir = File(System.getProperty("user.home"), "turbomesh_received").also { it.mkdirs() }
        val outFile = File(dir, name)
        try {
            outFile.outputStream().use { out ->
                (0 until totalChunks).forEach { i ->
                    state.chunks[i]?.let { out.write(it) }
                }
            }
            log.info("File saved: ${outFile.absolutePath}")
        } catch (e: Exception) {
            log.error("Failed to save file: ${e.message}")
            return
        }
        fileTransfers.remove(transferId)

        // Insert a summary message for the UI
        val summaryMsg = MeshMessage(
            id = UUID.randomUUID().toString(),
            sourceNodeId = msg.sourceNodeId,
            destinationNodeId = msg.destinationNodeId,
            type = MeshMessageType.FILE_COMPLETE,
            payload = "FILE:${outFile.absolutePath}:$name".toByteArray(),
            timestamp = System.currentTimeMillis(),
        )
        AppDatabase.insertMessage(summaryMsg)
        _messages.value = _messages.value + summaryMsg
        scope.launch { _inboundPackets.emit(summaryMsg) }
        scope.launch { _receivedFiles.emit(name to outFile) }
        _networkStats.value = _networkStats.value.copy(messagesReceived = _networkStats.value.messagesReceived + 1)
    }

    private suspend fun sweepTypingIndicators() {
        while (true) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val pruned = _typingNodes.value.filterValues { it > now }
            if (pruned.size != _typingNodes.value.size) _typingNodes.value = pruned
        }
    }

    private val deliveredPendingIds = mutableSetOf<String>()

    private suspend fun sweepPendingDeliveries() {
        while (true) {
            delay(10_000)
            val now = System.currentTimeMillis()
            val recentNodes = _nodeLastSeen.value.filterValues { now - it < 15_000 }.keys
            val nearby = _networkStats.value.nearbyNodes + recentNodes
            
            nearby.forEach { nodeId ->
                if (!crypto.hasPeerPublicKey(nodeId)) {
                    val msg = MeshMessage(
                        sourceNodeId = localNodeId,
                        destinationNodeId = nodeId,
                        type = MeshMessageType.KEY_EXCHANGE,
                        payload = crypto.getPublicKeyBytes(),
                        ttl = 5
                    )
                    routeOutbound(msg)
                }
                
                val pending = AppDatabase.getPendingMessages(nodeId)
                pending.forEach { msg ->
                    if (msg.id !in deliveredPendingIds) {
                        deliveredPendingIds.add(msg.id)
                        routeOutbound(msg)
                    }
                }
            }
            deliveredPendingIds.clear() // reset to retry on next cycle if not acked
        }
    }

    private suspend fun sweepScheduledMessages() {
        while (true) {
            delay(5_000)
            val now = System.currentTimeMillis()
            _messages.value
                .filter { msg ->
                    msg.sourceNodeId == localNodeId &&
                    msg.scheduledAtMs != null &&
                    msg.scheduledAtMs <= now &&
                    msg.pendingDelivery
                }
                .forEach { msg -> routeOutbound(msg) }
        }
    }

    fun sendGroupInvite(groupId: String, groupName: String, members: Set<String>) {
        val payload = "$groupId:$groupName:${members.joinToString(",")}".toByteArray()
        members.filter { it != localNodeId }.forEach { memberId ->
            val msg = MeshMessage(
                sourceNodeId = localNodeId,
                destinationNodeId = memberId,
                type = MeshMessageType.GROUP_INVITE,
                payload = payload,
                ttl = settingsStore.current().defaultTtl,
            )
            AppDatabase.insertMessage(msg)
            scope.launch { routeOutbound(msg) }
        }
    }

    private suspend fun loadStoredMessages() {
        val stored = withContext(Dispatchers.IO) { AppDatabase.getAllMessages() }
        // Filter out expired messages on load
        val now = System.currentTimeMillis()
        _messages.value = stored.filter { it.expiresAtMs == null || it.expiresAtMs > now }
    }

    fun connectBridge(url: String) = bridgeManager.connect(url)
    fun disconnectBridge() = bridgeManager.disconnect()
    fun startScan() = bleScanner.startScan()
    fun stopScan() = bleScanner.stopScan()
    fun getPath(destId: String): List<String> = router.getPath(destId)

    fun destroy() {
        heartbeat.destroy()
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
    fun writeLen(b: ByteArray) { buf.write(b.size.coerceAtMost(255)); buf.write(b.copyOf(b.size.coerceAtMost(255))) }
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
        MeshMessage(
            id = id, sourceNodeId = src, destinationNodeId = dst, type = type,
            payload = payload, timestamp = System.currentTimeMillis(), ttl = ttl, hopCount = hopCount,
        )
    } catch (_: Exception) { null }
}
