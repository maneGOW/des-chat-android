package com.manegow.model.identity

data class UserIdentity(
    val userId: UserId,
    val deviceId: DeviceId,
    val displayName: DisplayName,
    val avatarId: AvatarId = AvatarId.HAPPY,
    val publicKey: String? = null
)

enum class AvatarId {
    HAPPY,
    SAD,
    SURPRISED,
    ANGRY,
    COOL,
    WINK
}