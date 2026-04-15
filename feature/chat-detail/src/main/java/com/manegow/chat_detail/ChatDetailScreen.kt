package com.manegow.chat_detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manegow.model.chat.Message
import com.manegow.model.identity.UserId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    uiState: ChatDetailUiState,
    onBackClick: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.chatTitle.ifBlank { "Chat" }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text(text = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            if (!uiState.isLoading && uiState.error == null) {
                MessageInputBar(
                    value = uiState.draftMessage,
                    onValueChange = onDraftChanged,
                    onSendClick = onSendClick,
                    isSending = uiState.isSending
                )
            }
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                ChatLoadingContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }

            uiState.error != null -> {
                ChatErrorContent(
                    error = uiState.error,
                    onRetry = onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }

            uiState.messages.isEmpty() -> {
                EmptyChatContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }

            else -> {
                MessageList(
                    messages = uiState.messages,
                    localUserId = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun ChatLoadingContent(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ChatErrorContent(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Failed to load chat",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRetry) {
            Text(text = "Retry")
        }
    }
}

@Composable
private fun EmptyChatContent(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No messages yet. Say hello 👋",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    localUserId: UserId?,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = messages,
            key = { message -> message.messageId.value }
        ) { message ->
            MessageItem(
                message = message,
                isMine = localUserId != null && message.senderId == localUserId
            )
        }
    }
}

@Composable
private fun MessageItem(
    message: Message,
    isMine: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Text(
                text = if (isMine) "You" else message.senderId.value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyLarge
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "Status: ${message.status.name}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isSending: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(text = "Write a message")
            },
            enabled = !isSending
        )

        Button(
            onClick = onSendClick,
            enabled = value.isNotBlank() && !isSending
        ) {
            Text(text = if (isSending) "..." else "Send")
        }
    }
}