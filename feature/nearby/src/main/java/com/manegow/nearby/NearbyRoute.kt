package com.manegow.nearby

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NearbyRoute(
    viewModel: NearbyViewModel,
    onPeerClicked: (peerId: String, peerName: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.startDiscovery()
                }

                Lifecycle.Event.ON_STOP -> {
                    viewModel.stopDiscovery()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopDiscovery()
        }
    }

    NearbyScreen(
        uiState = uiState,
        onRetry = viewModel::retry,
        onPeerClicked = { peer ->
            val peerId = peer.userId?.value ?: return@NearbyScreen
            val peerName = peer.displayName?.value ?: "Unknown Device"
            onPeerClicked(peerId, peerName)
        }
    )
}
