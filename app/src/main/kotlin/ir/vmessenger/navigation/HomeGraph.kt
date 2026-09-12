package ir.vmessenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.ui.home.HomeNavigation
import ir.vmessenger.ui.home.HomeRoute

/** The tab shell. Everything it can navigate *to* is wired here, in the outer graph. */
internal fun NavGraphBuilder.homeGraph(navController: NavHostController) {
    composable<VmRoute.Home> {
        HomeRoute(navigation = rememberHomeNavigation(navController))
    }
}

/**
 * Turns the outer [NavHostController] into the callback bundle the tab shell speaks.
 * Remembered against the controller so the tabs are not recomposed by a new
 * identity on every frame.
 */
@Composable
private fun rememberHomeNavigation(navController: NavHostController): HomeNavigation =
    remember(navController) {
        HomeNavigation(
            onOpenConversation = { conversationId ->
                navController.navigate(VmRoute.Conversation(conversationId)) { launchSingleTop = true }
            },
            onOpenContact = { contactId ->
                navController.navigate(VmRoute.ContactDetail(contactId)) { launchSingleTop = true }
            },
            onNewChat = { navController.navigate(VmRoute.NewChat) },
            onNewGroup = { navController.navigate(VmRoute.NewGroup) },
            onOpenGroupInfo = { groupId -> navController.navigate(VmRoute.GroupInfo(groupId)) },
            onMyQr = { navController.navigate(VmRoute.PairingMyQr) },
            onScanQr = { navController.navigate(VmRoute.PairingScan) },
            onAddByHash = { navController.navigate(VmRoute.PairingHash) },
            onNavigateToIdentity = { navController.navigate(VmRoute.Identity) },
            onNavigateToNodes = { navController.navigate(VmRoute.Nodes) },
            onNavigateToAbout = { navController.navigate(VmRoute.About) },
            onNavigateToBackup = { navController.navigate(VmRoute.Backup) },
            onNavigateToUpdate = { navController.navigate(VmRoute.Update) },
            onNavigateToBlockedContacts = { navController.navigate(VmRoute.BlockedContacts) },
            onNavigateToDebug = { navController.navigate(VmRoute.Debug) },
        )
    }
