package com.manegow.model.chat

import com.manegow.model.common.Timestamp
import com.manegow.model.identity.UserId

data class Chat(
    val chatId: ChatId,
    val title: String,
    val type: ChatType,
    val participantIds: List<UserId>,
    val lastMessagePreview: String? = null,
    val updatedAtEpochMillis: Timestamp
) {
    init {
        require(title.isNotBlank()) { "Chat title cannot be blank" }
    }
}