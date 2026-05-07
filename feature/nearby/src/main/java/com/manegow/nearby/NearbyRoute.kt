package com.manegow.nearby

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NearbyRoute(
    viewModel: NearbyViewModel,
    navigateToChat: (peerId: String, peerName: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }

    NearbyScreen(
        uiState = uiState,
        onRetry = viewModel::retry,
        onPeerClicked = { peer ->
            val pId = peer.userId?.value?.lowercase()?.trim()
            val pName = peer.displayName?.value ?: "Desconocido"
            
            Log.d("NearbyRoute", "Peer clicked: ID=$pId, Name=$pName")
            
            if (!pId.isNullOrBlank() && pId != "unknown") {
                navigateToChat(pId, pName)
            } else {
                Log.w("NearbyRoute", "Ignoring click: ID is still unknown")
            }
        }
    )
}
