package com.manegow.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.usecase.mesh.ObserveNearbyPeersUseCase
import com.manegow.domain.usecase.mesh.StartPeerDiscoveryUseCase
import com.manegow.domain.usecase.mesh.StopPeerDiscoveryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NearbyViewModel(
    private val observeNearbyPeersUseCase: ObserveNearbyPeersUseCase,
    private val startPeerDiscoveryUseCase: StartPeerDiscoveryUseCase,
    private val stopPeerDiscoveryUseCase: StopPeerDiscoveryUseCase
): ViewModel() {

    private val _uiState = MutableStateFlow(NearbyUiState(isLoading = true))
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    init {
        observePeers()
        startDiscovery()
    }

    private fun observePeers() {
        viewModelScope.launch {
            observeNearbyPeersUseCase().collect { peers ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    nearbyPeers = peers,
                    error = null)
            }
        }
    }

    private fun startDiscovery() {
        viewModelScope.launch {
            runCatching {
                startPeerDiscoveryUseCase()
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = throwable.message ?: "Unknown error"
                )
            }
        }
    }

    fun retry() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null
        )
        startDiscovery()
    }

    override fun onCleared() {
        viewModelScope.launch {
            stopPeerDiscoveryUseCase()
        }
        super.onCleared()
    }
}