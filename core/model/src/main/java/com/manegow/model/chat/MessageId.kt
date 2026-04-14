package com.manegow.model.chat

@JvmInline
value class MessageId(val value: String){
    init {
        require(value.isNotBlank()) { "MessageId cannot be blank" }
    }
}