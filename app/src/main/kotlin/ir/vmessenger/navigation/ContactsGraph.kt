package ir.vmessenger.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.feature.contacts.ContactDetailRoute
import ir.vmessenger.feature.settings.BlockedContactsRoute

/**
 * Contact destinations that are full screens rather than tab content.
 *
 * `ContactDetail` is a route (not `remember` state inside the contacts tab) so back returns to the
 * list instead of leaving the tab, and so the screen survives rotation and process death.
 */
internal fun NavGraphBuilder.contactsGraph(navController: NavHostController) {
    composable<VmRoute.ContactDetail> { entry ->
        ContactDetailRoute(
            onNavigateBack = { navController.popIfCurrent(entry) },
            onOpenConversation = { conversationId ->
                navController.navigate(VmRoute.Conversation(conversationId)) { launchSingleTop = true }
            },
        )
    }
    composable<VmRoute.BlockedContacts> { entry ->
        BlockedContactsRoute(onNavigateBack = { navController.popIfCurrent(entry) })
    }
}
