package com.turbomesh.desktop.bluetooth

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.TimeUnit

class BridgeManager {
    private val log = LoggerFactory.getLogger(BridgeManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _inboundMessages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val inboundMessages: SharedFlow<Pair<String, ByteArray>> = _inboundMessages.asSharedFlow()

    fun connect(url: String) {
        if (_isConnected.value) return
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { _isConnected.value = true }
            override fun onMessage(ws: WebSocket, text: String) { parseRelayMessage(text) }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { _isConnected.value = false }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { _isConnected.value = false }
        })
    }

    fun disconnect() { webSocket?.close(1000, "Disconnecting"); webSocket = null; _isConnected.value = false }

    fun forward(sourceId: String, destId: String, packet: ByteArray) {
        if (!_isConnected.value) return
        val encoded = Base64.getEncoder().encodeToString(packet)
        webSocket?.send("""{"src":"$sourceId","dst":"$destId","data":"$encoded"}""")
    }

    fun destroy() { disconnect(); scope.cancel(); client.dispatcher.executorService.shutdown() }

    private fun parseRelayMessage(json: String) {
        val src = Regex(""""src"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return
        val data = Regex(""""data"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return
        scope.launch { _inboundMessages.emit(src to Base64.getDecoder().decode(data)) }
    }
}
