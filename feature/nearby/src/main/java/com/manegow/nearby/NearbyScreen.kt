package com.manegow.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manegow.model.nearby.Peer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    uiState: NearbyUiState,
    onRetry: () -> Unit,
    onPeerClicked: (Peer) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "Nearby Peers")
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                NearbyLoadingContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }

            uiState.error != null -> {
                NearbyErrorContent(
                    error = uiState.error,
                    onRetry = onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }

            uiState.nearbyPeers.isEmpty() -> {
                NearbyEmptyContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }

            else -> {
                NearbyPeersList(
                    peers = uiState.nearbyPeers,
                    onPeerClick = onPeerClicked,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun NearbyLoadingContent(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NearbyErrorContent(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRetry) {
            Text(text = "Retry")
        }
    }
}

@Composable
private fun NearbyEmptyContent(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No nearby peers found",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun NearbyPeersList(
    peers: List<Peer>,
    onPeerClick: (Peer) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = peers,
            key = { peer -> peer.deviceId.value }
        ) { peer ->
            PeerItem(
                peer = peer,
                onClick = { onPeerClick(peer) }
            )
        }
    }
}

@Composable
private fun PeerItem(
    peer: Peer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = peer.displayName?.value ?: "Unknown peer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "UserId: ${peer.userId?.value ?: "N/A"}",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "DeviceId: ${peer.deviceId.value}",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()

            Text(
                text = "Signal: ${peer.signalStrength.rssi} dBm",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Status: ${peer.status.name}",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Last seen: ${peer.lastSeen.epochMillis}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}