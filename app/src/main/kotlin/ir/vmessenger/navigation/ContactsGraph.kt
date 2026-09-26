package ir.vmessenger.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.feature.contacts.ContactDetailRoute
import ir.vmessenger.feature.settings.ActivityLogRoute
import ir.vmessenger.feature.settings.BlockedContactsRoute
import ir.vmessenger.ui.call.rememberCallLauncher

/**
 * Contact destinations that are full screens rather than tab content.
 *
 * `ContactDetail` is a route (not `remember` state inside the contacts tab) so back returns to the
 * list instead of leaving the tab, and so the screen survives rotation and process death.
 */
internal fun NavGraphBuilder.contactsGraph(navController: NavHostController) {
    composable<VmRoute.ContactDetail> { entry ->
        val startCall = rememberCallLauncher()
        ContactDetailRoute(
            onNavigateBack = { navController.popIfCurrent(entry) },
            onOpenConversation = { conversationId ->
                navController.navigate(VmRoute.Conversation(conversationId)) { launchSingleTop = true }
            },
            onStartCall = startCall,
        )
    }
    composable<VmRoute.BlockedContacts> { entry ->
        BlockedContactsRoute(onNavigateBack = { navController.popIfCurrent(entry) })
    }
    // Sits with the other privacy destinations, which is where Settings offers it.
    composable<VmRoute.ActivityLog> { entry ->
        ActivityLogRoute(onNavigateBack = { navController.popIfCurrent(entry) })
    }
}
