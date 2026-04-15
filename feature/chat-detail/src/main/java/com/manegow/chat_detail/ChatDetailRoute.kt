package com.manegow.chat_detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ChatDetailRoute(
    viewModel: ChatDetailViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ChatDetailScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onDraftChanged = viewModel::onDraftChanged,
        onSendClick = viewModel::sendMessage,
        onRetry = viewModel::retry
    )
}