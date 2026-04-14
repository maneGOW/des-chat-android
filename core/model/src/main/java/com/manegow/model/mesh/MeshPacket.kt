package com.manegow.model.mesh

import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DeviceId
import com.manegow.model.security.SessionId

data class MeshPacket (
    val packetId: PacketId,
    val sourceDeviceId: DeviceId,
    val targetDeviceId: DeviceId?,
    val packetType: PacketType,
    val ttl: Ttl,
    val hopCount: Int,
    val payload: ByteArray,
    val createdAtEpochMillis: Timestamp,
    val sessionId: SessionId? = null
) {
    init {
        require(hopCount >= 0) { "Hop count must be >= 0" }
    }
}