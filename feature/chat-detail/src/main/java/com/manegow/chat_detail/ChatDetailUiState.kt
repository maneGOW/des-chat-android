package com.manegow.chat_detail

import com.manegow.model.chat.ChatId
import com.manegow.model.chat.Message
import com.manegow.model.identity.UserId

data class ChatDetailUiState(
    val isLoading: Boolean = true,
    val localUserId: UserId? = null,
    val chatId: ChatId? = null,
    val chatTitle: String = "",
    val messages: List<Message> = emptyList(),
    val draftMessage: String = "",
    val isSending: Boolean = false,
    val error: String? = null
)