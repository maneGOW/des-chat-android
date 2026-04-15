package com.manegow.nearby

import com.manegow.model.nearby.Peer

data class NearbyUiState(
    val isLoading: Boolean = false,
    val nearbyPeers: List<Peer> = emptyList(),
    val error: String? = null
)