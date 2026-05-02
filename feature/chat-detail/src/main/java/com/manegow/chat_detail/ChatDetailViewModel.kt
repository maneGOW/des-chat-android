package com.manegow.chat_detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manegow.domain.usecase.chat.GetOrCreateDirectChatUseCase
import com.manegow.domain.usecase.chat.ObserveChatMessagesUseCase
import com.manegow.domain.usecase.chat.SendMessageUseCase
import com.manegow.model.chat.ChatId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatDetailViewModel(
    private val localUserId: UserId,
    private val peerUserId: UserId,
    private val peerDisplayName: DisplayName?,
    private val getOrCreateDirectChatUseCase: GetOrCreateDirectChatUseCase,
    private val observeChatMessagesUseCase: ObserveChatMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase
): ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatDetailUiState(
            isLoading = true,
            localUserId = localUserId,
            chatTitle = peerDisplayName?.value.orEmpty()
        )
    )

    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    init {
        loadChat()
    }

    private fun loadChat() {
        viewModelScope.launch {
            runCatching {
                val chat = getOrCreateDirectChatUseCase(
                    peerUserId = peerUserId,
                    peerDisplayName = peerDisplayName
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    chatId = chat.chatId,
                    chatTitle = chat.title,
                    error = null
                )

                observeMessages(chat.chatId)
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = throwable.message ?: "Failed to load chat"
                )
            }
        }
    }

    private fun observeMessages(chatId: ChatId) {
        viewModelScope.launch {
            observeChatMessagesUseCase(chatId).collectLatest { messages ->
                _uiState.value = _uiState.value.copy(
                    messages = messages,
                    error = null,
                )
            }
        }
    }

    fun onDraftChanged(newValue: String) {
        _uiState.value = _uiState.value.copy(
            draftMessage = newValue
        )
    }

    fun sendMessage() {
        val currentState = _uiState.value
        val chatId = currentState.chatId ?: return
        val text = currentState.draftMessage.trim()

        if(text.isBlank()) return

        viewModelScope.launch {
            _uiState.value = currentState.copy(
                isSending = true,
                error = null
            )

            runCatching {
                sendMessageUseCase(
                    chatId = chatId,
                    senderId = localUserId,
                    text = text
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    draftMessage = "",
                    isSending = false,
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = throwable.message ?: "Failed to send message"
                )
            }
        }
    }

    fun retry() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
        )
        loadChat()
    }
}