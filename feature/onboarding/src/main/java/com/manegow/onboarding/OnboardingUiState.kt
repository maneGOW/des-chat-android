package com.manegow.onboarding

sealed interface OnboardingUiState {
    data object Intro : OnboardingUiState
    data object Permissions : OnboardingUiState
    data object AvatarSelection : OnboardingUiState
    data object Username : OnboardingUiState
    data object Finished : OnboardingUiState
}