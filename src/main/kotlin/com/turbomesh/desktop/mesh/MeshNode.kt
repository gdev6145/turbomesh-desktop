package com.turbomesh.desktop.mesh

data class MeshNode(
    val id: String,
    val name: String,
    val rssi: Int,
    val address: String,
    val isProvisioned: Boolean = false,
    val isConnected: Boolean = false,
    val nickname: String = "",
    val rssiTrend: String = "",
    val connectedSinceMs: Long = 0L,
    val batteryLevel: Int = -1,
    val presenceStatus: String = "",
) {
    val displayName: String get() = when {
        nickname.isNotBlank() -> nickname
        name.isNotBlank() && name != "Unknown Device" -> name
        else -> address.takeLast(8)
    }
    val connectionQuality: String get() = when (rssi) {
        in Int.MIN_VALUE..-90 -> "Poor"
        in -89..-75 -> "Fair"
        in -74..-60 -> "Good"
        else -> "Excellent"
    }
}
