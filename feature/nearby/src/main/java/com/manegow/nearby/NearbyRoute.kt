package com.manegow.nearby

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NearbyRoute(
    viewModel: NearbyViewModel,
    onPeerClicked: (peerId: String, peerName: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NearbyScreen(
        uiState = uiState,
        onRetry = viewModel::retry,
        onPeerClicked = { peer ->
            val peerId = peer.userId?.value ?: return@NearbyScreen
            val peerName = peer.displayName?.value ?: return@NearbyScreen
            onPeerClicked(peerId, peerName)
        }
    )

}