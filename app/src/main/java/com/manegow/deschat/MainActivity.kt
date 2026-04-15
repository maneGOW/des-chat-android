package com.manegow.deschat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.manegow.deschat.navigation.AppNavHost
import com.manegow.deschat.ui.theme.DesChatTheme
import com.manegow.chat_detail.ChatDetailViewModel
import com.manegow.data.repository.FakeChatRepository
import com.manegow.data.repository.FakeMeshRepository
import com.manegow.domain.usecase.chat.GetOrCreateDirectChatUseCase
import com.manegow.domain.usecase.chat.ObserveChatMessagesUseCase
import com.manegow.domain.usecase.chat.SendMessageUseCase
import com.manegow.domain.usecase.mesh.ObserveNearbyPeersUseCase
import com.manegow.domain.usecase.mesh.StartPeerDiscoveryUseCase
import com.manegow.domain.usecase.mesh.StopPeerDiscoveryUseCase
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.nearby.NearbyViewModel
import kotlin.getValue

class MainActivity : ComponentActivity() {

    private val meshRepository by lazy { FakeMeshRepository() }
    private val chatRepository by lazy { FakeChatRepository() }

    private val observeNearbyPeersUseCase by lazy {
        ObserveNearbyPeersUseCase(meshRepository)
    }

    private val startPeerDiscoveryUseCase by lazy {
        StartPeerDiscoveryUseCase(meshRepository)
    }

    private val stopPeerDiscoveryUseCase by lazy {
        StopPeerDiscoveryUseCase(meshRepository)
    }

    private val getOrCreateDirectChatUseCase by lazy {
        GetOrCreateDirectChatUseCase(chatRepository)
    }

    private val observeChatMessagesUseCase by lazy {
        ObserveChatMessagesUseCase(chatRepository)
    }

    private val sendMessageUseCase by lazy {
        SendMessageUseCase(chatRepository)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val nearbyViewModel = provideNearbyViewModel()

        setContent {
            DesChatTheme {
                AppNavHost(
                    nearbyViewModel = nearbyViewModel,
                    chatDetailViewModelFactory = { peerId, peerName ->
                        provideChatDetailViewModel(
                            peerId = peerId,
                            peerName = peerName
                        )
                    }
                )
            }
        }
    }

    private fun provideNearbyViewModel(): NearbyViewModel {
        return ViewModelProvider(
            this,
            nearbyViewModelFactory()
        )[NearbyViewModel::class.java]
    }

    private fun provideChatDetailViewModel(
        peerId: String,
        peerName: String?
    ): ChatDetailViewModel {
        return ViewModelProvider(
            this,
            chatDetailViewModelFactory(peerId, peerName)
        )[ChatDetailViewModel::class.java]
    }

    private fun nearbyViewModelFactory(): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(NearbyViewModel::class.java)) {
                    return NearbyViewModel(
                        observeNearbyPeersUseCase = observeNearbyPeersUseCase,
                        startPeerDiscoveryUseCase = startPeerDiscoveryUseCase,
                        stopPeerDiscoveryUseCase = stopPeerDiscoveryUseCase
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    private fun chatDetailViewModelFactory(
        peerId: String,
        peerName: String?
    ): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ChatDetailViewModel::class.java)) {
                    return ChatDetailViewModel(
                        localUserId = UserId("local-user-id"),
                        peerUserId = UserId(peerId),
                        peerDisplayName = peerName?.let(::DisplayName),
                        getOrCreateDirectChatUseCase = getOrCreateDirectChatUseCase,
                        observeChatMessagesUseCase = observeChatMessagesUseCase,
                        sendMessageUseCase = sendMessageUseCase
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}