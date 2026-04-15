package com.manegow.domain.usecase.chat

import com.manegow.domain.repository.ChatRepository
import com.manegow.model.chat.ChatId
import com.manegow.model.identity.UserId

class SendMessageUseCase(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(
        chatId: ChatId,
        senderId: UserId,
        text: String
    ) {
        require(text.isNotBlank()) { "Message text cannot be blank" }

        chatRepository.sendMessage(
            chatId = chatId,
            senderId = senderId,
            text = text.trim()
        )
    }
}