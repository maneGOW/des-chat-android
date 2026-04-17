package com.manegow.deschat.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.manegow.chat_detail.ChatDetailRoute
import com.manegow.chat_detail.ChatDetailViewModel
import com.manegow.domain.usecase.chat.GetOrCreateDirectChatUseCase
import com.manegow.domain.usecase.chat.ObserveChatMessagesUseCase
import com.manegow.domain.usecase.chat.SendMessageUseCase
import com.manegow.domain.repository.IdentityRepository
import com.manegow.model.identity.DisplayName
import androidx.compose.runtime.collectAsState
import com.manegow.model.identity.UserIdentity
import com.manegow.model.identity.UserId
import com.manegow.nearby.NearbyRoute
import com.manegow.nearby.NearbyViewModel
import com.manegow.onboarding.OnboardingRoute

private const val ONBOARDING_ROUTE = "onboarding"
private const val NEARBY_ROUTE = "nearby"
private const val CHAT_DETAIL_ROUTE = "chat_detail"
private const val ARG_PEER_ID = "peerId"
private const val ARG_PEER_NAME = "peerName"

@Composable
fun AppNavHost(
    nearbyViewModel: NearbyViewModel,
    identityRepository: IdentityRepository,
    getOrCreateDirectChatUseCase: GetOrCreateDirectChatUseCase,
    observeChatMessagesUseCase: ObserveChatMessagesUseCase,
    sendMessageUseCase: SendMessageUseCase,
) {
    val navController = rememberNavController()

    val userIdentity by identityRepository.getUserIdentity().collectAsState(initial = null)

    if (userIdentity == null) {
        // Pantalla de carga o registro
        OnboardingRoute(
            identityRepository = identityRepository,
            onFinished = {
                // El collectAsState detectará el cambio automáticamente
            }
        )
        return
    }

    val localUserId = userIdentity!!.userId

    NavHost(
        navController = navController,
        startDestination = NEARBY_ROUTE
    ) {
        composable(route = ONBOARDING_ROUTE) {
            OnboardingRoute(
                identityRepository = identityRepository,
                onFinished = {
                    navController.navigate(NEARBY_ROUTE) {
                        popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                    }
                }
            )
        }

        composable(route = NEARBY_ROUTE) {
            NearbyRoute(
                viewModel = nearbyViewModel,
                onPeerClicked = { userId, name ->

                    navController.navigate(
                        route = buildChatDetailRoute(
                            peerId = userId,
                            peerName = name
                        )
                    )
                }
            )
        }

        composable(
            route = "$CHAT_DETAIL_ROUTE/{$ARG_PEER_ID}?$ARG_PEER_NAME={$ARG_PEER_NAME}",
            arguments = listOf(
                navArgument(ARG_PEER_ID) {
                    type = NavType.StringType
                },
                navArgument(ARG_PEER_NAME) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments
                ?.getString(ARG_PEER_ID)
                .orEmpty()

            val peerName = backStackEntry.arguments
                ?.getString(ARG_PEER_NAME)
                ?.takeIf { it.isNotBlank() }

            val chatDetailViewModel: ChatDetailViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
                key = "chat-detail-$peerId",
                factory = chatDetailViewModelFactory(
                    localUserId = localUserId,
                    peerId = peerId,
                    peerName = peerName,
                    getOrCreateDirectChatUseCase = getOrCreateDirectChatUseCase,
                    observeChatMessagesUseCase = observeChatMessagesUseCase,
                    sendMessageUseCase = sendMessageUseCase
                )
            )


            ChatDetailRoute(
                viewModel = chatDetailViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

private fun buildChatDetailRoute(
    peerId: String,
    peerName: String?
): String {
    val encodedName = Uri.encode(peerName.orEmpty(), "UTF-8")
    return "$CHAT_DETAIL_ROUTE/$peerId?$ARG_PEER_NAME=$encodedName"
}

private fun chatDetailViewModelFactory(
    localUserId: UserId,
    peerId: String,
    peerName: String?,
    getOrCreateDirectChatUseCase: GetOrCreateDirectChatUseCase,
    observeChatMessagesUseCase: ObserveChatMessagesUseCase,
    sendMessageUseCase: SendMessageUseCase
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatDetailViewModel::class.java)) {
                return ChatDetailViewModel(
                    localUserId = localUserId,
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