package com.manegow.domain.usecase.mesh

import com.manegow.domain.repository.MeshRepository

class StartPeerDiscoveryUseCase(
    private val meshRepository: MeshRepository
) {
    suspend operator fun invoke() {
        meshRepository.startDiscovery()
    }
}