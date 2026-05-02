package com.manegow.deschat.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.manegow.chat_detail.ChatDetailRoute
import com.manegow.chat_detail.ChatDetailViewModel
import com.manegow.chat_list.ChatListScreen
import com.manegow.chat_list.ChatListViewModel
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.IdentityRepository
import com.manegow.domain.usecase.chat.GetOrCreateDirectChatUseCase
import com.manegow.domain.usecase.chat.ObserveChatMessagesUseCase
import com.manegow.domain.usecase.chat.ObserveChatsUseCase
import com.manegow.domain.usecase.chat.SendMessageUseCase
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.nearby.NearbyRoute
import com.manegow.nearby.NearbyViewModel
import com.manegow.onboarding.OnboardingRoute
import com.manegow.settings.SettingsScreen
import com.manegow.settings.SettingsViewModel

private const val ONBOARDING_ROUTE = "onboarding"
private const val NEARBY_ROUTE = "nearby"
private const val CHATS_ROUTE = "chats"
private const val SETTINGS_ROUTE = "settings"
private const val CHAT_DETAIL_ROUTE = "chat_detail"
private const val ARG_PEER_ID = "peerId"
private const val ARG_PEER_NAME = "peerName"

sealed class BottomNavItem(val route: String, val icon: ImageVector, val label: String) {
    object Nearby : BottomNavItem(NEARBY_ROUTE, Icons.Default.People, "Personas")
    object Chats : BottomNavItem(CHATS_ROUTE, Icons.Default.Forum, "Chats")
    object Settings : BottomNavItem(SETTINGS_ROUTE, Icons.Default.Settings, "Ajustes")
}

@Composable
fun AppNavHost(
    nearbyViewModel: NearbyViewModel,
    identityRepository: IdentityRepository,
    chatRepository: ChatRepository,
    getOrCreateDirectChatUseCase: GetOrCreateDirectChatUseCase,
    observeChatMessagesUseCase: ObserveChatMessagesUseCase,
    observeChatsUseCase: ObserveChatsUseCase,
    sendMessageUseCase: SendMessageUseCase,
) {
    val navController = rememberNavController()

    val userIdentity by identityRepository.getUserIdentity().collectAsState(initial = null)

    if (userIdentity == null) {
        // Pantalla de carga o registro
            OnboardingRoute(
                identityRepository = identityRepository
            ) {
                // El collectAsState detectará el cambio automáticamente
            }
        return
    }

    val localUserId = userIdentity!!.userId

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomBarItems = listOf(
        BottomNavItem.Nearby,
        BottomNavItem.Chats,
        BottomNavItem.Settings
    )

    val showBottomBar = currentDestination?.route in bottomBarItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomBarItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NEARBY_ROUTE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(route = ONBOARDING_ROUTE) {
                OnboardingRoute(
                    identityRepository = identityRepository
                ) {
                    navController.navigate(NEARBY_ROUTE) {
                        popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                    }
                }
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

            composable(route = CHATS_ROUTE) {
                val chatListViewModel: ChatListViewModel = viewModel(
                    factory = chatListViewModelFactory(observeChatsUseCase)
                )
                ChatListScreen(
                    viewModel = chatListViewModel,
                    onChatClick = { chatId, name ->
                        navController.navigate(
                            route = buildChatDetailRoute(
                                peerId = chatId.value,
                                peerName = name
                            )
                        )
                    }
                )
            }

            composable(route = SETTINGS_ROUTE) {
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = settingsViewModelFactory(identityRepository, chatRepository)
                )
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onSessionDeleted = {
                        navController.navigate(ONBOARDING_ROUTE) {
                            popUpTo(0) { inclusive = true }
                        }
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

private fun settingsViewModelFactory(
    identityRepository: IdentityRepository,
    chatRepository: ChatRepository
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(identityRepository, chatRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

private fun chatListViewModelFactory(
    observeChatsUseCase: ObserveChatsUseCase
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatListViewModel::class.java)) {
                return ChatListViewModel(observeChatsUseCase) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
