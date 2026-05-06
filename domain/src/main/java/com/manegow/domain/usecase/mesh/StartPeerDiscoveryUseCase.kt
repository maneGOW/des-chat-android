package com.manegow.domain.usecase.mesh

import com.manegow.domain.repository.IMeshRepository

class StartPeerDiscoveryUseCase(
    private val IMeshRepository: IMeshRepository
) {
    suspend operator fun invoke() {
        IMeshRepository.startDiscovery()
    }
}