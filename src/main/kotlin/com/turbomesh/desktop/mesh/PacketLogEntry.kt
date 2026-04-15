package com.turbomesh.desktop.mesh

data class PacketLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val direction: PacketDirection,
    val src: String,
    val dst: String,
    val type: MeshMessageType,
    val sizeBytes: Int,
    val hopCount: Int,
    val msgId: String = "",
)

enum class PacketDirection { INBOUND, OUTBOUND }
