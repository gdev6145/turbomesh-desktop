package com.turbomesh.desktop.data

import com.turbomesh.desktop.bluetooth.BleScanner
import com.turbomesh.desktop.bluetooth.BridgeManager
import com.turbomesh.desktop.bluetooth.MeshNetworkManager
import com.turbomesh.desktop.mesh.CryptoManager
import com.turbomesh.desktop.mesh.MeshMessage
import com.turbomesh.desktop.mesh.MeshMessageType
import com.turbomesh.desktop.mesh.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeshRepository {
    val settingsStore = SettingsStore()
    val nicknameStore = NicknameStore()
    val groupStore = GroupStore()
    val starStore = StarStore()
    val noteStore = NoteStore()

    // Auto-reply: track last auto-reply time per peer (in-memory, resets on restart)
    private val autoRepliedTo = mutableMapOf<String, Long>()

    private val bleScanner = BleScanner()
    private val bridgeManager = BridgeManager()
    private val router = MeshRouter()
    private val crypto = CryptoManager()

    // Telemetry (writes local log when enabled)
    private val telemetry = Telemetry(settingsStore)

    val networkManager = MeshNetworkManager(
        bleScanner = bleScanner,
        bridgeManager = bridgeManager,
        router = router,
        crypto = crypto,
        settingsStore = settingsStore,
    )

    val messages: StateFlow<List<MeshMessage>> = networkManager.messages
    val scanResults = bleScanner.scanResults
    val isScanning = bleScanner.isScanning
    val scanError = bleScanner.scanError
    val isBridgeConnected = bridgeManager.isConnected
    val proximityEvents = bleScanner.proximityEvents
    val networkStats = networkManager.networkStats
    val nodeLastSeen = networkManager.nodeLastSeen
    val rssiHistory = networkManager.rssiHistory
    val typingNodes = networkManager.typingNodes
    val inboundPackets = networkManager.inboundPackets
    val packetLog = networkManager.packetLog
    val receivedFiles = networkManager.receivedFiles
    val readReceipts = networkManager.readReceipts
    val groupInvites = networkManager.groupInvites

    /** Set by TopologyDetailPanel "Send Message" button; App.kt observes to switch tabs. */
    val messagingDestination = MutableStateFlow<String?>(null)

    fun navigateToMessaging(nodeId: String) {
        messagingDestination.value = nodeId
    }

    val localNodeId: String get() = networkManager.localNodeId
    val publicKeyBytes: ByteArray get() = crypto.getPublicKeyBytes()

    var proximityAlertsEnabled: Boolean
        get() = bleScanner.proximityAlertsEnabled
        set(v) { bleScanner.proximityAlertsEnabled = v }

    fun startScan() = networkManager.startScan()
    fun stopScan() = networkManager.stopScan()
    fun clearScanResults() = bleScanner.clearResults()

    fun connectBridge(url: String) = networkManager.connectBridge(url)
    fun disconnectBridge() = networkManager.disconnectBridge()

    fun clearPacketLog() = networkManager.clearPacketLog()

    fun sendMessage(
        destinationId: String,
        text: String,
        type: MeshMessageType = MeshMessageType.DATA,
        replyToMsgId: String? = null,
        scheduledAtMs: Long? = null,
        expiresAtMs: Long? = null,
    ) {
        networkManager.sendMessage(
            destinationId = destinationId,
            payload = text.toByteArray(),
            type = type,
            replyToMsgId = replyToMsgId,
            scheduledAtMs = scheduledAtMs,
            expiresAtMs = expiresAtMs,
        )
        telemetry.logEvent("send_message", mapOf("destination" to destinationId, "type" to type.name))
    }

    fun broadcastMessage(text: String) =
        sendMessage(MeshMessage.BROADCAST_DESTINATION, text, MeshMessageType.BROADCAST).also {
            telemetry.logEvent("broadcast", mapOf("length" to text.length.toString()))
        }

    fun editMessage(msgId: String, newText: String) =
        networkManager.editMessage(msgId, newText.toByteArray())

    fun sendTyping(destinationId: String) = networkManager.sendTyping(destinationId)
    fun sendReaction(targetMsgId: String, destinationId: String, emoji: String) =
        networkManager.sendReaction(targetMsgId, destinationId, emoji)

    fun deleteMessage(msgId: String) = networkManager.deleteMessage(msgId)
    fun pinMessage(msgId: String, pinned: Boolean) = networkManager.pinMessage(msgId, pinned)
    fun markRead(msgId: String) = networkManager.markRead(msgId)
    fun acknowledgeMessage(msgId: String) = networkManager.acknowledgeMessage(msgId)

    fun storePeerKey(peerId: String, keyBytes: ByteArray) = crypto.storePeerPublicKey(peerId, keyBytes)
    fun hasPeerKey(peerId: String) = crypto.hasPeerPublicKey(peerId)
    fun getPairingPin(peerId: String) = crypto.derivePairingPin(peerId)

    fun setNickname(nodeId: String, name: String) = nicknameStore.set(nodeId, name)
    fun getNickname(nodeId: String) = nicknameStore.get(nodeId)

    fun updateSettings(s: MeshSettings) {
        settingsStore.update(s)
        if (s.bridgeEnabled && s.bridgeServerUrl.isNotBlank()) {
            networkManager.connectBridge(s.bridgeServerUrl)
        } else if (!s.bridgeEnabled) {
            networkManager.disconnectBridge()
        }
    }

    // ── File Transfer ─────────────────────────────────────────────────
    private val ioScope = CoroutineScope(Dispatchers.IO)

    fun sendFile(destinationId: String, file: File) {
        ioScope.launch {
            val bytes = file.readBytes()
            val chunkSize = 4096
            val chunks = bytes.toList().chunked(chunkSize)
            val transferId = java.util.UUID.randomUUID().toString().take(8)
            chunks.forEachIndexed { idx, chunk ->
                val meta = "$transferId:$idx:${chunks.size}:${file.name}:"
                val payload = meta.toByteArray() + chunk.toByteArray()
                networkManager.sendMessage(destinationId, payload, MeshMessageType.FILE_CHUNK)
            }
            val complete = "$transferId:${chunks.size}:${file.name}:${bytes.size}"
            networkManager.sendMessage(destinationId, complete.toByteArray(), MeshMessageType.FILE_COMPLETE)
        }
    }

    // ── Groups ────────────────────────────────────────────────────────
    fun createGroup(name: String, members: Set<String>): MeshGroup {
        val group = groupStore.createGroup(name, members)
        networkManager.sendGroupInvite(group.id, group.name, group.members)
        return group
    }

    fun sendToGroup(groupId: String, text: String) {
        val group = groupStore.getGroup(groupId) ?: return
        // Send to each member and also store as a group-destination message for local display
        group.members.filter { it != localNodeId }.forEach { memberId ->
            networkManager.sendMessage(destinationId = memberId, payload = text.toByteArray())
        }
        networkManager.sendMessage(destinationId = groupId, payload = text.toByteArray())
    }

    fun markAllRead(destinationId: String) {
        networkManager.messages.value
            .filter { msg ->
                msg.readAtMs == null && msg.deletedAtMs == null &&
                msg.sourceNodeId != localNodeId &&
                (msg.sourceNodeId == destinationId || msg.destinationNodeId == destinationId)
            }
            .forEach { msg -> networkManager.markRead(msg.id) }
    }

    // ── Forward ───────────────────────────────────────────────────────────
    fun forwardMessage(msg: com.turbomesh.desktop.mesh.MeshMessage, destinationId: String) {
        sendMessage(destinationId, "↩ ${String(msg.payload)}")
    }

    // ── Auto-reply ────────────────────────────────────────────────────────
    fun maybeAutoReply(fromNodeId: String) {
        val s = settingsStore.current()
        if (!s.autoReplyEnabled) return
        if (s.userStatus !in listOf("away", "dnd")) return
        if (fromNodeId == localNodeId) return
        val lastReply = autoRepliedTo[fromNodeId] ?: 0L
        val cooldownMs = 60 * 60 * 1000L // 1 hour between auto-replies per peer
        if (System.currentTimeMillis() - lastReply < cooldownMs) return
        autoRepliedTo[fromNodeId] = System.currentTimeMillis()
        sendMessage(fromNodeId, s.autoReplyMessage)
    }

    // ── Mute ──────────────────────────────────────────────────────────────
    fun isMuted(destinationId: String): Boolean =
        settingsStore.current().mutedDestinations.split(",").contains(destinationId)

    fun toggleMute(destinationId: String) {
        val s = settingsStore.current()
        val muted = s.mutedDestinations.split(",").filter { it.isNotBlank() }.toMutableSet()
        if (destinationId in muted) muted.remove(destinationId) else muted.add(destinationId)
        updateSettings(s.copy(mutedDestinations = muted.joinToString(",")))
    }

    fun clearConversation(destinationId: String) {
        networkManager.messages.value
            .filter { msg ->
                msg.sourceNodeId == destinationId || msg.destinationNodeId == destinationId
            }
            .forEach { msg -> networkManager.deleteMessage(msg.id) }
    }

    // ── Clipboard Sharing ─────────────────────────────────────────────
    fun sendClipboard(destinationId: String, text: String) =
        networkManager.sendMessage(destinationId, text.toByteArray(), MeshMessageType.CLIPBOARD)

    // ── Voice Notes ───────────────────────────────────────────────────
    fun sendVoiceNote(destinationId: String, wavData: ByteArray) {
        val encoded = java.util.Base64.getEncoder().encodeToString(wavData)
        networkManager.sendMessage(destinationId, encoded.toByteArray(), MeshMessageType.VOICE_COMPLETE)
    }

    // ── Scheduled Messages ────────────────────────────────────────────
    fun scheduleMessage(destinationId: String, text: String, atMs: Long) =
        networkManager.sendMessage(
            destinationId = destinationId,
            payload = text.toByteArray(),
            scheduledAtMs = atMs,
        )

    // ── Bulk Export ───────────────────────────────────────────────────
    fun exportAllConversations(): File {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val fname = "turbomesh_all_export_${sdf.format(Date())}.txt"
        val file = File(System.getProperty("user.home"), fname)
        val all = messages.value.filter { it.deletedAtMs == null }
        val sb = StringBuilder("TurboMesh Full Export — ${Date()}\n${"=".repeat(60)}\n\n")
        all.forEach { msg ->
            val sender = getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId.take(8) }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))
            val dest = if (msg.destinationNodeId == MeshMessage.BROADCAST_DESTINATION) "[broadcast]"
                       else getNickname(msg.destinationNodeId).ifBlank { msg.destinationNodeId.take(8) }
            sb.appendLine("[$time] $sender → $dest: ${String(msg.payload)}")
        }
        file.writeText(sb.toString())
        return file
    }

    init {
        val s = settingsStore.current()
        if (s.bridgeEnabled && s.bridgeServerUrl.isNotBlank()) {
            networkManager.connectBridge(s.bridgeServerUrl)
        }
    }

    fun destroy() {
        networkManager.destroy()
        bridgeManager.destroy()
    }
}
