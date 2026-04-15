package com.manegow.model.chat

import com.manegow.model.common.DeliveryState
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.UserId

data class Message(
    val messageId: MessageId,
    val chatId: ChatId,
    val senderId: UserId,
    val type: MessageType,
    val body: String,
    val createdAtEpochMillis: Timestamp,
    val deliveryState: DeliveryState,
    val status: MessageStatus,
    val isEncrypted: Boolean
) {
    init {
        require(body.isNotBlank()) { "Message body cannot be blank" }
    }
}