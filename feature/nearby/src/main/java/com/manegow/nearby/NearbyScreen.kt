package com.manegow.nearby

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manegow.model.nearby.Peer
import com.manegow.model.nearby.PeerStatus

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
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        RadarAnimation()
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Buscando personas cerca...",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Asegúrate de que tus amigos también tengan la app abierta.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RadarAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val radius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radius"
    )
    val opacity by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "opacity"
    )

    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        // Círculos de expansión
        Box(
            modifier = Modifier
                .size((radius * 2).dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = opacity),
                    shape = CircleShape
                )
        )
        // Icono central
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier
                    .padding(16.dp)
                    .size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
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
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PeerAvatar(name = peer.displayName?.value ?: "?")
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName?.value ?: "Usuario desconocido",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when(peer.status) {
                        PeerStatus.REACHABLE -> "Disponible"
                        PeerStatus.CONNECTED -> "Conectado"
                        PeerStatus.CONNECTING -> "Conectando..."
                        else -> "Visto recientemente"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SignalIndicator(rssi = peer.signalStrength.rssi)
        }
    }
}

@Composable
private fun PeerAvatar(name: String) {
    val initials = name.take(1).uppercase()
    val backgroundColor = remember(name) {
        val colors = listOf(Color(0xFFEF5350), Color(0xFFAB47BC), Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFFFA726))
        colors[name.hashCode().coerceAtLeast(0).rem(colors.size)]
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SignalIndicator(rssi: Int) {
    val (icon, label, color) = when {
        rssi > -60 -> Triple<ImageVector, String, Color>(Icons.Default.SignalCellularAlt, "Excelente", Color(0xFF4CAF50))
        rssi > -80 -> Triple<ImageVector, String, Color>(Icons.Default.SignalCellularAlt2Bar, "Bueno", Color(0xFF8BC34A))
        else -> Triple<ImageVector, String, Color>(Icons.Default.SignalCellularAlt1Bar, "Débil", Color(0xFFFF9800))
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f)
        )
    }
}
