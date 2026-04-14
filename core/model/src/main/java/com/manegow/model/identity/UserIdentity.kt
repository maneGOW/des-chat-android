package com.manegow.model.identity

data class UserIdentity (
    val userId: UserId,
    val deviceId: DeviceId,
    val displayName: DisplayName,
    val publicKey: String? = null
)