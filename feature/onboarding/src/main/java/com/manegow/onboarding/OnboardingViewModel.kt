package com.manegow.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.repository.IdentityRepository
import com.manegow.model.identity.AvatarId
import com.manegow.model.identity.DisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val identityRepository: IdentityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Intro)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _avatar = MutableStateFlow(AvatarId.HAPPY)
    val avatar: StateFlow<AvatarId> = _avatar.asStateFlow()

    fun onNameChanged(value: String) {
        _name.value = value
    }

    fun onAvatarSelected(value: AvatarId) {
        _avatar.value = value
    }

    fun onNextClicked() {
        _uiState.value = when (_uiState.value) {
            OnboardingUiState.Intro -> OnboardingUiState.Permissions
            OnboardingUiState.Permissions -> OnboardingUiState.AvatarSelection
            OnboardingUiState.AvatarSelection -> OnboardingUiState.Username
            OnboardingUiState.Username -> {
                saveIdentity()
                OnboardingUiState.Finished
            }
            OnboardingUiState.Finished -> OnboardingUiState.Finished
        }
    }

    private fun saveIdentity() {
        viewModelScope.launch {
            println("Saved avatar ${_avatar.value.name}")
            identityRepository.saveAvatarAndDisplayName(_avatar.value.name, DisplayName(_name.value))
            _uiState.value = OnboardingUiState.Finished
        }
    }
}