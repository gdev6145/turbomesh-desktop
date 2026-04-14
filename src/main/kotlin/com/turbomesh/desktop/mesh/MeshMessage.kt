package com.turbomesh.desktop.mesh

import java.util.UUID

data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    val sourceNodeId: String,
    val destinationNodeId: String,
    val type: MeshMessageType,
    val payload: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val hopCount: Int = 0,
    val ttl: Int = 7,
    val isAcknowledged: Boolean = false,
    val readAtMs: Long? = null,
    val pendingDelivery: Boolean = false,
    val replyToMsgId: String? = null,
    val isEdited: Boolean = false,
    val editedAtMs: Long? = null,
    val deletedAtMs: Long? = null,
    val isPinned: Boolean = false,
    val scheduledAtMs: Long? = null,
    val expiresAtMs: Long? = null,
) {
    companion object { const val BROADCAST_DESTINATION = "BROADCAST" }
    override fun equals(other: Any?) = other is MeshMessage && id == other.id
    override fun hashCode() = id.hashCode()
}
