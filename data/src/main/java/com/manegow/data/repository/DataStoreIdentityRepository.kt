package com.manegow.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.manegow.domain.repository.IdentityRepository
import com.manegow.model.identity.DeviceId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.model.identity.UserIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "identity")

class DataStoreIdentityRepository(private val context: Context) : IdentityRepository {

    private object PreferencesKeys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
    }

    override fun getUserIdentity(): Flow<UserIdentity?> {
        return context.dataStore.data.map { preferences ->
            val deviceId = preferences[PreferencesKeys.DEVICE_ID]
            val userId = preferences[PreferencesKeys.USER_ID]
            val displayName = preferences[PreferencesKeys.DISPLAY_NAME]

            if (deviceId != null && userId != null && displayName != null) {
                UserIdentity(
                    userId = UserId(userId),
                    deviceId = DeviceId(deviceId),
                    displayName = DisplayName(displayName)
                )
            } else {
                null
            }
        }
    }

    override suspend fun saveDisplayName(displayName: DisplayName) {
        context.dataStore.edit { preferences ->
            // Si es la primera vez, generamos los IDs
            if (preferences[PreferencesKeys.DEVICE_ID] == null) {
                preferences[PreferencesKeys.DEVICE_ID] = UUID.randomUUID().toString()
                preferences[PreferencesKeys.USER_ID] = UUID.randomUUID().toString()
            }
            preferences[PreferencesKeys.DISPLAY_NAME] = displayName.value
        }
    }

    override suspend fun isUserRegistered(): Boolean {
        val preferences = context.dataStore.data.first()
        return preferences[PreferencesKeys.DISPLAY_NAME] != null
    }
}
