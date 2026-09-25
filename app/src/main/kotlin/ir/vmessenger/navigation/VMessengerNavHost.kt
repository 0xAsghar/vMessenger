package ir.vmessenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.vmessenger.feature.identity.CreateIdentityRoute
import ir.vmessenger.ui.onboarding.NodeSetupRoute
import ir.vmessenger.ui.share.ShareTargetRoute

/**
 * The outer graph: every destination except the four bottom-navigation tabs.
 *
 * [startRoute] is resolved before the first frame (see `MainViewModel`), so the
 * host is never mounted on a placeholder destination and there is no in-app
 * splash to navigate away from.
 */
@Composable
@Suppress("LongParameterList") // the outer graph: one parameter per thing the whole stack depends on
fun VMessengerNavHost(
    startRoute: VmRoute,
    pendingConversationId: String?,
    onPendingConversationHandled: () -> Unit,
    shareWaiting: Boolean = false,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val homeReached = rememberHomeReached(navController, startRoute)
    PendingConversationEffect(
        navController = navController,
        homeReached = homeReached,
        pendingConversationId = pendingConversationId,
        onHandled = onPendingConversationHandled,
    )
    PendingShareEffect(navController = navController, homeReached = homeReached, waiting = shareWaiting)
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
        enterTransition = { sharedAxisEnter() },
        exitTransition = { sharedAxisExit() },
        popEnterTransition = { sharedAxisPopEnter() },
        popExitTransition = { sharedAxisPopExit() },
    ) {
        composable<VmRoute.NodeSetup> { entry ->
            val provisioned by entry.savedStateHandle.getStateFlow(NODE_PROVISIONED, false)
                .collectAsStateWithLifecycle()
            NodeSetupRoute(
                onDone = {
                    navController.navigate(VmRoute.Onboarding) {
                        popUpTo<VmRoute.NodeSetup> { inclusive = true }
                    }
                },
                onCreateNode = { navController.navigate(VmRoute.NewNode()) },
                provisioned = provisioned,
            )
        }
        composable<VmRoute.Onboarding> {
            CreateIdentityRoute(
                onIdentityCreated = {
                    navController.navigate(VmRoute.Home) {
                        popUpTo<VmRoute.Onboarding> { inclusive = true }
                    }
                },
            )
        }
        composable<VmRoute.ShareTarget> {
            ShareTargetRoute(
                onNavigateBack = { navController.popBackStack() },
                onShared = { conversationId ->
                    navController.navigate(VmRoute.Conversation(conversationId)) {
                        popUpTo<VmRoute.ShareTarget> { inclusive = true }
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
 * Whether [VmRoute.Home] has actually been on the stack yet.
 *
 * Both pending-intent effects wait on it: a notification tap or a share that arrives while the app
 * is still at onboarding must not push a destination on top of identity creation.
 */
@Composable
private fun rememberHomeReached(navController: NavHostController, startRoute: VmRoute): Boolean {
    val currentEntry by navController.currentBackStackEntryAsState()
    var homeReached by rememberSaveable { mutableStateOf(startRoute == VmRoute.Home) }
    LaunchedEffect(currentEntry) {
        if (currentEntry?.destination?.hasRoute(VmRoute.Home::class) == true) {
            homeReached = true
        }
    }
    return homeReached
}

/** Consumes the conversation id a message notification put on the launch intent, exactly once. */
@Composable
private fun PendingConversationEffect(
    navController: NavHostController,
    homeReached: Boolean,
    pendingConversationId: String?,
    onHandled: () -> Unit,
) {
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

/**
 * Opens the destination picker for a share another app sent us.
 *
 * Nothing is consumed here: the picker's view model takes the payload, which empties the store and
 * closes [waiting] behind it, so this cannot reopen for a share already shown.
 */
@Composable
private fun PendingShareEffect(navController: NavHostController, homeReached: Boolean, waiting: Boolean) {
    LaunchedEffect(waiting, homeReached) {
        if (waiting && homeReached) {
            navController.navigate(VmRoute.ShareTarget) { launchSingleTop = true }
        }
    }
}
