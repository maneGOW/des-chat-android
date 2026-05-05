package com.manegow.domain.usecase.mesh

import com.manegow.domain.repository.IMeshRepository
import com.manegow.model.nearby.Peer
import kotlinx.coroutines.flow.Flow

class ObserveNearbyPeersUseCase(
    private val IMeshRepository: IMeshRepository
) {
    operator fun invoke(): Flow<List<Peer>> {
        return IMeshRepository.observeNearbyPeers()
    }
}