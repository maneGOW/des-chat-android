package com.manegow.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.usecase.mesh.ObserveNearbyPeersUseCase
import com.manegow.domain.usecase.mesh.StartPeerDiscoveryUseCase
import com.manegow.domain.usecase.mesh.StopPeerDiscoveryUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NearbyViewModel(
    private val observeNearbyPeersUseCase: ObserveNearbyPeersUseCase,
    private val startPeerDiscoveryUseCase: StartPeerDiscoveryUseCase,
    private val stopPeerDiscoveryUseCase: StopPeerDiscoveryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(NearbyUiState(isLoading = true))
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    init {
        observePeers()
    }

    private fun observePeers() {
        if (observeJob != null) return

        observeJob = viewModelScope.launch {
            observeNearbyPeersUseCase().collect { peers ->
                _uiState.update {
                    it.copy(
                        isLoading =  false,
                        nearbyPeers = peers,
                        error = null
                    )
                }
            }
        }
    }

    fun onPermissionsGranted() {
        _uiState.update {
            it.copy(error = null)
        }
        startDiscovery()
    }

    fun startDiscovery() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                )
            }

            runCatching {
                startPeerDiscoveryUseCase()
            }.onSuccess {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = null,
                ) }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = throwable.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            runCatching {
                stopPeerDiscoveryUseCase()
            }
        }
    }

    fun retry() {
        stopDiscovery()
        startDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
    }
}