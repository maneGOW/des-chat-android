package com.manegow.domain.repository

import com.manegow.model.nearby.Peer
import kotlinx.coroutines.flow.Flow

interface MeshRepository {
    fun observeNearbyPeers(): Flow<List<Peer>>

    suspend fun startDiscovery()

    suspend fun stopDiscovery()
}