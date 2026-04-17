package com.manegow.onboarding

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manegow.domain.repository.IdentityRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

@Composable
fun OnboardingRoute(
    identityRepository: IdentityRepository,
    onFinished: () -> Unit
) {
    val onboardingViewModel: OnboardingViewModel = viewModel(
        factory = onboardingViewModelFactory(identityRepository)
    )

    OnboardingScreen(
        viewModel = onboardingViewModel,
        onFinished = onFinished
    )
}

private fun onboardingViewModelFactory(
    identityRepository: IdentityRepository
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
                return OnboardingViewModel(identityRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
