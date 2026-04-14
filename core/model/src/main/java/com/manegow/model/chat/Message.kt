package com.manegow.model.chat

import com.manegow.model.identity.UserId

data class Message(
    val messageId: MessageId,
    val chatId: ChatId,
    val senderId: UserId,
    val type: MessageType,
    val body: String,
    val createdAtEpochMillis: Long,
    val status: MessageStatus,
    val isEncrypted: Boolean
) {
    init {
        require(body.isNotBlank()) { "Message body cannot be blank" }
    }
}