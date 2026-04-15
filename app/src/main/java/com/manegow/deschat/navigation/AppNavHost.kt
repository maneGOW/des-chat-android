package com.manegow.deschat.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.manegow.chat_detail.ChatDetailRoute
import com.manegow.chat_detail.ChatDetailViewModel
import com.manegow.nearby.NearbyRoute
import com.manegow.nearby.NearbyViewModel


private const val NEARBY_ROUTE = "nearby"
private const val CHAT_DETAIL_ROUTE = "chat_detail"
private const val ARG_PEER_ID = "peerId"
private const val ARG_PEER_NAME = "peerName"

@Composable
fun AppNavHost(
    nearbyViewModel: NearbyViewModel,
    chatDetailViewModelFactory: (peerUserId: String, peerName: String?) -> ChatDetailViewModel
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NEARBY_ROUTE
    ) {
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

            val viewModel = chatDetailViewModelFactory(
                peerId,
                peerName
            )

            ChatDetailRoute(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

private fun buildChatDetailRoute(
    peerId: String,
    peerName: String?
): String {
    val encodedName = java.net.URLEncoder.encode(peerName.orEmpty(), "UTF-8")
    return "$CHAT_DETAIL_ROUTE/$peerId?$ARG_PEER_NAME=$encodedName"
}