package com.manegow.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.repository.IdentityRepository
import com.manegow.model.identity.DisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val identityRepository: IdentityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Welcome)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    fun onNameChanged(newName: String) {
        _name.value = newName
    }

    fun onNextClicked() {
        when (_uiState.value) {
            OnboardingUiState.Welcome -> _uiState.value = OnboardingUiState.AppPurpose
            OnboardingUiState.AppPurpose -> _uiState.value = OnboardingUiState.InputName
            OnboardingUiState.InputName -> {
                if (_name.value.isNotBlank()) {
                    saveAndFinish()
                }
            }
            OnboardingUiState.Finished -> { /* No-op */ }
        }
    }

    private fun saveAndFinish() {
        viewModelScope.launch {
            identityRepository.saveDisplayName(DisplayName(_name.value))
            _uiState.value = OnboardingUiState.Finished
        }
    }
}

sealed interface OnboardingUiState {
    object Welcome : OnboardingUiState
    object AppPurpose : OnboardingUiState
    object InputName : OnboardingUiState
    object Finished : OnboardingUiState
}
