package com.turbomesh.desktop.mesh

import org.slf4j.LoggerFactory

class MeshRouter {
    private val log = LoggerFactory.getLogger(MeshRouter::class.java)
    private val routingTable = mutableMapOf<String, MutableList<String>>()
    private val messageHistory = ArrayDeque<String>()

    fun registerDirectRoute(nodeId: String) { routingTable[nodeId] = mutableListOf() }
    fun registerRoute(destinationId: String, hops: List<String>) { routingTable[destinationId] = hops.toMutableList() }
    fun removeRoute(nodeId: String) { routingTable.remove(nodeId) }
    fun hasRoute(destinationId: String) = destinationId == MeshMessage.BROADCAST_DESTINATION || routingTable.containsKey(destinationId)
    fun nextHop(destinationId: String): String? = routingTable[destinationId]?.let { if (it.isEmpty()) destinationId else it.first() }
    fun knownNodes(): Set<String> = routingTable.keys.toSet()
    fun clearRoutes() = routingTable.clear()

    fun routeMessage(message: MeshMessage): MeshMessage? {
        if (message.ttl <= 0) return null
        if (message.id in messageHistory) return null
        messageHistory.addLast(message.id)
        if (messageHistory.size > 500) messageHistory.removeFirst()
        return message.copy(hopCount = message.hopCount + 1, ttl = message.ttl - 1)
    }
}
