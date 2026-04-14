package com.manegow.model.nearby

import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DeviceId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId

data class Peer(
    val deviceId: DeviceId,
    val userId: UserId?,
    val displayName: DisplayName?,
    val signalStrength: SignalStrength,
    val status: PeerStatus,
    val lastSeen: Timestamp
)