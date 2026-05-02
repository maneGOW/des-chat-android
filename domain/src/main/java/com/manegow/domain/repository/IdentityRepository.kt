package com.manegow.domain.repository

import com.manegow.model.identity.UserIdentity
import com.manegow.model.identity.DisplayName
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun getUserIdentity(): Flow<UserIdentity?>
    suspend fun saveDisplayName(displayName: DisplayName)
    suspend fun isUserRegistered(): Boolean
    suspend fun clearAllData()

    fun observeSettings(): Flow<UserSettings>
    suspend fun updateSettings(settings: UserSettings)
}

data class UserSettings(
    val notificationsEnabled: Boolean = true,
    val soundsEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
)
