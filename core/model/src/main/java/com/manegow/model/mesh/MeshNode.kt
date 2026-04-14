package com.manegow.model.mesh

import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DeviceId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.model.nearby.SignalStrength
import com.manegow.model.security.SessionId

data class MeshNode(
    val deviceId: DeviceId,
    val userId: UserId?,
    val displayName: DisplayName?,
    val signalStrength: SignalStrength?,
    val lastSeen: Timestamp,
    val isReachable: Boolean,
    val activeSessionId: SessionId? = null
)