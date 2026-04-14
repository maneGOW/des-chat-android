package com.manegow.model.identity

@JvmInline
value class DisplayName(val value: String) {
    init {
            require(value.isNotBlank()) { "DisplayName cannot be blank" }
            require(value.length <= 40) { "DisplayName max length is 40" }
    }
}