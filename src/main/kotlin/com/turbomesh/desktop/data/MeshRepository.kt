package com.turbomesh.desktop.data

import com.turbomesh.desktop.bluetooth.BleScanner
import com.turbomesh.desktop.bluetooth.BridgeManager
import com.turbomesh.desktop.bluetooth.MeshNetworkManager
import com.turbomesh.desktop.mesh.CryptoManager
import com.turbomesh.desktop.mesh.MeshMessage
import com.turbomesh.desktop.mesh.MeshMessageType
import com.turbomesh.desktop.mesh.MeshRouter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow

class MeshRepository {
    val settingsStore = SettingsStore()
    val nicknameStore = NicknameStore()

    private val bleScanner = BleScanner()
    private val bridgeManager = BridgeManager()
    private val router = MeshRouter()
    private val crypto = CryptoManager()

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

    fun sendMessage(
        destinationId: String,
        text: String,
        type: MeshMessageType = MeshMessageType.DATA,
        replyToMsgId: String? = null,
        scheduledAtMs: Long? = null,
        expiresAtMs: Long? = null,
    ) = networkManager.sendMessage(
        destinationId = destinationId,
        payload = text.toByteArray(),
        type = type,
        replyToMsgId = replyToMsgId,
        scheduledAtMs = scheduledAtMs,
        expiresAtMs = expiresAtMs,
    )

    fun broadcastMessage(text: String) =
        sendMessage(MeshMessage.BROADCAST_DESTINATION, text, MeshMessageType.BROADCAST)

    fun editMessage(msgId: String, newText: String) =
        networkManager.editMessage(msgId, newText.toByteArray())

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

    init {
        // Auto-connect bridge if already configured
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
