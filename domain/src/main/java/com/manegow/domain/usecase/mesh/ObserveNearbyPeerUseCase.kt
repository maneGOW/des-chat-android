package com.manegow.domain.usecase.mesh

import com.manegow.domain.repository.MeshRepository
import com.manegow.model.nearby.Peer
import kotlinx.coroutines.flow.Flow

class ObserveNearbyPeersUseCase(
    private val meshRepository: MeshRepository
) {
    operator fun invoke(): Flow<List<Peer>> {
        return meshRepository.observeNearbyPeers()
    }
}