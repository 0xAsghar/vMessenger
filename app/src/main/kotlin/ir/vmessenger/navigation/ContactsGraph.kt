package ir.vmessenger.navigation

import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.R
import ir.vmessenger.ui.placeholder.ComingSoonRoute

/**
 * Contact destinations that are full screens rather than tab content.
 *
 * `ContactDetail` is a route (not `remember` state inside the contacts tab) so
 * back returns to the list instead of leaving the tab, and so it survives
 * rotation. Its body arrives with the contacts rewrite.
 */
internal fun NavGraphBuilder.contactsGraph(navController: NavHostController) {
    composable<VmRoute.ContactDetail> {
        ComingSoonRoute(
            title = stringResource(R.string.route_contact_detail),
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable<VmRoute.BlockedContacts> {
        ComingSoonRoute(
            title = stringResource(R.string.route_blocked_contacts),
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
