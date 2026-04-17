package com.manegow.data.repository

import com.manegow.domain.repository.MeshRepository
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DeviceId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.model.nearby.Peer
import com.manegow.model.nearby.PeerStatus
import com.manegow.model.nearby.SignalStrength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeMeshRepository: MeshRepository {

    private val peersState = MutableStateFlow<List<Peer>>(emptyList())
    private var discoveryStarted = false


    override fun observeNearbyPeers(): Flow<List<Peer>> {
        return peersState.asStateFlow()
    }

    override fun observeIncomingData(): Flow<Pair<String, ByteArray>> {
        TODO("Not yet implemented")
    }

    override suspend fun startDiscovery() {
        discoveryStarted = true
        peersState.value = listOf(
            Peer(
                deviceId = DeviceId("device-alex"),
                userId = UserId("user-alex"),
                displayName = DisplayName("Alex"),
                signalStrength = SignalStrength(-52),
                status = PeerStatus.DISCOVERED,
                lastSeen = Timestamp(System.currentTimeMillis())
            ),
            Peer(
                deviceId = DeviceId("device-maya"),
                userId = UserId("user-maya"),
                displayName = DisplayName("Maya"),
                signalStrength = SignalStrength(-61),
                status = PeerStatus.CONNECTED,
                lastSeen = Timestamp(System.currentTimeMillis())
            ),
            Peer(
                deviceId = DeviceId("device-noah"),
                userId = UserId("user-noah"),
                displayName = DisplayName("Noah"),
                signalStrength = SignalStrength(-70),
                status = PeerStatus.DISCOVERED,
                lastSeen = Timestamp(System.currentTimeMillis())
            )
        )
    }

    override suspend fun stopDiscovery() {
        discoveryStarted = false
        peersState.value = emptyList()
    }

    override suspend fun sendData(deviceId: String, data: ByteArray) {
        TODO("Not yet implemented")
    }
}