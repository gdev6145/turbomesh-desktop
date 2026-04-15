package com.turbomesh.desktop.mesh

enum class MeshMessageType(val opcode: Byte) {
    DATA(0x01), CONTROL(0x02), ACK(0x03), HEARTBEAT(0x04), NETWORK_INFO(0x05),
    BROADCAST(0x06), TYPING(0x07), READ(0x08), REACTION(0x09),
    FILE_CHUNK(0x0A), FILE_COMPLETE(0x0B), ROUTE_ADV(0x0C), TELEMETRY(0x0D),
    EMERGENCY(0x0E), GROUP_INVITE(0x0F), CLIPBOARD(0x10),
    VOICE_CHUNK(0x11), VOICE_COMPLETE(0x12), REPLY(0x13), EDIT(0x14), DELETE(0x15), KEY_EXCHANGE(0x16);

    companion object {
        fun fromOpcode(opcode: Byte): MeshMessageType? = values().firstOrNull { it.opcode == opcode }
    }
}
