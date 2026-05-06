package com.manegow.domain.repository

import com.manegow.model.nearby.Peer
import kotlinx.coroutines.flow.Flow

interface IMeshRepository {
    fun observeNearbyPeers(): Flow<List<Peer>>
    fun observeIncomingData(): Flow<Pair<String, ByteArray>>
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun sendData(deviceId: String, data: ByteArray)
    suspend fun fetchPublicKey(deviceId: String): String?
}