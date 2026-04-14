package com.manegow.model.security

data class EncryptedPayload(
    val cipherText: ByteArray,
    val nonce: ByteArray,
    val algorithm: String
)