package com.manegow.domain.usecase.mesh

import com.manegow.domain.repository.IMeshRepository

class StopPeerDiscoveryUseCase(
    private val IMeshRepository: IMeshRepository
) {
    suspend operator fun invoke() {
        IMeshRepository.stopDiscovery()
    }
}