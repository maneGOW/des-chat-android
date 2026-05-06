package com.manegow.settings

import com.manegow.domain.repository.UserSettings

data class SettingsUiState(
    val nickname: String = "",
    val avatarName: String = "HAPPY",
    val settings: UserSettings = UserSettings(),
    val isSaving: Boolean = false,
    val showNicknameDialog: Boolean = false,
)