package com.turbomesh.desktop.bluetooth

import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import com.turbomesh.desktop.mesh.MeshNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory

private val MESH_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"

class BleScanner {
    private val log = LoggerFactory.getLogger(BleScanner::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _scanResults = MutableStateFlow<List<MeshNode>>(emptyList())
    val scanResults: StateFlow<List<MeshNode>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _proximityEvents = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 32)
    val proximityEvents: SharedFlow<Pair<String, Boolean>> = _proximityEvents.asSharedFlow()

    var proximityThreshold: Int = -75
    var proximityAlertsEnabled: Boolean = false

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private var scanJob: Job? = null
    private val knownNearby = mutableSetOf<String>()
    private var manager: DeviceManager? = null

    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        scanJob = scope.launch {
            try {
                val dm = DeviceManager.createInstance(false)
                manager = dm
                val adapter = dm.getAdapter() ?: run { log.warn("No Bluetooth adapter found"); return@launch }
                adapter.startDiscovery()
                while (isActive) {
                    delay(2_000)
                    val devices = dm.getDevices(true)
                    val nodes = devices
                        .filter { hasMeshService(it) }
                        .map { it.toMeshNode() }
                    _scanResults.value = nodes
                    if (proximityAlertsEnabled) checkProximity(nodes)
                }
            } catch (e: Exception) {
                log.warn("BLE scan error: ${e.message}")
                _scanError.value = e.message ?: "Unknown BLE error"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        try { manager?.getAdapter()?.stopDiscovery() } catch (_: Exception) {}
        _isScanning.value = false
    }

    fun clearResults() { _scanResults.value = emptyList(); knownNearby.clear(); _scanError.value = null }

    private fun hasMeshService(device: BluetoothDevice): Boolean =
        device.uuids?.any { it.toString().lowercase() == MESH_UUID } ?: true  // show all if UUIDs not yet resolved

    private fun BluetoothDevice.toMeshNode() = MeshNode(
        id = address, name = name ?: "Unknown", rssi = rssi?.toInt() ?: -100, address = address,
        isConnected = isConnected, isProvisioned = false,
    )

    private fun checkProximity(nodes: List<MeshNode>) {
        nodes.forEach { node ->
            val isNearby = node.rssi >= proximityThreshold
            val wasNearby = knownNearby.contains(node.id)
            if (isNearby && !wasNearby) { knownNearby.add(node.id); scope.launch { _proximityEvents.emit(node.id to true) } }
            else if (!isNearby && wasNearby) { knownNearby.remove(node.id); scope.launch { _proximityEvents.emit(node.id to false) } }
        }
    }
}
