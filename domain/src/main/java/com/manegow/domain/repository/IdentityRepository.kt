package com.manegow.domain.repository

import com.manegow.model.identity.UserIdentity
import com.manegow.model.identity.DisplayName
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun getUserIdentity(): Flow<UserIdentity?>
    suspend fun saveDisplayName(displayName: DisplayName)
    suspend fun isUserRegistered(): Boolean
}
