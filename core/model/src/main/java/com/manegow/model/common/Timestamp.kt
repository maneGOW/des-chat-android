package com.manegow.model.common

@JvmInline
value class Timestamp(val epochMillis: Long) {
    init {
        require(epochMillis >= 0L) { "Timestamp must be >= 0" }
    }
}