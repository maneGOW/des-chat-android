package com.manegow.model.identity

@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceId cannot be blank"}
    }
}