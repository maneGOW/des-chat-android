package com.manegow.model.common

enum class DeliveryState {
    CREATED,
    QUEUED,
    SENT_TO_MESH,
    BROADCASTING,
    RELAYED,
    DELIVERED,
    FAILED,
    EXPIRED
}