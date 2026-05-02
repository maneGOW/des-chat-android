package com.manegow.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.IdentityRepository
import com.manegow.domain.repository.UserSettings
import com.manegow.model.identity.DisplayName
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val nickname: String = "",
    val settings: UserSettings = UserSettings(),
    val isSaving: Boolean = false,
    val showNicknameDialog: Boolean = false,
)

class SettingsViewModel(
    private val identityRepository: IdentityRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            identityRepository.getUserIdentity().collect { identity ->
                _uiState.update { it.copy(nickname = identity?.displayName?.value ?: "") }
            }
        }
        viewModelScope.launch {
            identityRepository.observeSettings().collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun onNicknameChanged(newNickname: String) {
        viewModelScope.launch {
            identityRepository.saveDisplayName(DisplayName(newNickname))
            _uiState.update { it.copy(showNicknameDialog = false) }
        }
    }

    fun toggleNotifications(enabled: Boolean) {
        updateSettings { it.copy(notificationsEnabled = enabled) }
    }

    fun toggleSounds(enabled: Boolean) {
        updateSettings { it.copy(soundsEnabled = enabled) }
    }

    fun toggleVibration(enabled: Boolean) {
        updateSettings { it.copy(vibrationEnabled = enabled) }
    }

    private fun updateSettings(update: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            val currentSettings = _uiState.value.settings
            val newSettings = update(currentSettings)
            identityRepository.updateSettings(newSettings)
        }
    }

    fun setShowNicknameDialog(show: Boolean) {
        _uiState.update { it.copy(showNicknameDialog = show) }
    }

    fun deleteSession(onDeleted: () -> Unit) {
        viewModelScope.launch {
            identityRepository.clearAllData()
            chatRepository.clearAllData()
            onDeleted()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSessionDeleted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var tempNickname by remember { mutableStateOf("") }
    
    LaunchedEffect(uiState.showNicknameDialog) {
        if (uiState.showNicknameDialog) {
            tempNickname = uiState.nickname
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Perfil") {
                SettingsItem(
                    title = "Nickname",
                    subtitle = uiState.nickname.ifBlank { "No configurado" },
                    icon = Icons.Default.Person,
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
            title = { Text("Cambiar Nickname") },
            text = {
                OutlinedTextField(
                    value = tempNickname,
                    onValueChange = { tempNickname = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onNicknameChanged(tempNickname) },
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
