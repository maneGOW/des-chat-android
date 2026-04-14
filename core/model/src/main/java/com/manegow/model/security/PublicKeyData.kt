package com.manegow.model.security

data class PublicKeyData(
    val value: ByteArray,
    val algorithm: String
)