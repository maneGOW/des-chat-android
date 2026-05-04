package com.manegow.nearby

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NearbyRoute(
    viewModel: NearbyViewModel,
    onPeerClicked: (peerId: String, peerName: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Solo aseguramos que la búsqueda esté activa al entrar a esta pantalla
    // Pero no la detenemos al salir, para permitir recibir mensajes en segundo plano
    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }

    NearbyScreen(
        uiState = uiState,
        onRetry = viewModel::retry,
        onPeerClicked = { peer ->
            val peerId = peer.userId?.value ?: return@NearbyScreen
            val peerName = peer.displayName?.value ?: "Dispositivo desconocido"
            onPeerClicked(peerId, peerName)
        }
    )
}
