package ir.vmessenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.vmessenger.feature.identity.CreateIdentityRoute

/**
 * The outer graph: every destination except the four bottom-navigation tabs.
 *
 * [startRoute] is resolved before the first frame (see `MainViewModel`), so the
 * host is never mounted on a placeholder destination and there is no in-app
 * splash to navigate away from.
 */
@Composable
fun VMessengerNavHost(
    startRoute: VmRoute,
    pendingConversationId: String?,
    onPendingConversationHandled: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    PendingConversationEffect(
        navController = navController,
        startRoute = startRoute,
        pendingConversationId = pendingConversationId,
        onHandled = onPendingConversationHandled,
    )
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
        enterTransition = { sharedAxisEnter() },
        exitTransition = { sharedAxisExit() },
        popEnterTransition = { sharedAxisPopEnter() },
        popExitTransition = { sharedAxisPopExit() },
    ) {
        composable<VmRoute.Onboarding> {
            CreateIdentityRoute(
                onIdentityCreated = {
                    navController.navigate(VmRoute.Home) {
                        popUpTo<VmRoute.Onboarding> { inclusive = true }
                    }
                },
            )
        }
        homeGraph(navController)
        chatGraph(navController)
        contactsGraph(navController)
        pairingGraph(navController)
        settingsGraph(navController)
        developerToolsGraph(navController)
    }
}

/**
 * Consumes the conversation id a message notification put on the launch intent.
 *
 * It is applied exactly once, and only after [VmRoute.Home] has actually been on
 * the stack — a tap that arrives while the app is still at onboarding must not
 * push a conversation on top of it.
 */
@Composable
private fun PendingConversationEffect(
    navController: NavHostController,
    startRoute: VmRoute,
    pendingConversationId: String?,
    onHandled: () -> Unit,
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    var homeReached by rememberSaveable { mutableStateOf(startRoute == VmRoute.Home) }
    LaunchedEffect(currentEntry) {
        if (currentEntry?.destination?.hasRoute(VmRoute.Home::class) == true) {
            homeReached = true
        }
    }
    LaunchedEffect(pendingConversationId, homeReached) {
        if (pendingConversationId != null && homeReached) {
            navController.navigate(VmRoute.Conversation(pendingConversationId)) {
                launchSingleTop = true
                popUpTo<VmRoute.Home>()
            }
            onHandled()
        }
    }
}
