package com.manegow.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.IdentityRepository
import com.manegow.domain.repository.UserSettings
import com.manegow.model.identity.DisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val identityRepository: IdentityRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            identityRepository.getUserIdentity().collect { identity ->
                _uiState.update {
                    it.copy(
                        nickname = identity?.displayName?.value ?: "",
                        avatarName = identity?.avatarId?.name ?: "HAPPY"
                    )
                }
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

    fun onUserDataChanged(avatarName: String, newNickname: String) {
        viewModelScope.launch {
            identityRepository.saveAvatarAndDisplayName(avatarName,DisplayName(newNickname))
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