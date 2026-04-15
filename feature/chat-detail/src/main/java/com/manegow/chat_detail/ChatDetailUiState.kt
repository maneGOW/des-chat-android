package com.manegow.chat_detail

import com.manegow.model.chat.ChatId
import com.manegow.model.chat.Message

data class ChatDetailUiState(
    val isLoading: Boolean = true,
    val chatId: ChatId? = null,
    val chatTitle: String = "",
    val messages: List<Message> = emptyList(),
    val draftMessage: String = "",
    val isSending: Boolean = false,
    val error: String? = null
)