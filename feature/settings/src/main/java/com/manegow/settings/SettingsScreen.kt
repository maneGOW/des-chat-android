package com.manegow.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.IdentityRepository
import com.manegow.domain.repository.UserSettings
import com.manegow.model.identity.AvatarId
import com.manegow.model.identity.DisplayName
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSessionDeleted: () -> Unit
) {

    val uiState by viewModel.uiState.collectAsState()
    var tempNickname by remember { mutableStateOf("") }
    var tempAvatarName by remember { mutableStateOf("HAPPY") }
    
    LaunchedEffect(uiState.showNicknameDialog) {
        if (uiState.showNicknameDialog) {
            tempNickname = uiState.nickname
            tempAvatarName = uiState.avatarName
            println("Avatar name $tempAvatarName - ${uiState.avatarName}")
        }
    }

    Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Ajustes",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 24.dp, top = 8.dp, bottom = 16.dp)
                    )
                }
            }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Perfil") {
                ProfileSettingsItem(
                    avatarName = uiState.avatarName,
                    nickname = uiState.nickname,
                    onClick = { viewModel.setShowNicknameDialog(show = true) }
                )
            }

            SettingsSection(title = "Notificaciones") {
                SettingsSwitchItem(
                    title = "Habilitar notificaciones",
                    icon = Icons.Default.Notifications,
                    checked = uiState.settings.notificationsEnabled,
                    onCheckedChange = viewModel::toggleNotifications
                )
                SettingsSwitchItem(
                    title = "Sonidos de chat",
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    checked = uiState.settings.soundsEnabled,
                    onCheckedChange = viewModel::toggleSounds
                )
                SettingsSwitchItem(
                    title = "Vibración",
                    icon = Icons.Default.Vibration,
                    checked = uiState.settings.vibrationEnabled,
                    onCheckedChange = viewModel::toggleVibration
                )
            }

            SettingsSection(title = "Cuenta") {
                SettingsItem(
                    title = "Eliminar sesión",
                    subtitle = "Borra todos tus datos y cierra la cuenta",
                    icon = Icons.Default.DeleteForever,
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = { viewModel.deleteSession(onSessionDeleted) }
                )
            }
        }
    }

    if (uiState.showNicknameDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.setShowNicknameDialog(false) },
                title = { Text("Editar perfil") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        FaceAvatar(
                            avatarId = AvatarId.valueOf(tempAvatarName),
                            modifier = Modifier
                                .size(72.dp)
                                .align(Alignment.CenterHorizontally)
                        )

                        AvatarPickerRow(
                            selectedAvatarName = tempAvatarName,
                            onAvatarSelected = { tempAvatarName = it }
                        )

                        OutlinedTextField(
                            value = tempNickname,
                            onValueChange = { tempNickname = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.onUserDataChanged(
                                avatarName = tempAvatarName,
                                newNickname = tempNickname
                            )
                        },
                        enabled = tempNickname.isNotBlank()
                    ) {
                        Text("Guardar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.setShowNicknameDialog(false) }) {
                        Text("Cancelar")
                    }
                }
            )
    }
}

@Composable
private fun AvatarPickerRow(
    selectedAvatarName: String,
    onAvatarSelected: (String) -> Unit
) {
    val avatars = listOf("HAPPY", "SAD", "SURPRISED", "ANGRY", "COOL", "WINK")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        avatars.forEach { avatar ->
            val selected = avatar == selectedAvatarName

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clickable { onAvatarSelected(avatar) },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                    tonalElevation = if (selected) 2.dp else 0.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        FaceAvatar(
                            avatarId = AvatarId.valueOf(avatar),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsItem(
    avatarName: String,
    nickname: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = nickname.ifBlank { "No configurado" },
                fontWeight = FontWeight.Bold
            )
        },
        supportingContent = {
            Text("Toca para cambiar avatar y username")
        },
        leadingContent = {
            FaceAvatar(
                avatarId = AvatarId.valueOf(avatarName),
                modifier = Modifier.size(48.dp)
            )
        }
    )
}

@Composable
fun FaceAvatar(
    avatarId: AvatarId,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val faceColor = Color(0xFFFFC857)
        val eyeColor = Color(0xFF1F2937)
        val mouthColor = Color(0xFF1F2937)
        val stroke = size.minDimension * 0.06f
        val radius = size.minDimension / 2f

        drawCircle(
            color = faceColor,
            radius = radius
        )

        val eyeY = size.height * 0.38f
        val leftEyeX = size.width * 0.33f
        val rightEyeX = size.width * 0.67f

        when (avatarId) {
            AvatarId.COOL -> {
                drawLine(
                    color = eyeColor,
                    start = Offset(size.width * 0.2f, eyeY),
                    end = Offset(size.width * 0.8f, eyeY),
                    strokeWidth = stroke * 1.3f,
                    cap = StrokeCap.Round
                )
                drawRect(
                    color = eyeColor,
                    topLeft = Offset(size.width * 0.18f, eyeY - stroke * 1.4f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.22f, stroke * 2.8f)
                )
                drawRect(
                    color = eyeColor,
                    topLeft = Offset(size.width * 0.60f, eyeY - stroke * 1.4f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.22f, stroke * 2.8f)
                )
            }

            AvatarId.WINK -> {
                drawCircle(color = eyeColor, radius = stroke * 0.9f, center = Offset(leftEyeX, eyeY))
                drawLine(
                    color = eyeColor,
                    start = Offset(rightEyeX - stroke, eyeY),
                    end = Offset(rightEyeX + stroke, eyeY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            else -> {
                drawCircle(color = eyeColor, radius = stroke * 0.9f, center = Offset(leftEyeX, eyeY))
                drawCircle(color = eyeColor, radius = stroke * 0.9f, center = Offset(rightEyeX, eyeY))
            }
        }

        when (avatarId) {
            AvatarId.HAPPY -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.45f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.44f, size.height * 0.28f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            AvatarId.SAD -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.58f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.44f, size.height * 0.20f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            AvatarId.SURPRISED -> {
                drawCircle(
                    color = mouthColor,
                    radius = stroke * 1.5f,
                    center = Offset(size.width * 0.5f, size.height * 0.68f)
                )
            }

            AvatarId.ANGRY -> {
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.24f, size.height * 0.26f),
                    end = Offset(size.width * 0.40f, size.height * 0.32f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.76f, size.height * 0.26f),
                    end = Offset(size.width * 0.60f, size.height * 0.32f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.34f, size.height * 0.72f),
                    end = Offset(size.width * 0.66f, size.height * 0.72f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            AvatarId.COOL -> {
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.34f, size.height * 0.70f),
                    end = Offset(size.width * 0.66f, size.height * 0.70f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            AvatarId.WINK -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 15f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.32f, size.height * 0.50f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.22f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title, color = contentColor) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = contentColor) }
    )
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}
