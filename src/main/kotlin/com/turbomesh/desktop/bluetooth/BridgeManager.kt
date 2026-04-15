package com.turbomesh.desktop.bluetooth

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BridgeManager {
    private val log = LoggerFactory.getLogger(BridgeManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private var webSocket: WebSocket? = null

    // Reconnect state
    private var lastUrl: String? = null
    private var reconnectJob: Job? = null

    // Queue packets sent while bridge is offline (capped at 100)
    private val offlineQueue = LinkedBlockingQueue<Triple<String, String, ByteArray>>(100)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _inboundMessages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val inboundMessages: SharedFlow<Pair<String, ByteArray>> = _inboundMessages.asSharedFlow()

    fun connect(url: String) {
        lastUrl = url
        reconnectJob?.cancel()
        doConnect(url)
    }

    private fun doConnect(url: String) {
        webSocket?.close(1001, "Reconnecting")
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _isConnected.value = true
                log.info("Bridge connected to $url")
                flushOfflineQueue()
            }
            override fun onMessage(ws: WebSocket, text: String) { parseRelayMessage(text) }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                log.warn("Bridge failure: ${t.message}")
                scheduleReconnect()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                if (code != 1000) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val url = lastUrl ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 2_000L
            while (isActive && !_isConnected.value && lastUrl != null) {
                delay(delayMs)
                if (!_isConnected.value && lastUrl != null) {
                    log.info("Bridge reconnecting (retry)…")
                    doConnect(url)
                    delayMs = (delayMs * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private fun flushOfflineQueue() {
        var item = offlineQueue.poll()
        while (item != null) {
            val (src, dst, data) = item
            sendRaw(src, dst, data)
            item = offlineQueue.poll()
        }
        if (offlineQueue.isNotEmpty()) log.info("Flushed offline message queue")
    }

    fun disconnect() {
        lastUrl = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        _isConnected.value = false
    }

    fun forward(sourceId: String, destId: String, packet: ByteArray) {
        if (!_isConnected.value) {
            val offered = offlineQueue.offer(Triple(sourceId, destId, packet))
            if (!offered) log.warn("Offline queue full — dropping packet")
            return
        }
        sendRaw(sourceId, destId, packet)
    }

    private fun sendRaw(sourceId: String, destId: String, packet: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(packet)
        val json = buildJson(
            "src" to sourceId,
            "dst" to destId,
            "data" to encoded,
        )
        webSocket?.send(json)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private fun parseRelayMessage(json: String) {
        val src = Regex(""""src"\s*:\s*"([^"\\]+)"""").find(json)?.groupValues?.get(1) ?: return
        val data = Regex(""""data"\s*:\s*"([A-Za-z0-9+/=\n]+)"""").find(json)?.groupValues?.get(1) ?: return
        scope.launch {
            try {
                _inboundMessages.emit(src to Base64.getDecoder().decode(data.replace("\n", "")))
            } catch (e: Exception) {
                log.warn("Failed to decode relay message: ${e.message}")
            }
        }
    }

    /** Build a JSON object from key-value string pairs with proper escaping. */
    private fun buildJson(vararg pairs: Pair<String, String>): String {
        val body = pairs.joinToString(",") { (k, v) ->
            """"${k.jsonEscape()}":"${v.jsonEscape()}""""
        }
        return "{$body}"
    }

    private fun String.jsonEscape(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
