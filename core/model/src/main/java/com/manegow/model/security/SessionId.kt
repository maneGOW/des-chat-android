package com.manegow.model.security

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId cannot be blank" }
    }
}